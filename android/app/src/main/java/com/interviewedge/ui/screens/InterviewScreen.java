package com.interviewedge.ui.screens;

import android.os.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.widget.EditText;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.*;
import com.interviewedge.R;
import com.interviewedge.ui.RecordingScreen;

public class InterviewScreen extends RecordingScreen {
  private final Handler polling = new Handler(Looper.getMainLooper());
  private final Handler countdown = new Handler(Looper.getMainLooper());
  private TextView timeRemaining;
  private long answerDeadline;

  protected boolean focus() {
    return true;
  }

  public void onViewCreated(android.view.View view, Bundle state) {
    super.onViewCreated(view, state);
    requireActivity()
        .getOnBackPressedDispatcher()
        .addCallback(
            getViewLifecycleOwner(),
            new OnBackPressedCallback(true) {
              public void handleOnBackPressed() {
                new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Leave interview?")
                    .setMessage(
                        "Your saved answers remain available. Answer time continues while away.")
                    .setPositiveButton(
                        "Leave",
                        (d, w) -> {
                          setEnabled(false);
                          requireActivity().getOnBackPressedDispatcher().onBackPressed();
                        })
                    .setNegativeButton("Continue", null)
                    .show();
              }
            });
  }

  protected void render() {
    polling.removeCallbacksAndMessages(null);
    countdown.removeCallbacksAndMessages(null);
    timeRemaining = null;
    heading("Mock interview", "Loading your session…");
    get(
        "interviews/" + argument("id"),
        value -> {
          clear();
          var o = value.getAsJsonObject();
          var a = o.getAsJsonObject("activity");
          String state = s(a, "state");
          if (state.equals("COMPLETED") || state.equals("FINISHING")) {
            replace("Report", args("id", argument("id"), "module", "INTERVIEW"));
            return;
          }
          if (!state.equals("ACTIVE")) {
            heading("Answer submitted", "Preparing your next question.");
            text("Your answer is saved. AI feedback and a relevant follow-up are being prepared on your laptop. Your answer time is paused.");
            button("Check for next question", this::render);
            processingJobs(argument("id"), this::render);
            polling.postDelayed(this::render, 5000);
            return;
          }
          int seq = o.get("sequence").getAsInt();
          String previousSequence = model.saved("voiceSequence", "");
          if (!previousSequence.isBlank() && !previousSequence.equals(Integer.toString(seq)))
            clearRecordingForNextQuestion();
          model.save("voiceSequence", Integer.toString(seq));
          var turn = o.getAsJsonArray("turns").get(seq).getAsJsonObject();
          heading("Question " + (seq + 1), s(turn, "topic").replace('-', ' '));
          answerDeadline = SystemClock.elapsedRealtime() + Math.max(0, o.get("remainingSeconds").getAsInt()) * 1000L;
          timeRemaining = text("");
          updateCountdown();
          section(s(turn, "question"));
          boolean voice = s(a.getAsJsonObject("context"), "answerMode").equals("VOICE");
          final EditText[] answerField = new EditText[1];
          if (voice)
            recordingControls(180, media -> {
              String transcript = s(media, "transcript").trim();
              if (transcript.isEmpty()) {
                message.setText("No speech was recognized. Record again or type your answer.");
                return;
              }
              EditText answer = answerField[0];
              if (answer == null) return;
              String current = answer.getText().toString().trim();
              if (current.isEmpty() || current.equals(transcript)) {
                answer.setText(transcript);
                message.setText("Transcript loaded. Correct recognition mistakes, then submit your answer.");
              } else {
                new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Transcript is ready")
                    .setMessage("Use the recognized words or keep the answer you typed?")
                    .setPositiveButton("Use transcript", (dialog, which) -> answer.setText(transcript))
                    .setNegativeButton("Keep my answer", null)
                    .show();
              }
            });
          answerField[0] = answerEditor(seq, voice);
          button(
              "Finish interview",
              () -> new MaterialAlertDialogBuilder(requireContext())
                  .setTitle("Finish interview?")
                  .setMessage("The current answer has not been submitted. Only earlier submitted answers will be included in your report.")
                  .setPositiveButton("Finish now", (dialog, which) -> finishInterview())
                  .setNegativeButton("Keep answering", null)
                  .show());
        });
  }

  private void updateCountdown() {
    if (!isAdded() || timeRemaining == null) return;
    long seconds = Math.max(0, (answerDeadline - SystemClock.elapsedRealtime() + 999) / 1000);
    timeRemaining.setText(
        seconds == 0
            ? "Answer time has ended. Finish to see your report."
            : String.format(java.util.Locale.US, "Answer time remaining · %02d:%02d", seconds / 60, seconds % 60));
    if (seconds > 0) countdown.postDelayed(this::updateCountdown, 1000);
  }

  private void finishInterview() {
    if (isRecording()) {
      message.setText("Stop recording before finishing the interview.");
      return;
    }
    write(
        "POST",
        "interviews/" + argument("id") + "/finish",
        json(),
        result -> replace("Report", args("id", argument("id"), "module", "INTERVIEW", "jobId", s(result.getAsJsonObject(), "jobId"))));
  }

  private EditText answerEditor(int seq, boolean voice) {
    EditText answer =
        field(voice ? "Review transcript or type your answer" : "Your answer", model.saved("answer-" + seq, ""), true);
    saved(answer, "answer-" + seq);
    var submit = button(
        "Submit answer and continue",
        () -> {
          if (SystemClock.elapsedRealtime() >= answerDeadline) {
            message.setText("Answer time has ended. Finish the interview to see your report.");
            return;
          }
          if (isRecording()) {
            message.setText("Stop recording before submitting your answer.");
            return;
          }
          String text = answer.getText().toString().trim();
          if (text.isEmpty()) {
            message.setText("Enter an answer or review your recording before continuing.");
            return;
          }
          if (voice && hasRecording() && model.saved("mediaId", "").isBlank()) {
            new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Recording not attached")
                .setMessage("Review transcript to attach your recording. You can also submit the written answer without audio.")
                .setPositiveButton("Submit text only", (dialog, which) -> submitAnswer(seq, text, null))
                .setNegativeButton("Review recording", null)
                .show();
            return;
          }
          submitAnswer(seq, text, voice ? model.saved("mediaId", "") : null);
        });
    submit.setBackgroundTintList(ColorStateList.valueOf(requireContext().getColor(R.color.edge_primary)));
    submit.setTextColor(Color.WHITE);
    submit.setStrokeWidth(0);
    return answer;
  }

  private void submitAnswer(int seq, String text, String mediaId) {
    String submission = model.saved("submission-" + seq, "");
    if (submission.isBlank()) {
      submission = java.util.UUID.randomUUID().toString();
      model.save("submission-" + seq, submission);
    }
    write(
        "POST",
        "interviews/" + argument("id") + "/answers",
        json(
            "sequence",
            seq,
            "text",
            text,
            "mediaId",
            mediaId == null || mediaId.isBlank() ? null : mediaId,
            "submissionKey",
            submission),
        value -> {
          clearRecordingForNextQuestion();
          render();
        });
  }

  public void onDestroyView() {
    polling.removeCallbacksAndMessages(null);
    countdown.removeCallbacksAndMessages(null);
    super.onDestroyView();
  }
}

package com.interviewedge.ui.screens;

import android.os.*;
import android.widget.EditText;
import androidx.activity.OnBackPressedCallback;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.*;
import com.interviewedge.ui.RecordingScreen;

public class InterviewScreen extends RecordingScreen {
  private final Handler polling = new Handler(Looper.getMainLooper());

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
    heading("Mock interview", "Loading your session…");
    get(
        "interviews/" + argument("id"),
        value -> {
          clear();
          var o = value.getAsJsonObject();
          var a = o.getAsJsonObject("activity");
          String state = s(a, "state");
          if (state.equals("COMPLETED") || state.equals("FINISHING")) {
            navigate("Report", args("id", argument("id"), "module", "INTERVIEW"));
            return;
          }
          if (!state.equals("ACTIVE")) {
            text("Your answer is saved. Preparing feedback and the next question…");
            button("Refresh", this::render);
            processingJobs(argument("id"), this::render);
            polling.postDelayed(this::render, 5000);
            return;
          }
          int seq = o.get("sequence").getAsInt();
          var turn = o.getAsJsonArray("turns").get(seq).getAsJsonObject();
          heading("Question " + (seq + 1), s(turn, "topic").replace('-', ' '));
          text("Answer time remaining · " + o.get("remainingSeconds").getAsInt() / 60 + " min");
          section(s(turn, "question"));
          if (s(a.getAsJsonObject("context"), "answerMode").equals("VOICE")) {
            recordingControls(180, m -> answerEditor(seq, s(m, "transcript"), s(m, "id")));
            button("Use text for this answer", () -> answerEditor(seq, "", null));
          } else answerEditor(seq, model.saved("answer-" + seq, ""), null);
          button(
              "Finish interview",
              () ->
                  write(
                      "POST",
                      "interviews/" + argument("id") + "/finish",
                      json(),
                      r ->
                          navigate(
                              "Report",
                              args(
                                  "id",
                                  argument("id"),
                                  "module",
                                  "INTERVIEW",
                                  "jobId",
                                  s(r.getAsJsonObject(), "jobId")))));
        });
  }

  private void answerEditor(int seq, String initial, String mediaId) {
    EditText answer =
        field(mediaId == null ? "Your answer" : "Review your transcript", initial, true);
    saved(answer, "answer-" + seq);
    button(
        "Submit answer",
        () -> {
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
                  answer.getText().toString(),
                  "mediaId",
                  mediaId,
                  "submissionKey",
                  submission),
              value -> {
                model.save("audioFile", "");
                render();
              });
        });
  }

  public void onDestroyView() {
    polling.removeCallbacksAndMessages(null);
    super.onDestroyView();
  }
}

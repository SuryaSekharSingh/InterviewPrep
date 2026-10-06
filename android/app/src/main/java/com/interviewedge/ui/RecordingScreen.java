package com.interviewedge.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.os.*;
import android.widget.*;
import androidx.activity.result.*;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import com.google.gson.*;
import com.interviewedge.media.PcmRecorder;
import java.io.File;
import java.util.function.Consumer;

public abstract class RecordingScreen extends BaseScreen {
  private PcmRecorder recorder;
  private MediaPlayer player;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private TextView status;
  private Button retryTranscription;
  private long started;
  private int max;
  private int recordingGeneration;
  private Consumer<JsonObject> ready;
  private final ActivityResultLauncher<String> permission =
      registerForActivityResult(
          new ActivityResultContracts.RequestPermission(),
          allowed -> {
            if (allowed) startRecording();
            else if (message != null)
              message.setText("Microphone access is needed. You can use text mode for interviews.");
          });

  protected void recordingControls(int seconds, Consumer<JsonObject> onReady) {
    max = seconds;
    ready = onReady;
    status = text(hasRecording() ? "Recording saved on this device. Play or review it." : "Your microphone is off.");
    button(
        "Record answer",
        () -> {
          if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
              != PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.RECORD_AUDIO);
          else startRecording();
        });
    button("Stop recording", this::stopRecording);
    button(
        "Play recording",
        () -> {
          if (isRecording()) {
            message.setText("Stop recording before playback.");
            return;
          }
          File file = existingRecording();
          if (file == null || file.length() <= 44) {
            message.setText("Record an answer first.");
            return;
          }
          try {
            stopPlayback();
            player = new MediaPlayer();
            player.setDataSource(file.getAbsolutePath());
            player.setOnCompletionListener(p -> {
              stopPlayback();
              if (status != null) status.setText("Playback finished. Review the transcript or record again.");
            });
            player.prepare();
            player.start();
            status.setText("Playing your recording…");
          } catch (Exception e) {
            stopPlayback();
            message.setText("This recording could not be played. Please record again.");
          }
        });
    button(
        "Review transcript",
        () -> {
          File file = existingRecording();
          if (isRecording()) {
            message.setText("Stop recording first.");
            return;
          }
          if (file == null || file.length() <= 44) {
            message.setText("Record an answer first.");
            return;
          }
          int generation = recordingGeneration;
          status.setText("Uploading your recording…");
          model.upload(
              file,
              value -> {
                if (!isAdded() || generation != recordingGeneration) return;
                var o = value.getAsJsonObject();
                var media = o.getAsJsonObject("media");
                String mediaId = s(media, "id");
                if ("READY".equals(s(media, "state"))) {
                  model.save("mediaId", mediaId);
                  status.setText("Transcript ready. Review your answer before submitting.");
                  if (ready != null) ready.accept(media);
                } else {
                  status.setText("Transcribing on your laptop…");
                  waitForTranscript(s(o, "jobId"), mediaId, generation);
                }
              },
              error -> {
                if (isAdded() && generation == recordingGeneration && status != null)
                  status.setText("Upload failed. Your recording is saved here; tap Review transcript to retry.");
              });
        });
    text("Review the transcript to attach the recording. If transcription is unavailable, type your answer and submit it as text.");
  }

  private File existingRecording() {
    String name = model.saved("audioFile", "");
    return name.isBlank() ? null : new File(name);
  }

  protected boolean hasRecording() {
    File file = existingRecording();
    return file != null && file.isFile() && file.length() > 44;
  }

  protected boolean isRecording() {
    return recorder != null && recorder.recording();
  }

  protected void clearRecordingForNextQuestion() {
    recordingGeneration++;
    handler.removeCallbacksAndMessages(null);
    stopPlayback();
    File file = existingRecording();
    if (file != null) file.delete();
    model.save("audioFile", "");
    model.save("mediaId", "");
  }

  private void startRecording() {
    if (recorder != null && recorder.recording()) return;
    try {
      stopPlayback();
      if (recorder != null) recorder.release();
      recorder = new PcmRecorder();
      File previous = existingRecording();
      File next = new File(requireContext().getFilesDir(), "answer-" + java.util.UUID.randomUUID() + ".wav");
      recorder.start(next, max);
      recordingGeneration++;
      handler.removeCallbacksAndMessages(null);
      if (previous != null) previous.delete();
      model.save("audioFile", next.getAbsolutePath());
      started = SystemClock.elapsedRealtime();
      model.save("mediaId", "");
      if (retryTranscription != null) retryTranscription.setVisibility(android.view.View.GONE);
      message.setText("");
      tickRecording();
    } catch (Exception e) {
      message.setText("Microphone unavailable. Try again.");
    }
  }

  private void tickRecording() {
    if (!isAdded() || status == null || recorder == null) return;
    long elapsed = (SystemClock.elapsedRealtime() - started) / 1000;
    status.setText(
        recorder.recording()
            ? String.format(
                java.util.Locale.US,
                "Recording · %02d:%02d · microphone on",
                elapsed / 60,
                elapsed % 60)
            : "Recording stopped. Review before submitting.");
    if (recorder.recording()) handler.postDelayed(this::tickRecording, 1000);
  }

  private void stopRecording() {
    if (recorder == null || !recorder.recording()) return;
    try {
      recorder.stop();
      if (status != null)
        status.setText(hasRecording() ? "Recording saved. Play it or review the transcript." : "No audio was captured. Record again.");
    } catch (Exception e) {
      if (message != null) message.setText(e.getMessage());
    }
  }

  private void waitForTranscript(String jobId, String mediaId, int generation) {
    if (jobId.isBlank() || generation != recordingGeneration) {
      message.setText("Transcription could not start. Please try Review transcript again.");
      return;
    }
    get(
        "jobs/" + jobId,
        value -> {
          if (generation != recordingGeneration) return;
          String state = s(value.getAsJsonObject(), "state");
          if (state.equals("COMPLETED"))
            get(
                "media/" + mediaId,
                m -> {
                  if (generation != recordingGeneration) return;
                  model.save("mediaId", mediaId);
                  status.setText("Transcript ready. Review your answer before submitting.");
                  if (ready != null) ready.accept(m.getAsJsonObject());
                });
          else if (state.equals("FAILED")) {
            status.setText("Transcription failed. Your recording is still saved.");
            message.setText("Check the local speech service, retry, or type your answer below.");
            if (retryTranscription == null) {
              retryTranscription = button("Retry transcription", () -> {
                status.setText("Retrying transcription…");
                write("POST", "jobs/" + jobId + "/retry", json(), v -> waitForTranscript(jobId, mediaId, generation));
              });
            }
            retryTranscription.setVisibility(android.view.View.VISIBLE);
          } else {
            status.setText("Transcribing on your laptop…");
            handler.postDelayed(() -> waitForTranscript(jobId, mediaId, generation), 2500);
          }
        });
  }

  private void stopPlayback() {
    if (player != null) {
      player.release();
      player = null;
    }
  }

  public void onStop() {
    stopRecording();
    stopPlayback();
    super.onStop();
  }

  public void onDestroyView() {
    recordingGeneration++;
    handler.removeCallbacksAndMessages(null);
    if (recorder != null) recorder.release();
    stopPlayback();
    retryTranscription = null;
    status = null;
    super.onDestroyView();
  }
}

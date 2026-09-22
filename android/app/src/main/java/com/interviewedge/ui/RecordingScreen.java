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
  private long started;
  private int max;
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
    status = text("Your microphone is off.");
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
          try {
            stopPlayback();
            player = new MediaPlayer();
            player.setDataSource(audioFile().getAbsolutePath());
            player.prepare();
            player.start();
          } catch (Exception e) {
            message.setText("Record an answer first.");
          }
        });
    button(
        "Review transcript",
        () -> {
          File file = audioFile();
          if (recorder != null && recorder.recording()) {
            message.setText("Stop recording first.");
            return;
          }
          if (!file.isFile() || file.length() <= 44) {
            message.setText("Record an answer first.");
            return;
          }
          model.upload(
              file,
              value -> {
                if (!isAdded()) return;
                var o = value.getAsJsonObject();
                model.save("mediaId", o.getAsJsonObject("media").get("id").getAsString());
                waitForTranscript(s(o, "jobId"));
              });
        });
    text("Your recording is processed on the laptop. You can replace it before submitting.");
  }

  private File audioFile() {
    String name = model.saved("audioFile", "");
    if (name.isBlank()) {
      name =
          new File(requireContext().getFilesDir(), "answer-" + java.util.UUID.randomUUID() + ".wav")
              .getAbsolutePath();
      model.save("audioFile", name);
    }
    return new File(name);
  }

  private void startRecording() {
    if (recorder != null && recorder.recording()) return;
    try {
      stopPlayback();
      if (recorder != null) recorder.release();
      recorder = new PcmRecorder();
      recorder.start(audioFile(), max);
      started = SystemClock.elapsedRealtime();
      model.save("mediaId", "");
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
    if (recorder == null) return;
    try {
      recorder.stop();
      if (status != null) status.setText("Recording stopped. Ready for review.");
    } catch (Exception e) {
      if (message != null) message.setText(e.getMessage());
    }
  }

  private void waitForTranscript(String jobId) {
    get(
        "jobs/" + jobId,
        value -> {
          String state = s(value.getAsJsonObject(), "state");
          if (state.equals("COMPLETED"))
            get(
                "media/" + model.saved("mediaId", ""),
                m -> {
                  if (ready != null) ready.accept(m.getAsJsonObject());
                });
          else if (state.equals("FAILED")) {
            message.setText("Transcription failed. Check the local speech service.");
            button(
                "Retry transcription",
                () ->
                    write(
                        "POST", "jobs/" + jobId + "/retry", json(), v -> waitForTranscript(jobId)));
          } else {
            status.setText("Transcribing on your laptop…");
            handler.postDelayed(() -> waitForTranscript(jobId), 2500);
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
    handler.removeCallbacksAndMessages(null);
    if (recorder != null) recorder.release();
    super.onDestroyView();
  }
}

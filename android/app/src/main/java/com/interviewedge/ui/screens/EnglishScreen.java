package com.interviewedge.ui.screens;

import android.widget.EditText;
import com.interviewedge.ui.RecordingScreen;

public class EnglishScreen extends RecordingScreen {
  protected boolean focus() {
    return true;
  }

  protected void render() {
    heading("Self-introduction", "Aim for 60–90 seconds. Maximum two minutes.");
    text(
        "Introduce yourself, describe one project and your contribution, and explain your career"
            + " goal.");
    recordingControls(
        120,
        media -> {
          clear();
          heading(
              "Review your answer",
              "Correct recognition mistakes before submitting. Edited wording is labelled"
                  + " separately.");
          EditText transcript = field("Transcript", s(media, "transcript"), true);
          button(
              "Get feedback",
              () -> {
                String existing = model.saved("activityId", argument("id"));
                if (!existing.isBlank())
                  submit(existing, s(media, "id"), transcript.getText().toString());
                else
                  write(
                      "POST",
                      "english/attempts",
                      json("previousId", argument("previousId")),
                      created -> {
                        String id = s(created.getAsJsonObject().getAsJsonObject("activity"), "id");
                        model.save("activityId", id);
                        submit(id, s(media, "id"), transcript.getText().toString());
                      });
              });
          button(
              "Record again",
              () -> {
                clear();
                render();
              });
        });
  }

  private void submit(String id, String mediaId, String transcript) {
    write(
        "POST",
        "english/attempts/" + id + "/submit",
        json("mediaId", mediaId, "transcript", transcript),
        r ->
            navigate(
                "Report",
                args("id", id, "module", "ENGLISH", "jobId", s(r.getAsJsonObject(), "jobId"))));
  }
}

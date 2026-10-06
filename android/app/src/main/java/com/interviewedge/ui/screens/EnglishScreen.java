package com.interviewedge.ui.screens;

import android.widget.EditText;
import com.interviewedge.ui.BaseScreen;

public class EnglishScreen extends BaseScreen {
  protected boolean focus() {
    return true;
  }

  protected void render() {
    heading("Written self-introduction", "Write a clear introduction in your own words.");
    text("Introduce yourself, describe one project and your contribution, and explain your career goal.");
    EditText answer = field("Your introduction", model.saved("englishAnswer", ""), true);
    saved(answer, "englishAnswer");
    button(
        "Get feedback",
        () -> {
          String written = answer.getText().toString().trim();
          if (written.isEmpty()) {
            message.setText("Write your introduction before requesting feedback.");
            return;
          }
          get(
              "catalog",
              response -> {
                var catalog = response.getAsJsonObject();
                if (!catalog.has("englishPrompt")
                    || !s(catalog.getAsJsonObject("englishPrompt"), "version")
                        .equals("intro-written-v1")) {
                  message.setText(
                      "The backend is still running an older version. Restart it, then tap Get feedback again.");
                  return;
                }
                String existing = model.saved("activityId", "");
                if (!existing.isBlank()
                    && !model.saved("activityPromptVersion", "").equals("intro-written-v1")) {
                  existing = "";
                  model.save("activityId", "");
                  model.newKey();
                }
                if (!existing.isBlank()) submit(existing, written);
                else
                  write(
                      "POST",
                      "english/attempts",
                      json("previousId", argument("previousId")),
                      created -> {
                        String id = s(created.getAsJsonObject().getAsJsonObject("activity"), "id");
                        model.save("activityId", id);
                        model.save("activityPromptVersion", "intro-written-v1");
                        submit(id, written);
                      });
              });
        });
  }

  private void submit(String id, String answer) {
    write(
        "POST",
        "english/attempts/" + id + "/submit",
        json("text", answer),
        result ->
            navigate(
                "Report",
                args("id", id, "module", "ENGLISH", "jobId", s(result.getAsJsonObject(), "jobId"))));
  }
}

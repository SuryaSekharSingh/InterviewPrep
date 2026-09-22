package com.interviewedge.ui.screens;

import android.os.*;
import com.google.gson.*;
import com.interviewedge.ui.BaseScreen;

public class ReportScreen extends BaseScreen {
  private final Handler polling = new Handler(Looper.getMainLooper());

  protected void render() {
    polling.removeCallbacksAndMessages(null);
    heading("Your report", "Practice feedback, with evidence.");
    String module = argument("module"), id = argument("id");
    String path =
        module.equals("TEST")
            ? "tests/attempts/" + id + "/result"
            : module.equals("ENGLISH")
                ? "english/attempts/" + id + "/report"
                : "interviews/" + id + "/report";
    get(
        path,
        value -> {
          clear();
          var o = value.getAsJsonObject();
          if (o.has("state")) {
            text(
                "Your activity is "
                    + s(o, "state").toLowerCase(java.util.Locale.ROOT)
                    + ". Answers are saved.");
            button("Refresh report", this::render);
            String job = argument("jobId");
            processingJobs(id, this::render);
            if (!s(o, "state").equals("ACTIVE")) polling.postDelayed(this::render, 5000);
            return;
          }
          section(score(o, "score"));
          if (o.has("edited") && o.get("edited").getAsBoolean())
            text("Edited transcript · language practice only. Excluded from speaking progress.");
          if (o.has("pending") && o.get("pending").getAsInt() > 0)
            text(
                s(o, "pending")
                    + " short answers await reviewer approval. Graded subtotal: "
                    + score(o, "objectiveSubtotal"));
          if (o.has("strengths")) text("Strengths\n" + list(o.get("strengths")));
          if (o.has("improvements")) text("Focus next\n" + list(o.get("improvements")));
          if (o.has("evaluation")) evaluation(o.getAsJsonObject("evaluation"));
          if (o.has("metrics")) {
            var m = o.getAsJsonObject("metrics");
            text(
                "Approximate speaking rate: "
                    + s(m, "wordsPerMinute")
                    + " words/min\nApproximate fillers: "
                    + s(m, "fillerCount"));
          }
          if (o.has("change"))
            text("Change from previous comparable attempt: " + s(o, "change") + " points");
          if (o.has("transcript"))
            button(
                "Review transcript",
                () -> {
                  clear();
                  heading("Your transcript", "");
                  text(s(o, "transcript"));
                  button("Back to report", this::render);
                });
          if (o.has("items"))
            button("Review questions", () -> reviewItems(o.getAsJsonArray("items")));
          if (o.has("answers"))
            button("Review interview answers", () -> reviewAnswers(o.getAsJsonArray("answers")));
          if (module.equals("ENGLISH"))
            button("Try again", () -> navigate("English", args("previousId", id)));
          else button("Choose next practice", () -> root("Practice"));
          button("View progress", () -> root("Progress"));
        });
  }

  private String list(JsonElement element) {
    StringBuilder b = new StringBuilder();
    for (var v : element.getAsJsonArray()) b.append("• ").append(v.getAsString()).append("\n");
    return b.toString();
  }

  private void evaluation(JsonObject e) {
    if (e.has("strengths")) text("Strengths\n" + list(e.get("strengths")));
    if (e.has("improvements")) text("Improve\n" + list(e.get("improvements")));
    if (e.has("dimensions")) text("Rubric ratings (0–4)\n" + e.get("dimensions").toString());
    button(
        "Suggested improved answer",
        () ->
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Suggested wording")
                .setMessage(s(e, "improvedAnswer"))
                .setPositiveButton("Close", null)
                .show());
  }

  private void reviewItems(JsonArray items) {
    clear();
    heading("Question review", "Reviewed answers and explanations");
    for (var value : items) {
      var q = value.getAsJsonObject();
      section(s(q, "prompt"));
      text("Your answer: " + s(q, "response"));
      text("Reference: " + s(q, "correctAnswer"));
      text(s(q, "explanation"));
      text(s(q, "gradingStatus"));
    }
    button("Back to report", this::render);
  }

  private void reviewAnswers(JsonArray answers) {
    clear();
    heading("Answer review", "Feedback relates to your recorded response.");
    for (var value : answers) {
      var a = value.getAsJsonObject();
      section(s(a, "question"));
      text(s(a, "answer"));
      evaluation(a.getAsJsonObject("evaluation"));
    }
    button("Back to report", this::render);
  }

  public void onDestroyView() {
    polling.removeCallbacksAndMessages(null);
    super.onDestroyView();
  }
}

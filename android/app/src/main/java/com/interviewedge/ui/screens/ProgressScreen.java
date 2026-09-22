package com.interviewedge.ui.screens;

import com.google.gson.*;
import com.interviewedge.ui.BaseScreen;

public class ProgressScreen extends BaseScreen {
  protected void render() {
    heading("Your progress", "Performance and evidence, in one place.");
    get(
        "progress?days=30",
        value -> {
          clear();
          var o = value.getAsJsonObject();
          var summary = o.getAsJsonObject("summary");
          section(score(summary, "overallScore"));
          text(
              "Practice score = Interview 40% + Tests 40% + English 20%. This is not a placement"
                  + " prediction.");
          var modules = summary.getAsJsonObject("moduleScores");
          for (String key : new String[] {"INTERVIEW", "TEST", "ENGLISH"})
            text(key.toLowerCase() + " · " + score(modules, key));
          button("Topic strengths and gaps", this::competencies);
          button("Activity history", () -> navigate("History", args()));
          button("All recommendations", this::recommendations);
          JsonArray timeline = o.getAsJsonArray("timeline");
          if (!timeline.isEmpty()) {
            section("Recent checkpoints");
            for (int i = Math.max(0, timeline.size() - 5); i < timeline.size(); i++) {
              var point = timeline.get(i).getAsJsonObject();
              text(
                  s(point, "at").substring(0, 10)
                      + " · "
                      + score(point.getAsJsonObject("summary"), "overallScore"));
            }
          }
        });
  }

  private void competencies() {
    get(
        "competencies",
        value -> {
          clear();
          heading("Competencies", "Latest evidence from your practice.");
          if (value.getAsJsonArray().isEmpty()) text("Complete an activity to begin.");
          for (var item : value.getAsJsonArray()) {
            var o = item.getAsJsonObject();
            section(s(o, "id").replace('-', ' '));
            text(score(o, "score") + " · " + s(o, "evidenceCount") + " activities");
            text(
                "Last assessed "
                    + s(o, "lastAssessedAt").substring(0, 10)
                    + (o.get("needsRefresh").getAsBoolean() ? " · Needs refresh" : ""));
          }
          button("Back to progress", this::render);
        });
  }

  private void recommendations() {
    get(
        "recommendations",
        value -> {
          clear();
          heading("Recommended next", "Every suggestion has a reason.");
          for (var item : value.getAsJsonArray()) {
            var o = item.getAsJsonObject();
            section(s(o, "title"));
            text(s(o, "reason"));
            button(
                "Start",
                () ->
                    navigate(
                        HomeScreen.route(s(o, "module")),
                        args("topicId", s(o, "topicId"), "difficulty", s(o, "difficulty"))));
            button(
                "Dismiss for three days",
                () ->
                    write(
                        "PATCH", "recommendations/" + s(o, "id"), json(), v -> recommendations()));
          }
          button("Back", this::render);
        });
  }
}

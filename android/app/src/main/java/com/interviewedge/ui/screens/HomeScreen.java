package com.interviewedge.ui.screens;

import android.widget.LinearLayout;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.gson.*;
import com.interviewedge.R;
import com.interviewedge.ui.*;

public class HomeScreen extends BaseScreen {
  protected void render() {
    heading("Your practice", "A small step towards your next opportunity.");
    get(
        "dashboard",
        value -> {
          clear();
          JsonObject o = value.getAsJsonObject();
          heading("Hi, " + s(o, "displayName"), "Make room for your next opportunity.");
          Double score =
              o.get("overallScore").isJsonNull() ? null : o.get("overallScore").getAsDouble();
          var hero =
              card(
                  "YOUR NEXT CHAPTER",
                  score == null ? "Your next interview starts here." : "Keep your momentum going.",
                  score == null
                      ? "One focused session is all it takes to discover your starting point."
                      : "Build on what you know. A little practice today makes a difference.",
                  null);
          hero.setCardBackgroundColor(color(R.color.edge_primary_soft));
          hero.setStrokeWidth(0);
          button("Start a practice session", () -> root("Practice"));

          section("Your week at a glance");
          var weekly =
              card(
                  "WEEKLY GOAL",
                  s(o, "weeklyCompleted") + " of " + s(o, "weeklyGoal") + " activities",
                  "Progress comes from showing up. Keep taking small steps.",
                  null);
          LinearLayout weeklyContent = (LinearLayout) weekly.getChildAt(0);
          LinearProgressIndicator progress = new LinearProgressIndicator(requireContext());
          progress.setTrackThickness(dp(6));
          progress.setTrackCornerRadius(dp(3));
          progress.setIndicatorColor(color(R.color.edge_primary));
          progress.setTrackColor(color(R.color.edge_primary_soft));
          int completed = o.get("weeklyCompleted").getAsInt();
          int goal = o.get("weeklyGoal").getAsInt();
          progress.setProgress(goal > 0 ? Math.max(0, Math.min(100, completed * 100 / goal)) : 0);
          progress.setContentDescription(
              completed + " of " + goal + " weekly activities completed");
          LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(6));
          progressParams.topMargin = dp(16);
          weeklyContent.addView(progress, progressParams);
          card(
              "PRACTICE SCORE",
              score == null ? "A fresh start" : score(o, "overallScore"),
              score == null
                  ? "Complete your first activity to build your baseline."
                  : s(o, "scoreLabel") + " · See your strengths and progress.",
              () -> root("Progress"));
          JsonArray rec = o.getAsJsonArray("recommendations");
          if (!rec.isEmpty()) {
            section("Made for your next step");
            var r = rec.get(0).getAsJsonObject();
            card(
                "RECOMMENDED NEXT",
                s(r, "title"),
                s(r, "reason"),
                () ->
                    navigate(
                        route(s(r, "module")),
                        args("topicId", s(r, "topicId"), "difficulty", s(r, "difficulty"))));
          }
        });
  }

  static String route(String module) {
    return switch (module) {
      case "TEST" -> "TestSetup";
      case "ENGLISH" -> "English";
      default -> "InterviewSetup";
    };
  }
}

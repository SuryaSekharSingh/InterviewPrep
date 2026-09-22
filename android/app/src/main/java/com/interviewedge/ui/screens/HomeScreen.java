package com.interviewedge.ui.screens;

import android.widget.LinearLayout;
import com.google.gson.*;
import com.interviewedge.ui.*;

public class HomeScreen extends BaseScreen {
  protected void render() {
    heading("Your practice", "A small step towards your next opportunity.");
    get(
        "dashboard",
        value -> {
          clear();
          JsonObject o = value.getAsJsonObject();
          heading("Hi, " + s(o, "displayName"), "Keep your next step simple.");
          text(s(o, "scoreLabel"));
          Double score =
              o.get("overallScore").isJsonNull() ? null : o.get("overallScore").getAsDouble();
          ScoreRing ring = new ScoreRing(requireContext(), score);
          body.addView(ring, new LinearLayout.LayoutParams(-1, dp(220)));
          ring.setOnClickListener(v -> root("Progress"));
          ring.setFocusable(true);
          card(
              "WEEKLY GOAL",
              s(o, "weeklyCompleted") + " of " + s(o, "weeklyGoal") + " activities",
              "Small, consistent sessions build stronger interview recall.",
              null);
          JsonArray rec = o.getAsJsonArray("recommendations");
          if (!rec.isEmpty()) {
            var r = rec.get(0).getAsJsonObject();
            card(
                "RECOMMENDED NEXT",
                s(r, "title"),
                s(r, "reason"),
                () ->
                    navigate(
                        route(s(r, "module")),
                        args("topicId", s(r, "topicId"), "difficulty", s(r, "difficulty"))));
          } else button("Choose practice", () -> root("Practice"));
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

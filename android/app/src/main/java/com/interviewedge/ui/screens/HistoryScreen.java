package com.interviewedge.ui.screens;

import com.interviewedge.ui.BaseScreen;

public class HistoryScreen extends BaseScreen {
  protected void render() {
    heading("Activity history", "Open a report or resume saved work.");
    get(
        "activities",
        value -> {
          clear();
          for (var item : value.getAsJsonArray()) {
            var o = item.getAsJsonObject();
            String module = s(o, "module"), state = s(o, "state"), id = s(o, "id");
            section(
                module.toLowerCase(java.util.Locale.ROOT)
                    + " · "
                    + s(o, "createdAt").substring(0, 10));
            text(state + " · " + score(o, "score"));
            boolean report =
                state.equals("COMPLETED")
                    || state.equals("FINISHING")
                    || (module.equals("ENGLISH") && !state.equals("ACTIVE"));
            button(
                report ? "View report" : "Resume",
                () ->
                    navigate(
                        report
                            ? "Report"
                            : module.equals("TEST")
                                ? "Test"
                                : module.equals("INTERVIEW") ? "Interview" : "English",
                        args("id", id, "module", module)));
          }
          if (value.getAsJsonArray().isEmpty())
            text("Your completed and saved activities will appear here.");
        });
  }
}

package com.interviewedge.ui.screens;

import android.widget.*;
import com.google.gson.*;
import com.interviewedge.ui.BaseScreen;
import java.util.*;

public class TestSetupScreen extends BaseScreen {
  protected void render() {
    heading("Subject test", "Choose a short, focused practice session.");
    get(
        "catalog",
        value -> {
          clear();
          var topics = value.getAsJsonObject().getAsJsonArray("topics");
          Choice subject =
              choiceCards(
                  "Subject",
                  List.of("DSA", "DBMS", "OS"),
                  model.saved(
                      "subject",
                      argument("topicId").startsWith("dbms-")
                          ? "DBMS"
                          : argument("topicId").startsWith("os-") ? "OS" : "DSA"));
          button(
              "Choose topics",
              () -> {
                model.save("subject", subject.value());
                settings(topics);
              });
        });
  }

  private void settings(JsonArray topics) {
    clear();
    heading(
        model.saved("subject", "DSA") + " test",
        "Two minutes per question. The deadline continues if you disconnect.");
    List<String> ids = new ArrayList<>(List.of("")), names = new ArrayList<>(List.of("All topics"));
    for (var t : topics) {
      var o = t.getAsJsonObject();
      if (s(o, "subject").equals(model.saved("subject", "DSA"))) {
        ids.add(s(o, "id"));
        names.add(s(o, "name"));
      }
    }
    card(
        "STARTER TEST",
        "Mixed topics",
        "This build draws five questions from across the selected subject.",
        null);
    Choice difficulty =
        choiceCards(
            "Difficulty",
            List.of("Easy", "Medium", "Hard"),
            titleCase(argument("difficulty").isBlank() ? "EASY" : argument("difficulty")));
    text("Five questions · two minutes per question");
    button(
        "Start test",
        () ->
            write(
                "POST",
                "tests/attempts",
                json(
                    "subject",
                    model.saved("subject", "DSA"),
                    "topicId",
                    "",
                    "difficulty",
                    difficulty.value().toUpperCase(Locale.ROOT),
                    "count",
                    5),
                result -> {
                  model.newKey();
                  navigate(
                      "Test",
                      args(
                          "id",
                          result
                              .getAsJsonObject()
                              .getAsJsonObject("activity")
                              .get("id")
                              .getAsString()));
                }));
    button("Back", this::render);
  }

  private String titleCase(String value) {
    return value.substring(0, 1) + value.substring(1).toLowerCase(Locale.ROOT);
  }
}

package com.interviewedge.ui.screens;

import android.widget.*;
import com.google.gson.*;
import com.interviewedge.ui.BaseScreen;
import java.util.*;

public class InterviewSetupScreen extends BaseScreen {
  private JsonArray roles;

  protected void render() {
    heading("Set up interview", "One step at a time.");
    get(
        "catalog",
        value -> {
          roles = value.getAsJsonObject().getAsJsonArray("roles");
          step(Integer.parseInt(model.saved("step", "0")));
        });
  }

  private void step(int number) {
    model.save("step", Integer.toString(number));
    clear();
    heading("Set up interview", "Step " + (number + 1) + " of 3  ·  " + stepName(number));
    if (roles == null || roles.isEmpty()) {
      text("The role catalogue is not ready. Please try again after setup.");
      return;
    }
    if (number == 0) {
      List<String> names = new ArrayList<>();
      for (var r : roles) names.add(r.getAsJsonObject().get("name").getAsString());
      Choice role = choiceCards("Target role", names, model.saved("roleName", names.get(0)));
      text("We’ll use this role to choose relevant skills and interview prompts.");
      button(
          "Choose skills",
          () -> {
            int index = names.indexOf(role.value());
            var selected = roles.get(index).getAsJsonObject();
            model.save("roleName", selected.get("name").getAsString());
            model.save("roleId", selected.get("id").getAsString());
            skills(selected);
          });
    } else if (number == 1) {
      Choice type =
          choiceCards(
              "Interview type",
              List.of("Technical", "HR", "Mixed"),
              titleCase(model.saved("type", "TECHNICAL")));
      Choice difficulty =
          choiceCards(
              "Difficulty",
              List.of("Easy", "Medium", "Hard"),
              titleCase(model.saved("difficulty", "MEDIUM")));
      button(
          "Continue",
          () -> {
            model.save("type", type.value().toUpperCase(Locale.ROOT));
            model.save("difficulty", difficulty.value().toUpperCase(Locale.ROOT));
            step(2);
          });
      button(
          "Back",
          () -> {
            model.save("type", type.value().toUpperCase(Locale.ROOT));
            model.save("difficulty", difficulty.value().toUpperCase(Locale.ROOT));
            step(0);
          });
    } else {
      Choice minutes =
          choiceCards(
              "Answer time",
              List.of("10 minutes", "20 minutes", "30 minutes"),
              model.saved("minutes", "10") + " minutes");
      card(
          "GOOD TO KNOW",
          "Your full answer time is protected",
          "AI processing does not use your answer time. Type each answer to continue.",
          null);
      button(
          "Start interview",
          () -> {
            model.save("minutes", minutes.value().split(" ")[0]);
            JsonObject body =
                json(
                    "roleId",
                    model.saved("roleId", "java-developer"),
                    "skills",
                    JsonParser.parseString(model.saved("skills", "[]")),
                    "type",
                    model.saved("type", "TECHNICAL"),
                    "difficulty",
                    model.saved("difficulty", "MEDIUM"),
                    "answerMode",
                    "TEXT",
                    "minutes",
                    Integer.parseInt(model.saved("minutes", "10")));
            write(
                "POST",
                "interviews",
                body,
                result -> {
                  model.newKey();
                  navigate(
                      "Interview",
                      args(
                          "id",
                          result
                              .getAsJsonObject()
                              .getAsJsonObject("activity")
                              .get("id")
                              .getAsString()));
                });
          });
      button(
          "Back",
          () -> {
            model.save("minutes", minutes.value().split(" ")[0]);
            step(1);
          });
    }
  }

  private String stepName(int step) {
    return switch (step) {
      case 0 -> "Role";
      case 1 -> "Style";
      default -> "Answer time";
    };
  }

  private String titleCase(String value) {
    if ("HR".equals(value)) return value;
    return value.substring(0, 1) + value.substring(1).toLowerCase(Locale.ROOT);
  }

  private void skills(JsonObject selected) {
    clear();
    heading("Choose skills", "Select the topics you want to practise.");
    List<CheckBox> boxes = new ArrayList<>();
    Set<String> previous = new HashSet<>();
    boolean restore = s(selected, "id").equals(model.saved("skillsRoleId", ""));
    if (restore)
      for (var value : JsonParser.parseString(model.saved("skills", "[]")).getAsJsonArray())
        previous.add(value.getAsString());
    for (var item : selected.getAsJsonArray("skills")) {
      CheckBox box = new CheckBox(requireContext());
      box.setText(item.getAsString());
      box.setChecked(!restore || previous.contains(item.getAsString()));
      box.setMinHeight(dp(48));
      body.addView(box);
      boxes.add(box);
    }
    button(
        "Continue",
        () -> {
          List<String> skills =
              boxes.stream().filter(CheckBox::isChecked).map(b -> b.getText().toString()).toList();
          if (skills.isEmpty()) {
            message.setText("Choose at least one skill.");
            return;
          }
          model.save("skills", new Gson().toJson(skills));
          model.save("skillsRoleId", s(selected, "id"));
          step(1);
        });
  }
}

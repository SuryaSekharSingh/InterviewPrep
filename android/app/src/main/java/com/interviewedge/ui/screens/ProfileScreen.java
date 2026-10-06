package com.interviewedge.ui.screens;

import android.view.WindowManager;
import android.widget.*;
import androidx.activity.result.*;
import androidx.activity.result.contract.ActivityResultContracts;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.*;
import com.interviewedge.ui.BaseScreen;
import java.io.*;
import java.util.*;

public class ProfileScreen extends BaseScreen {
  private File exportFile;
  private final ActivityResultLauncher<String> exportLocation =
      registerForActivityResult(
          new ActivityResultContracts.CreateDocument("application/zip"),
          uri -> {
            if (exportFile == null) return;
            File file = exportFile;
            if (uri == null) {
              file.delete();
              exportFile = null;
              return;
            }
            var resolver = requireContext().getApplicationContext().getContentResolver();
            model.run(
                () -> {
                  try (var input = new FileInputStream(file);
                      var output = resolver.openOutputStream(uri)) {
                    if (output == null)
                      throw new IOException("Could not open the selected location.");
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                  } finally {
                    file.delete();
                  }
                  return true;
                },
                value -> {
                  if (getView() != null) message.setText("Export saved.");
                });
          });

  protected void render() {
    heading("Profile", "Your goals, preferences and privacy.");
    get(
        "catalog",
        catalog ->
            get("me", value -> showProfile(value.getAsJsonObject(), catalog.getAsJsonObject())));
  }

  private void showProfile(JsonObject profile, JsonObject catalog) {
    clear();
    EditText name = field("Name", s(profile, "displayName"), false);
    EditText education = field("Education (optional)", s(profile, "education"), false);
    List<String> roles = new ArrayList<>();
    for (var entry : catalog.getAsJsonArray("roles")) roles.add(s(entry.getAsJsonObject(), "id"));
    if (roles.isEmpty()) {
      text("The catalogue is not ready. Ask the administrator to finish setup.");
      return;
    }
    Spinner role = choice("Target role", roles, s(profile, "roleId"));
    List<String> goals = new ArrayList<>();
    for (int i = 1; i <= 30; i++) goals.add(Integer.toString(i));
    Spinner goal = choice("Weekly practice goal", goals, s(profile, "weeklyGoal"));
    CheckBox consent = new CheckBox(requireContext());
    consent.setText(
        "I agree to processing my written answers for practice feedback. Reports remain until account deletion.");
    consent.setChecked("privacy-v1".equals(s(profile, "consentVersion")));
    consent.setMinHeight(dp(48));
    body.addView(consent);
    button(
        "Save profile",
        () -> {
          JsonArray skills = new JsonArray();
          for (var entry : catalog.getAsJsonArray("roles")) {
            JsonObject preset = entry.getAsJsonObject();
            if (s(preset, "id").equals(role.getSelectedItem().toString()))
              skills = preset.getAsJsonArray("skills");
          }
          write(
              "PATCH",
              "me",
              json(
                  "displayName",
                  name.getText().toString().trim(),
                  "education",
                  education.getText().toString(),
                  "roleId",
                  role.getSelectedItem().toString(),
                  "skills",
                  skills,
                  "weeklyGoal",
                  Integer.parseInt(goal.getSelectedItem().toString()),
                  "consentVersion",
                  consent.isChecked() ? "privacy-v1" : ""),
              saved -> message.setText("Profile saved."));
        });
    button("Change password", this::changePassword);
    button("Export my data", () -> confirmPassword(this::export));
    button(
        "Delete account",
        () ->
            new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete your account?")
                .setMessage(
                    "Your profile and reports will be removed. This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton(
                    "Continue",
                    (dialog, which) ->
                        confirmPassword(() -> write("DELETE", "me", json(), result -> logout())))
                .show());
    button("Log out", this::logout);
  }

  private void confirmPassword(Runnable action) {
    EditText input = new EditText(requireContext());
    input.setHint("Current password");
    input.setInputType(129);
    input.setSaveEnabled(false);
    input.setMinHeight(dp(48));
    var dialog =
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle("Confirm your password")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Confirm", null)
            .create();
    dialog.setOnShowListener(
        ignored -> {
          dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
          dialog
              .getButton(-1)
              .setOnClickListener(
                  view -> {
                    dialog.getButton(-1).setEnabled(false);
                    model
                        .app()
                        .auth()
                        .reauthenticate(
                            input.getText().toString(),
                            error -> {
                              if (!isAdded() || getView() == null) return;
                              dialog.getButton(-1).setEnabled(true);
                              if (error != null) {
                                input.setError(error);
                                return;
                              }
                              dialog.dismiss();
                              action.run();
                            });
                  });
        });
    dialog.show();
  }

  private void export() {
    File file = new File(requireContext().getCacheDir(), "interviewedge-export.zip");
    model.run(
        () -> {
          try {
            model.app().repository().export(model.owner(), file);
            return file;
          } catch (Exception error) {
            file.delete();
            throw error;
          }
        },
        result -> {
          if (isAdded()) {
            exportFile = result;
            exportLocation.launch("interviewedge-export.zip");
          } else result.delete();
        });
  }

  private void logout() {
    model.run(
        () -> {
          model.app().clearSession();
          return true;
        },
        result -> {
          if (isAdded()) root("Login");
        });
  }

  private void changePassword() {
    clear();
    heading("Change password", "Other sessions and old recovery codes will stop working.");
    EditText current = password("Current password"), replacement = password("New password");
    button(
        "Change password",
        () ->
            model
                .app()
                .auth()
                .changePassword(
                    current.getText().toString(),
                    replacement.getText().toString(),
                    error -> {
                      if (!isAdded() || getView() == null) return;
                      if (error != null) {
                        message.setText(error);
                        return;
                      }
                      root("Login");
                    }));
    button("Back", this::render);
  }
}

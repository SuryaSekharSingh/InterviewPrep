package com.interviewedge.ui.screens;

import android.widget.EditText;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.interviewedge.ui.BaseScreen;

public class LoginScreen extends BaseScreen {
  protected boolean focus() {
    return true;
  }

  protected void render() {
    if (model.app().auth().uid() != null) {
      authenticated(null);
      return;
    }
    heading("Find your interview edge.", "A little practice. A lot more confidence.");
    clear();
    card(
        "YOUR PLACEMENT PRACTICE",
        "Build confidence one session at a time",
        "Practise interviews, sharpen your knowledge and improve your English.",
        null);
    EditText username = field("Username", model.saved("username", ""), false);
    saved(username, "username");
    EditText password = password("Password");
    button(
        "Log in",
        () -> {
          model.busy.setValue(true);
          model
              .app()
              .auth()
              .login(
                  username.getText().toString().trim(),
                  password.getText().toString(),
                  this::authenticated);
        });
    button(
        "Create account",
        () -> {
          model.busy.setValue(true);
          model
              .app()
              .auth()
              .register(
                  username.getText().toString().trim(),
                  password.getText().toString(),
                  this::authenticated);
        });
    button("Recover account", () -> recovery(username.getText().toString()));
    text(
        "New here? Enter a username and a password of at least 10 characters, then tap Create"
            + " account.");
  }

  private void authenticated(String error) {
    if (!isAdded() || getView() == null) return;
    model.busy.setValue(false);
    if (error != null) {
      message.setText(error);
      return;
    }
    var codes = model.app().auth().recoveryCodes();
    if (codes.isEmpty()) {
      root("Home");
      return;
    }
    android.widget.TextView content = new android.widget.TextView(requireContext());
    content.setPadding(dp(20), dp(12), dp(20), dp(12));
    content.setText(
        "Save these recovery codes somewhere safe. Each code can be used once. They will not be"
            + " shown again.\n\n"
            + String.join("\n", codes));
    content.setTextIsSelectable(true);
    android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
    scroll.addView(content);
    var dialog =
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle("Save your recovery codes")
            .setView(scroll)
            .setCancelable(false)
            .setPositiveButton(
                "I saved my codes",
                (d, w) -> {
                  model.app().auth().acknowledgeRecoveryCodes();
                  root("Profile");
                })
            .create();
    dialog.show();
    dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
  }

  private void recovery(String username) {
    clear();
    heading("Recover account", "Use one saved recovery code to set a new password.");
    EditText user = field("Username", username, false),
        code = field("Recovery code", "", false),
        replacement = password("New password");
    button(
        "Recover account",
        () -> {
          model.busy.setValue(true);
          model
              .app()
              .auth()
              .recover(
                  user.getText().toString().trim(),
                  code.getText().toString().trim(),
                  replacement.getText().toString(),
                  this::authenticated);
        });
    button("Back to login", this::render);
  }
}

package com.interviewedge.ui.screens;

import android.os.*;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.*;
import com.interviewedge.ui.BaseScreen;
import java.time.Instant;

public class TestScreen extends BaseScreen {
  private JsonArray items;
  private int position;
  private EditText answer;
  private RadioGroup options;
  private CheckBox marked;
  private TextView timer;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private long deadline, serverOffset;
  private final Handler autosave = new Handler(Looper.getMainLooper());
  private boolean restoring;
  private long editRevision;

  protected boolean focus() {
    return true;
  }

  public void onViewCreated(android.view.View view, Bundle state) {
    super.onViewCreated(view, state);
    requireActivity()
        .getOnBackPressedDispatcher()
        .addCallback(
            getViewLifecycleOwner(),
            new OnBackPressedCallback(true) {
              public void handleOnBackPressed() {
                new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Leave this test?")
                    .setMessage("Saved answers are retained. The test deadline continues.")
                    .setPositiveButton(
                        "Save and leave",
                        (d, w) ->
                            save(
                                () -> {
                                  setEnabled(false);
                                  requireActivity().getOnBackPressedDispatcher().onBackPressed();
                                }))
                    .setNegativeButton("Continue", null)
                    .show();
              }
            });
  }

  protected void render() {
    heading("Subject test", "Loading your saved answers…");
    get(
        "tests/attempts/" + argument("id"),
        value -> {
          var o = value.getAsJsonObject();
          String state = s(o.getAsJsonObject("activity"), "state");
          if (!state.equals("ACTIVE")) {
            navigate("Report", args("id", argument("id"), "module", "TEST"));
            return;
          }
          items = o.getAsJsonArray("items");
          position = Math.min(Integer.parseInt(model.saved("position", "0")), items.size() - 1);
          deadline = Instant.parse(s(o, "deadline")).toEpochMilli();
          serverOffset =
              Instant.parse(s(o, "serverTime")).toEpochMilli() - System.currentTimeMillis();
          showQuestion();
        });
  }

  private void showQuestion() {
    clear();
    model.save("position", Integer.toString(position));
    var item = items.get(position).getAsJsonObject();
    heading(
        "Question " + (position + 1) + " of " + items.size(), s(item, "topicId").replace('-', ' '));
    timer = text("");
    tick();
    section(s(item, "prompt"));
    answer = null;
    options = null;
    if (s(item, "type").equals("SHORT_ANSWER")) {
      answer = field("Your answer", s(item, "response"), true);
      answer.addTextChangedListener(
          new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            public void onTextChanged(CharSequence s, int start, int before, int count) {
              scheduleSave();
            }

            public void afterTextChanged(android.text.Editable e) {}
          });
    } else {
      options = new RadioGroup(requireContext());
      int id = 1;
      for (var option : item.getAsJsonArray("options")) {
        RadioButton radio = new RadioButton(requireContext());
        radio.setId(id++);
        radio.setText(option.getAsString());
        radio.setMinHeight(dp(52));
        radio.setTextSize(16);
        options.addView(radio);
        if (option.getAsString().equals(s(item, "response"))) radio.setChecked(true);
      }
      body.addView(options);
      options.setOnCheckedChangeListener((group, checked) -> scheduleSave());
    }
    marked = new CheckBox(requireContext());
    marked.setText("Mark for review");
    marked.setMinHeight(dp(48));
    marked.setChecked(item.get("marked").getAsBoolean());
    body.addView(marked);
    marked.setOnCheckedChangeListener((button, checked) -> scheduleSave());
    String itemId = s(item, "itemId"),
        path = "tests/attempts/" + argument("id") + "/responses/" + itemId;
    long restoreRevision = editRevision;
    model.restoreDraft(
        path,
        draft -> {
          if (!isAdded()
              || getView() == null
              || items == null
              || editRevision != restoreRevision
              || !s(items.get(position).getAsJsonObject(), "itemId").equals(itemId)
              || draft.isBlank()) return;
          var local = JsonParser.parseString(draft).getAsJsonObject();
          String value = s(local, "answer");
          if (value.equals(s(item, "response"))
              && local.get("marked").getAsBoolean() == item.get("marked").getAsBoolean()) return;
          restoring = true;
          if (answer != null) answer.setText(value);
          if (options != null)
            for (int i = 0; i < options.getChildCount(); i++) {
              RadioButton radio = (RadioButton) options.getChildAt(i);
              if (radio.getText().toString().equals(value)) radio.setChecked(true);
            }
          marked.setChecked(local.get("marked").getAsBoolean());
          restoring = false;
          message.setText("Local draft restored. Review it before saving.");
        });
    if (position + 1 < items.size())
      button(
          "Save and next",
          () ->
              save(
                  () -> {
                    position++;
                    showQuestion();
                  }));
    else button("Submit test", () -> save(this::submit));
    if (position > 0)
      button(
          "Previous",
          () ->
              save(
                  () -> {
                    position--;
                    showQuestion();
                  }));
    button(
        "Question list",
        () -> {
          String[] labels = new String[items.size()];
          for (int i = 0; i < labels.length; i++)
            labels[i] =
                "Question "
                    + (i + 1)
                    + (items.get(i).getAsJsonObject().get("marked").getAsBoolean()
                        ? " · Review"
                        : "");
          new MaterialAlertDialogBuilder(requireContext())
              .setTitle("Go to question")
              .setItems(
                  labels,
                  (d, n) ->
                      save(
                          () -> {
                            position = n;
                            showQuestion();
                          }))
              .show();
        });
  }

  private void save(Runnable after) {
    autosave.removeCallbacksAndMessages(null);
    if (items == null) return;
    var item = items.get(position).getAsJsonObject();
    String value = "";
    if (answer != null) value = answer.getText().toString();
    else if (options != null && options.getCheckedRadioButtonId() != -1)
      value =
          ((RadioButton) options.findViewById(options.getCheckedRadioButtonId()))
              .getText()
              .toString();
    String path = "tests/attempts/" + argument("id") + "/responses/" + s(item, "itemId");
    var payload =
        json(
            "answer",
            value,
            "marked",
            marked.isChecked(),
            "version",
            item.get("version").getAsInt());
    model.draft(path, payload.toString());
    String accepted = value;
    boolean acceptedMarked = marked.isChecked();
    write(
        "PUT",
        path,
        payload,
        result -> {
          item.addProperty("response", accepted);
          item.addProperty("marked", acceptedMarked);
          item.add("version", result.getAsJsonObject().get("version"));
          String latest =
              answer != null
                  ? answer.getText().toString()
                  : options != null && options.getCheckedRadioButtonId() != -1
                      ? ((RadioButton) options.findViewById(options.getCheckedRadioButtonId()))
                          .getText()
                          .toString()
                      : "";
          if (!latest.equals(accepted) || marked.isChecked() != acceptedMarked) save(after);
          else after.run();
        });
  }

  private void scheduleSave() {
    if (restoring) return;
    editRevision++;
    persistDraft();
    autosave.removeCallbacksAndMessages(null);
    autosave.postDelayed(
        () -> {
          if (!isAdded() || getView() == null) return;
          if (Boolean.TRUE.equals(model.busy.getValue())) {
            scheduleSave();
            return;
          }
          if (System.currentTimeMillis() + serverOffset < deadline) save(() -> {});
        },
        900);
  }

  private void persistDraft() {
    if (items == null || marked == null) return;
    var item = items.get(position).getAsJsonObject();
    String value =
        answer != null
            ? answer.getText().toString()
            : options != null && options.getCheckedRadioButtonId() != -1
                ? ((RadioButton) options.findViewById(options.getCheckedRadioButtonId()))
                    .getText()
                    .toString()
                : "";
    String path = "tests/attempts/" + argument("id") + "/responses/" + s(item, "itemId");
    model.draft(
        path,
        json(
                "answer",
                value,
                "marked",
                marked.isChecked(),
                "version",
                item.get("version").getAsInt())
            .toString());
  }

  private void submit() {
    write(
        "POST",
        "tests/attempts/" + argument("id") + "/submit",
        json(),
        value ->
            navigate(
                "Report",
                args(
                    "id",
                    argument("id"),
                    "module",
                    "TEST",
                    "jobId",
                    s(value.getAsJsonObject(), "jobId"))));
  }

  private void tick() {
    handler.removeCallbacksAndMessages(null);
    if (timer == null || !isAdded()) return;
    long seconds = Math.max(0, (deadline - System.currentTimeMillis() - serverOffset) / 1000);
    timer.setText(
        String.format(
            java.util.Locale.US, "Time remaining · %02d:%02d", seconds / 60, seconds % 60));
    if (seconds == 0) {
      button("Time ended · view result", this::submit);
      return;
    }
    handler.postDelayed(this::tick, 1000);
  }

  public void onDestroyView() {
    handler.removeCallbacksAndMessages(null);
    autosave.removeCallbacksAndMessages(null);
    super.onDestroyView();
  }
}

package com.interviewedge.ui;

import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.*;
import com.google.gson.*;
import com.interviewedge.R;
import java.util.*;
import java.util.function.Consumer;

public abstract class BaseScreen extends Fragment {
  protected ScreenModel model;
  protected LinearLayout body;
  protected TextView title, subtitle, message;
  private ProgressBar loading;

  public BaseScreen() {
    super(R.layout.fragment_screen);
  }

  public void onViewCreated(View view, Bundle saved) {
    super.onViewCreated(view, saved);
    model = new ViewModelProvider(this).get(ScreenModel.class);
    body = view.findViewById(R.id.body);
    title = view.findViewById(R.id.title);
    subtitle = view.findViewById(R.id.subtitle);
    message = view.findViewById(R.id.message);
    message.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            message.setVisibility(s == null || s.toString().isBlank() ? View.GONE : View.VISIBLE);
          }

          public void afterTextChanged(Editable value) {}
        });
    loading = view.findViewById(R.id.loading);
    androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
    model.busy.observe(
        getViewLifecycleOwner(),
        busy -> {
          loading.setVisibility(Boolean.TRUE.equals(busy) ? View.VISIBLE : View.GONE);
          enableButtons(body, !Boolean.TRUE.equals(busy));
        });
    model.message.observe(
        getViewLifecycleOwner(),
        value -> {
          message.setText(value);
        });
    model.signedOut.observe(
        getViewLifecycleOwner(),
        expired -> {
          if (Boolean.TRUE.equals(expired)) root("Login");
        });
    ((MainActivity) requireActivity()).navigation(!focus() && model.app().auth().uid() != null);
    render();
    if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
      view.setAlpha(0f);
      view.animate().alpha(1f).setDuration(180).start();
    }
  }

  private void enableButtons(ViewGroup group, boolean enabled) {
    for (int i = 0; i < group.getChildCount(); i++) {
      View v = group.getChildAt(i);
      if (v instanceof MaterialButton || v instanceof RadioButton) v.setEnabled(enabled);
      if (v instanceof ViewGroup g) enableButtons(g, enabled);
    }
  }

  protected boolean focus() {
    return false;
  }

  protected abstract void render();

  protected void heading(String text, String detail) {
    title.setText(text);
    subtitle.setText(detail);
  }

  protected int color(int resource) {
    return androidx.core.content.ContextCompat.getColor(requireContext(), resource);
  }

  protected int dp(int value) {
    return (int) (value * getResources().getDisplayMetrics().density);
  }

  protected void clear() {
    body.removeAllViews();
  }

  protected TextView text(String text) {
    TextView v = new TextView(requireContext());
    v.setText(text);
    v.setTextSize(16);
    v.setTextColor(color(R.color.edge_muted));
    v.setLineSpacing(dp(3), 1.0f);
    v.setPadding(0, dp(8), 0, dp(12));
    body.addView(v, new LinearLayout.LayoutParams(-1, -2));
    return v;
  }

  protected void section(String label) {
    TextView v = text(label);
    v.setTextSize(20);
    v.setTextColor(color(R.color.edge_ink));
    v.setPadding(0, dp(16), 0, dp(12));
    v.setTypeface(null, android.graphics.Typeface.BOLD);
    ViewCompatHelper.heading(v);
  }

  protected MaterialButton button(String label, Runnable action) {
    boolean primary = true;
    for (int i = 0; i < body.getChildCount(); i++)
      if (body.getChildAt(i) instanceof MaterialButton) {
        primary = false;
        break;
      }
    MaterialButton button =
        new MaterialButton(
            requireContext(),
            null,
            primary
                ? com.google.android.material.R.attr.materialButtonStyle
                : com.google.android.material.R.attr.materialButtonOutlinedStyle);
    button.setText(label);
    button.setAllCaps(false);
    button.setMinHeight(dp(56));
    button.setTextSize(16);
    button.setPadding(dp(20), dp(12), dp(20), dp(12));
    button.setCornerRadius(dp(18));
    button.setLetterSpacing(0);
    button.setElevation(0);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.setMargins(0, dp(8), 0, dp(8));
    body.addView(button, p);
    button.setOnClickListener(v -> action.run());
    return button;
  }

  protected MaterialCardView card(String eyebrow, String heading, String detail, Runnable action) {
    MaterialCardView card = new MaterialCardView(requireContext());
    card.setRadius(dp(24));
    card.setMinimumHeight(dp(64));
    card.setCardElevation(0);
    card.setStrokeWidth(dp(1));
    card.setStrokeColor(color(R.color.edge_border));
    card.setCardBackgroundColor(color(R.color.edge_surface));
    card.setClickable(action != null);
    card.setFocusable(action != null);
    LinearLayout content = new LinearLayout(requireContext());
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(20), dp(20), dp(20), dp(20));
    if (eyebrow != null && !eyebrow.isBlank()) {
      TextView overline = new TextView(requireContext());
      overline.setText(eyebrow.toUpperCase(Locale.ROOT));
      overline.setTextColor(color(R.color.edge_primary));
      overline.setTextSize(12);
      overline.setTypeface(null, android.graphics.Typeface.BOLD);
      overline.setLetterSpacing(.06f);
      content.addView(overline);
    }
    TextView title = new TextView(requireContext());
    title.setText(heading + (action == null ? "" : "  ›"));
    title.setTextColor(color(R.color.edge_ink));
    title.setTextSize(18);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
    titleParams.setMargins(0, dp(eyebrow == null || eyebrow.isBlank() ? 0 : 8), 0, dp(8));
    content.addView(title, titleParams);
    TextView description = new TextView(requireContext());
    description.setText(detail);
    description.setTextColor(color(R.color.edge_muted));
    description.setTextSize(15);
    description.setLineSpacing(dp(3), 1.0f);
    if (detail == null || detail.isBlank()) description.setVisibility(View.GONE);
    content.addView(description);
    card.addView(content);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.setMargins(0, 0, 0, dp(16));
    body.addView(card, params);
    if (action != null) card.setOnClickListener(v -> action.run());
    return card;
  }

  protected Choice choiceCards(String label, List<String> options, String selected) {
    section(label);
    RadioGroup group = new RadioGroup(requireContext());
    group.setOrientation(LinearLayout.VERTICAL);
    Map<Integer, String> values = new LinkedHashMap<>();
    for (String option : options) {
      var item = new com.google.android.material.radiobutton.MaterialRadioButton(requireContext());
      item.setId(View.generateViewId());
      item.setText(option);
      item.setTextColor(color(R.color.edge_ink));
      item.setTextSize(16);
      item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
      item.setMinHeight(dp(56));
      item.setPadding(dp(16), dp(12), dp(16), dp(12));
      item.setBackgroundResource(R.drawable.choice_background);
      RadioGroup.LayoutParams params = new RadioGroup.LayoutParams(-1, -2);
      params.setMargins(0, 0, 0, dp(8));
      group.addView(item, params);
      values.put(item.getId(), option);
      if (option.equals(selected)) group.check(item.getId());
    }
    if (group.getCheckedRadioButtonId() == View.NO_ID && group.getChildCount() > 0)
      group.check(group.getChildAt(0).getId());
    body.addView(group, new LinearLayout.LayoutParams(-1, -2));
    return new Choice(group, values);
  }

  protected record Choice(RadioGroup group, Map<Integer, String> values) {
    public String value() {
      return values.get(group.getCheckedRadioButtonId());
    }
  }

  protected EditText field(String label, String value, boolean multiline) {
    TextInputLayout layout = new TextInputLayout(requireContext());
    layout.setHint(label);
    layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
    layout.setBoxStrokeColorStateList(
        androidx.core.content.ContextCompat.getColorStateList(
            requireContext(), R.color.field_stroke));
    layout.setDefaultHintTextColor(
        android.content.res.ColorStateList.valueOf(color(R.color.edge_muted)));
    TextInputEditText input = new TextInputEditText(layout.getContext());
    input.setText(value);
    input.setTextSize(16);
    input.setMinHeight(dp(56));
    input.setTextColor(color(R.color.edge_ink));
    input.setPadding(dp(16), dp(18), dp(16), dp(18));
    input.setInputType(
        multiline
            ? android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            : android.text.InputType.TYPE_CLASS_TEXT);
    if (multiline) {
      input.setMinLines(4);
      input.setLineSpacing(dp(3), 1.0f);
      input.setGravity(Gravity.TOP);
    }
    layout.addView(input, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.setMargins(0, dp(8), 0, dp(12));
    body.addView(layout, p);
    return input;
  }

  protected EditText password(String label) {
    EditText input = field(label, "", false);
    input.setInputType(129);
    input.setSaveEnabled(false);
    return input;
  }

  protected Spinner choice(String label, List<String> options, String selected) {
    text(label);
    Spinner spinner = new Spinner(requireContext());
    spinner.setMinimumHeight(dp(56));
    spinner.setPadding(dp(12), dp(8), dp(12), dp(8));
    spinner.setContentDescription(label);
    var adapter =
        new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, options);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    spinner.setAdapter(adapter);
    if (options.contains(selected)) spinner.setSelection(options.indexOf(selected));
    body.addView(spinner, new LinearLayout.LayoutParams(-1, -2));
    return spinner;
  }

  protected void navigate(String route, Bundle args) {
    ((MainActivity) requireActivity()).show(route, args, true);
  }

  protected void replace(String route, Bundle args) {
    ((MainActivity) requireActivity()).show(route, args, false);
  }

  protected void root(String route) {
    ((MainActivity) requireActivity()).root(route);
  }

  protected Bundle args(String... pairs) {
    Bundle b = new Bundle();
    for (int i = 0; i < pairs.length; i += 2) b.putString(pairs[i], pairs[i + 1]);
    return b;
  }

  protected String argument(String key) {
    return requireArguments().getString(key, "");
  }

  protected JsonObject json(Object... pairs) {
    JsonObject o = new JsonObject();
    for (int i = 0; i < pairs.length; i += 2)
      o.add(pairs[i].toString(), new Gson().toJsonTree(pairs[i + 1]));
    return o;
  }

  protected String s(JsonObject o, String key) {
    return !o.has(key) || o.get(key).isJsonNull()
        ? ""
        : o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : o.get(key).toString();
  }

  protected String score(JsonObject o, String key) {
    return !o.has(key) || o.get(key).isJsonNull()
        ? "Not assessed"
        : String.format(Locale.US, "%.0f / 100", o.get(key).getAsDouble());
  }

  protected void get(String path, Consumer<JsonElement> callback) {
    model.get(
        path,
        result -> {
          if (!isAdded() || getView() == null) return;
          if (result.cached()) message.setText("Saved copy · reconnect to submit new work");
          callback.accept(result.value());
        });
  }

  protected void write(
      String method, String path, JsonElement payload, Consumer<JsonElement> callback) {
    model.write(
        method,
        path,
        payload,
        value -> {
          if (isAdded() && getView() != null) callback.accept(value);
        });
  }

  protected void processingJobs(String activityId, Runnable refreshed) {
    get(
        "jobs?activityId=" + activityId,
        value -> {
          for (var item : value.getAsJsonArray()) {
            var job = item.getAsJsonObject();
            if ("FAILED".equals(s(job, "state"))) {
              text("Processing could not finish. Your answer is saved.");
              String jobId = s(job, "id");
              button(
                  "Retry processing",
                  () -> write("POST", "jobs/" + jobId + "/retry", json(), r -> refreshed.run()));
            }
          }
        });
  }

  protected void saved(EditText input, String key) {
    input.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            model.save(key, s.toString());
          }

          public void afterTextChanged(Editable e) {}
        });
  }

  private static class ViewCompatHelper {
    static void heading(View v) {
      androidx.core.view.ViewCompat.setAccessibilityHeading(v, true);
    }
  }
}

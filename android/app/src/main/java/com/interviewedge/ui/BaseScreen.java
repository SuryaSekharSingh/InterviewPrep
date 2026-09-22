package com.interviewedge.ui;

import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
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
  }

  private void enableButtons(ViewGroup group, boolean enabled) {
    for (int i = 0; i < group.getChildCount(); i++) {
      View v = group.getChildAt(i);
      if (v instanceof MaterialButton) v.setEnabled(enabled);
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
    v.setTextColor(0xff344054);
    v.setPadding(0, dp(8), 0, dp(12));
    body.addView(v, new LinearLayout.LayoutParams(-1, -2));
    return v;
  }

  protected void section(String label) {
    TextView v = text(label);
    v.setTextSize(18);
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
    button.setMinHeight(dp(52));
    button.setCornerRadius(dp(14));
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
    card.setRadius(dp(18));
    card.setCardElevation(0);
    card.setStrokeWidth(dp(1));
    card.setStrokeColor(0xffe4e7ec);
    card.setCardBackgroundColor(0xffffffff);
    card.setClickable(action != null);
    card.setFocusable(action != null);
    LinearLayout content = new LinearLayout(requireContext());
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(20), dp(20), dp(20), dp(20));
    if (eyebrow != null && !eyebrow.isBlank()) {
      TextView overline = new TextView(requireContext());
      overline.setText(eyebrow.toUpperCase(Locale.ROOT));
      overline.setTextColor(0xff4f46e5);
      overline.setTextSize(12);
      overline.setTypeface(null, android.graphics.Typeface.BOLD);
      overline.setLetterSpacing(.08f);
      content.addView(overline);
    }
    TextView title = new TextView(requireContext());
    title.setText(heading + (action == null ? "" : "  ›"));
    title.setTextColor(0xff15213a);
    title.setTextSize(18);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
    titleParams.setMargins(0, dp(eyebrow == null || eyebrow.isBlank() ? 0 : 8), 0, dp(8));
    content.addView(title, titleParams);
    TextView description = new TextView(requireContext());
    description.setText(detail);
    description.setTextColor(0xff667085);
    description.setTextSize(14);
    description.setLineSpacing(0, 1.15f);
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
    MaterialButtonToggleGroup group = new MaterialButtonToggleGroup(requireContext());
    group.setOrientation(LinearLayout.VERTICAL);
    group.setSingleSelection(true);
    group.setSelectionRequired(true);
    Map<Integer, String> values = new LinkedHashMap<>();
    for (String option : options) {
      MaterialButton item =
          new MaterialButton(
              requireContext(),
              null,
              com.google.android.material.R.attr.materialButtonOutlinedStyle);
      item.setId(View.generateViewId());
      item.setText(option);
      item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
      item.setCheckable(true);
      item.setMinHeight(dp(52));
      item.setCornerRadius(dp(14));
      item.setLetterSpacing(0);
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
      params.setMargins(0, 0, 0, dp(8));
      group.addView(item, params);
      values.put(item.getId(), option);
      if (option.equals(selected)) item.setChecked(true);
    }
    if (group.getCheckedButtonId() == View.NO_ID && group.getChildCount() > 0)
      ((MaterialButton) group.getChildAt(0)).setChecked(true);
    body.addView(group, new LinearLayout.LayoutParams(-1, -2));
    return new Choice(group, values);
  }

  protected record Choice(MaterialButtonToggleGroup group, Map<Integer, String> values) {
    public String value() {
      return values.get(group.getCheckedButtonId());
    }
  }

  protected EditText field(String label, String value, boolean multiline) {
    TextInputLayout layout = new TextInputLayout(requireContext());
    layout.setHint(label);
    layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
    TextInputEditText input = new TextInputEditText(layout.getContext());
    input.setText(value);
    input.setTextSize(16);
    input.setMinHeight(dp(52));
    input.setInputType(
        multiline
            ? android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            : android.text.InputType.TYPE_CLASS_TEXT);
    if (multiline) {
      input.setMinLines(4);
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
    spinner.setMinimumHeight(dp(48));
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

package com.interviewedge.ui;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.*;
import androidx.fragment.app.Fragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.interviewedge.*;
import com.interviewedge.ui.screens.*;

public class MainActivity extends AppCompatActivity {
  private BottomNavigationView navigation;
  private boolean selectingNavigation;
  private boolean navigationRequested;
  private boolean keyboardVisible;

  public void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.activity_main);
    WindowCompat.getInsetsController(getWindow(), findViewById(R.id.root))
        .setAppearanceLightNavigationBars(getResources().getBoolean(R.bool.edge_light_bars));
    ViewCompat.setOnApplyWindowInsetsListener(
        findViewById(R.id.root),
        (view, insets) -> {
          var bars =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
          view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
          if (navigation != null)
            navigation.setVisibility(
                navigationRequested && !keyboardVisible ? View.VISIBLE : View.GONE);
          // The root owns system/IME spacing; don't apply it again inside the bottom bar.
          return WindowInsetsCompat.CONSUMED;
        });
    navigation = findViewById(R.id.navigation);
    navigation.setOnItemSelectedListener(
        item -> {
          if (selectingNavigation) return true;
          String route =
              item.getItemId() == R.id.nav_home
                  ? "Home"
                  : item.getItemId() == R.id.nav_practice
                      ? "Practice"
                      : item.getItemId() == R.id.nav_progress ? "Progress" : "Profile";
          root(route);
          return true;
        });
    if (state == null) root(((EdgeApp) getApplication()).auth().uid() == null ? "Login" : "Home");
  }

  public void root(String route) {
    getSupportFragmentManager()
        .popBackStackImmediate(
            null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
    show(route, new Bundle(), false);
    int item =
        switch (route) {
          case "Home" -> R.id.nav_home;
          case "Practice" -> R.id.nav_practice;
          case "Progress" -> R.id.nav_progress;
          case "Profile" -> R.id.nav_profile;
          default -> 0;
        };
    if (item != 0 && navigation.getSelectedItemId() != item) {
      selectingNavigation = true;
      navigation.setSelectedItemId(item);
      selectingNavigation = false;
    }
  }

  public void show(String route, Bundle args, boolean back) {
    Fragment screen =
        switch (route) {
          case "Login" -> new LoginScreen();
          case "Home" -> new HomeScreen();
          case "Practice" -> new PracticeScreen();
          case "Progress" -> new ProgressScreen();
          case "Profile" -> new ProfileScreen();
          case "InterviewSetup" -> new InterviewSetupScreen();
          case "Interview" -> new InterviewScreen();
          case "TestSetup" -> new TestSetupScreen();
          case "Test" -> new TestScreen();
          case "English" -> new EnglishScreen();
          case "Report" -> new ReportScreen();
          case "History" -> new HistoryScreen();
          default -> throw new IllegalArgumentException("Unknown screen");
        };
    screen.setArguments(args);
    var tx = getSupportFragmentManager().beginTransaction().replace(R.id.content, screen);
    if (back) tx.addToBackStack(route);
    tx.commit();
  }

  public void navigation(boolean visible) {
    navigationRequested = visible;
    navigation.setVisibility(visible && !keyboardVisible ? View.VISIBLE : View.GONE);
  }
}

package com.interviewedge.ui.screens;

import com.interviewedge.ui.BaseScreen;

public class PracticeScreen extends BaseScreen {
  protected void render() {
    heading("Practice", "Pick one focused activity. You can review every result afterwards.");
    card(
        "AI interview · 10–30 min",
        "Mock interview",
        "Practise technical, HR or mixed questions for your target role.",
        () -> navigate("InterviewSetup", args()));
    card(
        "DSA · DBMS · OS",
        "Subject test",
        "Answer five, ten or fifteen reviewed questions at your level.",
        () -> navigate("TestSetup", args()));
    card(
        "Speaking · 2 min",
        "Self-introduction",
        "Record, review your transcript and compare your next attempt.",
        () -> navigate("English", args()));
  }
}

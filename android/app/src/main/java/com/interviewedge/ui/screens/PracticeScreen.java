package com.interviewedge.ui.screens;

import com.interviewedge.ui.BaseScreen;

public class PracticeScreen extends BaseScreen {
  protected void render() {
    heading("Practice", "Three ways to grow. Choose your focus for today.");
    card(
        "01 / INTERVIEW · 10–30 MIN",
        "Mock interview",
        "Practise technical, HR or mixed questions for your target role.",
        () -> navigate("InterviewSetup", args()));
    card(
        "02 / KNOWLEDGE · DSA, DBMS & OS",
        "Subject test",
        "Answer five, ten or fifteen reviewed questions at your level.",
        () -> navigate("TestSetup", args()));
    card(
        "03 / ENGLISH · WRITING",
        "Written self-introduction",
        "Write, review language feedback and compare your next attempt.",
        () -> navigate("English", args()));
  }
}

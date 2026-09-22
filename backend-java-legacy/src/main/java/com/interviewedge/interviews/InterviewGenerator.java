package com.interviewedge.interviews;

public interface InterviewGenerator {
  record FollowUp(String question) {}

  FollowUp followUp(
      String kind,
      String topic,
      String previousQuestion,
      String answer,
      String reference,
      int depth);
}

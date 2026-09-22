package com.interviewedge.assessment;

import java.util.*;

public interface AnswerEvaluator {
  record Request(
      String kind, String question, String reference, List<String> criteria, String answer) {}

  record Evaluation(
      Map<String, Double> dimensions,
      List<String> evidence,
      List<String> strengths,
      List<String> improvements,
      String improvedAnswer,
      boolean scorable,
      String model,
      String rubricVersion) {}

  Evaluation evaluate(Request request);
}

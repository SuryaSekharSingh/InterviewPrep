package com.interviewedge.assessment;

import java.util.*;

public final class Scoring {
  private Scoring() {}

  public static Map<String, Double> weights(String kind) {
    return switch (kind) {
      case "HR" -> Map.of("relevance", .30, "structure", .25, "examples", .30, "clarity", .15);
      case "ENGLISH" -> Map.of("grammar", .30, "clarity", .35, "relevance", .35);
      case "SHORT_ANSWER" -> Map.of("correctness", .75, "reasoning", .25);
      default -> Map.of("correctness", .40, "reasoning", .30, "clarity", .20, "relevance", .10);
    };
  }

  public static double score(String kind, Map<String, Double> dimensions) {
    var weights = weights(kind);
    if (!dimensions.keySet().equals(weights.keySet()))
      throw new IllegalArgumentException("Rubric dimensions mismatch");
    double total = 0;
    for (var e : weights.entrySet()) {
      Double value = dimensions.get(e.getKey());
      if (value == null || !Double.isFinite(value) || value < 0 || value > 4)
        throw new IllegalArgumentException("Invalid rubric score");
      total += value * 25 * e.getValue();
    }
    return Math.round(total * 10) / 10.0;
  }

  public static Double overall(Map<String, Double> modules) {
    if (!modules.keySet().containsAll(List.of("INTERVIEW", "TEST", "ENGLISH"))) return null;
    return Math.round(
            (modules.get("INTERVIEW") * .4 + modules.get("TEST") * .4 + modules.get("ENGLISH") * .2)
                * 10)
        / 10.0;
  }
}

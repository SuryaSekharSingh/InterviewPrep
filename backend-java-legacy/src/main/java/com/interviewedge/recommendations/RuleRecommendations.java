package com.interviewedge.recommendations;

import com.interviewedge.identity.Profiles;
import com.interviewedge.progress.ProgressService;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RuleRecommendations implements RecommendationPolicy {
  private final ProgressService progress;
  private final Profiles profiles;
  private final JdbcTemplate db;

  public RuleRecommendations(ProgressService progress, Profiles profiles, JdbcTemplate db) {
    this.progress = progress;
    this.profiles = profiles;
    this.db = db;
  }

  public List<Map<String, Object>> recommend(String uid) {
    List<Map<String, Object>> result = new ArrayList<>();
    var summary = progress.summary(uid);
    var modules = (Map<?, ?>) summary.get("moduleScores");
    for (String module : List.of("TEST", "INTERVIEW", "ENGLISH"))
      if (!modules.containsKey(module))
        result.add(
            item(
                "baseline:" + module,
                module,
                "",
                "Build your " + module.toLowerCase() + " baseline",
                "Complete one activity to establish an initial score.",
                "EASY"));
    var skills = (List<?>) profiles.get(uid).get("skills");
    var competencies = new ArrayList<>(progress.competencies(uid));
    competencies.sort(
        Comparator.<Map<String, Object>, Boolean>comparing(c -> !skills.contains(c.get("id")))
            .thenComparingDouble(c -> ((Number) c.get("score")).doubleValue()));
    for (var c : competencies) {
      String id = (String) c.get("id");
      double score = ((Number) c.get("score")).doubleValue();
      int count = ((Number) c.get("evidenceCount")).intValue();
      String module =
          id.startsWith("english-")
              ? "ENGLISH"
              : id.startsWith("dsa-") || id.startsWith("dbms-") || id.startsWith("os-")
                  ? "TEST"
                  : "INTERVIEW";
      String reason =
          count < 2
              ? "Try another activity to confirm your level."
              : Boolean.TRUE.equals(c.get("needsRefresh"))
                  ? "Refresh a skill you have not assessed recently."
                  : score < 60
                      ? "Recent answers suggest this topic needs practice."
                      : score < 80
                          ? "Apply this skill with a more detailed example."
                          : "You have consistent evidence; try a harder activity.";
      result.add(
          item(
              "topic:" + id,
              module,
              id,
              "Practise " + id.replace('-', ' '),
              reason,
              count < 2 || score < 60 ? "EASY" : score < 80 ? "MEDIUM" : "HARD"));
    }
    var dismissed =
        db.query(
            "SELECT rule_key FROM recommendation_dismissal WHERE user_id=? AND"
                + " until_at>CURRENT_TIMESTAMP",
            (r, n) -> r.getString(1),
            uid);
    return result.stream().filter(r -> !dismissed.contains(r.get("id"))).limit(3).toList();
  }

  private Map<String, Object> item(
      String id, String module, String topic, String title, String reason, String difficulty) {
    return Map.of(
        "id",
        id,
        "module",
        module,
        "topicId",
        topic,
        "title",
        title,
        "reason",
        reason,
        "difficulty",
        difficulty,
        "ruleVersion",
        "v1");
  }

  public void dismiss(String uid, String key) {
    if (key == null
        || key.length() > 120
        || recommend(uid).stream().noneMatch(r -> r.get("id").equals(key)))
      throw com.interviewedge.common.ApiException.bad("Unknown recommendation.");
    if (db.update(
            "UPDATE recommendation_dismissal SET until_at=? WHERE user_id=? AND rule_key=?",
            OffsetDateTime.now().plusDays(3),
            uid,
            key)
        == 0)
      db.update(
          "INSERT INTO recommendation_dismissal(user_id,rule_key,until_at) VALUES(?,?,?)",
          uid,
          key,
          OffsetDateTime.now().plusDays(3));
  }
}

package com.interviewedge.progress;

import com.interviewedge.assessment.*;
import com.interviewedge.common.*;
import com.interviewedge.identity.Profiles;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProgressService {
  private final JdbcTemplate db;
  private final Profiles profiles;
  private final AssessmentEvents events;
  private final Clock clock;

  public ProgressService(JdbcTemplate db, Profiles profiles, AssessmentEvents events, Clock clock) {
    this.db = db;
    this.profiles = profiles;
    this.events = events;
    this.clock = clock;
  }

  @Transactional
  public void project(AssessmentEvents.Event event) {
    if (!events.claim(event.id())) return;
    var e = event.data();
    String id = (String) e.get("activityId"),
        uid = (String) e.get("userId"),
        module = (String) e.get("module");
    @SuppressWarnings("unchecked")
    var context = (Map<String, Object>) e.get("context");
    String previous = Json.text(context, "previousId", ""),
        group = Json.text(context, "retryGroup", id);
    if (!context.containsKey("retryGroup") && !previous.isBlank()) {
      var previousGroup =
          db.query(
              "SELECT retry_group FROM progress_activity WHERE activity_id=? AND user_id=?",
              (r, n) -> r.getString(1),
              previous,
              uid);
      if (!previousGroup.isEmpty()) group = previousGroup.get(0);
    }
    double score = ((Number) e.get("score")).doubleValue();
    OffsetDateTime at = Instant.parse((String) e.get("completedAt")).atOffset(ZoneOffset.UTC);
    if (db.update(
            "UPDATE progress_activity SET score=?,context=?,completed_at=? WHERE activity_id=?",
            score,
            Json.write(context),
            at,
            id)
        == 0)
      db.update(
          "INSERT INTO"
              + " progress_activity(activity_id,user_id,module,score,retry_group,context,completed_at)"
              + " VALUES(?,?,?,?,?,?,?)",
          id,
          uid,
          module,
          score,
          group,
          Json.write(context),
          at);
    db.update("DELETE FROM competency_evidence WHERE activity_id=?", id);
    var competencies = (Map<?, ?>) e.get("competencies");
    for (var entry : competencies.entrySet())
      db.update(
          "INSERT INTO competency_evidence(activity_id,competency,score) VALUES(?,?,?)",
          id,
          entry.getKey(),
          ((Number) entry.getValue()).doubleValue());
    String summary = Json.write(summary(uid));
    if (db.update(
            "UPDATE progress_snapshot SET summary=? WHERE source_activity=? AND"
                + " scoring_version='v1'",
            summary,
            id)
        == 0)
      db.update(
          "INSERT INTO progress_snapshot(id,user_id,source_activity,scoring_version,summary)"
              + " VALUES(?,?,?,'v1',?)",
          UUID.randomUUID().toString(),
          uid,
          id,
          summary);
  }

  public Map<String, Object> summary(String uid) {
    var rows =
        db.query(
            "SELECT * FROM progress_activity WHERE user_id=? ORDER BY completed_at"
                + " DESC,activity_id",
            (r, n) ->
                Map.<String, Object>of(
                    "retry_group",
                    r.getString("retry_group"),
                    "module",
                    r.getString("module"),
                    "score",
                    r.getDouble("score"),
                    "completed_at",
                    r.getObject("completed_at", OffsetDateTime.class)),
            uid);
    Set<String> seen = new HashSet<>();
    Map<String, List<Double>> scores = new TreeMap<>();
    Map<String, Integer> evidence = new TreeMap<>();
    for (var row : rows) {
      if (!seen.add((String) row.get("retry_group"))) continue;
      String module = (String) row.get("module");
      evidence.merge(module, 1, Integer::sum);
      var values = scores.computeIfAbsent(module, k -> new ArrayList<>());
      if (values.size() < 5) values.add(((Number) row.get("score")).doubleValue());
    }
    Map<String, Double> modules = new TreeMap<>();
    scores.forEach(
        (k, v) ->
            modules.put(
                k, round(v.stream().mapToDouble(Double::doubleValue).average().orElseThrow())));
    Double overall = Scoring.overall(modules);
    var profile = profiles.get(uid);
    ZoneId zone = ZoneId.of((String) profile.get("timezone"));
    LocalDate monday =
        LocalDate.now(clock.withZone(zone))
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
    long week =
        rows.stream()
            .filter(
                r ->
                    ((OffsetDateTime) r.get("completed_at"))
                            .toInstant()
                            .atZone(zone)
                            .toLocalDate()
                            .compareTo(monday)
                        >= 0)
            .count();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("overallScore", overall);
    result.put("moduleScores", modules);
    result.put("evidenceCounts", evidence);
    result.put(
        "scoreLabel",
        overall == null
            ? "Build your baseline"
            : evidence.values().stream().allMatch(n -> n >= 3)
                ? "Practice score"
                : "Early estimate");
    result.put("weeklyCompleted", week);
    result.put("weeklyGoal", profile.get("weeklyGoal"));
    result.put("scoringVersion", "v1");
    result.put("eligibleActivities", rows.size());
    result.put("weights", Map.of("INTERVIEW", 40, "TEST", 40, "ENGLISH", 20));
    return result;
  }

  public List<Map<String, Object>> competencies(String uid) {
    var rows =
        db.query(
            "SELECT e.competency,e.score,p.completed_at,p.retry_group FROM competency_evidence e"
                + " JOIN progress_activity p ON e.activity_id=p.activity_id WHERE p.user_id=? ORDER"
                + " BY p.completed_at DESC",
            (r, n) ->
                Map.<String, Object>of(
                    "competency",
                    r.getString("competency"),
                    "score",
                    r.getDouble("score"),
                    "retry_group",
                    r.getString("retry_group"),
                    "completed_at",
                    r.getObject("completed_at", OffsetDateTime.class)),
            uid);
    Map<String, List<Double>> values = new TreeMap<>();
    Map<String, String> dates = new TreeMap<>();
    Set<String> seen = new HashSet<>();
    for (var r : rows) {
      String key = (String) r.get("competency");
      if (!seen.add(key + ":" + r.get("retry_group"))) continue;
      var v = values.computeIfAbsent(key, k -> new ArrayList<>());
      if (v.size() < 5) v.add(((Number) r.get("score")).doubleValue());
      dates.putIfAbsent(key, ((OffsetDateTime) r.get("completed_at")).toInstant().toString());
    }
    List<Map<String, Object>> result = new ArrayList<>();
    values.forEach(
        (k, v) ->
            result.add(
                Map.of(
                    "id",
                    k,
                    "score",
                    round(v.stream().mapToDouble(Double::doubleValue).average().orElseThrow()),
                    "evidenceCount",
                    v.size(),
                    "lastAssessedAt",
                    dates.get(k),
                    "needsRefresh",
                    Instant.parse(dates.get(k))
                        .isBefore(clock.instant().minus(Duration.ofDays(30))))));
    return result;
  }

  public List<Map<String, Object>> timeline(String uid, int days) {
    if (days < 1 || days > 365) throw ApiException.bad("Choose a period from 1 to 365 days.");
    return db.query(
        "SELECT summary,created_at FROM progress_snapshot WHERE user_id=? AND created_at>=? ORDER"
            + " BY created_at",
        (r, n) ->
            Map.of(
                "at",
                r.getObject("created_at", OffsetDateTime.class).toInstant().toString(),
                "summary",
                Json.read(r.getString("summary"))),
        uid,
        OffsetDateTime.now(clock).minusDays(days));
  }

  private double round(double v) {
    return Math.round(v * 10) / 10.0;
  }
}

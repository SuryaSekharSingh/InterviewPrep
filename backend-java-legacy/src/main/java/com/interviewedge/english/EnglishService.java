package com.interviewedge.english;

import com.interviewedge.assessment.*;
import com.interviewedge.common.*;
import com.interviewedge.identity.Profiles;
import com.interviewedge.jobs.*;
import com.interviewedge.media.MediaService;
import java.util.*;
import java.util.regex.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnglishService implements JobHandler {
  public static final String PROMPT =
      "Introduce yourself, describe one project and your contribution, and explain your career"
          + " goal.";
  private final JdbcTemplate db;
  private final Activities activities;
  private final Jobs jobs;
  private final MediaService media;
  private final Profiles profiles;
  private final AnswerEvaluator evaluator;

  public EnglishService(
      JdbcTemplate db,
      Activities activities,
      Jobs jobs,
      MediaService media,
      Profiles profiles,
      AnswerEvaluator evaluator) {
    this.db = db;
    this.activities = activities;
    this.jobs = jobs;
    this.media = media;
    this.profiles = profiles;
    this.evaluator = evaluator;
  }

  public record Submission(String mediaId, String transcript) {}

  @Transactional
  public Map<String, Object> create(String uid, String key, String previousId) {
    profiles.requireConsent(uid);
    String group =
        UUID.nameUUIDFromBytes((uid + ":" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString();
    if (previousId != null && !previousId.isBlank()) {
      var previous = activities.owned(uid, previousId);
      if (!previous.module().equals("ENGLISH") || !previous.state().equals("COMPLETED"))
        throw ApiException.bad("Choose a completed English attempt.");
      group =
          db.queryForObject(
              "SELECT group_id FROM english_attempt WHERE activity_id=?", String.class, previousId);
    }
    var a =
        activities.create(
            uid,
            "ENGLISH",
            key,
            Map.of(
                "promptVersion",
                "intro-v1",
                "previousId",
                previousId == null ? "" : previousId,
                "retryGroup",
                group));
    db.update(
        "INSERT INTO english_attempt(activity_id,group_id,previous_id) VALUES(?,?,?) ON CONFLICT DO"
            + " NOTHING",
        a.id(),
        group,
        previousId);
    return Map.of("activity", a, "prompt", PROMPT);
  }

  @Transactional
  public Map<String, Object> submit(String uid, String id, Submission input) {
    var a = activities.lock(uid, id);
    if (!a.module().equals("ENGLISH")) throw ApiException.missing();
    if (input == null || input.mediaId() == null)
      throw ApiException.bad("A recording is required.");
    var m = media.owned(uid, input.mediaId());
    if (!m.state().equals("READY")) throw ApiException.bad("Wait for transcription.");
    if (m.durationSeconds() > 120)
      throw ApiException.bad("English recordings must be under two minutes.");
    String confirmed = input.transcript() == null ? m.transcript() : input.transcript().trim();
    if (confirmed.isBlank() || confirmed.length() > 8000)
      throw ApiException.bad("Review the transcript.");
    if (!a.state().equals("ACTIVE")) {
      var prior =
          db.queryForMap(
              "SELECT media_id,confirmed_transcript FROM english_attempt WHERE activity_id=?", id);
      if (!input.mediaId().equals(prior.get("media_id"))
          || !confirmed.equals(prior.get("confirmed_transcript")))
        throw ApiException.conflict("Attempt already submitted.");
    } else {
      db.update(
          "UPDATE english_attempt SET media_id=?,raw_transcript=?,confirmed_transcript=?,edited=?"
              + " WHERE activity_id=?",
          m.id(),
          m.transcript(),
          confirmed,
          !confirmed.equals(m.transcript()),
          id);
      activities.state(id, "PROCESSING");
    }
    return Map.of(
        "activityId",
        id,
        "jobId",
        jobs.enqueue(uid, "ENGLISH_REPORT", "english:" + id, Map.of("activityId", id)));
  }

  public String kind() {
    return "ENGLISH_REPORT";
  }

  @Transactional
  public void handle(String uid, Map<String, Object> payload) {
    String id = (String) payload.get("activityId");
    var a = activities.lock(uid, id);
    if (a.state().equals("COMPLETED")) return;
    var attempt = db.queryForMap("SELECT * FROM english_attempt WHERE activity_id=?", id);
    var m = media.owned(uid, (String) attempt.get("media_id"));
    String raw = (String) attempt.get("raw_transcript"),
        confirmed = (String) attempt.get("confirmed_transcript");
    boolean edited = (Boolean) attempt.get("edited");
    var e =
        evaluator.evaluate(
            new AnswerEvaluator.Request(
                "ENGLISH",
                PROMPT,
                "Use the student's own claims; evaluate grammar, logical organisation and prompt"
                    + " relevance without inventing facts.",
                List.of(
                    "Studies or background",
                    "One concrete project and contribution",
                    "Career goal"),
                confirmed));
    Double score = e.scorable() ? Scoring.score("ENGLISH", e.dimensions()) : null;
    int words = wordCount(raw);
    int fillers = fillerCount(raw);
    double rate = Math.round(words * 60 / m.durationSeconds());
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("module", "ENGLISH");
    report.put("promptVersion", "intro-v1");
    report.put("score", score);
    report.put("edited", edited);
    report.put("transcript", confirmed);
    report.put("rawTranscript", raw);
    report.put("evaluation", e);
    report.put(
        "metrics",
        Map.of(
            "wordCount",
            words,
            "wordsPerMinute",
            rate,
            "fillerCount",
            fillers,
            "durationSeconds",
            m.durationSeconds(),
            "approximate",
            true));
    String previous = (String) attempt.get("previous_id");
    if (previous != null && !previous.isBlank()) {
      var old = activities.owned(uid, previous);
      report.put("previousAttemptId", previous);
      if (old.score() != null && score != null && old.eligible() && !edited)
        report.put("change", Math.round((score - old.score()) * 10) / 10.0);
    }
    Map<String, Double> competencies = new TreeMap<>();
    if (score != null) e.dimensions().forEach((k, v) -> competencies.put("english-" + k, v * 25));
    activities.complete(id, report, score, score != null && !edited, competencies);
  }

  public Map<String, Object> report(String uid, String id) {
    var a = activities.owned(uid, id);
    if (!a.module().equals("ENGLISH")) throw ApiException.missing();
    return a.report() == null ? Map.of("state", a.state()) : a.report();
  }

  public static int wordCount(String text) {
    Matcher m = Pattern.compile("[\\p{L}\\p{N}]+(?:['’-][\\p{L}]+)*").matcher(text);
    int count = 0;
    while (m.find()) count++;
    return count;
  }

  public static int fillerCount(String text) {
    Matcher m = Pattern.compile("(?i)\\b(um+|uh+|erm|you know|sort of|kind of)\\b").matcher(text);
    int count = 0;
    while (m.find()) count++;
    return count;
  }
}

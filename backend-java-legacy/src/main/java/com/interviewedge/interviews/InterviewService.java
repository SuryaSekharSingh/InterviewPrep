package com.interviewedge.interviews;

import com.interviewedge.assessment.*;
import com.interviewedge.common.*;
import com.interviewedge.content.ContentCatalog;
import com.interviewedge.identity.Profiles;
import com.interviewedge.jobs.*;
import com.interviewedge.media.MediaService;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InterviewService {
  public record Setup(
      String roleId,
      List<String> skills,
      String type,
      String difficulty,
      String answerMode,
      int minutes) {}

  public record Answer(int sequence, String text, String mediaId, String submissionKey) {}

  private final JdbcTemplate db;
  private final Activities activities;
  private final ContentCatalog catalog;
  private final Jobs jobs;
  private final MediaService media;
  private final Profiles profiles;
  private final Clock clock;

  public InterviewService(
      JdbcTemplate db,
      Activities activities,
      ContentCatalog catalog,
      Jobs jobs,
      MediaService media,
      Profiles profiles,
      Clock clock) {
    this.db = db;
    this.activities = activities;
    this.catalog = catalog;
    this.jobs = jobs;
    this.media = media;
    this.profiles = profiles;
    this.clock = clock;
  }

  @Transactional
  public Map<String, Object> create(String uid, String key, Setup s) {
    profiles.requireConsent(uid);
    if (s == null
        || !catalog.roleExists(s.roleId())
        || s.skills() == null
        || s.skills().isEmpty()
        || !catalog.roleSkills(s.roleId()).containsAll(s.skills())
        || !List.of("TECHNICAL", "HR", "MIXED").contains(s.type())
        || !List.of("EASY", "MEDIUM", "HARD").contains(s.difficulty())
        || !List.of("TEXT", "VOICE").contains(s.answerMode())
        || !List.of(10, 20, 30).contains(s.minutes()))
      throw ApiException.bad("Check interview settings.");
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("roleId", s.roleId());
    context.put("skills", s.skills());
    context.put("type", s.type());
    context.put("difficulty", s.difficulty());
    context.put("answerMode", s.answerMode());
    context.put("minutes", s.minutes());
    var a = activities.create(uid, "INTERVIEW", key, context);
    if (db.queryForList("SELECT activity_id FROM interview_session WHERE activity_id=?", a.id())
        .isEmpty()) {
      int max = s.minutes() == 10 ? 8 : s.minutes() == 20 ? 12 : 16;
      var seeds =
          catalog.seeds(s.roleId(), s.type()).stream()
              .filter(seed -> seed.kind().equals("HR") || s.skills().contains(seed.topic()))
              .toList();
      if (seeds.isEmpty())
        throw ApiException.bad("No reviewed interview prompts match these settings.");
      db.update(
          "INSERT INTO"
              + " interview_session(activity_id,remaining_seconds,max_questions,wall_deadline,current_started)"
              + " VALUES(?,?,?,?,?)",
          a.id(),
          s.minutes() * 60,
          max,
          OffsetDateTime.now(clock).plusMinutes(s.minutes() * 3L),
          OffsetDateTime.now(clock));
      var first =
          seeds.stream()
              .filter(seed -> !s.type().equals("MIXED") || seed.kind().equals("TECHNICAL"))
              .findFirst()
              .orElse(seeds.get(0));
      insertTurn(
          a.id(), 0, first.topic(), first.kind(), first.prompt(), first.referenceAnswer(), 0);
    }
    return view(uid, a.id());
  }

  private void insertTurn(
      String id, int seq, String topic, String kind, String prompt, String reference, int depth) {
    db.update(
        "INSERT INTO"
            + " interview_turn(id,activity_id,sequence,topic,kind,prompt,reference_answer,followup_depth)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        id,
        seq,
        topic,
        kind,
        prompt,
        reference,
        depth);
  }

  public Map<String, Object> view(String uid, String id) {
    var a = activities.owned(uid, id);
    if (!a.module().equals("INTERVIEW")) throw ApiException.missing();
    var s = db.queryForMap("SELECT * FROM interview_session WHERE activity_id=?", id);
    int seq = ((Number) s.get("current_sequence")).intValue(),
        remaining = ((Number) s.get("remaining_seconds")).intValue();
    OffsetDateTime started = offset(s.get("current_started"));
    if (started != null && a.state().equals("ACTIVE"))
      remaining =
          Math.max(
              0,
              remaining
                  - (int) Duration.between(started.toInstant(), clock.instant()).getSeconds());
    var turns =
        db.query(
            "SELECT * FROM interview_turn WHERE activity_id=? ORDER BY sequence",
            (r, n) -> {
              Map<String, Object> t = new LinkedHashMap<>();
              t.put("sequence", r.getInt("sequence"));
              t.put("question", r.getString("prompt"));
              t.put("topic", r.getString("topic"));
              t.put("kind", r.getString("kind"));
              t.put("answer", r.getString("answer"));
              return t;
            },
            id);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("activity", a);
    result.put("turns", turns);
    result.put("sequence", seq);
    result.put("remainingSeconds", remaining);
    result.put("wallDeadline", offset(s.get("wall_deadline")).toInstant().toString());
    return result;
  }

  private OffsetDateTime offset(Object v) {
    if (v == null) return null;
    if (v instanceof OffsetDateTime o) return o;
    return ((java.sql.Timestamp) v).toInstant().atOffset(ZoneOffset.UTC);
  }

  @Transactional
  public Map<String, Object> answer(String uid, String id, Answer input) {
    var a = activities.lock(uid, id);
    if (!a.module().equals("INTERVIEW")) throw ApiException.missing();
    if (input == null
        || input.submissionKey() == null
        || input.submissionKey().isBlank()
        || input.submissionKey().length() > 100
        || input.text() == null
        || input.text().isBlank()
        || input.text().length() > 8000)
      throw ApiException.bad("An answer and submission key are required.");
    var prior =
        db.queryForList(
            "SELECT answer,media_id,submission_key FROM interview_turn WHERE activity_id=? AND"
                + " sequence=?",
            id,
            input.sequence());
    if (prior.isEmpty()) throw ApiException.missing();
    var turn = prior.get(0);
    if (turn.get("answer") != null) {
      if (!input.submissionKey().equals(turn.get("submission_key"))
          || !input.text().equals(turn.get("answer"))
          || !Objects.equals(input.mediaId(), turn.get("media_id")))
        throw ApiException.conflict("This question already has an accepted answer.");
      return Map.of(
          "jobId",
          jobs.enqueue(
              uid,
              "INTERVIEW_ANSWER",
              "interview-answer:" + id + ":" + input.sequence(),
              Map.of("activityId", id, "sequence", input.sequence())),
          "activityId",
          id);
    }
    var s = db.queryForMap("SELECT * FROM interview_session WHERE activity_id=?", id);
    if (!a.state().equals("ACTIVE")
        || input.sequence() != ((Number) s.get("current_sequence")).intValue())
      throw ApiException.conflict("Wait for the next question or reload this interview.");
    long elapsed =
        Duration.between(offset(s.get("current_started")).toInstant(), clock.instant())
            .getSeconds();
    int remaining = ((Number) s.get("remaining_seconds")).intValue() - (int) Math.max(0, elapsed);
    if (remaining <= 0 || !clock.instant().isBefore(offset(s.get("wall_deadline")).toInstant()))
      throw ApiException.conflict("Interview time has expired. Finish to receive your report.");
    if (input.mediaId() != null) {
      var m = media.owned(uid, input.mediaId());
      if (!m.state().equals("READY")) throw ApiException.bad("Wait for transcription.");
    }
    db.update(
        "UPDATE interview_turn SET answer=?,media_id=?,submission_key=? WHERE activity_id=? AND"
            + " sequence=?",
        input.text(),
        input.mediaId(),
        input.submissionKey(),
        id,
        input.sequence());
    db.update(
        "UPDATE interview_session SET remaining_seconds=?,current_started=NULL WHERE activity_id=?",
        remaining,
        id);
    activities.state(id, "PROCESSING");
    return Map.of(
        "jobId",
        jobs.enqueue(
            uid,
            "INTERVIEW_ANSWER",
            "interview-answer:" + id + ":" + input.sequence(),
            Map.of("activityId", id, "sequence", input.sequence())),
        "activityId",
        id);
  }

  @Transactional
  public Map<String, Object> finish(String uid, String id) {
    var a = activities.lock(uid, id);
    if (!a.module().equals("INTERVIEW")) throw ApiException.missing();
    if (a.state().equals("COMPLETED")) return Map.of("activityId", id, "state", "COMPLETED");
    activities.state(id, "FINISHING");
    return Map.of(
        "activityId",
        id,
        "jobId",
        jobs.enqueue(uid, "INTERVIEW_REPORT", "interview-report:" + id, Map.of("activityId", id)));
  }

  @Transactional
  public void process(
      String uid,
      String id,
      int sequence,
      AnswerEvaluator evaluator,
      InterviewGenerator generator) {
    var a = activities.lock(uid, id);
    if (a.state().equals("COMPLETED")) return;
    var t =
        db.queryForMap(
            "SELECT * FROM interview_turn WHERE activity_id=? AND sequence=?", id, sequence);
    if (t.get("answer") == null) throw new IllegalStateException("Answer missing");
    AnswerEvaluator.Evaluation evaluation;
    if (t.get("evaluation") == null) {
      evaluation =
          evaluator.evaluate(
              new AnswerEvaluator.Request(
                  (String) t.get("kind"),
                  (String) t.get("prompt"),
                  (String) t.get("reference_answer"),
                  List.of("Use only stated facts", "Support reasoning with a relevant example"),
                  (String) t.get("answer")));
      db.update(
          "UPDATE interview_turn SET evaluation=? WHERE id=?", Json.write(evaluation), t.get("id"));
    } else evaluation = Json.read((String) t.get("evaluation"), AnswerEvaluator.Evaluation.class);
    var s = db.queryForMap("SELECT * FROM interview_session WHERE activity_id=?", id);
    if (((Number) s.get("current_sequence")).intValue() > sequence) return;
    if (a.state().equals("FINISHING")
        || sequence + 1 >= ((Number) s.get("max_questions")).intValue()
        || !clock.instant().isBefore(offset(s.get("wall_deadline")).toInstant())) {
      report(uid, id);
      return;
    }
    int depth = ((Number) t.get("followup_depth")).intValue();
    if (evaluation.scorable() && depth < 2) {
      var follow =
          generator.followUp(
              (String) t.get("kind"),
              (String) t.get("topic"),
              (String) t.get("prompt"),
              (String) t.get("answer"),
              (String) t.get("reference_answer"),
              depth + 1);
      insertTurn(
          id,
          sequence + 1,
          (String) t.get("topic"),
          (String) t.get("kind"),
          follow.question(),
          (String) t.get("reference_answer"),
          depth + 1);
    } else {
      var seen =
          db.query(
              "SELECT prompt FROM interview_turn WHERE activity_id=?",
              (r, n) -> r.getString(1),
              id);
      var skills = (List<?>) a.context().get("skills");
      var available =
          catalog
              .seeds((String) a.context().get("roleId"), (String) a.context().get("type"))
              .stream()
              .filter(
                  seed ->
                      !seen.contains(seed.prompt())
                          && (seed.kind().equals("HR") || skills.contains(seed.topic())))
              .toList();
      if (available.isEmpty()) {
        report(uid, id);
        return;
      }
      var seed =
          available.stream()
              .filter(
                  v -> !a.context().get("type").equals("MIXED") || !v.kind().equals(t.get("kind")))
              .findFirst()
              .orElse(available.get(0));
      insertTurn(
          id, sequence + 1, seed.topic(), seed.kind(), seed.prompt(), seed.referenceAnswer(), 0);
    }
    db.update(
        "UPDATE interview_session SET current_sequence=?,current_started=? WHERE activity_id=?",
        sequence + 1,
        OffsetDateTime.now(clock),
        id);
    activities.state(id, "ACTIVE");
  }

  @Transactional
  public void report(String uid, String id) {
    var a = activities.lock(uid, id);
    if (a.state().equals("COMPLETED")) return;
    var turns =
        db.queryForList(
            "SELECT sequence,prompt,kind,topic,answer,evaluation FROM interview_turn WHERE"
                + " activity_id=? AND answer IS NOT NULL ORDER BY sequence",
            id);
    if (turns.stream().anyMatch(t -> t.get("evaluation") == null))
      throw new IllegalStateException("Answers are still being evaluated");
    List<Map<String, Object>> details = new ArrayList<>();
    List<Double> scores = new ArrayList<>();
    Map<String, List<Double>> byTopic = new TreeMap<>();
    List<String> strengths = new ArrayList<>(), improvements = new ArrayList<>();
    for (var t : turns) {
      var e = Json.read((String) t.get("evaluation"), AnswerEvaluator.Evaluation.class);
      Map<String, Object> d = new LinkedHashMap<>();
      d.put("question", t.get("prompt"));
      d.put("answer", t.get("answer"));
      d.put("evaluation", e);
      details.add(d);
      if (e.scorable()) {
        double score = Scoring.score((String) t.get("kind"), e.dimensions());
        scores.add(score);
        byTopic.computeIfAbsent((String) t.get("topic"), k -> new ArrayList<>()).add(score);
      }
      strengths.addAll(e.strengths());
      improvements.addAll(e.improvements());
    }
    Double score =
        scores.isEmpty()
            ? null
            : scores.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("module", "INTERVIEW");
    report.put("score", score);
    report.put("strengths", strengths.stream().distinct().limit(3).toList());
    report.put("improvements", improvements.stream().distinct().limit(3).toList());
    report.put("answers", details);
    report.put("rubricVersion", "rubric-v1");
    Map<String, Double> competencies = new TreeMap<>();
    byTopic.forEach(
        (k, v) ->
            competencies.put(
                k, v.stream().mapToDouble(Double::doubleValue).average().orElseThrow()));
    activities.complete(id, report, score, score != null, competencies);
  }

  public Map<String, Object> result(String uid, String id) {
    var a = activities.owned(uid, id);
    if (!a.module().equals("INTERVIEW")) throw ApiException.missing();
    return a.report() == null ? Map.of("state", a.state()) : a.report();
  }
}

package com.interviewedge.tests;

import com.interviewedge.assessment.*;
import com.interviewedge.common.*;
import com.interviewedge.content.ContentCatalog;
import com.interviewedge.jobs.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TestService {
  private final JdbcTemplate db;
  private final Activities activities;
  private final ContentCatalog content;
  private final QuestionSelectionPolicy selection;
  private final Jobs jobs;
  private final Clock clock;

  public TestService(
      JdbcTemplate db,
      Activities activities,
      ContentCatalog content,
      QuestionSelectionPolicy selection,
      Jobs jobs,
      Clock clock) {
    this.db = db;
    this.activities = activities;
    this.content = content;
    this.selection = selection;
    this.jobs = jobs;
    this.clock = clock;
  }

  public record Setup(String subject, String topicId, String difficulty, int count) {}

  public record Response(String answer, boolean marked, int version) {}

  @Transactional
  public Map<String, Object> create(String uid, String key, Setup s) {
    if (s == null
        || !List.of("DSA", "DBMS", "OS").contains(s.subject())
        || !List.of("EASY", "MEDIUM", "HARD").contains(s.difficulty())
        || !List.of(5, 10, 15).contains(s.count()))
      throw ApiException.bad("Invalid test settings.");
    String topic = s.topicId() == null ? "" : s.topicId();
    var context =
        Map.<String, Object>of(
            "subject",
            s.subject(),
            "topicId",
            topic,
            "difficulty",
            s.difficulty(),
            "count",
            s.count());
    var a = activities.create(uid, "TEST", key, context);
    if (db.queryForList("SELECT activity_id FROM test_attempt WHERE activity_id=?", a.id())
        .isEmpty()) {
      var questions =
          selection.select(content.candidates(s.subject(), topic, s.difficulty()), s.count());
      db.update(
          "INSERT INTO test_attempt(activity_id,deadline) VALUES(?,?)",
          a.id(),
          OffsetDateTime.now(clock).plusMinutes(s.count() * 2L));
      for (int i = 0; i < questions.size(); i++)
        db.update(
            "INSERT INTO test_item(id,activity_id,question_id,position) VALUES(?,?,?,?)",
            UUID.randomUUID().toString(),
            a.id(),
            questions.get(i).id(),
            i);
    }
    return view(uid, a.id());
  }

  public Map<String, Object> view(String uid, String id) {
    var a = activities.owned(uid, id);
    if (!a.module().equals("TEST")) throw ApiException.missing();
    String deadline = deadline(id).toInstant().toString();
    var items =
        db.query(
            "SELECT * FROM test_item WHERE activity_id=? ORDER BY position",
            (r, n) -> {
              Map<String, Object> m =
                  new LinkedHashMap<>(content.question(r.getString("question_id")).publicView());
              m.put("itemId", r.getString("id"));
              m.put("position", r.getInt("position"));
              m.put("response", r.getString("response"));
              m.put("marked", r.getBoolean("marked"));
              m.put("version", r.getInt("version"));
              return m;
            },
            id);
    return Map.of(
        "activity",
        a,
        "deadline",
        deadline,
        "serverTime",
        clock.instant().toString(),
        "items",
        items);
  }

  private OffsetDateTime deadline(String id) {
    return db.queryForObject(
        "SELECT deadline FROM test_attempt WHERE activity_id=?",
        (r, n) -> r.getObject(1, OffsetDateTime.class),
        id);
  }

  @Transactional
  public Map<String, Object> save(String uid, String id, String itemId, Response response) {
    var a = activities.lock(uid, id);
    if (!a.module().equals("TEST")) throw ApiException.missing();
    if (!a.state().equals("ACTIVE") || !clock.instant().isBefore(deadline(id).toInstant()))
      throw ApiException.conflict("The test is no longer accepting answers.");
    String answer = response.answer() == null ? "" : response.answer();
    if (answer.length() > 6000) throw ApiException.bad("Answer is too long.");
    String qid =
        db
            .query(
                "SELECT question_id FROM test_item WHERE id=? AND activity_id=?",
                (r, n) -> r.getString(1),
                itemId,
                id)
            .stream()
            .findFirst()
            .orElseThrow(ApiException::missing);
    var q = content.question(qid);
    if (!q.type().equals("SHORT_ANSWER") && !answer.isEmpty() && !q.options().contains(answer))
      throw ApiException.bad("Choose an option from this question.");
    if (db.update(
            "UPDATE test_item SET response=?,marked=?,version=version+1 WHERE id=? AND"
                + " activity_id=? AND version=?",
            answer,
            response.marked(),
            itemId,
            id,
            response.version())
        != 1) {
      var current =
          db.queryForMap(
              "SELECT response,marked,version FROM test_item WHERE id=? AND activity_id=?",
              itemId,
              id);
      if (((Number) current.get("version")).intValue() == response.version() + 1
          && answer.equals(current.get("response"))
          && Boolean.valueOf(response.marked()).equals(current.get("marked")))
        return Map.of("version", response.version() + 1, "saved", true);
      throw ApiException.conflict("Answer changed. Reload before saving.");
    }
    return Map.of("version", response.version() + 1, "saved", true);
  }

  @Transactional
  public Map<String, Object> submit(String uid, String id) {
    var a = activities.lock(uid, id);
    if (!a.module().equals("TEST")) throw ApiException.missing();
    if (a.state().equals("COMPLETED")) return Map.of("state", "COMPLETED", "activityId", id);
    activities.state(id, "PROCESSING");
    String jobId = jobs.enqueue(uid, "GRADE_TEST", "grade-test:" + id, Map.of("activityId", id));
    return Map.of("state", "PROCESSING", "jobId", jobId, "activityId", id);
  }

  public List<Map<String, Object>> expired() {
    return db.query(
        "SELECT a.id,a.user_id FROM activity a JOIN test_attempt t ON a.id=t.activity_id WHERE"
            + " a.state='ACTIVE' AND t.deadline<=?",
        (r, n) -> Map.<String, Object>of("id", r.getString(1), "uid", r.getString(2)),
        OffsetDateTime.now(clock));
  }

  public Map<String, Object> result(String uid, String id) {
    var a = activities.owned(uid, id);
    if (!a.module().equals("TEST")) throw ApiException.missing();
    if (a.report() == null) return Map.of("state", a.state());
    return a.report();
  }

  @Transactional
  public void grade(String uid, String id, AnswerEvaluator evaluator) {
    var a = activities.lock(uid, id);
    if (a.state().equals("COMPLETED")) return;
    List<Map<String, Object>> details = new ArrayList<>();
    Map<String, List<Double>> byTopic = new TreeMap<>();
    double total = 0;
    int correct = 0, unanswered = 0, pending = 0;
    var items =
        db.queryForList(
            "SELECT id,question_id,response,points,grading_status,feedback FROM test_item WHERE"
                + " activity_id=? ORDER BY position",
            id);
    for (var item : items) {
      var q = content.question((String) item.get("question_id"));
      String answer = (String) item.get("response");
      Double points = null;
      String status = "FINAL";
      Object feedback = q.explanation();
      if ("REVIEWED".equals(item.get("grading_status"))) {
        points = ((Number) item.get("points")).doubleValue();
        status = "REVIEWED";
        feedback = Json.read((String) item.get("feedback"));
      } else if (answer == null || answer.isBlank()) {
        points = 0.0;
        unanswered++;
      } else if (!q.type().equals("SHORT_ANSWER")) {
        points = q.answer().equals(answer) ? 100.0 : 0.0;
        if (points == 100) correct++;
      } else {
        try {
          var evaluation =
              evaluator.evaluate(
                  new AnswerEvaluator.Request(
                      "SHORT_ANSWER", q.prompt(), q.answer(), q.criteria(), answer));
          feedback = evaluation;
          // Semantic AI marks remain provisional until a human reviews them.
          status = "PROVISIONAL";
          pending++;
        } catch (Exception e) {
          status = "PENDING";
          pending++;
        }
      }
      if (points != null) {
        total += points;
        byTopic.computeIfAbsent(q.topicId(), k -> new ArrayList<>()).add(points);
      }
      db.update(
          "UPDATE test_item SET points=?,grading_status=?,feedback=? WHERE id=?",
          points,
          status,
          Json.write(feedback),
          item.get("id"));
      Map<String, Object> d = new LinkedHashMap<>(q.publicView());
      d.put("itemId", item.get("id"));
      d.put("response", answer);
      d.put("correctAnswer", q.answer());
      d.put("explanation", q.explanation());
      d.put("points", points);
      d.put("gradingStatus", status);
      d.put("feedback", feedback);
      details.add(d);
    }
    int objective = items.size() - pending;
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("module", "TEST");
    report.put("score", pending == 0 ? total / items.size() : null);
    report.put("objectiveSubtotal", objective == 0 ? null : total / objective);
    report.put("correct", correct);
    report.put("unanswered", unanswered);
    report.put("pending", pending);
    report.put("items", details);
    report.put("rubricVersion", "test-v1");
    Map<String, Double> competencies = new TreeMap<>();
    byTopic.forEach(
        (k, v) ->
            competencies.put(k, v.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
    activities.complete(
        id, report, pending == 0 ? total / items.size() : null, pending == 0, competencies);
  }

  @Transactional
  public void review(
      String actor,
      String id,
      String itemId,
      double points,
      String reason,
      AnswerEvaluator evaluator) {
    var a = activities.get(id);
    activities.lock(a.userId(), id);
    if (!Double.isFinite(points)
        || points < 0
        || points > 100
        || reason == null
        || reason.isBlank()
        || reason.length() > 2000)
      throw ApiException.bad("A score from 0 to 100 and review reason are required.");
    if (db.update(
            "UPDATE test_item SET points=?,grading_status='REVIEWED',feedback=? WHERE id=? AND"
                + " activity_id=? AND grading_status IN ('PROVISIONAL','PENDING')",
            points,
            Json.write(Map.of("reviewer", actor, "reason", reason)),
            itemId,
            id)
        != 1) throw ApiException.conflict("Item is not awaiting review.");
    activities.state(id, "PROCESSING");
    grade(a.userId(), id, evaluator);
  }

  public List<Map<String, Object>> pendingReviews() {
    return db.queryForList(
        "SELECT i.id AS item_id,i.activity_id,i.response,i.feedback,q.prompt,q.answer,q.criteria"
            + " FROM test_item i JOIN question q ON q.id=i.question_id WHERE i.grading_status IN"
            + " ('PROVISIONAL','PENDING') ORDER BY i.activity_id");
  }
}

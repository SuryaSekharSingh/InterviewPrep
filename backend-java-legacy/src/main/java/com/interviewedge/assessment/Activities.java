package com.interviewedge.assessment;

import com.interviewedge.common.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Activities {
  public record Activity(
      String id,
      String userId,
      String module,
      String state,
      Map<String, Object> context,
      Map<String, Object> report,
      Double score,
      boolean eligible,
      int version,
      String createdAt,
      String completedAt) {}

  private final JdbcTemplate db;

  public Activities(JdbcTemplate db) {
    this.db = db;
  }

  private Activity map(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    return new Activity(
        r.getString("id"),
        r.getString("user_id"),
        r.getString("module"),
        r.getString("state"),
        Json.read(r.getString("context")),
        r.getString("report") == null ? null : Json.read(r.getString("report")),
        (Double) r.getObject("score"),
        r.getBoolean("eligible"),
        r.getInt("version"),
        r.getObject("created_at", OffsetDateTime.class).toInstant().toString(),
        r.getObject("completed_at") == null
            ? null
            : r.getObject("completed_at", OffsetDateTime.class).toInstant().toString());
  }

  public Activity get(String id) {
    return db.query("SELECT * FROM activity WHERE id=?", this::map, id).stream()
        .findFirst()
        .orElseThrow(ApiException::missing);
  }

  public Activity owned(String uid, String id) {
    Activity a = get(id);
    if (!a.userId().equals(uid)) throw ApiException.missing();
    return a;
  }

  public Activity lock(String uid, String id) {
    return db
        .query("SELECT * FROM activity WHERE id=? AND user_id=? FOR UPDATE", this::map, id, uid)
        .stream()
        .findFirst()
        .orElseThrow(ApiException::missing);
  }

  public List<Activity> history(String uid) {
    return db.query(
        "SELECT * FROM activity WHERE user_id=? ORDER BY created_at DESC LIMIT 200",
        this::map,
        uid);
  }

  public List<Activity> export(String uid) {
    return db.query(
        "SELECT * FROM activity WHERE user_id=? ORDER BY created_at DESC", this::map, uid);
  }

  @Transactional
  public Activity create(String uid, String module, String key, Map<String, Object> context) {
    if (key == null || key.isBlank() || key.length() > 100)
      throw ApiException.bad("A submission key is required.");
    db.queryForList("SELECT id FROM edge_user WHERE id=? FOR UPDATE", uid);
    String hash = hash(module + Json.write(new TreeMap<>(context)));
    var existing =
        db.query(
            "SELECT * FROM activity WHERE user_id=? AND submission_key=?", this::map, uid, key);
    if (!existing.isEmpty()) {
      String prior =
          db.queryForObject(
              "SELECT request_hash FROM activity WHERE id=?", String.class, existing.get(0).id());
      if (!hash.equals(prior))
        throw ApiException.conflict("Submission key was already used for different settings.");
      return existing.get(0);
    }
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO activity(id,user_id,module,state,context,submission_key,request_hash)"
            + " VALUES(?,?,?,'ACTIVE',?,?,?)",
        id,
        uid,
        module,
        Json.write(context),
        key,
        hash);
    return get(id);
  }

  public void state(String id, String state) {
    db.update("UPDATE activity SET state=?,version=version+1 WHERE id=?", state, id);
  }

  @Transactional
  public void complete(
      String id,
      Map<String, Object> report,
      Double score,
      boolean eligible,
      Map<String, Double> competencies) {
    Activity a = get(id);
    if (a.state().equals("COMPLETED")) return;
    if (score != null && (!Double.isFinite(score) || score < 0 || score > 100))
      throw new IllegalArgumentException("Invalid score");
    if (eligible && score == null) throw new IllegalArgumentException("Eligible score required");
    db.update(
        "UPDATE activity SET"
            + " state='COMPLETED',report=?,score=?,eligible=?,completed_at=CURRENT_TIMESTAMP,version=version+1"
            + " WHERE id=?",
        Json.write(report),
        score,
        eligible,
        id);
    if (eligible) {
      var event =
          Map.of(
              "activityId",
              id,
              "userId",
              a.userId(),
              "module",
              a.module(),
              "score",
              score,
              "competencies",
              competencies,
              "scoringVersion",
              "v1",
              "context",
              a.context(),
              "completedAt",
              java.time.Instant.now().toString());
      db.update(
          "INSERT INTO assessment_outbox(id,activity_id,version,payload) VALUES(?,?,?,?) ON"
              + " CONFLICT DO NOTHING",
          UUID.randomUUID().toString(),
          id,
          a.version() + 1,
          Json.write(event));
    }
  }

  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}

package com.interviewedge.jobs;

import com.interviewedge.common.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class Jobs {
  private final JdbcTemplate db;

  public Jobs(JdbcTemplate db) {
    this.db = db;
  }

  public String enqueue(String uid, String kind, String key, Map<String, Object> payload) {
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO job(id,user_id,kind,dedupe_key,payload,activity_id) VALUES(?,?,?,?,?,?) ON"
            + " CONFLICT DO NOTHING",
        id,
        uid,
        kind,
        key,
        Json.write(payload),
        payload.get("activityId"));
    return db.queryForObject("SELECT id FROM job WHERE dedupe_key=?", String.class, key);
  }

  public Map<String, Object> owned(String uid, String id) {
    return db
        .query(
            "SELECT id,kind,state,attempts,error_code FROM job WHERE id=? AND user_id=?",
            (r, n) -> {
              Map<String, Object> result = new LinkedHashMap<>();
              result.put("id", r.getString("id"));
              result.put("kind", r.getString("kind"));
              result.put("state", r.getString("state"));
              result.put("attempts", r.getInt("attempts"));
              result.put("errorCode", r.getString("error_code"));
              return result;
            },
            id,
            uid)
        .stream()
        .findFirst()
        .orElseThrow(ApiException::missing);
  }

  public void retry(String uid, String id) {
    owned(uid, id);
    if (db.update(
            "UPDATE job SET"
                + " state='QUEUED',attempts=0,error_code=NULL,available_at=CURRENT_TIMESTAMP WHERE"
                + " id=? AND user_id=? AND state='FAILED'",
            id,
            uid)
        != 1) throw ApiException.conflict("Only failed jobs can be retried.");
  }

  public List<Map<String, Object>> forActivity(String uid, String activityId) {
    try {
      UUID.fromString(activityId);
    } catch (IllegalArgumentException e) {
      throw ApiException.bad("Invalid activity identifier.");
    }
    var ids =
        db.query(
            "SELECT id,payload,activity_id FROM job WHERE user_id=? AND (activity_id=? OR"
                + " activity_id IS NULL) ORDER BY created_at",
            (r, n) ->
                activityId.equals(r.getString("activity_id"))
                        || activityId.equals(Json.read(r.getString("payload")).get("activityId"))
                    ? r.getString("id")
                    : null,
            uid,
            activityId);
    return ids.stream().filter(Objects::nonNull).map(id -> owned(uid, id)).toList();
  }
}

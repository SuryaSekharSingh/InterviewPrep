package com.interviewedge.assessment;

import com.interviewedge.common.Json;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AssessmentEvents {
  public record Event(String id, Map<String, Object> data) {}

  private final JdbcTemplate db;

  public AssessmentEvents(JdbcTemplate db) {
    this.db = db;
  }

  public List<Event> pending() {
    return db.query(
        "SELECT id,payload FROM assessment_outbox WHERE processed=FALSE ORDER BY"
            + " activity_id,version LIMIT 50",
        (r, n) -> new Event(r.getString(1), Json.read(r.getString(2))));
  }

  public boolean claim(String id) {
    return db.update(
            "UPDATE assessment_outbox SET processed=TRUE WHERE id=? AND processed=FALSE", id)
        == 1;
  }
}

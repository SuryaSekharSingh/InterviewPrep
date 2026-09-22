package com.interviewedge.jobs;

import com.interviewedge.common.Json;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "edge.jobs-enabled", havingValue = "true", matchIfMissing = true)
public class JobWorker {
  private final JdbcTemplate db;
  private final Map<String, JobHandler> handlers = new HashMap<>();

  public JobWorker(JdbcTemplate db, List<JobHandler> handlers) {
    this.db = db;
    handlers.forEach(
        h -> {
          if (this.handlers.put(h.kind(), h) != null)
            throw new IllegalStateException("Duplicate handler");
        });
  }

  @Scheduled(fixedDelay = 1500)
  public void run() {
    db.update(
        "UPDATE job SET state='QUEUED',available_at=CURRENT_TIMESTAMP WHERE state='RUNNING' AND"
            + " lease_until<CURRENT_TIMESTAMP");
    var due =
        db.queryForList(
            "SELECT id,user_id,kind,payload,attempts FROM job WHERE state='QUEUED' AND"
                + " available_at<=CURRENT_TIMESTAMP ORDER BY created_at LIMIT 1");
    if (due.isEmpty()) return;
    var j = due.get(0);
    String id = (String) j.get("id");
    if (db.update(
            "UPDATE job SET state='RUNNING',attempts=attempts+1,lease_until=? WHERE id=? AND"
                + " state='QUEUED'",
            OffsetDateTime.now().plusMinutes(15),
            id)
        != 1) return;
    try {
      JobHandler handler = handlers.get((String) j.get("kind"));
      if (handler == null) throw new IllegalStateException("No handler");
      handler.handle((String) j.get("user_id"), Json.read((String) j.get("payload")));
      db.update("UPDATE job SET state='COMPLETED',lease_until=NULL,error_code=NULL WHERE id=?", id);
    } catch (Exception e) {
      int attempt = ((Number) j.get("attempts")).intValue() + 1;
      db.update(
          "UPDATE job SET state=?,error_code=?,lease_until=NULL,available_at=? WHERE id=?",
          attempt >= 3 ? "FAILED" : "QUEUED",
          "PROCESSING_UNAVAILABLE",
          OffsetDateTime.now().plusSeconds(15L * attempt),
          id);
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("Job {} failed ({})", id, e.getClass().getSimpleName());
    }
  }
}

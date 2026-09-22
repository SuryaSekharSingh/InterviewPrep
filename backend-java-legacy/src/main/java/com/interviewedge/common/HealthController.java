package com.interviewedge.common;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class HealthController {
  private final JdbcTemplate db;

  public HealthController(JdbcTemplate db) {
    this.db = db;
  }

  @GetMapping("/health")
  public Map<String, Object> health() {
    db.queryForObject("SELECT 1", Integer.class);
    return Map.of("status", "UP", "application", "InterviewEdge");
  }
}

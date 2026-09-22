package com.interviewedge.identity;

import com.interviewedge.common.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Profiles {
  private final JdbcTemplate db;

  public Profiles(JdbcTemplate db) {
    this.db = db;
  }

  @Transactional
  public void ensure(String uid) {
    if (db.queryForList("SELECT id FROM edge_user WHERE id=?", uid).isEmpty()) {
      db.update("INSERT INTO edge_user(id) VALUES(?) ON CONFLICT DO NOTHING", uid);
      db.update("INSERT INTO profile(user_id) VALUES(?) ON CONFLICT DO NOTHING", uid);
    }
    String state = db.queryForObject("SELECT status FROM edge_user WHERE id=?", String.class, uid);
    if (!"ACTIVE".equals(state))
      throw new ApiException(
          org.springframework.http.HttpStatus.FORBIDDEN, "Account deletion is in progress.");
  }

  public Map<String, Object> get(String uid) {
    return db.queryForObject(
        "SELECT * FROM profile WHERE user_id=?",
        (r, n) -> {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("displayName", r.getString("display_name"));
          m.put("roleId", r.getString("role_id"));
          m.put("weeklyGoal", r.getInt("weekly_goal"));
          m.put("education", r.getString("education"));
          m.put("skills", Json.read("{\"v\":" + r.getString("skills") + "}").get("v"));
          m.put("timezone", r.getString("timezone"));
          m.put("retentionDays", r.getInt("retention_days"));
          m.put("consentVersion", r.getString("consent_version"));
          return m;
        },
        uid);
  }

  public Map<String, Object> update(String uid, Map<String, Object> body) {
    Map<String, Object> p = get(uid);
    p.putAll(body);
    String name = Json.text(p, "displayName", "").trim(), role = Json.text(p, "roleId", "");
    int goal = Json.integer(p, "weeklyGoal", 5), retention = Json.integer(p, "retentionDays", 7);
    String timezone = Json.text(p, "timezone", "Asia/Kolkata");
    if (name.isBlank()
        || name.length() > 100
        || goal < 1
        || goal > 30
        || !List.of(7, 30).contains(retention))
      throw ApiException.bad("Check name, weekly goal and retention.");
    try {
      ZoneId.of(timezone);
    } catch (DateTimeException e) {
      throw ApiException.bad("Choose a valid time zone.");
    }
    if (db.queryForList("SELECT id FROM role_catalog WHERE id=?", role).isEmpty())
      throw ApiException.bad("Unknown role.");
    Object skills = p.get("skills");
    if (!(skills instanceof List<?> l)
        || l.size() > 15
        || l.stream().anyMatch(v -> !(v instanceof String s) || s.length() > 80))
      throw ApiException.bad("Choose up to 15 skills.");
    String education = Json.text(p, "education", "");
    if (education.length() > 500) throw ApiException.bad("Education is too long.");
    String consent = Json.text(p, "consentVersion", "");
    if (!consent.isBlank() && !consent.equals("privacy-v1"))
      throw ApiException.bad("Unsupported privacy notice.");
    db.update(
        "UPDATE profile SET"
            + " display_name=?,role_id=?,weekly_goal=?,education=?,skills=?,timezone=?,retention_days=?,consent_version=?,updated_at=CURRENT_TIMESTAMP"
            + " WHERE user_id=?",
        name,
        role,
        goal,
        education,
        Json.write(skills),
        timezone,
        retention,
        consent,
        uid);
    return get(uid);
  }

  public void requireConsent(String uid) {
    if (!"privacy-v1".equals(get(uid).get("consentVersion")))
      throw ApiException.bad("Review and accept the recording and AI privacy notice in Profile.");
  }
}

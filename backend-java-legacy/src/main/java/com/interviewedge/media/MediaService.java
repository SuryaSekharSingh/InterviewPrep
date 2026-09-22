package com.interviewedge.media;

import com.interviewedge.common.*;
import com.interviewedge.identity.Profiles;
import com.interviewedge.jobs.Jobs;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediaService {
  public record Media(
      String id, String state, double durationSeconds, String transcript, String expiresAt) {}

  private final JdbcTemplate db;
  private final MediaStorage storage;
  private final Jobs jobs;
  private final Profiles profiles;

  public MediaService(JdbcTemplate db, MediaStorage storage, Jobs jobs, Profiles profiles) {
    this.db = db;
    this.storage = storage;
    this.jobs = jobs;
    this.profiles = profiles;
  }

  public Media owned(String uid, String id) {
    return db
        .query(
            "SELECT * FROM media WHERE id=? AND user_id=? AND state<>'DELETED'",
            (r, n) ->
                new Media(
                    r.getString("id"),
                    r.getString("state"),
                    r.getDouble("duration_seconds"),
                    r.getString("transcript"),
                    r.getObject("delete_after", OffsetDateTime.class).toInstant().toString()),
            id,
            uid)
        .stream()
        .findFirst()
        .orElseThrow(ApiException::missing);
  }

  @Transactional
  public Map<String, Object> upload(String uid, byte[] bytes) {
    profiles.requireConsent(uid);
    double duration = WavValidator.validate(bytes);
    db.queryForList("SELECT id FROM edge_user WHERE id=? FOR UPDATE", uid);
    String checksum;
    try {
      checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    var previous =
        db.query(
            "SELECT id FROM media WHERE user_id=? AND checksum=? AND state<>'DELETED'",
            (r, n) -> r.getString(1),
            uid,
            checksum);
    if (!previous.isEmpty())
      return Map.of(
          "media",
          owned(uid, previous.get(0)),
          "jobId",
          jobs.enqueue(
              uid,
              "TRANSCRIBE",
              "transcribe:" + previous.get(0),
              Map.of("mediaId", previous.get(0))));
    String id = UUID.randomUUID().toString(), key = id + ".wav";
    storage.write(key, bytes);
    try {
      int retention = ((Number) profiles.get(uid).get("retentionDays")).intValue();
      db.update(
          "INSERT INTO"
              + " media(id,user_id,storage_key,bytes,duration_seconds,checksum,state,delete_after)"
              + " VALUES(?,?,?,?,?,?,'PROCESSING',?)",
          id,
          uid,
          key,
          bytes.length,
          duration,
          checksum,
          OffsetDateTime.now().plusDays(retention));
      String job = jobs.enqueue(uid, "TRANSCRIBE", "transcribe:" + id, Map.of("mediaId", id));
      return Map.of("media", owned(uid, id), "jobId", job);
    } catch (RuntimeException e) {
      storage.delete(key);
      throw e;
    }
  }

  public Path path(String uid, String id) {
    owned(uid, id);
    return storage.path(
        db.queryForObject("SELECT storage_key FROM media WHERE id=?", String.class, id));
  }

  public void transcribe(String uid, String id, SpeechTranscriber transcriber) {
    Media m = owned(uid, id);
    if (m.state().equals("READY")) return;
    String transcript = transcriber.transcribe(path(uid, id));
    db.update(
        "UPDATE media SET transcript=?,state='READY' WHERE id=? AND user_id=? AND"
            + " state='PROCESSING'",
        transcript,
        id,
        uid);
  }

  public void delete(String uid, String id) {
    owned(uid, id);
    String key = db.queryForObject("SELECT storage_key FROM media WHERE id=?", String.class, id);
    storage.delete(key);
    db.update(
        "UPDATE media SET state='DELETED',transcript=NULL,checksum=? WHERE id=?",
        UUID.randomUUID().toString(),
        id);
  }

  public List<String> ids(String uid) {
    return db.query(
        "SELECT id FROM media WHERE user_id=? AND state<>'DELETED'", (r, n) -> r.getString(1), uid);
  }

  @Scheduled(fixedDelay = 60000)
  public void expire() {
    var expired =
        db.queryForList(
            "SELECT id,user_id FROM media WHERE state<>'DELETED' AND"
                + " delete_after<CURRENT_TIMESTAMP");
    for (var m : expired) delete((String) m.get("user_id"), (String) m.get("id"));
  }
}

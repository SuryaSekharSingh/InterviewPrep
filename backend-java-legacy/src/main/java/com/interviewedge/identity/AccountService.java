package com.interviewedge.identity;

import com.interviewedge.assessment.Activities;
import com.interviewedge.common.*;
import com.interviewedge.jobs.*;
import com.interviewedge.media.MediaService;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService implements JobHandler {
  private final Profiles profiles;
  private final Activities activities;
  private final MediaService media;
  private final Jobs jobs;
  private final IdentityVerifier identities;
  private final JdbcTemplate db;
  private final List<UserDataSource> sources;

  public AccountService(
      Profiles profiles,
      Activities activities,
      MediaService media,
      Jobs jobs,
      IdentityVerifier identities,
      JdbcTemplate db,
      List<UserDataSource> sources) {
    this.profiles = profiles;
    this.activities = activities;
    this.media = media;
    this.jobs = jobs;
    this.identities = identities;
    this.db = db;
    this.sources = List.copyOf(sources);
  }

  public void export(String uid, OutputStream output) throws IOException {
    try (var zip = new ZipOutputStream(output)) {
      zip.putNextEntry(new ZipEntry("profile.json"));
      zip.write(Json.write(profiles.get(uid)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("activities.json"));
      zip.write(
          Json.write(activities.export(uid)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      zip.closeEntry();
      for (var source : sources) {
        zip.putNextEntry(new ZipEntry(source.name() + ".json"));
        zip.write(Json.write(source.export(uid)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
      }
      for (String id : media.ids(uid)) {
        var file = media.path(uid, id);
        if (Files.exists(file)) {
          zip.putNextEntry(new ZipEntry("recordings/" + id + ".wav"));
          Files.copy(file, zip);
          zip.closeEntry();
        }
      }
    }
  }

  @Transactional
  public Map<String, Object> delete(String uid) {
    db.update("UPDATE edge_user SET status='DELETING' WHERE id=?", uid);
    return Map.of(
        "state",
        "DELETING",
        "jobId",
        jobs.enqueue(uid, "DELETE_ACCOUNT", "delete:" + uid, Map.of()));
  }

  public String kind() {
    return "DELETE_ACCOUNT";
  }

  public void handle(String uid, Map<String, Object> payload) {
    for (String id : media.ids(uid)) media.delete(uid, id);
    identities.delete(uid);
    db.update("UPDATE audit_event SET actor='deleted-account' WHERE actor=?", uid);
    db.update("DELETE FROM edge_user WHERE id=?", uid);
  }
}

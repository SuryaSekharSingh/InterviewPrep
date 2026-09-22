package com.interviewedge.identity;

import com.interviewedge.assessment.Activities;
import com.interviewedge.common.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LocalAccounts {
  public record Session(
      String userId, String token, String expiresAt, List<String> recoveryCodes) {}

  private final JdbcTemplate db;
  private final Profiles profiles;
  private final Clock clock;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
  private final SecureRandom random = new SecureRandom();
  private final String dummy = passwords.encode("non-existent-account-timing-check");

  public LocalAccounts(JdbcTemplate db, Profiles profiles, Clock clock) {
    this.db = db;
    this.profiles = profiles;
    this.clock = clock;
  }

  private String username(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_.-]{3,40}"))
      throw ApiException.bad(
          "Use a username of 3–40 letters, numbers, dots, hyphens or underscores.");
    return value.toLowerCase(Locale.ROOT);
  }

  private void password(String value) {
    if (value == null || value.length() < 10 || value.getBytes(StandardCharsets.UTF_8).length > 72)
      throw ApiException.bad(
          "Use a password of at least 10 characters and no more than 72 UTF-8 bytes.");
  }

  private ApiException rejected() {
    return new ApiException(HttpStatus.UNAUTHORIZED, "The credentials could not be verified.");
  }

  private String randomToken(int bytes) {
    byte[] value = new byte[bytes];
    random.nextBytes(value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private Session issue(String uid, List<String> recovery) {
    String token = randomToken(32);
    OffsetDateTime now = OffsetDateTime.now(clock), expires = now.plusDays(7);
    db.update(
        "INSERT INTO login_session(token_hash,user_id,authenticated_at,expires_at) VALUES(?,?,?,?)",
        Activities.hash(token),
        uid,
        now,
        expires);
    return new Session(uid, token, expires.toInstant().toString(), recovery);
  }

  private List<String> recovery(String uid) {
    db.update("DELETE FROM recovery_code WHERE user_id=?", uid);
    List<String> codes = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      String code = randomToken(24);
      codes.add(code);
      db.update(
          "INSERT INTO recovery_code(code_hash,user_id) VALUES(?,?)", Activities.hash(code), uid);
    }
    return codes;
  }

  @Transactional
  public Session register(String name, String secret) {
    String user = username(name);
    password(secret);
    if (!db.queryForList("SELECT user_id FROM local_account WHERE username=?", user).isEmpty())
      throw ApiException.conflict("Choose another username.");
    String uid = UUID.randomUUID().toString();
    profiles.ensure(uid);
    db.update(
        "INSERT INTO local_account(user_id,username,password_hash) VALUES(?,?,?)",
        uid,
        user,
        passwords.encode(secret));
    return issue(uid, recovery(uid));
  }

  @Transactional
  public Session login(String name, String secret) {
    String user = username(name);
    if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length > 72) throw rejected();
    var matches =
        db.queryForList(
            "SELECT a.user_id,a.password_hash,u.status FROM local_account a JOIN edge_user u ON"
                + " u.id=a.user_id WHERE username=? FOR UPDATE",
            user);
    String hash = matches.isEmpty() ? dummy : (String) matches.get(0).get("password_hash");
    boolean valid = passwords.matches(secret, hash);
    if (!valid || matches.isEmpty() || !"ACTIVE".equals(matches.get(0).get("status")))
      throw rejected();
    return issue((String) matches.get(0).get("user_id"), List.of());
  }

  @Transactional
  public Session recover(String name, String code, String newPassword) {
    String user = username(name);
    password(newPassword);
    if (code == null || code.length() > 100) throw rejected();
    var matches =
        db.queryForList(
            "SELECT a.user_id FROM local_account a JOIN edge_user u ON u.id=a.user_id WHERE"
                + " username=? AND u.status='ACTIVE' FOR UPDATE",
            user);
    if (matches.isEmpty()) throw rejected();
    String uid = (String) matches.get(0).get("user_id");
    if (db.update(
            "DELETE FROM recovery_code WHERE user_id=? AND code_hash=?",
            uid,
            Activities.hash(code.trim()))
        != 1) throw rejected();
    db.update(
        "UPDATE local_account SET password_hash=? WHERE user_id=?",
        passwords.encode(newPassword),
        uid);
    db.update("DELETE FROM login_session WHERE user_id=?", uid);
    return issue(uid, recovery(uid));
  }

  @Transactional
  public Session changePassword(String uid, String current, String replacement) {
    password(replacement);
    String hash =
        db.queryForObject(
            "SELECT password_hash FROM local_account WHERE user_id=? FOR UPDATE",
            String.class,
            uid);
    if (current == null
        || current.getBytes(StandardCharsets.UTF_8).length > 72
        || !passwords.matches(current, hash)) throw rejected();
    db.update(
        "UPDATE local_account SET password_hash=? WHERE user_id=?",
        passwords.encode(replacement),
        uid);
    db.update("DELETE FROM login_session WHERE user_id=?", uid);
    return issue(uid, recovery(uid));
  }

  public void logout(String token) {
    db.update("DELETE FROM login_session WHERE token_hash=?", Activities.hash(token));
  }

  @Transactional
  public Session reauthenticate(String uid, String secret) {
    String hash =
        db.queryForObject(
            "SELECT a.password_hash FROM local_account a JOIN edge_user u ON u.id=a.user_id WHERE"
                + " a.user_id=? AND u.status='ACTIVE' FOR UPDATE",
            String.class,
            uid);
    if (secret == null
        || secret.getBytes(StandardCharsets.UTF_8).length > 72
        || !passwords.matches(secret, hash)) throw rejected();
    return issue(uid, List.of());
  }

  public void remove(String uid) {
    db.update("DELETE FROM login_session WHERE user_id=?", uid);
    db.update("DELETE FROM local_account WHERE user_id=?", uid);
    db.update("DELETE FROM recovery_code WHERE user_id=?", uid);
  }
}

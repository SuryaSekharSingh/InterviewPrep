package com.interviewedge.identity;

import com.interviewedge.assessment.Activities;
import com.interviewedge.common.ApiException;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class LocalIdentityVerifier implements IdentityVerifier {
  private final JdbcTemplate db;
  private final LocalAccounts accounts;
  private final Set<String> admins;
  private final Clock clock;

  public LocalIdentityVerifier(
      JdbcTemplate db,
      LocalAccounts accounts,
      @Value("${edge.admin-uids}") String admins,
      Clock clock) {
    this.db = db;
    this.accounts = accounts;
    this.admins = new HashSet<>(Arrays.asList(admins.split(",")));
    this.clock = clock;
  }

  public Identity verify(String token) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw unauthorized();
    return db
        .query(
            "SELECT user_id,authenticated_at FROM login_session WHERE token_hash=? AND"
                + " expires_at>?",
            (r, n) ->
                new Identity(
                    r.getString(1),
                    admins.contains(r.getString(1)),
                    r.getObject(2, OffsetDateTime.class).toEpochSecond()),
            Activities.hash(token),
            OffsetDateTime.now(clock))
        .stream()
        .findFirst()
        .orElseThrow(this::unauthorized);
  }

  private ApiException unauthorized() {
    return new ApiException(HttpStatus.UNAUTHORIZED, "Your session expired. Sign in again.");
  }

  public void delete(String uid) {
    accounts.remove(uid);
  }
}

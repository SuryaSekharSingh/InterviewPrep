package com.interviewedge.identity;

import com.interviewedge.common.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me")
public class AccountController {
  private final AccountService accounts;

  public AccountController(AccountService accounts) {
    this.accounts = accounts;
  }

  private void recent(Authentication auth) {
    var identity = (IdentityVerifier.Identity) auth.getCredentials();
    if (java.time.Instant.now().getEpochSecond() - identity.authenticatedAt() > 300)
      throw new ApiException(
          HttpStatus.UNAUTHORIZED, "Sign in again before exporting or deleting your account.");
  }

  @PostMapping("/exports")
  public void export(Authentication auth, HttpServletResponse response) throws java.io.IOException {
    recent(auth);
    response.setContentType("application/zip");
    response.setHeader("Content-Disposition", "attachment; filename=interviewedge-export.zip");
    response.setHeader("Cache-Control", "no-store");
    accounts.export(auth.getName(), response.getOutputStream());
  }

  @DeleteMapping
  public Map<String, Object> delete(Authentication auth) {
    recent(auth);
    return accounts.delete(auth.getName());
  }
}

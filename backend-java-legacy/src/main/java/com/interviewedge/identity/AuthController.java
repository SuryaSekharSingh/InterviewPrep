package com.interviewedge.identity;

import com.interviewedge.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  public record Credentials(String username, String password) {}

  public record Recovery(String username, String recoveryCode, String newPassword) {}

  public record PasswordChange(String currentPassword, String newPassword) {}

  private final LocalAccounts accounts;
  private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

  public AuthController(LocalAccounts accounts) {
    this.accounts = accounts;
  }

  private void limit(HttpServletRequest request) {
    long now = System.currentTimeMillis();
    var queue = attempts.computeIfAbsent(request.getRemoteAddr(), k -> new ArrayDeque<>());
    synchronized (queue) {
      while (!queue.isEmpty() && queue.peekFirst() < now - 60000) queue.removeFirst();
      if (queue.size() >= 10)
        throw new ApiException(
            org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
            "Please wait one minute before retrying.");
      queue.addLast(now);
    }
  }

  @PostMapping("/register")
  public LocalAccounts.Session register(HttpServletRequest request, @RequestBody Credentials c) {
    limit(request);
    return accounts.register(c.username(), c.password());
  }

  @PostMapping("/login")
  public LocalAccounts.Session login(HttpServletRequest request, @RequestBody Credentials c) {
    limit(request);
    return accounts.login(c.username(), c.password());
  }

  @PostMapping("/recover")
  public LocalAccounts.Session recover(HttpServletRequest request, @RequestBody Recovery c) {
    limit(request);
    return accounts.recover(c.username(), c.recoveryCode(), c.newPassword());
  }

  @PostMapping("/password")
  public LocalAccounts.Session change(
      Principal p, HttpServletRequest request, @RequestBody PasswordChange c) {
    limit(request);
    return accounts.changePassword(p.getName(), c.currentPassword(), c.newPassword());
  }

  @PostMapping("/reauthenticate")
  public LocalAccounts.Session reauthenticate(
      Principal p, HttpServletRequest request, @RequestBody Credentials c) {
    limit(request);
    return accounts.reauthenticate(p.getName(), c.password());
  }

  @PostMapping("/logout")
  public Map<String, Object> logout(@RequestHeader("Authorization") String auth) {
    accounts.logout(auth.substring(7));
    return Map.of("signedOut", true);
  }
}

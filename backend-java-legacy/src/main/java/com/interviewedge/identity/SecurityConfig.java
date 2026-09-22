package com.interviewedge.identity;

import com.interviewedge.common.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
  @Bean
  SecurityFilterChain security(HttpSecurity http, IdentityVerifier verifier, Profiles profiles)
      throws Exception {
    var filter =
        new OncePerRequestFilter() {
          final Map<String, Window> limits = new ConcurrentHashMap<>();

          protected void doFilterInternal(
              HttpServletRequest req, HttpServletResponse res, FilterChain chain)
              throws ServletException, IOException {
            if (!req.getRequestURI().startsWith("/api/")
                || List.of("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/recover")
                    .contains(req.getRequestURI())) {
              chain.doFilter(req, res);
              return;
            }
            try {
              String bearer = req.getHeader("Authorization");
              if (bearer == null || !bearer.startsWith("Bearer "))
                throw new ApiException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "Sign in to continue.");
              var identity = verifier.verify(bearer.substring(7));
              long minute = System.currentTimeMillis() / 60000;
              if (limits.size() > 10000)
                limits.entrySet().removeIf(e -> e.getValue().minute < minute);
              Window w =
                  limits.compute(
                      identity.uid(),
                      (k, v) -> v == null || v.minute != minute ? new Window(minute) : v);
              if (w.count.incrementAndGet() > 180)
                throw new ApiException(
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                    "Please wait before retrying.");
              profiles.ensure(identity.uid());
              var auth =
                  new UsernamePasswordAuthenticationToken(
                      identity.uid(),
                      identity,
                      List.of(
                          new SimpleGrantedAuthority(
                              identity.admin() ? "ROLE_ADMIN" : "ROLE_STUDENT")));
              SecurityContextHolder.getContext().setAuthentication(auth);
              chain.doFilter(req, res);
            } catch (ApiException e) {
              res.setStatus(e.status.value());
              res.setContentType("application/json");
              res.getWriter()
                  .write(Json.write(Map.of("code", e.status.name(), "message", e.getMessage())));
            } finally {
              SecurityContextHolder.clearContext();
            }
          }
        };
    return http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .headers(
            h ->
                h.contentSecurityPolicy(
                    c ->
                        c.policyDirectives(
                            "default-src 'self'; script-src 'self'; style-src 'self'; object-src"
                                + " 'none'; base-uri 'self'; frame-ancestors 'none'")))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/health",
                        "/admin",
                        "/admin/**",
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/api/v1/auth/recover")
                    .permitAll()
                    .requestMatchers("/api/v1/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  static class Window {
    final long minute;
    final AtomicInteger count = new AtomicInteger();

    Window(long minute) {
      this.minute = minute;
    }
  }
}

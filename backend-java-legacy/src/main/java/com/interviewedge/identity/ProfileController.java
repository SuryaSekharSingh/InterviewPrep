package com.interviewedge.identity;

import java.security.Principal;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me")
public class ProfileController {
  private final Profiles profiles;

  public ProfileController(Profiles profiles) {
    this.profiles = profiles;
  }

  @GetMapping
  public Map<String, Object> get(Principal p) {
    return profiles.get(p.getName());
  }

  @PatchMapping
  public Map<String, Object> update(Principal p, @RequestBody Map<String, Object> body) {
    return profiles.update(p.getName(), body);
  }
}

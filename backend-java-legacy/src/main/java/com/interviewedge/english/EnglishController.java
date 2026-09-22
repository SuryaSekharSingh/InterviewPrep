package com.interviewedge.english;

import java.security.Principal;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/english/attempts")
public class EnglishController {
  private final EnglishService service;

  public EnglishController(EnglishService service) {
    this.service = service;
  }

  @PostMapping
  public Map<String, Object> create(
      Principal p,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, String> body) {
    return service.create(p.getName(), key, body.get("previousId"));
  }

  @PostMapping("/{id}/submit")
  public Map<String, Object> submit(
      Principal p, @PathVariable String id, @RequestBody EnglishService.Submission body) {
    return service.submit(p.getName(), id, body);
  }

  @GetMapping("/{id}/report")
  public Map<String, Object> report(Principal p, @PathVariable String id) {
    return service.report(p.getName(), id);
  }
}

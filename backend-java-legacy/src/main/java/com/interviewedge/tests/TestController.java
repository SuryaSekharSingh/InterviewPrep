package com.interviewedge.tests;

import java.security.Principal;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tests/attempts")
public class TestController {
  private final TestService service;

  public TestController(TestService service) {
    this.service = service;
  }

  @PostMapping
  public Map<String, Object> create(
      Principal p, @RequestHeader("Idempotency-Key") String key, @RequestBody TestService.Setup s) {
    return service.create(p.getName(), key, s);
  }

  @GetMapping("/{id}")
  public Map<String, Object> get(Principal p, @PathVariable String id) {
    return service.view(p.getName(), id);
  }

  @PutMapping("/{id}/responses/{itemId}")
  public Map<String, Object> save(
      Principal p,
      @PathVariable String id,
      @PathVariable String itemId,
      @RequestBody TestService.Response r) {
    return service.save(p.getName(), id, itemId, r);
  }

  @PostMapping("/{id}/submit")
  public Map<String, Object> submit(Principal p, @PathVariable String id) {
    return service.submit(p.getName(), id);
  }

  @GetMapping("/{id}/result")
  public Map<String, Object> result(Principal p, @PathVariable String id) {
    return service.result(p.getName(), id);
  }
}

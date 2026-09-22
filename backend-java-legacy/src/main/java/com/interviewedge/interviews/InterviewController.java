package com.interviewedge.interviews;

import java.security.Principal;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/interviews")
public class InterviewController {
  private final InterviewService service;

  public InterviewController(InterviewService service) {
    this.service = service;
  }

  @PostMapping
  public Map<String, Object> create(
      Principal p,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody InterviewService.Setup s) {
    return service.create(p.getName(), key, s);
  }

  @GetMapping("/{id}")
  public Map<String, Object> get(Principal p, @PathVariable String id) {
    return service.view(p.getName(), id);
  }

  @PostMapping("/{id}/answers")
  public Map<String, Object> answer(
      Principal p, @PathVariable String id, @RequestBody InterviewService.Answer a) {
    return service.answer(p.getName(), id, a);
  }

  @PostMapping("/{id}/finish")
  public Map<String, Object> finish(Principal p, @PathVariable String id) {
    return service.finish(p.getName(), id);
  }

  @GetMapping("/{id}/report")
  public Map<String, Object> report(Principal p, @PathVariable String id) {
    return service.result(p.getName(), id);
  }
}

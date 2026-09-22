package com.interviewedge.jobs;

import java.security.Principal;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {
  private final Jobs jobs;

  public JobController(Jobs jobs) {
    this.jobs = jobs;
  }

  @GetMapping
  public Object list(Principal p, @RequestParam String activityId) {
    return jobs.forActivity(p.getName(), activityId);
  }

  @GetMapping("/{id}")
  public Map<String, Object> get(Principal p, @PathVariable String id) {
    return jobs.owned(p.getName(), id);
  }

  @PostMapping("/{id}/retry")
  public Map<String, Object> retry(Principal p, @PathVariable String id) {
    jobs.retry(p.getName(), id);
    return jobs.owned(p.getName(), id);
  }
}

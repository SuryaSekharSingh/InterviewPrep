package com.interviewedge.progress;

import com.interviewedge.assessment.Activities;
import com.interviewedge.identity.Profiles;
import com.interviewedge.recommendations.RuleRecommendations;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ProgressController {
  private final ProgressService progress;
  private final RuleRecommendations recommendations;
  private final Activities activities;
  private final Profiles profiles;

  public ProgressController(
      ProgressService progress,
      RuleRecommendations recommendations,
      Activities activities,
      Profiles profiles) {
    this.progress = progress;
    this.recommendations = recommendations;
    this.activities = activities;
    this.profiles = profiles;
  }

  @GetMapping("/dashboard")
  public Map<String, Object> dashboard(Principal p) {
    Map<String, Object> m = new LinkedHashMap<>(progress.summary(p.getName()));
    m.put("displayName", profiles.get(p.getName()).get("displayName"));
    m.put("recommendations", recommendations.recommend(p.getName()));
    return m;
  }

  @GetMapping("/progress")
  public Map<String, Object> progress(Principal p, @RequestParam(defaultValue = "30") int days) {
    return Map.of(
        "summary", progress.summary(p.getName()), "timeline", progress.timeline(p.getName(), days));
  }

  @GetMapping("/competencies")
  public Object competencies(Principal p) {
    return progress.competencies(p.getName());
  }

  @GetMapping("/activities")
  public Object history(Principal p, @RequestParam(defaultValue = "") String module) {
    return activities.history(p.getName()).stream()
        .filter(a -> module.isBlank() || a.module().equals(module))
        .map(
            a -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("id", a.id());
              m.put("module", a.module());
              m.put("state", a.state());
              m.put("score", a.score());
              m.put("createdAt", a.createdAt());
              m.put("completedAt", a.completedAt());
              return m;
            })
        .toList();
  }

  @GetMapping("/recommendations")
  public Object recommendations(Principal p) {
    return recommendations.recommend(p.getName());
  }

  @PatchMapping("/recommendations/{id}")
  public Object dismiss(Principal p, @PathVariable String id) {
    recommendations.dismiss(p.getName(), id);
    return Map.of("dismissed", true);
  }
}

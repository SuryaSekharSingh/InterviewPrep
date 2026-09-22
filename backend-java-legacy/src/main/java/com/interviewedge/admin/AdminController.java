package com.interviewedge.admin;

import com.interviewedge.assessment.AnswerEvaluator;
import com.interviewedge.content.ContentCatalog;
import com.interviewedge.tests.TestService;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
  private final ContentCatalog content;
  private final TestService tests;
  private final AnswerEvaluator evaluator;

  public AdminController(ContentCatalog content, TestService tests, AnswerEvaluator evaluator) {
    this.content = content;
    this.tests = tests;
    this.evaluator = evaluator;
  }

  @GetMapping("/questions")
  public Object questions() {
    return content.all();
  }

  @PostMapping("/questions")
  public Object draft(Principal p, @RequestBody ContentCatalog.Question q) {
    return content.draft(p.getName(), q);
  }

  @PostMapping("/questions/import")
  public Object importQuestions(Principal p, @RequestBody List<ContentCatalog.Question> questions) {
    return content.importDrafts(p.getName(), questions);
  }

  @PostMapping("/questions/{id}/publish")
  public Object publish(Principal p, @PathVariable String id) {
    return content.publish(p.getName(), id);
  }

  @PostMapping("/questions/{id}/retire")
  public Object retire(Principal p, @PathVariable String id) {
    content.retire(p.getName(), id);
    return Map.of("retired", true);
  }

  @GetMapping("/seeds")
  public Object seeds() {
    return content.seedDrafts();
  }

  @PostMapping("/seeds/{id}/publish")
  public Object publishSeed(Principal p, @PathVariable String id) {
    content.publishSeed(p.getName(), id);
    return Map.of("published", true);
  }

  @GetMapping("/reviews")
  public Object reviews() {
    return tests.pendingReviews();
  }

  public record Review(double points, String reason) {}

  @PostMapping("/reviews/{activityId}/{itemId}")
  public Object review(
      Principal p,
      @PathVariable String activityId,
      @PathVariable String itemId,
      @RequestBody Review r) {
    tests.review(p.getName(), activityId, itemId, r.points(), r.reason(), evaluator);
    return Map.of("reviewed", true);
  }
}

package com.interviewedge.tests;

import com.interviewedge.assessment.AnswerEvaluator;
import com.interviewedge.jobs.JobHandler;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TestJobs implements JobHandler {
  private final TestService tests;
  private final AnswerEvaluator evaluator;

  public TestJobs(TestService tests, AnswerEvaluator evaluator) {
    this.tests = tests;
    this.evaluator = evaluator;
  }

  public String kind() {
    return "GRADE_TEST";
  }

  public void handle(String uid, Map<String, Object> payload) {
    tests.grade(uid, (String) payload.get("activityId"), evaluator);
  }

  @Scheduled(fixedDelay = 5000)
  public void expire() {
    for (var a : tests.expired()) tests.submit((String) a.get("uid"), (String) a.get("id"));
  }
}

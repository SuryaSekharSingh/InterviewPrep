package com.interviewedge.interviews;

import com.interviewedge.assessment.AnswerEvaluator;
import com.interviewedge.jobs.JobHandler;
import java.util.Map;
import org.springframework.context.annotation.*;

@Configuration
public class InterviewJobs {
  @Bean
  JobHandler interviewAnswerJob(
      InterviewService service, AnswerEvaluator evaluator, InterviewGenerator generator) {
    return new JobHandler() {
      public String kind() {
        return "INTERVIEW_ANSWER";
      }

      public void handle(String uid, Map<String, Object> p) {
        service.process(
            uid,
            (String) p.get("activityId"),
            ((Number) p.get("sequence")).intValue(),
            evaluator,
            generator);
      }
    };
  }

  @Bean
  JobHandler interviewReportJob(InterviewService service) {
    return new JobHandler() {
      public String kind() {
        return "INTERVIEW_REPORT";
      }

      public void handle(String uid, Map<String, Object> p) {
        service.report(uid, (String) p.get("activityId"));
      }
    };
  }
}

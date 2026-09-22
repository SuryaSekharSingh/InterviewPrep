package com.interviewedge.interviews;

import com.interviewedge.assessment.Activities;
import com.interviewedge.common.UserDataSource;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class InterviewDataSource implements UserDataSource {
  private final JdbcTemplate db;
  private final Activities activities;

  public InterviewDataSource(JdbcTemplate db, Activities activities) {
    this.db = db;
    this.activities = activities;
  }

  public String name() {
    return "interview-answers";
  }

  public Object export(String uid) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (var activity : activities.export(uid))
      if (activity.module().equals("INTERVIEW"))
        result.add(
            Map.of(
                "activityId",
                activity.id(),
                "turns",
                db.queryForList(
                    "SELECT sequence,topic,kind,prompt,answer,media_id,evaluation FROM"
                        + " interview_turn WHERE activity_id=? ORDER BY sequence",
                    activity.id())));
    return result;
  }
}

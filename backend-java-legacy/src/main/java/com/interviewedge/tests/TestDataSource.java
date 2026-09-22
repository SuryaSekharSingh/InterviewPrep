package com.interviewedge.tests;

import com.interviewedge.assessment.Activities;
import com.interviewedge.common.UserDataSource;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TestDataSource implements UserDataSource {
  private final JdbcTemplate db;
  private final Activities activities;

  public TestDataSource(JdbcTemplate db, Activities activities) {
    this.db = db;
    this.activities = activities;
  }

  public String name() {
    return "test-responses";
  }

  public Object export(String uid) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (var activity : activities.export(uid))
      if (activity.module().equals("TEST"))
        result.add(
            Map.of(
                "activityId",
                activity.id(),
                "responses",
                db.queryForList(
                    "SELECT"
                        + " id,question_id,position,response,marked,version,points,grading_status,feedback"
                        + " FROM test_item WHERE activity_id=? ORDER BY position",
                    activity.id())));
    return result;
  }
}

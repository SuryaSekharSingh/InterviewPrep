package com.interviewedge.english;

import com.interviewedge.assessment.Activities;
import com.interviewedge.common.UserDataSource;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class EnglishDataSource implements UserDataSource {
  private final JdbcTemplate db;
  private final Activities activities;

  public EnglishDataSource(JdbcTemplate db, Activities activities) {
    this.db = db;
    this.activities = activities;
  }

  public String name() {
    return "english-transcripts";
  }

  public Object export(String uid) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (var activity : activities.export(uid))
      if (activity.module().equals("ENGLISH"))
        result.addAll(
            db.queryForList(
                "SELECT"
                    + " activity_id,group_id,previous_id,prompt_version,media_id,raw_transcript,confirmed_transcript,edited"
                    + " FROM english_attempt WHERE activity_id=?",
                activity.id()));
    return result;
  }
}

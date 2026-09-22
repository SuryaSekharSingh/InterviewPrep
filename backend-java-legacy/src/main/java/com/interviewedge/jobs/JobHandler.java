package com.interviewedge.jobs;

import java.util.Map;

public interface JobHandler {
  String kind();

  void handle(String userId, Map<String, Object> payload);
}

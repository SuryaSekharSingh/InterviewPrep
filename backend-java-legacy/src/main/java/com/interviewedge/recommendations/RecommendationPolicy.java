package com.interviewedge.recommendations;

import java.util.*;

public interface RecommendationPolicy {
  List<Map<String, Object>> recommend(String uid);
}

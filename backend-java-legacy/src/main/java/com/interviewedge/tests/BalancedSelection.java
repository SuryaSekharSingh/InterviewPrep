package com.interviewedge.tests;

import com.interviewedge.common.ApiException;
import com.interviewedge.content.ContentCatalog.Question;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class BalancedSelection implements QuestionSelectionPolicy {
  public List<Question> select(List<Question> candidates, int count) {
    if (candidates.size() < count)
      throw ApiException.bad(
          "Not enough reviewed questions for these settings. Choose fewer questions or another"
              + " topic.");
    Map<String, Deque<Question>> groups = new TreeMap<>();
    var shuffled = new ArrayList<>(candidates);
    Collections.shuffle(shuffled);
    shuffled.forEach(q -> groups.computeIfAbsent(q.topicId(), k -> new ArrayDeque<>()).add(q));
    List<Question> selected = new ArrayList<>();
    while (selected.size() < count)
      for (var group : groups.values()) {
        if (!group.isEmpty()) selected.add(group.remove());
        if (selected.size() == count) break;
      }
    return selected;
  }
}

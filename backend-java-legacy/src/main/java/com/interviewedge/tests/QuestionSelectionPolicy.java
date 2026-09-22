package com.interviewedge.tests;

import com.interviewedge.content.ContentCatalog.Question;
import java.util.List;

public interface QuestionSelectionPolicy {
  List<Question> select(List<Question> candidates, int count);
}

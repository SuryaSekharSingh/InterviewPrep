package com.interviewedge.progress;

import com.interviewedge.assessment.AssessmentEvents;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ProgressProjector {
  private final ProgressService progress;
  private final AssessmentEvents events;

  public ProgressProjector(ProgressService progress, AssessmentEvents events) {
    this.progress = progress;
    this.events = events;
  }

  @Scheduled(fixedDelay = 2000)
  public void consume() {
    for (var event : events.pending()) progress.project(event);
  }
}

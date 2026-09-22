package com.interviewedge.media;

import com.interviewedge.jobs.JobHandler;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TranscriptionJob implements JobHandler {
  private final MediaService media;
  private final SpeechTranscriber transcriber;

  public TranscriptionJob(MediaService media, SpeechTranscriber transcriber) {
    this.media = media;
    this.transcriber = transcriber;
  }

  public String kind() {
    return "TRANSCRIBE";
  }

  public void handle(String uid, Map<String, Object> p) {
    media.transcribe(uid, (String) p.get("mediaId"), transcriber);
  }
}

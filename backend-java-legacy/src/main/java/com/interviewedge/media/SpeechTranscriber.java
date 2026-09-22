package com.interviewedge.media;

import java.nio.file.Path;

public interface SpeechTranscriber {
  String transcribe(Path audio);
}

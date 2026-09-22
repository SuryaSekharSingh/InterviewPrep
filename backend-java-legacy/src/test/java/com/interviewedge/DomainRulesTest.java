package com.interviewedge;

import static org.junit.jupiter.api.Assertions.*;

import com.interviewedge.assessment.Scoring;
import com.interviewedge.english.EnglishService;
import com.interviewedge.media.WavValidator;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DomainRulesTest {
  @Test
  void overallRequiresAllModules() {
    assertNull(Scoring.overall(Map.of("TEST", 100.0)));
    assertEquals(72.0, Scoring.overall(Map.of("INTERVIEW", 75.0, "TEST", 68.0, "ENGLISH", 74.0)));
  }

  @Test
  void rubricRejectsInvalidDimensions() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Scoring.score("ENGLISH", Map.of("grammar", 5.0, "clarity", 4.0, "relevance", 4.0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> Scoring.score("TECHNICAL", Map.of("correctness", 4.0)));
  }

  @Test
  void englishMetricsCountWordsAndBoundedFillers() {
    assertEquals(8, EnglishService.wordCount("Um, I built an app for my team."));
    assertEquals(2, EnglishService.fillerCount("Um, this is useful, you know."));
    assertEquals(0, EnglishService.fillerCount("The number is known."));
  }

  @Test
  void rejectsSilenceAndAcceptsValidPcm() {
    byte[] wav = wave();
    assertThrows(RuntimeException.class, () -> WavValidator.validate(wav));
    ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 44; i < wav.length; i += 2) b.putShort(i, (short) 1000);
    assertEquals(1.0, WavValidator.validate(wav));
    b.putInt(24, 48000);
    assertThrows(RuntimeException.class, () -> WavValidator.validate(wav));
  }

  byte[] wave() {
    ByteBuffer b = ByteBuffer.allocate(32044).order(ByteOrder.LITTLE_ENDIAN);
    b.put("RIFF".getBytes(StandardCharsets.US_ASCII))
        .putInt(32036)
        .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII))
        .putInt(16)
        .putShort((short) 1)
        .putShort((short) 1)
        .putInt(16000)
        .putInt(32000)
        .putShort((short) 2)
        .putShort((short) 16)
        .put("data".getBytes(StandardCharsets.US_ASCII))
        .putInt(32000);
    return b.array();
  }
}

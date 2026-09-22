package com.interviewedge;

import static org.junit.jupiter.api.Assertions.*;

import com.interviewedge.assessment.AnswerEvaluator;
import com.interviewedge.assessment.Scoring;
import com.interviewedge.common.Json;
import com.interviewedge.infrastructure.ai.OllamaAdapter;
import com.interviewedge.infrastructure.media.WhisperAdapter;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in provider checks using synthetic text and the provider's public audio sample. */
@EnabledIfEnvironmentVariable(named = "EDGE_LIVE_AI", matches = "true")
class LocalAiSmokeTest {
  private final OllamaAdapter text = new OllamaAdapter("http://127.0.0.1:11434", "qwen3:4b");
  private final String question = "Compare indexed access in Java ArrayList and LinkedList.";
  private final String reference =
      "ArrayList provides constant-time indexed access. LinkedList traverses nodes and has"
          + " linear-time indexed access in the worst case.";
  private final String answer =
      "ArrayList uses an array, so get(index) is O(1). LinkedList traverses nodes to reach an"
          + " index, so indexed access is O(n) in the worst case.";

  @Test
  void realModelProducesAValidatedEvaluation() throws Exception {
    long start = System.nanoTime();
    var result =
        text.evaluate(
            new AnswerEvaluator.Request(
                "TECHNICAL",
                question,
                reference,
                List.of("Explain representation and indexed access complexity"),
                answer));
    double elapsed = (System.nanoTime() - start) / 1_000_000_000.0;
    assertTrue(result.scorable());
    assertFalse(result.evidence().isEmpty());
    assertTrue(result.evidence().stream().allMatch(answer::contains));
    assertTrue(Double.isFinite(Scoring.score("TECHNICAL", result.dimensions())));
    save(
        "text-evaluation",
        Map.of(
            "seconds",
            elapsed,
            "model",
            result.model(),
            "dimensions",
            result.dimensions(),
            "scorable",
            result.scorable()));
  }

  @Test
  void realModelProducesAFollowUp() throws Exception {
    long start = System.nanoTime();
    var result = text.followUp("TECHNICAL", "collections", question, answer, reference, 1);
    assertTrue(result.question().length() >= 10 && result.question().length() <= 1500);
    assertNotEquals(
        question.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", ""),
        result.question().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", ""));
    save(
        "text-followup",
        Map.of(
            "seconds",
            (System.nanoTime() - start) / 1_000_000_000.0,
            "question",
            result.question()));
  }

  @Test
  void realSpeechAdapterTranscribesThePublicSample() throws Exception {
    var speech =
        new WhisperAdapter(System.getenv("WHISPER_EXECUTABLE"), System.getenv("WHISPER_MODEL"));
    long start = System.nanoTime();
    String result = speech.transcribe(Path.of(System.getenv("EDGE_SPEECH_SAMPLE")));
    assertTrue(result.toLowerCase(Locale.ROOT).contains("country"));
    assertTrue(result.split("\\s+").length >= 15);
    save(
        "speech-adapter",
        Map.of(
            "seconds",
            (System.nanoTime() - start) / 1_000_000_000.0,
            "model",
            "base.en",
            "sample",
            "public JFK sample",
            "wordCount",
            result.split("\\s+").length));
  }

  private void save(String name, Map<String, Object> result) throws Exception {
    Path output = Path.of("target", "live-ai-results");
    Files.createDirectories(output);
    Files.writeString(output.resolve(name + ".json"), Json.write(result));
  }
}

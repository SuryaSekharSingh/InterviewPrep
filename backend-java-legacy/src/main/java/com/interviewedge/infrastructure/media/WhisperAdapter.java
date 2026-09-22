package com.interviewedge.infrastructure.media;

import com.interviewedge.media.SpeechTranscriber;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WhisperAdapter implements SpeechTranscriber {
  private final String executable, model;

  public WhisperAdapter(
      @Value("${edge.whisper-executable}") String executable,
      @Value("${edge.whisper-model}") String model) {
    this.executable = executable;
    this.model = model;
  }

  public String transcribe(Path audio) {
    Path temp = null;
    Process p = null;
    try {
      temp = Files.createTempDirectory("interviewedge-transcription-");
      Path output = temp.resolve("transcript");
      p =
          new ProcessBuilder(
                  executable,
                  "-m",
                  Path.of(model).toAbsolutePath().toString(),
                  "-f",
                  audio.toString(),
                  "-l",
                  "en",
                  "-otxt",
                  "-of",
                  output.toString(),
                  "-nt")
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      if (!p.waitFor(180, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new IllegalStateException("Transcription timed out");
      }
      Path text = Path.of(output + ".txt");
      if (p.exitValue() != 0 || !Files.exists(text) || Files.size(text) > 40000)
        throw new IllegalStateException("Transcription failed");
      String result = Files.readString(text).trim();
      if (result.isBlank() || result.matches("(?i).*\\[(blank_audio|silence|music)\\].*"))
        throw new IllegalStateException("No clear speech detected");
      return result;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Transcription interrupted", e);
    } catch (Exception e) {
      throw new IllegalStateException("Local transcription unavailable", e);
    } finally {
      if (p != null && p.isAlive()) p.destroyForcibly();
      if (temp != null)
        try (var entries = Files.list(temp)) {
          for (Path file : entries.toList()) Files.deleteIfExists(file);
        } catch (Exception ignored) {
        }
      if (temp != null)
        try {
          Files.deleteIfExists(temp);
        } catch (Exception ignored) {
        }
    }
  }
}

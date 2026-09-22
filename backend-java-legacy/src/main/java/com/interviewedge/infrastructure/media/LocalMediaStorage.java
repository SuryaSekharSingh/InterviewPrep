package com.interviewedge.infrastructure.media;

import com.interviewedge.media.MediaStorage;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalMediaStorage implements MediaStorage {
  private final Path root;

  public LocalMediaStorage(@Value("${edge.media-root}") String root) {
    this.root = Path.of(root).toAbsolutePath().normalize();
    try {
      Files.createDirectories(this.root);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot create media directory", e);
    }
  }

  public Path path(String key) {
    if (!key.matches("[a-f0-9-]{36}\\.wav"))
      throw new IllegalArgumentException("Invalid storage key");
    Path p = root.resolve(key).normalize();
    if (!p.startsWith(root)) throw new IllegalArgumentException("Invalid path");
    return p;
  }

  public void write(String key, byte[] bytes) {
    try {
      Files.write(path(key), bytes, StandardOpenOption.CREATE_NEW);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot store recording", e);
    }
  }

  public void delete(String key) {
    try {
      Files.deleteIfExists(path(key));
    } catch (Exception e) {
      throw new IllegalStateException("Cannot delete recording", e);
    }
  }
}

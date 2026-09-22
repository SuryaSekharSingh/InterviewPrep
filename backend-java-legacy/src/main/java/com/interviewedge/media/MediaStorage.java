package com.interviewedge.media;

import java.nio.file.Path;

public interface MediaStorage {
  void write(String key, byte[] bytes);

  Path path(String key);

  void delete(String key);
}

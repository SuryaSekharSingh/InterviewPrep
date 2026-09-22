package com.interviewedge.data;

import androidx.annotation.NonNull;
import androidx.room.*;

@Entity(primaryKeys = {"userId", "path"})
public class CacheEntry {
  @NonNull public String userId = "";
  @NonNull public String path = "";
  public String json = "";
  public long savedAt;

  public CacheEntry() {}

  @Ignore
  public CacheEntry(String userId, String path, String json) {
    this.userId = userId;
    this.path = path;
    this.json = json;
    this.savedAt = System.currentTimeMillis();
  }
}

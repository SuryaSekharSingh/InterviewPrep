package com.interviewedge.data;

import androidx.room.*;

@Database(
    entities = {CacheEntry.class},
    version = 1,
    exportSchema = false)
public abstract class EdgeDatabase extends RoomDatabase {
  public abstract CacheDao cache();
}

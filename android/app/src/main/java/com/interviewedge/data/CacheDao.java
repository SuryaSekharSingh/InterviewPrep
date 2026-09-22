package com.interviewedge.data;

import androidx.room.*;

@Dao
public interface CacheDao {
  @Query("SELECT * FROM CacheEntry WHERE userId=:uid AND path=:path LIMIT 1")
  CacheEntry get(String uid, String path);

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  void put(CacheEntry entry);

  @Query("DELETE FROM CacheEntry WHERE userId=:uid")
  void clear(String uid);
}

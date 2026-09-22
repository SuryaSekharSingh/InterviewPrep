package com.interviewedge.media;

import com.interviewedge.common.UserDataSource;
import org.springframework.stereotype.Component;

@Component
public class MediaDataSource implements UserDataSource {
  private final MediaService media;

  public MediaDataSource(MediaService media) {
    this.media = media;
  }

  public String name() {
    return "recordings";
  }

  public Object export(String uid) {
    return media.ids(uid).stream().map(id -> media.owned(uid, id)).toList();
  }
}

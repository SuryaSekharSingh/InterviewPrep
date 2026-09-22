package com.interviewedge.common;

public interface UserDataSource {
  String name();

  Object export(String userId);
}

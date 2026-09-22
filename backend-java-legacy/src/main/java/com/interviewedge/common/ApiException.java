package com.interviewedge.common;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
  public final HttpStatus status;

  public ApiException(HttpStatus status, String message) {
    super(message);
    this.status = status;
  }

  public static ApiException bad(String message) {
    return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  public static ApiException conflict(String message) {
    return new ApiException(HttpStatus.CONFLICT, message);
  }

  public static ApiException missing() {
    return new ApiException(HttpStatus.NOT_FOUND, "Record not found");
  }
}

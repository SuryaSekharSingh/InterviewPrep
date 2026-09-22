package com.interviewedge.common;

import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class Errors {
  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> domain(ApiException e) {
    return ResponseEntity.status(e.status)
        .body(Map.of("code", e.status.name(), "message", e.getMessage()));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    org.springframework.web.bind.MethodArgumentNotValidException.class
  })
  ResponseEntity<?> invalid(Exception e) {
    return ResponseEntity.unprocessableEntity()
        .body(Map.of("code", "INVALID_INPUT", "message", "Check the submitted fields."));
  }

  @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
  ResponseEntity<?> duplicate(org.springframework.dao.DuplicateKeyException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            Map.of("code", "CONFLICT", "message", "This entry already exists. Refresh and retry."));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception e) {
    String requestId = UUID.randomUUID().toString();
    org.slf4j.LoggerFactory.getLogger(Errors.class)
        .error("Request {} failed: {}", requestId, e.getClass().getSimpleName());
    return ResponseEntity.internalServerError()
        .body(
            Map.of(
                "code",
                "INTERNAL_ERROR",
                "message",
                "The operation could not finish. Retry safely.",
                "requestId",
                requestId));
  }
}

package com.interviewedge.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

public final class Json {
  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

  private Json() {}

  public static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot serialize data", e);
    }
  }

  public static Map<String, Object> read(String value) {
    try {
      return MAPPER.readValue(value, new TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON document", e);
    }
  }

  public static <T> T read(String value, Class<T> type) {
    try {
      return MAPPER.readValue(value, type);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid document", e);
    }
  }

  public static String text(Map<String, Object> data, String key, String fallback) {
    Object v = data.get(key);
    return v instanceof String s ? s : fallback;
  }

  public static int integer(Map<String, Object> data, String key, int fallback) {
    Object v = data.get(key);
    return v instanceof Number n ? n.intValue() : fallback;
  }
}

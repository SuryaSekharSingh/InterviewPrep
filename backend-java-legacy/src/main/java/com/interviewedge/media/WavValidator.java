package com.interviewedge.media;

import com.interviewedge.common.ApiException;
import java.nio.*;
import java.nio.charset.StandardCharsets;

public final class WavValidator {
  private WavValidator() {}

  public static double validate(byte[] bytes) {
    if (bytes.length < 32044 || bytes.length > 6_000_044)
      throw ApiException.bad("Record between 1 and 180 seconds.");
    ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    if (!ascii(bytes, 0).equals("RIFF")
        || !ascii(bytes, 8).equals("WAVE")
        || !ascii(bytes, 12).equals("fmt ")
        || b.getInt(16) != 16
        || b.getShort(20) != 1
        || b.getShort(22) != 1
        || b.getInt(24) != 16000
        || b.getInt(28) != 32000
        || b.getShort(32) != 2
        || b.getShort(34) != 16
        || !ascii(bytes, 36).equals("data")
        || b.getInt(40) != bytes.length - 44
        || b.getInt(4) != bytes.length - 8
        || (bytes.length - 44) % 2 != 0)
      throw ApiException.bad("Upload a mono 16 kHz, 16-bit PCM WAV recording.");
    double seconds = (bytes.length - 44) / 32000.0;
    if (seconds > 180) throw ApiException.bad("Recording exceeds three minutes.");
    long magnitude = 0;
    for (int i = 44; i < bytes.length; i += 2) magnitude += Math.abs((int) b.getShort(i));
    if (magnitude / ((bytes.length - 44) / 2.0) < 10)
      throw ApiException.bad("No audible speech detected. Move closer and record again.");
    return seconds;
  }

  private static String ascii(byte[] b, int p) {
    return new String(b, p, 4, StandardCharsets.US_ASCII);
  }
}

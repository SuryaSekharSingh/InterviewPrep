package com.interviewedge.media;

import android.media.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class PcmRecorder {
  private volatile boolean recording;
  private volatile String failure;
  private AudioRecord audio;
  private Future<?> future;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  @SuppressWarnings("MissingPermission")
  public void start(File file, int maxSeconds) throws IOException {
    if (recording) throw new IOException("Already recording");
    failure = null;
    int buffer =
        Math.max(
            4096,
            AudioRecord.getMinBufferSize(
                16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT));
    audio =
        new AudioRecord(
            MediaRecorder.AudioSource.MIC,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            buffer);
    if (audio.getState() != AudioRecord.STATE_INITIALIZED) {
      audio.release();
      throw new IOException("Microphone unavailable.");
    }
    recording = true;
    audio.startRecording();
    future =
        executor.submit(
            () -> {
              int bytes = 0;
              byte[] chunk = new byte[buffer];
              try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
                out.setLength(0);
                out.write(new byte[44]);
                while (recording && bytes < maxSeconds * 32000) {
                  int read =
                      audio.read(chunk, 0, Math.min(chunk.length, maxSeconds * 32000 - bytes));
                  if (read < 0) {
                    if (recording) throw new IOException("Microphone interrupted.");
                    break;
                  }
                  out.write(chunk, 0, read);
                  bytes += read;
                }
                out.seek(0);
                out.write(header(bytes));
              } catch (Exception e) {
                failure = "Recording interrupted. Please record again.";
              } finally {
                recording = false;
                try {
                  audio.stop();
                } catch (Exception ignored) {
                }
                audio.release();
              }
            });
  }

  public void stop() throws Exception {
    recording = false;
    if (future != null) future.get(5, TimeUnit.SECONDS);
    if (failure != null) throw new IOException(failure);
  }

  public boolean recording() {
    return recording;
  }

  public void release() {
    recording = false;
    executor.shutdown();
  }

  private byte[] header(int size) {
    ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
    b.put("RIFF".getBytes(StandardCharsets.US_ASCII))
        .putInt(size + 36)
        .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII))
        .putInt(16)
        .putShort((short) 1)
        .putShort((short) 1)
        .putInt(16000)
        .putInt(32000)
        .putShort((short) 2)
        .putShort((short) 16)
        .put("data".getBytes(StandardCharsets.US_ASCII))
        .putInt(size);
    return b.array();
  }
}

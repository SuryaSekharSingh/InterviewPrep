package com.interviewedge.data;

import android.content.Context;
import androidx.room.Room;
import com.google.gson.*;
import com.interviewedge.BuildConfig;
import com.interviewedge.identity.AuthGateway;
import com.interviewedge.identity.AuthGateway.SessionSnapshot;
import java.io.*;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class Repository {
  public record Data(JsonElement value, boolean cached) {}

  private final EdgeApi api;
  private final AuthGateway auth;
  private final EdgeDatabase db;
  private final Object cacheLock = new Object();
  private final java.util.concurrent.ExecutorService draftWriter =
      java.util.concurrent.Executors.newSingleThreadExecutor();

  public Repository(Context context, AuthGateway auth) {
    this.auth = auth;
    db =
        Room.databaseBuilder(
                context.getApplicationContext(), EdgeDatabase.class, "interviewedge.db")
            .build();
    OkHttpClient client =
        new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    api =
        new Retrofit.Builder()
            .baseUrl(BuildConfig.API_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(EdgeApi.class);
  }

  private SessionSnapshot session(String owner) throws SessionChanged {
    SessionSnapshot current;
    try {
      current = auth.snapshot();
    } catch (IllegalStateException error) {
      throw new SessionChanged();
    }
    if (!Objects.equals(owner, current.userId())) throw new SessionChanged();
    return current;
  }

  private void requireCurrent(SessionSnapshot expected) throws SessionChanged {
    if (!auth.isCurrent(expected)) throw new SessionChanged();
  }

  public Data get(String owner, String path) throws Exception {
    SessionSnapshot expected = session(owner);
    try {
      JsonElement value = execute(api.get("Bearer " + expected.token(), path), expected);
      synchronized (cacheLock) {
        requireCurrent(expected);
        db.cache().put(new CacheEntry(owner, path, value.toString()));
      }
      return new Data(value, false);
    } catch (IOException error) {
      synchronized (cacheLock) {
        requireCurrent(expected);
        CacheEntry cached = db.cache().get(owner, path);
        if (cached != null) return new Data(JsonParser.parseString(cached.json), true);
      }
      throw error;
    }
  }

  public JsonElement write(String owner, String method, String path, JsonElement body, String key)
      throws Exception {
    SessionSnapshot expected = session(owner);
    String bearer = "Bearer " + expected.token();
    return execute(
        switch (method) {
          case "PATCH" -> api.patch(bearer, path, body);
          case "PUT" -> api.put(bearer, path, body);
          case "DELETE" -> api.delete(bearer, path);
          default -> api.post(bearer, path, key, body);
        },
        expected);
  }

  public JsonElement upload(String owner, File file) throws Exception {
    SessionSnapshot expected = session(owner);
    return execute(
        api.upload(
            "Bearer " + expected.token(),
            MultipartBody.Part.createFormData(
                "file", "recording.wav", RequestBody.create(file, MediaType.get("audio/wav")))),
        expected);
  }

  private JsonElement execute(retrofit2.Call<JsonElement> call, SessionSnapshot expected)
      throws Exception {
    requireCurrent(expected);
    var response = call.execute();
    try (ResponseBody errorBody = response.errorBody()) {
      requireCurrent(expected);
      if (!response.isSuccessful()) {
        String message = "Request failed (" + response.code() + ").";
        if (errorBody != null) {
          try {
            var error = JsonParser.parseString(errorBody.string()).getAsJsonObject();
            if (error.has("message")) message = error.get("message").getAsString();
          } catch (Exception ignored) {
          }
        }
        throw new ApiFailure(message, response.code(), expected);
      }
      return response.body() == null ? new JsonObject() : response.body();
    }
  }

  public void export(String owner, File file) throws Exception {
    SessionSnapshot expected = session(owner);
    var response = api.export("Bearer " + expected.token()).execute();
    try (ResponseBody body = response.body();
        ResponseBody error = response.errorBody()) {
      requireCurrent(expected);
      if (!response.isSuccessful() || body == null)
        throw new ApiFailure("Sign in again before exporting.", response.code(), expected);
      try (var input = body.byteStream();
          var output = new FileOutputStream(file)) {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
          requireCurrent(expected);
          output.write(buffer, 0, count);
        }
      }
    } catch (Exception error) {
      file.delete();
      throw error;
    }
  }

  public void saveDraft(String owner, String key, String value) {
    synchronized (cacheLock) {
      if (owner != null && owner.equals(auth.uid()))
        db.cache().put(new CacheEntry(owner, "draft:" + key, value));
    }
  }

  public void queueDraft(String owner, String key, String value) {
    draftWriter.execute(() -> saveDraft(owner, key, value));
  }

  public String draft(String owner, String key) {
    synchronized (cacheLock) {
      if (owner == null || !owner.equals(auth.uid())) return "";
      var value = db.cache().get(owner, "draft:" + key);
      return value == null ? "" : value.json;
    }
  }

  public void clear(String owner) {
    synchronized (cacheLock) {
      db.cache().clear(owner);
    }
  }

  public static class SessionChanged extends Exception {
    public SessionChanged() {
      super("Your session changed. Open the screen again.");
    }
  }

  public static class ApiFailure extends Exception {
    public final int status;
    public final SessionSnapshot session;

    ApiFailure(String message, int status, SessionSnapshot session) {
      super(message);
      this.status = status;
      this.session = session;
    }
  }
}

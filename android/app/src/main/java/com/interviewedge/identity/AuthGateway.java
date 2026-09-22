package com.interviewedge.identity;

import android.content.*;
import android.os.*;
import android.security.keystore.*;
import android.util.Base64;
import com.google.gson.*;
import com.interviewedge.BuildConfig;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import okhttp3.*;

public class AuthGateway {
  public interface Result {
    void done(String error);
  }

  private final SharedPreferences preferences;
  private final OkHttpClient client = new OkHttpClient();
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private volatile JsonObject session;
  private List<String> recoveryCodes = List.of();
  private long epoch;

  public record SessionSnapshot(String userId, String token) {}

  public synchronized SessionSnapshot snapshot() {
    if (session == null) throw new IllegalStateException("Sign in to continue.");
    return new SessionSnapshot(
        session.get("userId").getAsString(), session.get("token").getAsString());
  }

  public synchronized boolean isCurrent(SessionSnapshot expected) {
    return session != null
        && expected.userId().equals(session.get("userId").getAsString())
        && expected.token().equals(session.get("token").getAsString());
  }

  public AuthGateway(Context context) {
    preferences = context.getSharedPreferences("session", Context.MODE_PRIVATE);
    String sealed = preferences.getString("sealed", null);
    if (sealed != null)
      try {
        session = JsonParser.parseString(unseal(sealed)).getAsJsonObject();
      } catch (Exception e) {
        preferences.edit().clear().apply();
      }
  }

  public synchronized String uid() {
    return session == null ? null : session.get("userId").getAsString();
  }

  public boolean configured() {
    return true;
  }

  public boolean verified() {
    return uid() != null;
  }

  public synchronized String token() {
    if (session == null) throw new IllegalStateException("Sign in to continue.");
    return session.get("token").getAsString();
  }

  public synchronized List<String> recoveryCodes() {
    return List.copyOf(recoveryCodes);
  }

  public synchronized void acknowledgeRecoveryCodes() {
    recoveryCodes = List.of();
  }

  private SecretKey key() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (!store.containsAlias("interviewedge-session")) {
      KeyGenerator generator =
          KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      generator.init(
          new KeyGenParameterSpec.Builder(
                  "interviewedge-session",
                  KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      generator.generateKey();
    }
    return (SecretKey) store.getKey("interviewedge-session", null);
  }

  private String seal(String plain) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
        + ":"
        + Base64.encodeToString(
            cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
  }

  private String unseal(String value) throws Exception {
    String[] parts = value.split(":", 2);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        key(),
        new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
    return new String(
        cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
  }

  private JsonObject body(String... pairs) {
    JsonObject b = new JsonObject();
    for (int i = 0; i < pairs.length; i += 2) b.addProperty(pairs[i], pairs[i + 1]);
    return b;
  }

  private synchronized void request(
      String path, JsonObject body, boolean authenticated, Result callback) {
    if (authenticated && session == null) {
      callback.done("Sign in to continue.");
      return;
    }
    String prior = authenticated ? token() : null;
    long expectedEpoch = epoch;
    executor.execute(
        () -> {
          String error = null;
          try {
            Request.Builder request =
                new Request.Builder()
                    .url(BuildConfig.API_URL + "auth/" + path)
                    .post(RequestBody.create(body.toString(), MediaType.get("application/json")));
            if (prior != null) request.header("Authorization", "Bearer " + prior);
            try (Response response = client.newCall(request.build()).execute()) {
              if (response.body() == null)
                throw new IllegalStateException("No response from the laptop backend.");
              JsonObject result =
                  JsonParser.parseString(response.body().string()).getAsJsonObject();
              if (!response.isSuccessful())
                throw new IllegalStateException(
                    result.has("message")
                        ? result.get("message").getAsString()
                        : "Sign-in failed.");
              List<String> codes = new ArrayList<>();
              if (result.has("recoveryCodes"))
                result.getAsJsonArray("recoveryCodes").forEach(c -> codes.add(c.getAsString()));
              result.remove("recoveryCodes");
              synchronized (AuthGateway.this) {
                if (epoch != expectedEpoch)
                  throw new IllegalStateException("Your session changed. Please try again.");
                if (!preferences.edit().putString("sealed", seal(result.toString())).commit())
                  throw new IllegalStateException("Could not store session securely.");
                session = result;
                recoveryCodes = codes;
                epoch++;
              }
            }
          } catch (Exception e) {
            error =
                e.getMessage() == null
                    ? "Check the laptop connection and try again."
                    : e.getMessage();
          }
          String finalError = error;
          main.post(() -> callback.done(finalError));
        });
  }

  public void login(String name, String password, Result callback) {
    request("login", body("username", name, "password", password), false, callback);
  }

  public void register(String name, String password, Result callback) {
    request("register", body("username", name, "password", password), false, callback);
  }

  public void recover(String name, String code, String password, Result callback) {
    request(
        "recover",
        body("username", name, "recoveryCode", code, "newPassword", password),
        false,
        callback);
  }

  public void changePassword(String current, String replacement, Result callback) {
    request(
        "password", body("currentPassword", current, "newPassword", replacement), true, callback);
  }

  public void reauthenticate(String password, Result callback) {
    request("reauthenticate", body("password", password), true, callback);
  }

  public synchronized void logout() {
    String old = session == null ? null : token();
    session = null;
    epoch++;
    recoveryCodes = List.of();
    preferences.edit().clear().commit();
    if (old != null)
      executor.execute(
          () -> {
            try (Response ignored =
                client
                    .newCall(
                        new Request.Builder()
                            .url(BuildConfig.API_URL + "auth/logout")
                            .header("Authorization", "Bearer " + old)
                            .post(RequestBody.create("{}", MediaType.get("application/json")))
                            .build())
                    .execute()) {
            } catch (Exception ignored) {
            }
          });
  }

  public synchronized boolean logoutIfCurrent(SessionSnapshot expected) {
    if (!isCurrent(expected)) return false;
    logout();
    return true;
  }
}

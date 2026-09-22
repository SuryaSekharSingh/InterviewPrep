package com.interviewedge.ui;

import android.app.Application;
import android.os.*;
import androidx.lifecycle.*;
import com.google.gson.*;
import com.interviewedge.EdgeApp;
import com.interviewedge.data.Repository;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class ScreenModel extends AndroidViewModel {
  public final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
  public final MutableLiveData<String> message = new MutableLiveData<>("");
  public final MutableLiveData<Boolean> signedOut = new MutableLiveData<>(false);
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final SavedStateHandle state;
  private final String owner;

  public ScreenModel(Application app, SavedStateHandle state) {
    super(app);
    this.state = state;
    this.owner = ((EdgeApp) app).auth().uid();
  }

  public String owner() {
    return owner;
  }

  public EdgeApp app() {
    return getApplication();
  }

  public String key() {
    String k = state.get("key");
    if (k == null) {
      k = UUID.randomUUID().toString();
      state.set("key", k);
    }
    return k;
  }

  public void newKey() {
    state.set("key", UUID.randomUUID().toString());
  }

  public String saved(String name, String fallback) {
    String s = state.get(name);
    return s == null ? fallback : s;
  }

  public void save(String name, String value) {
    state.set(name, value);
  }

  public void get(String path, Consumer<Repository.Data> result) {
    run(() -> app().repository().get(owner, path), result);
  }

  public void write(String method, String path, JsonElement body, Consumer<JsonElement> result) {
    String submissionKey = key();
    run(() -> app().repository().write(owner, method, path, body, submissionKey), result);
  }

  public void upload(File file, Consumer<JsonElement> result) {
    run(() -> app().repository().upload(owner, file), result);
  }

  public void draft(String id, String value) {
    state.set("draft:" + id, value);
    app().repository().queueDraft(owner, id, value);
  }

  public void restoreDraft(String id, Consumer<String> result) {
    String pending = state.get("draft:" + id);
    if (pending != null) {
      result.accept(pending);
      return;
    }
    run(() -> app().repository().draft(owner, id), result);
  }

  public <T> void run(Callable<T> work, Consumer<T> result) {
    busy.setValue(true);
    message.setValue("");
    executor.execute(
        () -> {
          try {
            T value = work.call();
            main.post(
                () -> {
                  busy.setValue(false);
                  result.accept(value);
                });
          } catch (Exception e) {
            boolean expired =
                e instanceof Repository.ApiFailure failure
                    && failure.status == 401
                    && app().clearSession(failure.session);
            main.post(
                () -> {
                  busy.setValue(false);
                  message.setValue(e.getMessage() == null ? "Please retry." : e.getMessage());
                  if (expired) signedOut.setValue(true);
                });
          }
        });
  }

  protected void onCleared() {
    executor.shutdownNow();
  }
}

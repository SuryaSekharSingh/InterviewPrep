package com.interviewedge.data;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.google.gson.JsonParser;
import com.interviewedge.EdgeApp;

public class SyncAnswerWorker extends Worker {
  public SyncAnswerWorker(@NonNull Context c, @NonNull WorkerParameters p) {
    super(c, p);
  }

  @NonNull
  public Result doWork() {
    var app = (EdgeApp) getApplicationContext();
    String owner = getInputData().getString("owner");
    if (owner == null || !owner.equals(app.auth().uid())) return Result.failure();
    try {
      app.repository()
          .write(
              owner,
              "PUT",
              getInputData().getString("path"),
              JsonParser.parseString(getInputData().getString("body")),
              null);
      return Result.success();
    } catch (Repository.ApiFailure e) {
      return e.status == 409 || e.status == 422 || e.status == 404
          ? Result.failure()
          : Result.retry();
    } catch (Exception e) {
      return getRunAttemptCount() < 4 ? Result.retry() : Result.failure();
    }
  }
}

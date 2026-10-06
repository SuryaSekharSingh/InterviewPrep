package com.interviewedge;

import android.app.Application;
import com.interviewedge.data.*;
import com.interviewedge.identity.AuthGateway;

public class EdgeApp extends Application {
  private AuthGateway auth;
  private Repository repository;

  public void onCreate() {
    super.onCreate();
    auth = new AuthGateway(this);
    repository = new Repository(this, auth);
  }

  public AuthGateway auth() {
    return auth;
  }

  public Repository repository() {
    return repository;
  }

  public void clearSession() {
    try {
      clearSession(auth.snapshot());
    } catch (IllegalStateException ignored) {
    }
  }

  public boolean clearSession(AuthGateway.SessionSnapshot expected) {
    if (!auth.logoutIfCurrent(expected)) return false;
    String uid = expected.userId();
    if (uid != null) {
      androidx.work.WorkManager.getInstance(this).cancelAllWorkByTag(uid);
      repository.clear(uid);
    }
    deletePrivateFiles(getCacheDir());
    return true;
  }

  private void deletePrivateFiles(java.io.File directory) {
    java.io.File[] files = directory.listFiles();
    if (files == null) return;
    for (java.io.File file : files) {
      if (file.isDirectory()) deletePrivateFiles(file);
      file.delete();
    }
  }
}

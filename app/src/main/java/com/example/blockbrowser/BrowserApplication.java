package com.example.blockbrowser;

import android.app.Application;

/** Sets up the private-browsing profile before any WebView code runs in a process. */
public class BrowserApplication extends Application {
  @Override public void onCreate() {
    super.onCreate();
    if (PrivateProfile.isPrivateProcess()) PrivateProfile.prepareInPrivateProcess(this);
    else PrivateProfile.wipeFromMainProcess(this);
  }
}

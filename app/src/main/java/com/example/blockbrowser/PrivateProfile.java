package com.example.blockbrowser;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import android.webkit.WebView;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

/**
 * Private browsing runs in its own process (":incognito") with its own WebView data directory, so it never
 * shares cookies, storage, cache or service workers with normal browsing.
 *
 * Private data is deleted from disk whenever no private window can be using it:
 *  - when a private process starts, before any WebView API runs (covers crashes, force-stop and process kills);
 *  - when the main process starts and no private process is running (so leftovers do not wait on disk until
 *    the next private session).
 * Only files and folders whose names contain "incognito" are touched; normal browsing data is never deleted here.
 */
final class PrivateProfile {
  static final String PROCESS_SUFFIX = ":incognito";
  static final String DATA_DIRECTORY_SUFFIX = "incognito";
  static final String PREFERENCES = "browser_incognito";
  static final String LOG_TAG = "BlockBrowserPrivate"; // read by scripts/private-isolation-check.sh

  private static volatile boolean ready = false;
  private static Thread mainProcessWipe;

  private PrivateProfile() {}

  /** True once this process has been set up as the private process. */
  static boolean isReady() { return ready; }

  static boolean isPrivateProcess() {
    return Build.VERSION.SDK_INT >= 28 && Application.getProcessName().endsWith(PROCESS_SUFFIX);
  }

  /** Must run in the private process before any WebView API. */
  static void prepareInPrivateProcess(Context context) {
    if (Build.VERSION.SDK_INT < 28 || ready) return;
    int removed = wipe(context);
    WebView.setDataDirectorySuffix(DATA_DIRECTORY_SUFFIX);
    ready = true;
    Log.i(LOG_TAG, "private process started; removed " + removed + " leftover private entries");
  }

  /** Main process: delete leftovers in the background unless a private window is open right now. */
  static void wipeFromMainProcess(Context context) {
    if (Build.VERSION.SDK_INT < 28) return;
    if (privateProcessRunning(context)) {
      Log.i(LOG_TAG, "private process running; leaving its data alone");
      return;
    }
    Context app = context.getApplicationContext();
    mainProcessWipe = new Thread(() -> {
      int removed = wipe(app);
      Log.i(LOG_TAG, "main process removed " + removed + " leftover private entries");
    }, "private-profile-wipe");
    mainProcessWipe.start();
  }

  /** Called before starting a private window, so the background wipe cannot overlap a new private session. */
  static void awaitMainProcessWipe() {
    Thread t = mainProcessWipe;
    if (t == null) return;
    try { t.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
  }

  static boolean privateProcessRunning(Context context) {
    ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
    List<ActivityManager.RunningAppProcessInfo> processes = manager == null ? null : manager.getRunningAppProcesses();
    if (processes == null) return false;
    for (ActivityManager.RunningAppProcessInfo p : processes) {
      if (p.pid != Process.myPid() && p.processName != null && p.processName.endsWith(PROCESS_SUFFIX)) return true;
    }
    return false;
  }

  /** Deletes private WebView data, private cache folders and the private preferences file. Returns entries removed. */
  static int wipe(Context context) {
    int removed = 0;
    File dataDir = context.getDataDir();
    File cacheDir = context.getCacheDir();
    // WebView names its suffixed folders after the suffix (e.g. app_webview_incognito). Check two levels of the
    // cache folder too, because WebView may nest its cache under a "WebView" folder.
    removed += deleteMatching(dataDir);
    removed += deleteMatching(cacheDir);
    File[] cacheChildren = cacheDir.listFiles();
    if (cacheChildren != null) for (File child : cacheChildren) if (child.isDirectory()) removed += deleteMatching(child);
    File prefsDir = new File(dataDir, "shared_prefs");
    for (String name : new String[]{PREFERENCES + ".xml", PREFERENCES + ".xml.bak"}) {
      File f = new File(prefsDir, name);
      if (f.exists() && f.delete()) removed++;
    }
    return removed;
  }

  private static int deleteMatching(File dir) {
    File[] children = dir == null ? null : dir.listFiles();
    if (children == null) return 0;
    int removed = 0;
    for (File child : children) {
      String name = child.getName().toLowerCase(Locale.ROOT);
      if (name.contains(DATA_DIRECTORY_SUFFIX) && !name.startsWith(PREFERENCES)) {
        deleteRecursively(child);
        removed++;
      }
    }
    return removed;
  }

  private static void deleteRecursively(File file) {
    // Never follow symbolic links out of the app's own folders.
    if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
      File[] children = file.listFiles();
      if (children != null) for (File child : children) deleteRecursively(child);
    }
    if (!file.delete() && file.exists()) Log.w(LOG_TAG, "could not delete " + file.getName());
  }
}

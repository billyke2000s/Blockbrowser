package com.example.blockbrowser;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Map;

/**
 * Private window. Runs in the ":incognito" process with its own WebView data directory (set up by
 * {@link BrowserApplication} before any WebView call), so cookies, storage, cache and service workers are never
 * shared with normal browsing. Nothing about the session is written to normal browser data: no history, session,
 * recently closed tabs or settings changes. Normal bookmarks and history can be read (for suggestions) but not changed.
 *
 * When the last private window closes, the profile is cleared and the private process ends. Anything left on disk
 * after a crash or force-stop is deleted by {@link PrivateProfile} before it can be used again.
 */
public class IncognitoActivity extends MainActivity {
  static final int PRIVATE_SURFACE = 0xff251b38, PRIVATE_BADGE = 0xff4a3470;
  /** Settings a private window starts with, copied from normal browsing. Changes made in private are not kept. */
  static final String[] COPIED_SETTINGS = {"adblock", "search_engine", "homepage", "third_party_cookies", "text_zoom", "dark", "fullscreen"};
  private static int openWindows = 0;
  private boolean browserStarted = false;

  @Override public void onCreate(Bundle state) {
    if (!PrivateProfile.isReady()) {
      // Android 8.x cannot give a second process its own WebView profile, so refuse rather than share data.
      super.onCreateWithoutBrowser(state);
      Toast.makeText(this, "Private browsing requires Android 9 or newer", Toast.LENGTH_LONG).show();
      finish();
      return;
    }
    if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false);
    else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); // keeps private pages out of the recents preview
    super.onCreate(state);
    browserStarted = true;
    openWindows++;
    if (Build.VERSION.SDK_INT >= 28) setTaskDescription(new android.app.ActivityManager.TaskDescription("Block Browser — Private"));
  }

  @Override SharedPreferences browserPreferences() {
    SharedPreferences privatePrefs = getSharedPreferences(PrivateProfile.PREFERENCES, MODE_PRIVATE);
    if (openWindows == 0) {
      SharedPreferences.Editor editor = privatePrefs.edit().clear();
      Map<String, ?> normal = getSharedPreferences("browser", MODE_PRIVATE).getAll();
      for (String key : COPIED_SETTINGS) {
        Object value = normal.get(key);
        if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof String) editor.putString(key, (String) value);
      }
      editor.commit();
    }
    return privatePrefs;
  }

  @Override SharedPreferences library() {
    return new ReadOnlyPreferences(getSharedPreferences("browser", MODE_PRIVATE),
        () -> runOnUiThread(() -> Toast.makeText(this, "Bookmarks and history can't be changed in a private window", Toast.LENGTH_LONG).show()));
  }

  @Override int autofillMode() {
    return Build.VERSION.SDK_INT >= 26 ? View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS : 0;
  }

  @Override void applyProfileStyle() {
    outer.setBackgroundColor(PRIVATE_SURFACE);
    root.setBackgroundColor(PRIVATE_SURFACE);
    toolbar.setBackgroundColor(PRIVATE_SURFACE);
    getWindow().setStatusBarColor(PRIVATE_SURFACE);
    getWindow().setNavigationBarColor(PRIVATE_SURFACE);
    TextView badge = new TextView(this);
    badge.setText("Private");
    badge.setContentDescription("Private browsing");
    badge.setTextColor(0xfff1e8ff);
    badge.setTextSize(12);
    badge.setTypeface(null, Typeface.BOLD);
    badge.setGravity(Gravity.CENTER);
    badge.setPadding(dp(10), 0, dp(10), 0);
    badge.setBackground(rounded(PRIVATE_BADGE, 14));
    badge.setOnClickListener(v -> Toast.makeText(this,
        "Private: history, cookies and site data from this window are deleted when it closes. Downloads are kept.",
        Toast.LENGTH_LONG).show());
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30));
    lp.setMargins(dp(2), 0, dp(2), 0);
    toolbar.addView(badge, 1, lp);
    address.setHint("Private search or URL");
    // Ask the keyboard not to learn from what is typed here (the keyboard decides whether to honour it).
    address.setImeOptions(address.getImeOptions() | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
    address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
  }

  @Override void openPrivateWindow() { newTab(homeUrl()); }
  @Override void schedulePrivateCleanup() {} // never clean the profile this window is using

  @Override void saveSession() {}
  @Override void scheduleSessionSave() {}
  @Override void saveVisit(String url, String title) {}
  @Override void restoreSession() { restoringSession = true; newTab(homeUrl()); restoringSession = false; }
  @Override void rememberClosedTab(String url) {}

  @Override protected void onDestroy() {
    if (!browserStarted) { super.onDestroy(); return; }
    // Only clear when the user actually closes the window; a system-initiated destroy keeps the session.
    boolean last = openWindows <= 1 && isFinishing();
    if (last) {
      for (WebView web : tabs) { web.clearHistory(); web.clearCache(true); web.clearFormData(); }
      CookieManager.getInstance().removeAllCookies(null);
      CookieManager.getInstance().flush();
      WebStorage.getInstance().deleteAllData();
    }
    super.onDestroy();
    openWindows = Math.max(0, openWindows - 1);
    if (last) {
      getSharedPreferences(PrivateProfile.PREFERENCES, MODE_PRIVATE).edit().clear().commit();
      // End the process so nothing private stays in memory. Files WebView still holds are deleted when the next
      // private or main process starts (see PrivateProfile).
      new Handler(Looper.getMainLooper()).postDelayed(() -> {
        if (openWindows == 0) {
          Log.i(PrivateProfile.LOG_TAG, "last private window closed; ending private process");
          Process.killProcess(Process.myPid());
        }
      }, 300);
    }
  }

  @Override protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    // "New private window" while one is open: add a tab to the existing window instead.
    if (intent.getData() == null && browserStarted) newTab(homeUrl());
  }
}

package com.example.blockbrowser;

import android.os.Bundle;
import android.os.Build;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.content.Intent;
import android.widget.Toast;

/** Separate process and WebView profile. Private tabs never share the regular cookie jar. */
public class IncognitoActivity extends MainActivity {
  private static boolean profileInitialized=false;
  private static int activePrivateWindows=0;
  @Override android.content.SharedPreferences browserPreferences(){return getSharedPreferences("browser_incognito",MODE_PRIVATE);}
  @Override public void onCreate(Bundle state) {
    if(!profileInitialized && Build.VERSION.SDK_INT>=28) {
      WebView.setDataDirectorySuffix("incognito");
      profileInitialized=true;
    }
    // Do not clear preferences while another private activity is still alive.
    super.onCreate(state);
    activePrivateWindows++;
    setTitle("Block Browser — Private");
  }
  @Override void saveSession(){}
  @Override void scheduleSessionSave(){}
  @Override void saveVisit(String url,String title){}
  @Override void restoreSession(){restoringSession=true;newTab(homeUrl());restoringSession=false;}
  @Override void rememberClosedTab(String url){}
  @Override protected void onDestroy(){
    // Cleanup of the shared private profile must be coordinated across windows.
    for(WebView web:tabs){web.clearHistory();web.clearCache(true);web.clearFormData();}
    // Cookie and origin storage cleanup must not run while another private window is active.
    if (activePrivateWindows == 1) CookieManager.getInstance().removeAllCookies(null);
    if (activePrivateWindows == 1) CookieManager.getInstance().flush();
    if (activePrivateWindows == 1) WebStorage.getInstance().deleteAllData();
    super.onDestroy();
    activePrivateWindows = Math.max(0, activePrivateWindows - 1);
    if (activePrivateWindows == 0) getSharedPreferences("browser_incognito",MODE_PRIVATE).edit().clear().apply();
  }
}

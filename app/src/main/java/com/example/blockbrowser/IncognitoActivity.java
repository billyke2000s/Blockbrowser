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
  @Override android.content.SharedPreferences browserPreferences(){return getSharedPreferences("browser_incognito",MODE_PRIVATE);}
  @Override public void onCreate(Bundle state) {
    if(!profileInitialized && Build.VERSION.SDK_INT>=28) {
      WebView.setDataDirectorySuffix("incognito");
      profileInitialized=true;
    }
    getSharedPreferences("browser_incognito",MODE_PRIVATE).edit().clear().commit();
    super.onCreate(state);
    setTitle("Block Browser — Private");
  }
  @Override void saveSession(){}
  @Override void scheduleSessionSave(){}
  @Override void saveVisit(String url,String title){}
  @Override void restoreSession(){restoringSession=true;newTab(homeUrl());restoringSession=false;}
  @Override void rememberClosedTab(String url){}
  @Override protected void onDestroy(){
    for(WebView web:tabs){web.clearHistory();web.clearCache(true);web.clearFormData();}
    CookieManager.getInstance().removeAllCookies(null);
    CookieManager.getInstance().flush();
    WebStorage.getInstance().deleteAllData();
    super.onDestroy();
    getSharedPreferences("browser_incognito",MODE_PRIVATE).edit().clear().apply();
  }
}

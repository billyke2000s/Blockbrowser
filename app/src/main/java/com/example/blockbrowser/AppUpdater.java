package com.example.blockbrowser;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** User-initiated update check; Android's package installer retains application data on a signed update. */
final class AppUpdater {
  private static final String API = "https://api.github.com/repos/billyke2000s/Blockbrowser/releases/latest";
  private AppUpdater() {}

  static void check(MainActivity a) {
    Toast.makeText(a, "Checking for updates…", Toast.LENGTH_SHORT).show();
    new Thread(() -> {
      try {
        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(12000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "BlockBrowser-updater");
        try {
          if (c.getResponseCode() != 200) throw new Exception("GitHub returned " + c.getResponseCode());
          ByteArrayOutputStream bytes = new ByteArrayOutputStream();
          try (InputStream in = c.getInputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
              if (bytes.size() + n > 1024 * 1024) throw new Exception("Release metadata too large");
              bytes.write(buffer, 0, n);
            }
          }
          JSONObject release = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
          JSONArray assets = release.optJSONArray("assets");
          String apk = null;
          if (assets != null) for (int i = 0; i < assets.length(); i++) {
            JSONObject item = assets.optJSONObject(i);
            if (item != null && "BlockBrowser-debug.apk".equals(item.optString("name"))) {
              apk = item.optString("browser_download_url");
              break;
            }
          }
          if (apk == null || !apk.startsWith("https://github.com/billyke2000s/Blockbrowser/releases/download/"))
            throw new Exception("No official APK in latest release");
          String tag = release.optString("tag_name", "");
          int remote = tag.startsWith("build-") ? Integer.parseInt(tag.substring(6)) : -1;
          long installed = Build.VERSION.SDK_INT >= 28
              ? a.getPackageManager().getPackageInfo(a.getPackageName(), 0).getLongVersionCode()
              : a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionCode;
          final String url = apk;
          a.runOnUiThread(() -> {
            if (remote <= installed) {
              new AlertDialog.Builder(a).setTitle("Up to date")
                  .setMessage("Installed build " + installed + ". Latest release: " + tag)
                  .setPositiveButton("OK", null).show();
            } else {
              new AlertDialog.Builder(a).setTitle("Update available")
                  .setMessage("Build " + remote + " is available. Android will preserve your data only if this APK uses the same signing key as the installed version.")
                  .setPositiveButton("Download and install", (d, w) -> download(a, url))
                  .setNegativeButton("Cancel", null).show();
            }
          });
        } finally { c.disconnect(); }
      } catch (Exception e) {
        a.runOnUiThread(() -> new AlertDialog.Builder(a).setTitle("Update check failed")
            .setMessage(e.getMessage()).setPositiveButton("OK", null).show());
      }
    }, "BlockBrowser-update-check").start();
  }

  private static void download(MainActivity a, String url) {
    try {
      DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
      DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
      req.setTitle("Block Browser update");
      req.setDescription("Downloading official APK");
      req.setMimeType("application/vnd.android.package-archive");
      req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
      long id = dm.enqueue(req);
      Handler handler = new Handler(Looper.getMainLooper());
      Runnable[] poll = new Runnable[1];
      poll[0] = () -> {
        try (Cursor cursor = dm.query(new DownloadManager.Query().setFilterById(id))) {
          if (cursor == null || !cursor.moveToFirst()) return;
          int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
          if (status == DownloadManager.STATUS_SUCCESSFUL) {
            Uri uri = dm.getUriForDownloadedFile(id);
            if (uri == null) throw new Exception("Downloaded APK unavailable");
            new AlertDialog.Builder(a).setTitle("Install update")
                .setMessage("Android will verify the APK signature. Your history and settings are retained when the signature matches.")
                .setPositiveButton("Install", (d, w) -> install(a, uri))
                .setNegativeButton("Later", null).show();
          } else if (status == DownloadManager.STATUS_FAILED) {
            Toast.makeText(a, "Update download failed", Toast.LENGTH_LONG).show();
          } else handler.postDelayed(poll[0], 1500);
        } catch (Exception e) {
          Toast.makeText(a, "Update error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
      };
      handler.postDelayed(poll[0], 1500);
    } catch (Exception e) {
      Toast.makeText(a, "Cannot download update: " + e.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  private static void install(MainActivity a, Uri uri) {
    if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
      new AlertDialog.Builder(a).setTitle("Allow app updates")
          .setMessage("Allow Block Browser to install updates in Android settings, then return and tap Install again.")
          .setPositiveButton("Open settings", (d, w) -> a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
              Uri.parse("package:" + a.getPackageName()))))
          .setNegativeButton("Cancel", null).show();
      return;
    }
    Intent intent = new Intent(Intent.ACTION_VIEW);
    intent.setDataAndType(uri, "application/vnd.android.package-archive");
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    try { a.startActivity(intent); }
    catch (Exception e) { Toast.makeText(a, "Could not open Android installer", Toast.LENGTH_LONG).show(); }
  }
}

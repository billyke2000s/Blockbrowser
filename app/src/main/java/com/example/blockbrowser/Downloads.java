package com.example.blockbrowser;

import android.Manifest;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.WebSettings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Downloads through Android's DownloadManager (system notifications, survives the app closing), plus the
 * Downloads screen with live progress, open, share, retry, cancel and delete.
 *
 * Limits: DownloadManager has no public pause/resume, so none is offered; files a page builds in memory (blob:)
 * can't be fetched; files go to the shared Downloads folder in normal and private windows alike.
 */
final class Downloads {
  private Downloads() {}

  static final long MAX_DATA_URL_BYTES = 50L * 1024 * 1024;
  static final String PRIVATE_LABEL = "Private download";

  /** Starts a download from a page link or a site's download response. */
  static void start(MainActivity a, String url, String userAgent, String disposition, String mime, String referer) {
    if (url == null) return;
    if (url.startsWith("data:")) { withStorage(a, () -> saveDataUrl(a, url, mime)); return; }
    if (!UrlTools.isWebUrl(url)) {
      a.toastLong("This file was created by the page and can't be downloaded by Block Browser");
      return;
    }
    withStorage(a, () -> enqueue(a, url, userAgent, disposition, mime, referer));
  }

  /**
   * Copies a finished file into the shared Downloads folder and returns a content:// address other apps can
   * open. Android picks a free name if the name is taken. Runs on a background thread.
   */
  static Uri saveFile(Context c, File src, String name, String mime) throws java.io.IOException {
    if (Build.VERSION.SDK_INT >= 29) {
      ContentValues v = new ContentValues();
      v.put(MediaStore.Downloads.DISPLAY_NAME, name);
      v.put(MediaStore.Downloads.MIME_TYPE, mime);
      v.put(MediaStore.Downloads.IS_PENDING, 1);
      Uri item = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
      if (item == null) throw new java.io.IOException("no storage");
      try (OutputStream out = c.getContentResolver().openOutputStream(item); java.io.InputStream in = new java.io.FileInputStream(src)) {
        if (out == null) throw new java.io.IOException("no storage");
        copy(in, out);
      } catch (java.io.IOException e) {
        c.getContentResolver().delete(item, null, null);
        throw e;
      }
      v.clear();
      v.put(MediaStore.Downloads.IS_PENDING, 0);
      c.getContentResolver().update(item, v, null, null);
      return item;
    }
    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("no storage");
    File f = new File(dir, name);
    int dot = name.lastIndexOf('.');
    for (int n = 1; f.exists(); n++) f = new File(dir, dot > 0 ? name.substring(0, dot) + " (" + n + ")" + name.substring(dot) : name + " (" + n + ")");
    try (java.io.InputStream in = new java.io.FileInputStream(src); OutputStream out = new FileOutputStream(f)) { copy(in, out); }
    // The media scanner hands back a content:// address for the file, which other apps are allowed to open.
    java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
    Uri[] result = new Uri[1];
    android.media.MediaScannerConnection.scanFile(c, new String[]{f.getPath()}, new String[]{mime}, (path, uri) -> { result[0] = uri; done.countDown(); });
    try { done.await(20, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    if (result[0] == null) throw new java.io.IOException("saved, but Android didn't index the file");
    return result[0];
  }

  private static void copy(java.io.InputStream in, OutputStream out) throws java.io.IOException {
    byte[] buf = new byte[1 << 16];
    int n;
    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
  }

  /**
   * Android 8-9 need the storage permission to save into the shared Downloads folder. Both read and write: the
   * system only gives an app a writable view of shared storage when it holds both.
   */
  static void withStorage(MainActivity a, Runnable then) {
    if (Build.VERSION.SDK_INT >= 29
        || (a.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            && a.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)) {
      then.run();
      return;
    }
    a.requestRuntime(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, granted -> {
      if (granted[0] && granted[1]) then.run();
      else a.toastLong("Block Browser needs storage permission to save downloads on this Android version");
    });
  }

  static String fileName(String url, String disposition, String mime) {
    String name = URLUtil.guessFileName(url, disposition, mime);
    name = name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
    if (name.isEmpty() || name.startsWith(".")) name = "download" + name;
    return name.length() > 120 ? name.substring(name.length() - 120) : name;
  }

  static String mimeFor(String name, String given) {
    if (given != null && !given.isEmpty() && !given.equals("application/octet-stream")) return given;
    int dot = name.lastIndexOf('.');
    String guessed = dot < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    return guessed != null ? guessed : (given == null || given.isEmpty() ? "application/octet-stream" : given);
  }

  static void enqueue(MainActivity a, String url, String userAgent, String disposition, String mime, String referer) {
    try {
      String name = fileName(url, disposition, mime);
      DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
      req.setMimeType(mimeFor(name, mime));
      req.addRequestHeader("User-Agent", userAgent == null ? WebSettings.getDefaultUserAgent(a) : userAgent);
      // Same cookies as the page, so downloads from signed-in sites work.
      String cookies = CookieManager.getInstance().getCookie(url);
      if (cookies != null) req.addRequestHeader("Cookie", cookies);
      if (UrlTools.isWebUrl(referer) && !(referer.startsWith("https://") && url.startsWith("http://"))) req.addRequestHeader("Referer", referer);
      req.setTitle(name);
      // Private downloads are labelled so the normal Downloads screen doesn't show where they came from.
      req.setDescription(a.isPrivate() ? PRIVATE_LABEL : UrlTools.host(url));
      req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
      req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
      ((DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
      a.showSnackbar("Downloading " + name, "View", () -> showPanel(a), null);
    } catch (Exception e) {
      a.toastLong("Download failed: " + e.getMessage());
    }
  }

  /** Saves a data: URL (e.g. an image a page generated) to Downloads. */
  static void saveDataUrl(MainActivity a, String url, String givenMime) {
    int comma = url.indexOf(',');
    if (comma < 0) { a.toast("This file can't be saved"); return; }
    String meta = url.substring(5, comma);
    boolean base64 = meta.endsWith(";base64");
    String mime = (base64 ? meta.substring(0, meta.length() - 7) : meta).split(";")[0];
    if (mime.isEmpty()) mime = givenMime == null ? "application/octet-stream" : givenMime;
    final String type = mime;
    a.runInBackground(() -> {
      try {
        String payload = url.substring(comma + 1);
        byte[] bytes = base64 ? Base64.decode(payload, Base64.DEFAULT) : Uri.decode(payload).getBytes("UTF-8");
        if (bytes.length > MAX_DATA_URL_BYTES) throw new IllegalStateException("file too large");
        String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(type);
        String name = "download-" + System.currentTimeMillis() + (ext == null ? "" : "." + ext);
        if (Build.VERSION.SDK_INT >= 29) {
          ContentValues v = new ContentValues();
          v.put(MediaStore.Downloads.DISPLAY_NAME, name);
          v.put(MediaStore.Downloads.MIME_TYPE, type);
          v.put(MediaStore.Downloads.IS_PENDING, 1);
          Uri item = a.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
          if (item == null) throw new IllegalStateException("no storage");
          try (OutputStream out = a.getContentResolver().openOutputStream(item)) { if (out == null) throw new IllegalStateException("no storage"); out.write(bytes); }
          v.clear();
          v.put(MediaStore.Downloads.IS_PENDING, 0);
          a.getContentResolver().update(item, v, null, null);
        } else {
          File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
          if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("no storage");
          File f = new File(dir, name);
          try (FileOutputStream out = new FileOutputStream(f)) { out.write(bytes); }
          android.media.MediaScannerConnection.scanFile(a, new String[]{f.getPath()}, new String[]{type}, null);
        }
        a.runOnUiThread(() -> a.toast("Saved " + name + " to Downloads"));
      } catch (Exception e) {
        a.runOnUiThread(() -> a.toast("Could not save this file"));
      }
    });
  }

  // ---------------------------------------------------------------------------------------------------
  // Downloads screen
  // ---------------------------------------------------------------------------------------------------

  static final class Row {
    long id; String title, uri, mime, description; int status, reason; long done, total, modified;
    boolean isPrivate() { return PRIVATE_LABEL.equals(description); }
  }

  static List<Row> query(DownloadManager dm) {
    List<Row> out = new ArrayList<>();
    try (Cursor c = dm.query(new DownloadManager.Query())) {
      if (c == null) return out;
      while (c.moveToNext() && out.size() < 300) {
        Row r = new Row();
        r.id = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
        r.title = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
        r.uri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI));
        r.description = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_DESCRIPTION));
        r.mime = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE));
        r.status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
        r.reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
        r.done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
        r.total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
        r.modified = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP));
        out.add(r);
      }
    } catch (Exception ignored) {}
    java.util.Collections.sort(out, (x, y) -> Long.compare(y.id, x.id)); // newest first
    return out;
  }

  static String failureReason(int reason) {
    switch (reason) {
      case DownloadManager.ERROR_INSUFFICIENT_SPACE: return "not enough storage";
      case DownloadManager.ERROR_DEVICE_NOT_FOUND: return "storage not available";
      case DownloadManager.ERROR_FILE_ALREADY_EXISTS: return "file already exists";
      case DownloadManager.ERROR_HTTP_DATA_ERROR: return "connection problem";
      case DownloadManager.ERROR_CANNOT_RESUME: return "couldn't resume";
      case DownloadManager.ERROR_TOO_MANY_REDIRECTS: return "too many redirects";
      case DownloadManager.ERROR_UNHANDLED_HTTP_CODE: return "unexpected server response";
      case DownloadManager.ERROR_FILE_ERROR: return "couldn't write the file";
      default: return reason >= 400 && reason < 600 ? "server error " + reason : "unknown error";
    }
  }

  static String pauseReason(int reason) {
    switch (reason) {
      case DownloadManager.PAUSED_WAITING_FOR_NETWORK: return "Waiting for network";
      case DownloadManager.PAUSED_QUEUED_FOR_WIFI: return "Waiting for Wi-Fi";
      case DownloadManager.PAUSED_WAITING_TO_RETRY: return "Waiting to retry";
      default: return "Paused by Android";
    }
  }

  static String sizes(Context c, long done, long total) {
    if (total > 0) return Formatter.formatShortFileSize(c, done) + " of " + Formatter.formatShortFileSize(c, total);
    return Formatter.formatShortFileSize(c, done);
  }

  static void showPanel(MainActivity a) {
    DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
    ListPanel panel = new ListPanel(a, "Downloads");
    panel.setEmptyText("No downloads yet.\n\nFiles are saved to your phone's Downloads folder.");
    Handler h = new Handler(Looper.getMainLooper());
    Runnable[] refresh = new Runnable[1];
    refresh[0] = () -> {
      if (!panel.isShowing()) return;
      List<Row> rows = query(dm);
      List<ListPanel.Item> items = new ArrayList<>();
      boolean active = false;
      for (StreamJobs.Job j : StreamJobs.list(a.getFilesDir())) {
        items.add(streamItem(a, j, refresh[0]));
        if (j.active()) active = true;
      }
      for (Row r : rows) {
        ListPanel.Item it = new ListPanel.Item();
        it.title = r.title == null || r.title.isEmpty() ? "Download" : r.title;
        it.glyph = r.status == DownloadManager.STATUS_SUCCESSFUL ? "✓" : r.status == DownloadManager.STATUS_FAILED ? "!" : "⇩";
        switch (r.status) {
          case DownloadManager.STATUS_RUNNING:
            active = true;
            it.subtitle = (r.total > 0 ? (r.done * 100 / r.total) + "% · " : "") + sizes(a, r.done, r.total);
            it.progress = r.total > 0 ? (int) (r.done * 100 / r.total) : 101;
            break;
          case DownloadManager.STATUS_PENDING:
            active = true;
            it.subtitle = "Waiting to start";
            it.progress = 101;
            break;
          case DownloadManager.STATUS_PAUSED:
            active = true;
            it.subtitle = pauseReason(r.reason) + " · " + sizes(a, r.done, r.total);
            it.progress = r.total > 0 ? (int) (r.done * 100 / r.total) : 0;
            break;
          case DownloadManager.STATUS_FAILED:
            it.subtitle = "Failed — " + failureReason(r.reason) + ". Tap to retry.";
            break;
          default:
            it.subtitle = Formatter.formatShortFileSize(a, r.total > 0 ? r.total : r.done)
                + " · " + (r.isPrivate() ? "private window" : UrlTools.host(r.uri)) + " · " + DateUtils.getRelativeTimeSpanString(r.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        }
        it.click = () -> {
          if (r.status == DownloadManager.STATUS_SUCCESSFUL) open(a, dm, r, false);
          else if (r.status == DownloadManager.STATUS_FAILED && !(r.isPrivate() && !a.isPrivate())) retry(a, dm, r);
          else actions(a, dm, r, refresh[0]);
        };
        it.longClick = () -> actions(a, dm, r, refresh[0]);
        items.add(it);
      }
      panel.setItems(items);
      h.postDelayed(refresh[0], active ? 1000 : 4000);
    };
    panel.setOnDismiss(() -> h.removeCallbacksAndMessages(null));
    panel.show();
    refresh[0].run();
  }

  // ---------------------------------------------------------------------------------------------------
  // Video-stream downloads (StreamDownloadService)
  // ---------------------------------------------------------------------------------------------------

  static ListPanel.Item streamItem(MainActivity a, StreamJobs.Job j, Runnable refresh) {
    ListPanel.Item it = new ListPanel.Item();
    it.title = j.title;
    boolean canRetry = j.video != null && !j.isPrivate;
    switch (j.status) {
      case StreamJobs.RUNNING:
        it.glyph = "⇩";
        if (j.piecesTotal > 0 && j.piecesDone >= j.piecesTotal) { it.subtitle = "Saving video…"; it.progress = 101; }
        else if (j.piecesTotal > 0) {
          int pct = j.piecesDone * 100 / j.piecesTotal;
          it.subtitle = "Video stream · " + pct + "% · " + Formatter.formatShortFileSize(a, j.bytes);
          it.progress = pct;
        } else { it.subtitle = "Video stream · starting"; it.progress = 101; }
        break;
      case StreamJobs.QUEUED:
        it.glyph = "⇩"; it.subtitle = "Waiting to start"; it.progress = 101;
        break;
      case StreamJobs.DONE:
        it.glyph = "✓";
        it.subtitle = Formatter.formatShortFileSize(a, j.size) + (j.quality != null ? " · " + j.quality : "") + " · "
            + (j.isPrivate ? "private window" : UrlTools.host(j.referer != null ? j.referer : j.video)) + " · "
            + DateUtils.getRelativeTimeSpanString(j.updated, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        break;
      case StreamJobs.CANCELLED:
        it.glyph = "!"; it.subtitle = "Cancelled" + (canRetry ? ". Tap to retry." : "");
        break;
      default:
        it.glyph = "!";
        it.subtitle = "Failed — " + (j.error == null ? "unknown error" : j.error) + (canRetry ? ". Tap to retry." : "");
    }
    it.click = () -> {
      if (StreamJobs.DONE.equals(j.status)) openStream(a, j, false);
      else if (!j.active() && canRetry) retryStream(a, j);
      else streamActions(a, j, refresh);
    };
    it.longClick = () -> streamActions(a, j, refresh);
    return it;
  }

  static void streamActions(MainActivity a, StreamJobs.Job j, Runnable refresh) {
    List<String> labels = new ArrayList<>();
    List<Runnable> acts = new ArrayList<>();
    if (StreamJobs.DONE.equals(j.status) && j.resultUri != null) {
      Uri uri = Uri.parse(j.resultUri);
      labels.add("Open"); acts.add(() -> openStream(a, j, false));
      labels.add("Open with…"); acts.add(() -> openStream(a, j, true));
      labels.add("Play in Block Browser"); acts.add(() -> {
        Intent intent = new Intent(a, VideoPlayerActivity.class).setDataAndType(uri, j.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        a.startActivity(intent);
      });
      labels.add("Share file"); acts.add(() -> {
        Intent intent = new Intent(Intent.ACTION_SEND).setType(j.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        a.startActivity(Intent.createChooser(intent, "Share file"));
      });
      labels.add("Delete"); acts.add(() -> new AlertDialog.Builder(a).setTitle("Delete " + j.title + "?")
          .setMessage("The file is removed from your phone.")
          .setPositiveButton("Delete", (d, n) -> {
            try { a.getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
            StreamJobs.delete(a.getFilesDir(), j.id);
            refresh.run();
          }).setNegativeButton("Cancel", null).show());
    } else if (j.active()) {
      labels.add("Cancel download"); acts.add(() -> { StreamDownloadService.cancel(a, j.id); refresh.run(); });
    } else {
      if (j.video != null && !j.isPrivate) { labels.add("Retry"); acts.add(() -> retryStream(a, j)); }
      if (j.video != null && !j.isPrivate) { labels.add("Copy stream address"); acts.add(() -> a.copyText("Stream address", j.video)); }
      labels.add("Remove from list"); acts.add(() -> { StreamJobs.delete(a.getFilesDir(), j.id); refresh.run(); });
    }
    new AlertDialog.Builder(a).setTitle(j.title).setItems(labels.toArray(new String[0]), (d, i) -> acts.get(i).run()).show();
  }

  static void openStream(MainActivity a, StreamJobs.Job j, boolean chooser) {
    if (j.resultUri == null) return;
    Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(j.resultUri), j.mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
    try {
      a.startActivity(chooser ? Intent.createChooser(intent, "Open with") : intent);
    } catch (Exception e) {
      a.toast("No app installed can open this file");
    }
  }

  static void retryStream(MainActivity a, StreamJobs.Job j) {
    StreamJobs.delete(a.getFilesDir(), j.id);
    String title = j.title.replaceAll("\\.(mp4|ts)$", "");
    a.startStreamDownload(j.video, j.audio, title, j.quality, j.userAgent, j.referer, UrlTools.host(j.video), null);
  }

  static void actions(MainActivity a, DownloadManager dm, Row r, Runnable refresh) {
    List<String> labels = new ArrayList<>();
    List<Runnable> acts = new ArrayList<>();
    boolean done = r.status == DownloadManager.STATUS_SUCCESSFUL;
    if (done) {
      labels.add("Open"); acts.add(() -> open(a, dm, r, false));
      labels.add("Open with…"); acts.add(() -> open(a, dm, r, true));
      if (r.mime != null && (r.mime.startsWith("video/") || r.mime.startsWith("audio/"))) {
        labels.add("Play in Block Browser"); acts.add(() -> {
          Uri uri = dm.getUriForDownloadedFile(r.id);
          if (uri == null) { a.toast("File no longer exists"); return; }
          Intent intent = new Intent(a, VideoPlayerActivity.class);
          intent.setDataAndType(uri, r.mime);
          intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
          a.startActivity(intent);
        });
      }
      labels.add("Share file"); acts.add(() -> share(a, dm, r));
    }
    // A private download's address is never shown, copied or retried from a normal window.
    boolean hideSource = r.isPrivate() && !a.isPrivate();
    if (r.status == DownloadManager.STATUS_FAILED && !hideSource) { labels.add("Retry"); acts.add(() -> retry(a, dm, r)); }
    if (UrlTools.isWebUrl(r.uri) && !hideSource) { labels.add("Copy download link"); acts.add(() -> a.copyText("Download link", r.uri)); }
    if (done || r.status == DownloadManager.STATUS_FAILED) {
      labels.add("Delete"); acts.add(() -> new AlertDialog.Builder(a).setTitle("Delete " + r.title + "?")
          .setMessage(done ? "The file is removed from your phone." : "The download is removed from the list.")
          .setPositiveButton("Delete", (d, n) -> { dm.remove(r.id); refresh.run(); }).setNegativeButton("Cancel", null).show());
    } else {
      labels.add("Cancel download"); acts.add(() -> { dm.remove(r.id); refresh.run(); });
    }
    new AlertDialog.Builder(a).setTitle(r.title).setItems(labels.toArray(new String[0]), (d, i) -> acts.get(i).run()).show();
  }

  static void open(MainActivity a, DownloadManager dm, Row r, boolean chooser) {
    Uri uri = dm.getUriForDownloadedFile(r.id);
    if (uri == null) { a.toast("File no longer exists"); return; }
    String mime = dm.getMimeTypeForDownloadedFile(r.id);
    Intent intent = new Intent(Intent.ACTION_VIEW);
    intent.setDataAndType(uri, mime == null ? "*/*" : mime);
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
    try {
      a.startActivity(chooser ? Intent.createChooser(intent, "Open with") : intent);
    } catch (Exception e) {
      a.toast("No app installed can open this file");
    }
  }

  static void share(MainActivity a, DownloadManager dm, Row r) {
    Uri uri = dm.getUriForDownloadedFile(r.id);
    if (uri == null) { a.toast("File no longer exists"); return; }
    Intent intent = new Intent(Intent.ACTION_SEND);
    intent.setType(r.mime == null ? "*/*" : r.mime);
    intent.putExtra(Intent.EXTRA_STREAM, uri);
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    a.startActivity(Intent.createChooser(intent, "Share file"));
  }

  static void retry(MainActivity a, DownloadManager dm, Row r) {
    if (!UrlTools.isWebUrl(r.uri)) { a.toast("This download can't be retried"); return; }
    dm.remove(r.id);
    String disposition = r.title == null ? null : "attachment; filename=\"" + r.title.replace("\"", "") + "\"";
    start(a, r.uri, null, disposition, r.mime, null);
  }
}

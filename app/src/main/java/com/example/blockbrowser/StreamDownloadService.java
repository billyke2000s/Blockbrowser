package com.example.blockbrowser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.format.Formatter;
import android.util.Log;
import android.webkit.CookieManager;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads video streams (HLS) in the background with a progress notification, converts them to MP4 and saves
 * them to the phone's Downloads folder. One download runs at a time; others wait their turn.
 *
 * Runs in the main app process. A private window passes the cookie for the video's own site in the start intent,
 * and the service never reads the normal cookie store for private downloads, so they can't carry normal sign-ins.
 */
public class StreamDownloadService extends Service {
  static final String TAG = "BlockBrowserStream";
  static final String ACTION_START = "com.example.blockbrowser.STREAM_START";
  static final String ACTION_CANCEL = "com.example.blockbrowser.STREAM_CANCEL";
  static final String CHANNEL = "downloads";
  static final int FOREGROUND_ID = 7100;

  static final String X_VIDEO = "video", X_AUDIO = "audio", X_TITLE = "title", X_UA = "ua", X_REFERER = "referer",
      X_PRIVATE = "private", X_COOKIE = "cookie", X_COOKIE_HOST = "cookieHost", X_QUALITY = "quality", X_ID = "id";

  private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "stream-job"));
  private final Handler main = new Handler(Looper.getMainLooper());
  private volatile int queued;
  private volatile long runningId = -1;
  private volatile long cancelId = -1;
  private volatile boolean stopAll;

  /** Starts a stream download. Call from an activity (a foreground app may start a foreground service). */
  static void start(Context c, String video, String audio, String title, String quality, String userAgent, String referer,
                    boolean isPrivate, String cookieHost, String cookie) {
    Intent i = new Intent(c, StreamDownloadService.class).setAction(ACTION_START)
        .putExtra(X_VIDEO, video).putExtra(X_AUDIO, audio).putExtra(X_TITLE, title).putExtra(X_QUALITY, quality)
        .putExtra(X_UA, userAgent).putExtra(X_REFERER, referer).putExtra(X_PRIVATE, isPrivate)
        .putExtra(X_COOKIE_HOST, cookieHost).putExtra(X_COOKIE, cookie);
    if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
  }

  static void cancel(Context c, long id) {
    c.startService(new Intent(c, StreamDownloadService.class).setAction(ACTION_CANCEL).putExtra(X_ID, id));
  }

  @Override public IBinder onBind(Intent intent) { return null; }

  @Override public void onCreate() {
    super.onCreate();
    // This is a fresh process: any download still marked running was cut off when the old one died.
    StreamJobs.markInterrupted(getFilesDir());
    if (Build.VERSION.SDK_INT >= 26) {
      NotificationChannel ch = new NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW);
      ch.setDescription("Progress of video downloads");
      getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }
  }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    String action = intent == null ? null : intent.getAction();
    if (ACTION_CANCEL.equals(action)) {
      long id = intent.getLongExtra(X_ID, -1);
      StreamJobs.Job j = StreamJobs.load(getFilesDir(), id);
      if (id == runningId) cancelId = id;
      else if (j != null && StreamJobs.QUEUED.equals(j.status)) { j.status = StreamJobs.CANCELLED; StreamJobs.save(getFilesDir(), j); }
      if (queued == 0 && runningId < 0) stopSelf(startId);
      return START_NOT_STICKY;
    }
    if (!ACTION_START.equals(action)) { if (queued == 0) stopSelf(startId); return START_NOT_STICKY; }

    // Must become a foreground service promptly after startForegroundService().
    goForeground(progressNotification("Preparing download", null, 0, 0, -1));

    StreamJobs.Job job = new StreamJobs.Job();
    job.id = System.currentTimeMillis();
    job.created = job.id;
    job.isPrivate = intent.getBooleanExtra(X_PRIVATE, false);
    job.video = intent.getStringExtra(X_VIDEO);
    job.audio = intent.getStringExtra(X_AUDIO);
    job.referer = intent.getStringExtra(X_REFERER);
    job.userAgent = intent.getStringExtra(X_UA);
    job.quality = intent.getStringExtra(X_QUALITY);
    job.title = StreamJobs.fileName(intent.getStringExtra(X_TITLE), job.referer != null ? job.referer : job.video);
    job.status = StreamJobs.QUEUED;
    StreamJobs.save(getFilesDir(), job);
    String cookieHost = intent.getStringExtra(X_COOKIE_HOST);
    String cookie = intent.getStringExtra(X_COOKIE);
    queued++;
    worker.execute(() -> run(job, cookieHost, cookie, startId));
    return START_NOT_STICKY;
  }

  private void run(StreamJobs.Job job, String cookieHost, String cookie, int startId) {
    File work = new File(getCacheDir(), "stream-" + job.id);
    File filesDir = getFilesDir();
    Runnable heartbeat = new Runnable() { // keeps the job looking alive to the Downloads screen while converting
      @Override public void run() { StreamJobs.save(filesDir, job); main.postDelayed(this, 30_000); }
    };
    try {
      StreamJobs.Job saved = StreamJobs.load(filesDir, job.id);
      if (stopAll || (saved != null && StreamJobs.CANCELLED.equals(saved.status))) return;
      runningId = job.id;
      job.status = StreamJobs.RUNNING;
      StreamJobs.save(filesDir, job);
      main.postDelayed(heartbeat, 30_000);
      HlsDownload.CookieSource cookies = job.isPrivate
          ? url -> cookieHost != null && cookieHost.equalsIgnoreCase(UrlTools.host(url)) ? cookie : null
          : url -> { try { return CookieManager.getInstance().getCookie(url); } catch (Exception e) { return null; } };
      HlsDownload.Http http = new HlsDownload.Http(job.userAgent, job.referer, cookies);
      showProgress(progressNotification(job.title, "Starting", 0, 0, job.id));
      HlsDownload.Result r = HlsDownload.download(http, job.video, job.audio, work, new HlsDownload.Listener() {
        @Override public void progress(int done, int total, long bytes) {
          job.piecesDone = done;
          job.piecesTotal = total;
          job.bytes = bytes;
          StreamJobs.save(filesDir, job);
          showProgress(progressNotification(job.title, Formatter.formatShortFileSize(StreamDownloadService.this, bytes), done, total, job.id));
        }
        @Override public boolean cancelled() { return stopAll || cancelId == job.id; }
      });
      showProgress(progressNotification(job.title, "Saving video", 0, 0, job.id));
      File mp4 = new File(work, "out.mp4");
      File toSave;
      try {
        StreamRemuxer.toMp4(r.video, r.audio, mp4);
        toSave = mp4;
      } catch (IOException convert) {
        Log.w(TAG, "MP4 conversion failed; saving the stream as downloaded", convert);
        if (r.audio != null) throw new IOException("couldn't combine the video and audio (" + convert.getMessage() + ")");
        // Joined fragmented-MP4 pieces already are an MP4; joined MPEG-TS pieces play in most video apps.
        toSave = r.video;
        if (!r.videoFragmentedMp4) { job.mime = "video/mp2t"; job.title = job.title.replaceAll("\\.mp4$", ".ts"); }
      }
      Uri uri;
      try {
        uri = Downloads.saveFile(this, toSave, job.title, job.mime);
      } catch (IOException | RuntimeException save) {
        Log.w(TAG, "saving to Downloads failed", save);
        throw new IOException("couldn't save to Downloads (" + save.getMessage() + ")");
      }
      job.size = toSave.length();
      job.resultUri = uri.toString();
      job.status = StreamJobs.DONE;
      job.error = null;
      Log.i(TAG, "saved " + job.title + " (" + job.size + " bytes, " + r.duration + " s)");
      finished(job, true);
    } catch (HlsDownload.Cancelled e) {
      if (stopAll && cancelId != job.id) { job.status = StreamJobs.FAILED; job.error = "stopped by Android"; }
      else job.status = StreamJobs.CANCELLED;
      finished(job, false);
    } catch (HlsDownload.Refused e) {
      job.status = StreamJobs.FAILED;
      job.error = e.getMessage();
      finished(job, false);
    } catch (Exception e) {
      Log.w(TAG, "stream download failed", e);
      job.status = StreamJobs.FAILED;
      job.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      finished(job, false);
    } finally {
      main.removeCallbacks(heartbeat);
      deleteTree(work);
      runningId = -1;
      main.post(() -> {
        queued--;
        if (queued <= 0) {
          queued = 0;
          stopForeground(STOP_FOREGROUND_REMOVE); // the finished notification is posted separately
          stopSelf();
        }
      });
    }
  }

  private void finished(StreamJobs.Job job, boolean ok) {
    StreamJobs.save(getFilesDir(), job);
    NotificationManager nm = getSystemService(NotificationManager.class);
    if (nm == null) return;
    if (StreamJobs.CANCELLED.equals(job.status)) { nm.cancel((int) (job.id % Integer.MAX_VALUE)); return; }
    Notification.Builder b = builder()
        .setSmallIcon(ok ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_notify_error)
        .setContentTitle(ok ? job.title : "Download failed")
        .setContentText(ok ? "Download complete · " + Formatter.formatShortFileSize(this, job.size) : job.title + ": " + job.error)
        .setAutoCancel(true);
    if (ok && job.resultUri != null) {
      Intent open = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(job.resultUri), job.mime)
          .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
      b.setContentIntent(PendingIntent.getActivity(this, (int) (job.id % 100000), open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
    }
    try { nm.notify((int) (job.id % Integer.MAX_VALUE), b.build()); } catch (SecurityException ignored) {}
  }

  private Notification.Builder builder() {
    return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
  }

  private Notification progressNotification(String title, String text, int done, int total, long jobId) {
    Notification.Builder b = builder()
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(title)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(total > 0 ? total : 0, done, total <= 0);
    String extra = queued > 1 ? " · " + (queued - 1) + " more waiting" : "";
    if (text != null) b.setContentText((total > 0 ? (done * 100 / total) + "% · " : "") + text + extra);
    if (jobId >= 0) {
      PendingIntent cancel = PendingIntent.getService(this, (int) (jobId % 100000),
          new Intent(this, StreamDownloadService.class).setAction(ACTION_CANCEL).putExtra(X_ID, jobId),
          PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
      b.addAction(new Notification.Action.Builder(null, "Cancel", cancel).build());
    }
    return b.build();
  }

  private void goForeground(Notification n) {
    if (Build.VERSION.SDK_INT >= 29) startForeground(FOREGROUND_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    else startForeground(FOREGROUND_ID, n);
  }

  private void showProgress(Notification n) {
    NotificationManager nm = getSystemService(NotificationManager.class);
    try { if (nm != null) nm.notify(FOREGROUND_ID, n); } catch (SecurityException ignored) {}
  }

  /** Android 15 limits background data transfers to 6 hours a day; stop cleanly when told to. */
  @Override public void onTimeout(int startId, int fgsType) {
    stopAll = true;
    Log.w(TAG, "Android's daily limit for background downloads was reached");
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
  }

  @Override public void onDestroy() {
    stopAll = true;
    worker.shutdownNow();
    super.onDestroy();
  }

  private static void deleteTree(File f) {
    File[] kids = f.listFiles();
    if (kids != null) for (File k : kids) deleteTree(k);
    f.delete();
  }
}

package com.example.blockbrowser;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Video-stream downloads shown on the Downloads screen. Android's DownloadManager can't fetch a stream (it is
 * hundreds of pieces), so {@link StreamDownloadService} records each one as a small JSON file. Files rather than
 * memory, because private windows run in a separate process and their Downloads screen must see them too.
 */
final class StreamJobs {
  private StreamJobs() {}

  static final String RUNNING = "running", QUEUED = "queued", DONE = "done", FAILED = "failed", CANCELLED = "cancelled";
  /** A running job with no progress for this long belongs to a download that was killed. */
  static final long STALE_MS = 3 * 60 * 1000;

  static final class Job {
    long id;
    String title = "", status = QUEUED, error, resultUri, mime = "video/mp4", quality;
    int piecesDone, piecesTotal;
    long bytes, size, created, updated;
    boolean isPrivate;
    // What is needed to retry. Never stored for private downloads.
    String video, audio, referer, userAgent;

    boolean active() { return RUNNING.equals(status) || QUEUED.equals(status); }

    JSONObject toJson() {
      JSONObject o = new JSONObject();
      try {
        o.put("id", id).put("title", title).put("status", status).put("mime", mime)
            .put("piecesDone", piecesDone).put("piecesTotal", piecesTotal).put("bytes", bytes).put("size", size)
            .put("created", created).put("updated", updated).put("private", isPrivate);
        if (error != null) o.put("error", error);
        if (resultUri != null) o.put("uri", resultUri);
        if (quality != null) o.put("quality", quality);
        if (!isPrivate) {
          if (video != null) o.put("video", video);
          if (audio != null) o.put("audio", audio);
          if (referer != null) o.put("referer", referer);
          if (userAgent != null) o.put("userAgent", userAgent);
        }
      } catch (Exception ignored) {}
      return o;
    }

    static Job fromJson(JSONObject o) {
      Job j = new Job();
      j.id = o.optLong("id");
      j.title = o.optString("title", "");
      j.status = o.optString("status", FAILED);
      j.mime = o.optString("mime", "video/mp4");
      j.piecesDone = o.optInt("piecesDone");
      j.piecesTotal = o.optInt("piecesTotal");
      j.bytes = o.optLong("bytes");
      j.size = o.optLong("size");
      j.created = o.optLong("created");
      j.updated = o.optLong("updated");
      j.isPrivate = o.optBoolean("private");
      j.error = o.has("error") ? o.optString("error") : null;
      j.resultUri = o.has("uri") ? o.optString("uri") : null;
      j.quality = o.has("quality") ? o.optString("quality") : null;
      j.video = o.has("video") ? o.optString("video") : null;
      j.audio = o.has("audio") ? o.optString("audio") : null;
      j.referer = o.has("referer") ? o.optString("referer") : null;
      j.userAgent = o.has("userAgent") ? o.optString("userAgent") : null;
      return j;
    }
  }

  static File dir(File filesDir) { return new File(filesDir, "stream-jobs"); }

  static synchronized void save(File filesDir, Job j) {
    File d = dir(filesDir);
    if (!d.isDirectory() && !d.mkdirs()) return;
    j.updated = System.currentTimeMillis();
    File tmp = new File(d, j.id + ".json.tmp");
    try (FileOutputStream out = new FileOutputStream(tmp)) {
      out.write(j.toJson().toString().getBytes(StandardCharsets.UTF_8));
      out.getFD().sync();
    } catch (IOException e) {
      return;
    }
    // Rename is atomic, so another process never reads half a file.
    if (!tmp.renameTo(new File(d, j.id + ".json"))) tmp.delete();
  }

  static Job load(File filesDir, long id) {
    try {
      byte[] b = Files.readAllBytes(new File(dir(filesDir), id + ".json").toPath());
      return Job.fromJson(new JSONObject(new String(b, StandardCharsets.UTF_8)));
    } catch (Exception e) {
      return null;
    }
  }

  /** All jobs, newest first. Running jobs that stopped reporting are shown as interrupted. */
  static List<Job> list(File filesDir) {
    List<Job> out = new ArrayList<>();
    File[] files = dir(filesDir).listFiles((d, name) -> name.endsWith(".json"));
    if (files == null) return out;
    long now = System.currentTimeMillis();
    for (File f : files) {
      try {
        Job j = Job.fromJson(new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
        if (j.active() && now - j.updated > STALE_MS) { j.status = FAILED; j.error = "interrupted"; }
        out.add(j);
      } catch (Exception ignored) {}
    }
    Collections.sort(out, (a, b) -> Long.compare(b.created, a.created));
    return out;
  }

  static void delete(File filesDir, long id) {
    new File(dir(filesDir), id + ".json").delete();
  }

  /** Called when the download service starts in a fresh process: anything still "running" was cut off. */
  static void markInterrupted(File filesDir) {
    for (Job j : list(filesDir)) {
      if (j.active()) { j.status = FAILED; j.error = "interrupted"; save(filesDir, j); }
    }
  }

  /** "Some page title" -> a safe file name ending in .mp4. */
  static String fileName(String title, String fallbackUrl) {
    String t = title == null ? "" : title.trim();
    if (t.isEmpty() || UrlTools.isWebUrl(t)) {
      String host = UrlTools.host(fallbackUrl);
      t = host.isEmpty() ? "video" : "video from " + host;
    }
    t = t.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").replaceAll("\\s+", " ").trim();
    if (t.startsWith(".")) t = "_" + t;
    if (t.length() > 100) t = t.substring(0, 100).trim();
    return t + ".mp4";
  }
}

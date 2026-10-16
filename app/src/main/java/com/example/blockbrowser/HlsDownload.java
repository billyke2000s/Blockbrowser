package com.example.blockbrowser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Downloads an HLS stream: fetches a media playlist and joins its pieces into one file per track
 * (video, and audio when the stream keeps it separate). Turning those files into an MP4 is Android-specific
 * and done by {@link StreamRemuxer}; this class has no Android dependencies, so it can be tested on the JVM
 * against a real stream.
 *
 * Refuses encrypted streams (DRM or AES-128): Block Browser does not get around copy protection.
 */
final class HlsDownload {
  private HlsDownload() {}

  /** A stream Block Browser won't or can't save; the message is shown to the user as is. */
  static final class Refused extends IOException {
    Refused(String message) { super(message); }
  }

  interface CookieSource { String cookieFor(String url); }

  interface Listener {
    void progress(int piecesDone, int piecesTotal, long bytes);
    boolean cancelled();
  }

  static final int THREADS = 4;
  static final int MAX_PIECE_BYTES = 256 * 1024 * 1024;
  static final int MAX_PLAYLIST_BYTES = 8 * 1024 * 1024;

  /** HTTP with the page's user agent, referrer and cookies, so streams behind a sign-in still load. */
  static final class Http {
    final String userAgent, referer;
    final CookieSource cookies;
    int connectTimeoutMs = 20_000, readTimeoutMs = 30_000;

    Http(String userAgent, String referer, CookieSource cookies) {
      this.userAgent = userAgent;
      this.referer = referer;
      this.cookies = cookies;
    }

    static final class Response { byte[] body; String finalUrl; }

    Response get(String url, long offset, long length, int maxBytes) throws IOException {
      String current = url;
      for (int redirects = 0; redirects < 8; redirects++) {
        HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(connectTimeoutMs);
        c.setReadTimeout(readTimeoutMs);
        if (userAgent != null) c.setRequestProperty("User-Agent", userAgent);
        if (referer != null && UrlTools.isWebUrl(referer) && !(referer.startsWith("https://") && current.startsWith("http://"))) c.setRequestProperty("Referer", referer);
        String cookie = cookies == null ? null : cookies.cookieFor(current);
        if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
        if (offset >= 0) c.setRequestProperty("Range", "bytes=" + offset + "-" + (length >= 0 ? offset + length - 1 : ""));
        try {
          int code = c.getResponseCode();
          if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
            String next = new URL(new URL(current), c.getHeaderField("Location")).toString();
            if (current.startsWith("https://") && next.startsWith("http://")) throw new IOException("server redirected to an insecure address");
            current = next;
            continue;
          }
          if (code >= 400) throw new HttpError(code);
          Response r = new Response();
          r.finalUrl = current;
          // A server that ignores Range sends the whole file (200); keep only the requested part.
          long skip = (offset >= 0 && code == 200) ? offset : 0;
          long want = (offset >= 0 && length >= 0) ? length : -1;
          try (InputStream in = c.getInputStream()) { r.body = readAll(in, skip, want, maxBytes); }
          return r;
        } finally {
          c.disconnect();
        }
      }
      throw new IOException("too many redirects");
    }

    String getText(String url, String[] finalUrl) throws IOException {
      Response r = get(url, -1, -1, MAX_PLAYLIST_BYTES);
      if (finalUrl != null) finalUrl[0] = r.finalUrl;
      return new String(r.body, StandardCharsets.UTF_8);
    }
  }

  static final class HttpError extends IOException {
    final int code;
    HttpError(int code) { super("server answered " + code); this.code = code; }
    boolean retryable() { return code == 408 || code == 429 || code >= 500; }
  }

  static byte[] readAll(InputStream in, long skip, long want, int maxBytes) throws IOException {
    while (skip > 0) {
      long n = in.skip(skip);
      if (n <= 0) { if (in.read() < 0) throw new IOException("piece ended early"); n = 1; }
      skip -= n;
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream(want > 0 && want < maxBytes ? (int) want : 1 << 16);
    byte[] buf = new byte[1 << 16];
    long left = want;
    int n;
    while ((left < 0 || left > 0) && (n = in.read(buf, 0, left < 0 ? buf.length : (int) Math.min(buf.length, left))) > 0) {
      out.write(buf, 0, n);
      if (left > 0) left -= n;
      if (out.size() > maxBytes) throw new IOException("piece too large");
    }
    if (left > 0) throw new IOException("piece ended early");
    return out.toByteArray();
  }

  /** Loads and parses a playlist; relative entries resolve against the address after redirects. */
  static HlsPlaylist load(Http http, String url) throws IOException {
    String[] finalUrl = new String[1];
    String text = http.getText(url, finalUrl);
    return HlsPlaylist.parse(text, finalUrl[0]);
  }

  /** Checks a media playlist is something Block Browser can save. */
  static void check(HlsPlaylist media) throws IOException {
    if (media.encryption != null) throw new Refused("This video is copy-protected, so it can't be downloaded");
    if (media.isMaster) throw new IOException("expected a media playlist");
    if (!media.ended) throw new Refused("This is a live stream. Only videos that have finished can be downloaded");
    if (media.segments.isEmpty()) throw new IOException("the stream has no pieces");
  }

  static final class Result {
    File video, audio;
    boolean videoFragmentedMp4, audioFragmentedMp4;
    double duration;
    long bytes;
  }

  /**
   * Downloads a stream into workDir: video.part and, when given an audio playlist, audio.part.
   * Each part is the stream's pieces joined in order (with the fragmented-MP4 header first, if any).
   */
  static Result download(Http http, String videoPlaylist, String audioPlaylist, File workDir, Listener listener) throws IOException {
    HlsPlaylist video = load(http, videoPlaylist);
    if (video.isMaster) {
      // Given a master playlist, take the best quality (the app normally asks the user first).
      HlsPlaylist.Variant best = video.qualities().get(0);
      HlsPlaylist.Rendition a = video.audioFor(best);
      if (video.encryption != null) throw new Refused("This video is copy-protected, so it can't be downloaded");
      return download(http, best.uri, audioPlaylist != null ? audioPlaylist : a == null ? null : a.uri, workDir, listener);
    }
    check(video);
    HlsPlaylist audio = null;
    if (audioPlaylist != null) {
      audio = load(http, audioPlaylist);
      check(audio);
    }
    if (!workDir.isDirectory() && !workDir.mkdirs()) throw new IOException("can't create a temporary folder");
    int total = pieces(video) + (audio == null ? 0 : pieces(audio));
    Result r = new Result();
    r.duration = video.duration();
    Progress p = new Progress(listener, total);
    r.video = new File(workDir, "video.part");
    r.videoFragmentedMp4 = video.init != null;
    writeTrack(http, video, r.video, p);
    if (audio != null) {
      r.audio = new File(workDir, "audio.part");
      r.audioFragmentedMp4 = audio.init != null;
      writeTrack(http, audio, r.audio, p);
    }
    r.bytes = p.bytes.get();
    return r;
  }

  static int pieces(HlsPlaylist media) { return media.segments.size() + (media.init != null ? 1 : 0); }

  private static final class Progress {
    final Listener listener;
    final int total;
    int done;
    final AtomicLong bytes = new AtomicLong();
    long lastReport;
    Progress(Listener listener, int total) { this.listener = listener; this.total = total; }

    void piece(int size) throws IOException {
      done++;
      bytes.addAndGet(size);
      if (listener == null) return;
      if (listener.cancelled()) throw new Cancelled();
      long now = System.currentTimeMillis();
      if (now - lastReport > 400 || done == total) { lastReport = now; listener.progress(done, total, bytes.get()); }
    }
  }

  static final class Cancelled extends IOException {
    Cancelled() { super("cancelled"); }
  }

  /** Fetches a track's pieces a few at a time and writes them in order. */
  static void writeTrack(Http http, HlsPlaylist media, File out, Progress p) throws IOException {
    ExecutorService pool = Executors.newFixedThreadPool(THREADS, r -> { Thread t = new Thread(r, "stream-download"); t.setDaemon(true); return t; });
    try (OutputStream file = new FileOutputStream(out)) {
      if (media.init != null) {
        byte[] init = fetchPiece(http, media.init, p.listener);
        file.write(init);
        p.piece(init.length);
      }
      Deque<Future<byte[]>> window = new ArrayDeque<>();
      int next = 0;
      while (next < media.segments.size() || !window.isEmpty()) {
        while (next < media.segments.size() && window.size() < THREADS * 2) {
          HlsPlaylist.Segment s = media.segments.get(next++);
          window.add(pool.submit(() -> stripId3(fetchPiece(http, s, p.listener))));
        }
        byte[] data;
        try {
          data = window.poll().get();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new Cancelled();
        } catch (ExecutionException e) {
          Throwable cause = e.getCause();
          if (cause instanceof IOException) throw (IOException) cause;
          throw new IOException(cause);
        }
        file.write(data);
        p.piece(data.length);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  /** One piece, retried a few times on network errors and temporary server errors. */
  static byte[] fetchPiece(Http http, HlsPlaylist.Segment s, Listener listener) throws IOException {
    IOException last = null;
    for (int attempt = 0; attempt < 4; attempt++) {
      if (listener != null && listener.cancelled()) throw new Cancelled();
      try {
        return http.get(s.uri, s.offset, s.length, MAX_PIECE_BYTES).body;
      } catch (HttpError e) {
        if (!e.retryable()) throw e;
        last = e;
      } catch (IOException e) {
        last = e;
      }
      try { Thread.sleep(1000L << attempt); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Cancelled(); }
    }
    throw last;
  }

  /**
   * Audio-only HLS pieces (.aac) start with an ID3 tag holding their timestamp. Joined together, those tags would
   * sit in the middle of the audio, which Android's AAC reader can't skip; drop them.
   */
  static byte[] stripId3(byte[] b) {
    int pos = 0;
    while (b.length - pos >= 10 && b[pos] == 'I' && b[pos + 1] == 'D' && b[pos + 2] == '3') {
      int size = ((b[pos + 6] & 0x7f) << 21) | ((b[pos + 7] & 0x7f) << 14) | ((b[pos + 8] & 0x7f) << 7) | (b[pos + 9] & 0x7f);
      boolean footer = (b[pos + 5] & 0x10) != 0;
      int end = pos + 10 + size + (footer ? 10 : 0);
      if (end > b.length) break;
      pos = end;
    }
    if (pos == 0) return b;
    byte[] out = new byte[b.length - pos];
    System.arraycopy(b, pos, out, 0, out.length);
    return out;
  }
}

package com.example.blockbrowser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Spots video addresses among the requests a page makes, so ⋮ → Download video can offer videos that a page
 * plays through a script (e.g. an HLS stream fed to the player in pieces) and that never appear as a plain
 * {@code <video src>}. No Android dependencies, so it can be unit-tested on the JVM.
 */
final class MediaSniffer {
  private MediaSniffer() {}

  enum Kind { HLS, DASH, FILE }

  /** What kind of video an address is, judged from its path; null for anything else (including stream pieces). */
  static Kind classify(String url) {
    if (!UrlTools.isWebUrl(url)) return null;
    String lower = url.toLowerCase(Locale.ROOT);
    int cut = lower.length();
    for (char c : new char[]{'?', '#'}) { int i = lower.indexOf(c); if (i >= 0 && i < cut) cut = i; }
    String path = lower.substring(0, cut);
    String query = cut < lower.length() ? lower.substring(cut) : "";
    if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return Kind.HLS;
    if (path.endsWith(".mpd")) return Kind.DASH;
    if (path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".webm") || path.endsWith(".mov")
        || path.endsWith(".mkv") || path.endsWith(".ogv")) {
      // Sites that stream with byte ranges request one track of a file in slices; those slices aren't a video.
      if (query.contains("bytestart=") || query.contains("byteend=") || query.contains("range=") || query.contains("bytes=")) return null;
      return Kind.FILE;
    }
    return null;
  }

  static final class Found {
    final String url; final Kind kind;
    Found(String url, Kind kind) { this.url = url; this.kind = kind; }
  }

  /** Videos seen on one tab's current page. Written from WebView request threads, read on the UI thread. */
  static final class Log {
    static final int MAX = 40;
    private final LinkedHashMap<String, Kind> seen = new LinkedHashMap<>();

    /** Records the address if it is a video; returns true if it was recorded. */
    synchronized boolean offer(String url) {
      Kind k = classify(url);
      if (k == null || seen.containsKey(url)) return false;
      if (seen.size() >= MAX) seen.remove(seen.keySet().iterator().next());
      seen.put(url, k);
      return true;
    }

    synchronized void clear() { seen.clear(); }

    /** Streams first (they are what pages actually play), then files; newest first within each. */
    synchronized List<Found> snapshot() {
      List<Found> streams = new ArrayList<>(), files = new ArrayList<>();
      for (java.util.Map.Entry<String, Kind> e : seen.entrySet()) {
        Found f = new Found(e.getKey(), e.getValue());
        if (f.kind == Kind.FILE) files.add(0, f); else streams.add(0, f);
      }
      streams.addAll(files);
      return streams;
    }
  }
}

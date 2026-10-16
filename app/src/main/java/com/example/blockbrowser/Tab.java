package com.example.blockbrowser;

import android.graphics.Bitmap;
import android.util.SparseIntArray;
import android.webkit.WebView;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * One browser tab. The WebView is created only when the tab is first shown and can be released again under
 * memory pressure ("discarded"); the tab keeps its address, title, scroll position and thumbnail meanwhile.
 */
final class Tab {
  private static long nextId = 1;
  final long id = nextId++;
  WebView web;            // null until shown, or after being discarded
  String url;             // last known address (also what a discarded tab reloads)
  String title = "";
  Bitmap favicon, thumbnail;
  boolean desktop;
  boolean loading;
  int progress;
  /** Set when a site opened this tab with window.open; such tabs close when the site calls window.close(). */
  boolean openedByScript;
  /** The tab this one was opened from, so Back can return there when this tab has no history. */
  Tab parent;
  /** Scroll position to apply once the next page load finishes (session restore, discarded tabs). */
  Integer restoreY;
  /** Scroll position seen after the last restore attempt, to tell our own scrolling from the user's. */
  int restoreAttemptY = -1;
  /** Scroll position for each back/forward history index, so Back and Forward return to the same spot. */
  final SparseIntArray positions = new SparseIntArray();
  /** Address shown while an error page is displayed; Reload retries it. */
  String errorUrl;
  /** Host of the page in the main frame; read by request threads for per-site ad blocking. */
  volatile String pageHost = "";
  /** Videos and video streams the current page has requested, for ⋮ → Download video. */
  final MediaSniffer.Log media = new MediaSniffer.Log();
  /** Requests blocked on the current page. */
  final AtomicInteger blocked = new AtomicInteger();
  long lastUsed = System.currentTimeMillis();

  Tab(String url) { this.url = url; }

  /** Address to show and save: the live page, the failed address, or the saved one. */
  String currentUrl() {
    if (errorUrl != null) return errorUrl;
    if (web != null) {
      String u = web.getUrl();
      if (u != null && !u.startsWith("data:") && !u.equals("about:blank")) return u;
    }
    return url;
  }

  String displayTitle() {
    if (title != null && !title.isEmpty()) return title;
    String u = currentUrl();
    return u == null || u.isEmpty() ? "New tab" : UrlTools.display(u);
  }
}

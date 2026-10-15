package com.example.blockbrowser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ranks address-bar suggestions. Pure Java so it can be unit-tested.
 *
 * Order of preference: what was typed (when it is a web address), pages whose address starts with the text,
 * bookmarks, frequently and recently visited pages, title matches, then search-engine suggestions.
 */
final class SuggestionRanker {
  static final int TYPED_URL = 0, BOOKMARK = 1, HISTORY = 2, SEARCH = 3, SEARCH_SUGGESTION = 4;

  /** A saved page that might be suggested. */
  static final class Page {
    final String url, title; final boolean bookmark; final int visits; final long lastVisit;
    Page(String url, String title, boolean bookmark, int visits, long lastVisit) {
      this.url = url; this.title = title == null ? "" : title; this.bookmark = bookmark; this.visits = visits; this.lastVisit = lastVisit;
    }
  }

  static final class Suggestion {
    final int type; final String title; final String url; final double score;
    Suggestion(int type, String title, String url, double score) { this.type = type; this.title = title; this.url = url; this.score = score; }
    @Override public String toString() { return type + ":" + url; }
  }

  private SuggestionRanker() {}

  /**
   * @param typedUrl the address that pressing Enter would load (from {@link UrlTools#toNavigableUrl}), or null
   * @param searchPrefix search engine URL prefix used for search rows
   */
  static List<Suggestion> rank(String query, String typedUrl, List<Page> pages, List<String> searchSuggestions,
                               String searchPrefix, long now, int max) {
    String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    List<Suggestion> out = new ArrayList<>();
    if (q.isEmpty()) return out;
    Set<String> seen = new HashSet<>();

    List<Suggestion> pageRows = new ArrayList<>();
    for (Page p : merge(pages)) {
      double s = score(q, p, now);
      if (s > 0) pageRows.add(new Suggestion(p.bookmark ? BOOKMARK : HISTORY, p.title.isEmpty() ? UrlTools.display(p.url) : p.title, p.url, s));
    }
    Collections.sort(pageRows, (a, b) -> Double.compare(b.score, a.score));

    boolean typedIsWeb = typedUrl != null && !typedUrl.startsWith(searchPrefix);
    if (typedIsWeb) {
      // A saved page with exactly the typed address is shown (with its title) instead of a bare "go to" row.
      Suggestion saved = null;
      for (Suggestion s : pageRows) if (normalise(s.url).equals(normalise(typedUrl))) { saved = s; break; }
      out.add(saved != null ? saved : new Suggestion(TYPED_URL, UrlTools.display(typedUrl), typedUrl, Double.MAX_VALUE));
      seen.add(normalise(typedUrl));
    }
    // The best saved page goes above "search for…" when it is a strong address match.
    int pageLimit = Math.max(0, max - 2);
    int taken = 0;
    for (Suggestion s : pageRows) {
      if (taken >= pageLimit || out.size() >= max) break;
      if (seen.add(normalise(s.url))) { out.add(s); taken++; }
    }
    if (!typedIsWeb && out.size() < max) {
      // Searching what was typed is always offered when it isn't an address; place it after strong address matches.
      int insertAt = 0;
      while (insertAt < out.size() && out.get(insertAt).score >= 1000) insertAt++;
      out.add(insertAt, new Suggestion(SEARCH, query.trim(), searchPrefix + UrlTools.encode(query.trim()), 0));
    }
    if (searchSuggestions != null) {
      Set<String> phrases = new HashSet<>();
      phrases.add(q);
      for (String phrase : searchSuggestions) {
        if (out.size() >= max) break;
        if (phrase == null) continue;
        String p = phrase.trim();
        if (p.isEmpty() || !phrases.add(p.toLowerCase(Locale.ROOT))) continue;
        out.add(new Suggestion(SEARCH_SUGGESTION, p, searchPrefix + UrlTools.encode(p), 0));
      }
    }
    return out;
  }

  /** Same page saved as both bookmark and history: keep one entry that counts as a bookmark with the history stats. */
  private static List<Page> merge(List<Page> pages) {
    java.util.LinkedHashMap<String, Page> byUrl = new java.util.LinkedHashMap<>();
    for (Page p : pages) {
      if (p == null || !UrlTools.isWebUrl(p.url)) continue;
      String key = normalise(p.url);
      Page old = byUrl.get(key);
      if (old == null) { byUrl.put(key, p); continue; }
      String title = !old.title.isEmpty() ? (old.bookmark ? old.title : (p.bookmark && !p.title.isEmpty() ? p.title : old.title)) : p.title;
      byUrl.put(key, new Page(old.bookmark ? old.url : p.url, title, old.bookmark || p.bookmark,
          Math.max(old.visits, p.visits), Math.max(old.lastVisit, p.lastVisit)));
    }
    return new ArrayList<>(byUrl.values());
  }

  static String normalise(String url) {
    String d = UrlTools.display(url).toLowerCase(Locale.ROOT);
    return d;
  }

  static double score(String q, Page p, long now) {
    String shortUrl = UrlTools.display(p.url).toLowerCase(Locale.ROOT);
    String host = UrlTools.host(p.url);
    if (host.startsWith("www.")) host = host.substring(4);
    String title = p.title.toLowerCase(Locale.ROOT);
    double match;
    if (shortUrl.equals(q) || host.equals(q)) match = 3000;
    else if (host.startsWith(q)) match = 2000 - Math.min(500, host.length() * 5);
    else if (shortUrl.startsWith(q)) match = 1500 - Math.min(500, shortUrl.length());
    else if (wordStart(title, q)) match = 600;
    else if (wordStart(shortUrl, q)) match = 400;
    else if (title.contains(q) && q.length() >= 3) match = 200;
    else if (shortUrl.contains(q) && q.length() >= 3) match = 150;
    else return 0;
    double frecency = Math.log(1 + Math.max(0, p.visits)) * 40;
    if (p.lastVisit > 0) {
      double days = Math.max(0, (now - p.lastVisit) / 86_400_000.0);
      frecency += 120 / (1 + days);
    }
    return match + frecency + (p.bookmark ? 250 : 0);
  }

  private static boolean wordStart(String text, String q) {
    int i = text.indexOf(q);
    while (i >= 0) {
      if (i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1))) return true;
      i = text.indexOf(q, i + 1);
    }
    return false;
  }
}

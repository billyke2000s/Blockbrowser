package com.example.blockbrowser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Search-engine suggestions for the address bar. Only used when the user turns them on; never in private windows.
 * What was typed is sent to the selected search engine. The endpoints are informal APIs and can change: CI checks
 * them with scripts/check-suggest-endpoints.sh.
 */
final class SearchSuggestions {
  private SearchSuggestions() {}

  /** Suggestion endpoint for each entry of MainActivity.ENGINE_NAMES, or null where the engine has none. */
  static final String[] ENDPOINTS = {
      "https://suggestqueries.google.com/complete/search?client=firefox&q=", // Google
      "https://api.bing.com/osjson.aspx?query=",                             // Bing
      "https://duckduckgo.com/ac/?type=list&q=",                             // DuckDuckGo
      "https://search.brave.com/api/suggest?q=",                             // Brave Search
      null,                                                                  // Startpage
      "https://ac.ecosia.org/autocomplete?type=list&q=",                     // Ecosia
      "https://search.yahoo.com/sugg/os?output=fxjson&command=",             // Yahoo
      "https://api.qwant.com/v3/suggest?q=",                                 // Qwant
      null,                                                                  // Mojeek
      "https://searx.be/autocompleter?q=",                                   // SearXNG (searx.be)
  };

  static boolean supported(int engine) { return engine >= 0 && engine < ENDPOINTS.length && ENDPOINTS[engine] != null; }

  /** Fetches suggestions; returns an empty list on any error or after the timeout. Call off the UI thread. */
  static List<String> fetch(int engine, String query, int timeoutMs) {
    if (!supported(engine) || query == null || query.trim().isEmpty()) return Collections.emptyList();
    HttpURLConnection c = null;
    try {
      c = (HttpURLConnection) new URL(ENDPOINTS[engine] + UrlTools.encode(query.trim())).openConnection();
      c.setConnectTimeout(timeoutMs);
      c.setReadTimeout(timeoutMs);
      c.setInstanceFollowRedirects(true);
      c.setRequestProperty("Accept", "application/json, application/x-suggestions+json");
      c.setUseCaches(false);
      if (c.getResponseCode() != 200) return Collections.emptyList();
      try (InputStream in = c.getInputStream()) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n, total = 0;
        while ((n = in.read(buf)) > 0 && total < 256 * 1024) { out.write(buf, 0, n); total += n; }
        return parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
      }
    } catch (Exception e) {
      return Collections.emptyList();
    } finally {
      if (c != null) c.disconnect();
    }
  }

  /**
   * Understands the common response shapes: OpenSearch ["query", ["a", "b"]], a list of {"phrase": "a"} objects
   * (DuckDuckGo without type=list), and Qwant's {"data": {"items": [{"value": "a"}]}}.
   */
  static List<String> parse(String body) {
    List<String> out = new ArrayList<>();
    if (body == null) return out;
    String text = body.trim();
    try {
      if (text.startsWith("[")) {
        JSONArray root = new JSONArray(text);
        if (root.length() >= 2 && root.opt(1) instanceof JSONArray) {
          JSONArray list = root.getJSONArray(1);
          for (int i = 0; i < list.length(); i++) addString(out, list.opt(i));
        } else {
          for (int i = 0; i < root.length(); i++) {
            Object item = root.opt(i);
            if (item instanceof JSONObject) addString(out, ((JSONObject) item).opt("phrase"));
          }
        }
      } else if (text.startsWith("{")) {
        JSONObject root = new JSONObject(text);
        JSONObject data = root.optJSONObject("data");
        JSONArray items = data == null ? null : data.optJSONArray("items");
        if (items != null) for (int i = 0; i < items.length(); i++) {
          JSONObject item = items.optJSONObject(i);
          if (item != null) addString(out, item.opt("value"));
        }
      }
    } catch (Exception ignored) {
      // Unexpected format: show no search suggestions rather than fail.
    }
    return out.size() > 8 ? new ArrayList<>(out.subList(0, 8)) : out;
  }

  private static void addString(List<String> out, Object value) {
    if (value instanceof String) {
      String s = ((String) value).trim();
      if (!s.isEmpty() && s.length() <= 200) out.add(s);
    }
  }
}

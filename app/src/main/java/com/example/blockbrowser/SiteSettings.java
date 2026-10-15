package com.example.blockbrowser;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Per-site choices: remembered website permissions (by origin) and sites where ad blocking is off (by host).
 *
 * Normal windows save them in the browser preferences. Private windows keep them in memory only; they start with
 * the normal "Block" decisions and ad-block exceptions (never normal "Allow" decisions), like Chrome's incognito.
 */
final class SiteSettings {
  static final String CAMERA = "camera", MICROPHONE = "microphone", LOCATION = "location", PROTECTED_MEDIA = "protected_media";
  static final String[] KINDS = {CAMERA, MICROPHONE, LOCATION, PROTECTED_MEDIA};
  static final int ASK = 0, ALLOW = 1, BLOCK = 2;

  private final SharedPreferences prefs; // null = memory only
  private final Map<String, Map<String, Integer>> permissions = new HashMap<>();
  private final Set<String> adblockOff = new HashSet<>();

  SiteSettings(SharedPreferences prefs) {
    this.prefs = prefs;
    if (prefs != null) load(prefs, false);
  }

  /** Memory-only settings for a private window, seeded from normal settings as described above. */
  static SiteSettings forPrivate(SharedPreferences normal) {
    SiteSettings s = new SiteSettings(null);
    s.load(normal, true);
    return s;
  }

  private void load(SharedPreferences source, boolean blocksOnly) {
    try {
      JSONObject root = new JSONObject(source.getString("site_permissions", "{}"));
      for (Iterator<String> it = root.keys(); it.hasNext(); ) {
        String origin = it.next();
        JSONObject kinds = root.optJSONObject(origin);
        if (kinds == null) continue;
        for (String kind : KINDS) {
          int v = kinds.optInt(kind, ASK);
          if (v == BLOCK || (v == ALLOW && !blocksOnly)) put(origin, kind, v);
        }
      }
      JSONArray hosts = new JSONArray(source.getString("adblock_off_sites", "[]"));
      for (int i = 0; i < hosts.length(); i++) adblockOff.add(hosts.optString(i));
    } catch (Exception ignored) {}
  }

  private void put(String origin, String kind, int value) {
    Map<String, Integer> m = permissions.get(origin);
    if (m == null) { m = new HashMap<>(); permissions.put(origin, m); }
    if (value == ASK) m.remove(kind); else m.put(kind, value);
    if (m.isEmpty()) permissions.remove(origin);
  }

  synchronized int get(String origin, String kind) {
    Map<String, Integer> m = permissions.get(origin);
    Integer v = m == null ? null : m.get(kind);
    return v == null ? ASK : v;
  }

  synchronized void set(String origin, String kind, int value) {
    if (origin == null || origin.isEmpty()) return;
    put(origin, kind, value);
    save();
  }

  synchronized void clearOrigin(String origin) {
    permissions.remove(origin);
    save();
  }

  synchronized List<String> originsWithPermissions() {
    List<String> out = new ArrayList<>(permissions.keySet());
    Collections.sort(out);
    return out;
  }

  /** Thread-safe: called from WebView's request threads. */
  synchronized boolean adblockDisabledFor(String host) {
    if (host == null || adblockOff.isEmpty()) return false;
    String h = host;
    while (true) {
      if (adblockOff.contains(h)) return true;
      int dot = h.indexOf('.');
      if (dot < 0 || dot == h.length() - 1) return false;
      h = h.substring(dot + 1);
    }
  }

  synchronized void setAdblockDisabled(String host, boolean off) {
    if (host == null || host.isEmpty()) return;
    if (off) adblockOff.add(host); else adblockOff.remove(host);
    save();
  }

  synchronized List<String> adblockOffSites() { return new ArrayList<>(new TreeSet<>(adblockOff)); }

  private void save() {
    if (prefs == null) return;
    JSONObject root = new JSONObject();
    try {
      for (Map.Entry<String, Map<String, Integer>> e : permissions.entrySet()) {
        JSONObject kinds = new JSONObject();
        for (Map.Entry<String, Integer> k : e.getValue().entrySet()) kinds.put(k.getKey(), k.getValue().intValue());
        root.put(e.getKey(), kinds);
      }
    } catch (Exception ignored) {}
    prefs.edit().putString("site_permissions", root.toString()).putString("adblock_off_sites", new JSONArray(adblockOff).toString()).apply();
  }

  static String label(String kind) {
    switch (kind) {
      case CAMERA: return "Camera";
      case MICROPHONE: return "Microphone";
      case LOCATION: return "Location";
      case PROTECTED_MEDIA: return "Protected content (DRM)";
      default: return kind;
    }
  }

  static String valueLabel(int v) { return v == ALLOW ? "Allowed" : v == BLOCK ? "Blocked" : "Ask"; }
}

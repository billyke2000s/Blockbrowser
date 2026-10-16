package com.example.blockbrowser;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parser for HLS (.m3u8) playlists, as described in RFC 8216. Handles both kinds:
 * a master playlist (the list of qualities, plus separate audio tracks) and a media playlist (the list of pieces).
 * No Android dependencies, so it can be unit-tested on the JVM.
 */
final class HlsPlaylist {

  static final class Variant {
    String uri, codecs, audioGroup;
    long bandwidth;
    int width, height;

    /** "1080p · 5.2 Mbps", "Audio only · 128 kbps" or "2.1 Mbps". */
    String label() {
      String rate = bandwidth >= 1_000_000 ? String.format(Locale.ROOT, "%.1f Mbps", bandwidth / 1e6)
          : bandwidth > 0 ? (bandwidth / 1000) + " kbps" : "";
      String what = height > 0 ? height + "p" : isAudioOnly() ? "Audio only" : "";
      if (what.isEmpty()) return rate.isEmpty() ? "Default quality" : rate;
      return rate.isEmpty() ? what : what + " · " + rate;
    }

    boolean isAudioOnly() {
      if (height > 0 || codecs == null) return false;
      String c = codecs.toLowerCase(Locale.ROOT);
      return !(c.contains("avc") || c.contains("hvc") || c.contains("hev") || c.contains("vp0") || c.contains("vp9") || c.contains("av01") || c.contains("dvh"));
    }
  }

  static final class Rendition {
    String type, groupId, name, language, uri;
    boolean isDefault;
  }

  static final class Segment {
    String uri;
    double duration;
    long offset = -1, length = -1; // byte range within uri, when the playlist gives one
  }

  // Master playlist
  boolean isMaster;
  final List<Variant> variants = new ArrayList<>();
  final List<Rendition> renditions = new ArrayList<>();
  // Media playlist
  final List<Segment> segments = new ArrayList<>();
  Segment init;            // EXT-X-MAP: header every fragmented-MP4 piece depends on
  boolean ended;           // EXT-X-ENDLIST present: the video is complete (not a live stream)
  /** Encryption method if any piece (or, in a master playlist, the session) is encrypted, else null. */
  String encryption;

  double duration() {
    double d = 0;
    for (Segment s : segments) d += s.duration;
    return d;
  }

  /** Variants from best to worst quality, keeping the highest-bitrate one of each resolution. */
  List<Variant> qualities() {
    List<Variant> sorted = new ArrayList<>(variants);
    Collections.sort(sorted, (a, b) -> {
      if (a.isAudioOnly() != b.isAudioOnly()) return a.isAudioOnly() ? 1 : -1;
      if (a.height != b.height) return Integer.compare(b.height, a.height);
      return Long.compare(b.bandwidth, a.bandwidth);
    });
    List<Variant> out = new ArrayList<>();
    for (Variant v : sorted) {
      boolean dup = false;
      for (Variant o : out) if (o.height == v.height && v.height > 0 && o.isAudioOnly() == v.isAudioOnly()) { dup = true; break; }
      if (!dup) out.add(v);
    }
    return out;
  }

  /** The separate audio playlist a variant needs, or null when its audio is inside the video pieces. */
  Rendition audioFor(Variant v) {
    if (v.audioGroup == null) return null;
    Rendition first = null;
    for (Rendition r : renditions) {
      if (!"AUDIO".equals(r.type) || !v.audioGroup.equals(r.groupId) || r.uri == null) continue;
      if (r.isDefault) return r;
      if (first == null) first = r;
    }
    return first;
  }

  static HlsPlaylist parse(String text, String baseUrl) throws IOException {
    if (text == null) throw new IOException("empty playlist");
    String body = text.startsWith("﻿") ? text.substring(1) : text;
    String[] lines = body.split("\r\n|\r|\n");
    int first = 0;
    while (first < lines.length && lines[first].trim().isEmpty()) first++;
    if (first >= lines.length || !lines[first].trim().startsWith("#EXTM3U")) throw new IOException("not an HLS playlist");

    HlsPlaylist p = new HlsPlaylist();
    Variant pendingVariant = null;
    double pendingDuration = 0;
    long pendingLength = -1, pendingOffset = -1;
    long nextOffset = 0;
    String lastRangeUri = null;
    for (int i = first + 1; i < lines.length; i++) {
      String line = lines[i].trim();
      if (line.isEmpty()) continue;
      if (line.startsWith("#")) {
        if (line.startsWith("#EXT-X-STREAM-INF:")) {
          p.isMaster = true;
          Map<String, String> a = attributes(line.substring(18));
          pendingVariant = new Variant();
          pendingVariant.bandwidth = parseLong(a.get("BANDWIDTH"), parseLong(a.get("AVERAGE-BANDWIDTH"), 0));
          pendingVariant.codecs = a.get("CODECS");
          pendingVariant.audioGroup = a.get("AUDIO");
          String res = a.get("RESOLUTION");
          if (res != null) {
            int x = res.toLowerCase(Locale.ROOT).indexOf('x');
            if (x > 0) { pendingVariant.width = (int) parseLong(res.substring(0, x), 0); pendingVariant.height = (int) parseLong(res.substring(x + 1), 0); }
          }
        } else if (line.startsWith("#EXT-X-MEDIA:")) {
          p.isMaster = true;
          Map<String, String> a = attributes(line.substring(13));
          Rendition r = new Rendition();
          r.type = a.get("TYPE");
          r.groupId = a.get("GROUP-ID");
          r.name = a.get("NAME");
          r.language = a.get("LANGUAGE");
          r.uri = a.containsKey("URI") ? resolve(baseUrl, a.get("URI")) : null;
          r.isDefault = "YES".equalsIgnoreCase(a.get("DEFAULT"));
          p.renditions.add(r);
        } else if (line.startsWith("#EXT-X-SESSION-KEY:")) {
          String method = attributes(line.substring(19)).get("METHOD");
          if (method != null && !method.equalsIgnoreCase("NONE")) p.encryption = method;
        } else if (line.startsWith("#EXT-X-KEY:")) {
          String method = attributes(line.substring(11)).get("METHOD");
          if (method != null && !method.equalsIgnoreCase("NONE")) p.encryption = method;
        } else if (line.startsWith("#EXTINF:")) {
          String v = line.substring(8);
          int comma = v.indexOf(',');
          pendingDuration = parseDouble(comma >= 0 ? v.substring(0, comma) : v);
        } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
          long[] r = byteRange(line.substring(17));
          pendingLength = r[0];
          pendingOffset = r[1];
        } else if (line.startsWith("#EXT-X-MAP:")) {
          Map<String, String> a = attributes(line.substring(11));
          if (a.get("URI") != null) {
            Segment s = new Segment();
            s.uri = resolve(baseUrl, a.get("URI"));
            if (a.get("BYTERANGE") != null) {
              long[] r = byteRange(a.get("BYTERANGE"));
              s.length = r[0];
              s.offset = r[1] < 0 ? 0 : r[1];
            }
            // A playlist may switch headers mid-way (e.g. around an inserted clip); the first one is kept.
            if (p.init == null) p.init = s;
          }
        } else if (line.startsWith("#EXT-X-ENDLIST")) {
          p.ended = true;
        }
        continue;
      }
      // A URI line.
      String uri = resolve(baseUrl, line);
      if (pendingVariant != null) {
        pendingVariant.uri = uri;
        p.variants.add(pendingVariant);
        pendingVariant = null;
      } else {
        Segment s = new Segment();
        s.uri = uri;
        s.duration = pendingDuration;
        if (pendingLength >= 0) {
          s.length = pendingLength;
          s.offset = pendingOffset >= 0 ? pendingOffset : (uri.equals(lastRangeUri) ? nextOffset : 0);
          nextOffset = s.offset + s.length;
          lastRangeUri = uri;
        }
        p.segments.add(s);
        pendingDuration = 0;
        pendingLength = -1;
        pendingOffset = -1;
      }
    }
    if (p.isMaster && p.variants.isEmpty()) throw new IOException("playlist lists no video");
    return p;
  }

  /** "length[@offset]" -> {length, offset or -1}. */
  static long[] byteRange(String v) {
    String s = v.trim();
    int at = s.indexOf('@');
    long length = parseLong(at >= 0 ? s.substring(0, at) : s, -1);
    long offset = at >= 0 ? parseLong(s.substring(at + 1), -1) : -1;
    return new long[]{length, offset};
  }

  /** Parses an attribute list such as {@code BANDWIDTH=800000,CODECS="avc1.4d401f,mp4a.40.2",RESOLUTION=640x360}. */
  static Map<String, String> attributes(String s) {
    Map<String, String> out = new LinkedHashMap<>();
    int i = 0, n = s.length();
    while (i < n) {
      int eq = s.indexOf('=', i);
      if (eq < 0) break;
      String key = s.substring(i, eq).trim().toUpperCase(Locale.ROOT);
      int j = eq + 1;
      String value;
      if (j < n && s.charAt(j) == '"') {
        int close = s.indexOf('"', j + 1);
        if (close < 0) close = n;
        value = s.substring(j + 1, close);
        j = close + 1;
        int comma = s.indexOf(',', j);
        j = comma < 0 ? n : comma + 1;
      } else {
        int comma = s.indexOf(',', j);
        value = s.substring(j, comma < 0 ? n : comma).trim();
        j = comma < 0 ? n : comma + 1;
      }
      out.put(key, value);
      i = j;
    }
    return out;
  }

  /** Resolves a playlist entry against the playlist's own address. */
  static String resolve(String base, String ref) {
    String r = ref.trim();
    if (UrlTools.isWebUrl(r)) return r;
    try {
      return URI.create(escapeIllegal(base)).resolve(escapeIllegal(r)).toString();
    } catch (Exception e) {
      return r;
    }
  }

  /** Percent-encodes characters servers use in addresses but java.net.URI rejects (spaces, |, ^, braces...). */
  static String escapeIllegal(String s) {
    StringBuilder b = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c <= ' ' || c == '|' || c == '^' || c == '{' || c == '}' || c == '`' || c == '\\' || c == '"' || c == '<' || c == '>' || c >= 0x7f) {
        byte[] bytes = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (Character.isHighSurrogate(c) && i + 1 < s.length()) { bytes = s.substring(i, i + 2).getBytes(java.nio.charset.StandardCharsets.UTF_8); i++; }
        for (byte x : bytes) b.append('%').append(String.format(Locale.ROOT, "%02X", x & 0xff));
      } else {
        b.append(c);
      }
    }
    return b.toString();
  }

  private static long parseLong(String s, long fallback) {
    if (s == null) return fallback;
    try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return fallback; }
  }

  private static double parseDouble(String s) {
    try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return 0; }
  }
}

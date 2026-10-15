package com.example.blockbrowser;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** URL helpers with no Android dependencies, so they can be unit-tested on the JVM. */
final class UrlTools {
  private UrlTools() {}

  /** Query parameters that only exist to track people across sites. Removed from page addresses when enabled. */
  static final Set<String> TRACKING_PARAMS = new HashSet<>(Arrays.asList(
      "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id", "utm_name",
      "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "mc_eid", "igshid", "yclid",
      "_hsenc", "_hsmi", "mkt_tok", "twclid", "ttclid", "li_fat_id", "oly_enc_id", "oly_anon_id", "vero_id", "rb_clickid"));

  static boolean isWebUrl(String url) {
    if (url == null) return false;
    String lower = url.toLowerCase(Locale.ROOT);
    return lower.startsWith("https://") || lower.startsWith("http://");
  }

  /**
   * Turns what was typed into the address bar into something to load: a web address, or a search.
   * Anything with a non-web scheme (javascript:, file:, content:, intent:, data:...) is searched for, never opened.
   */
  static String toNavigableUrl(String input, String searchPrefix) {
    if (input == null) return null;
    String text = input.trim();
    if (text.isEmpty()) return null;
    if (isWebUrl(text)) return text;
    if (text.equalsIgnoreCase("about:blank")) return "about:blank";
    boolean hasSpace = text.indexOf(' ') >= 0;
    if (!hasSpace && looksLikeHost(text)) {
      String host = hostPart(text);
      boolean local = host.equals("localhost") || host.endsWith(".localhost") || isIpv4(host);
      return (local ? "http://" : "https://") + text;
    }
    return searchPrefix + encode(text);
  }

  private static boolean looksLikeHost(String text) {
    if (hasScheme(text)) return false;
    String host = hostPart(text);
    if (host.isEmpty()) return false;
    if (host.equals("localhost") || isIpv4(host)) return true;
    int dot = host.lastIndexOf('.');
    if (dot <= 0 || dot == host.length() - 1) return false;
    String tld = host.substring(dot + 1);
    for (int i = 0; i < tld.length(); i++) if (!Character.isLetter(tld.charAt(i))) return false;
    for (int i = 0; i < host.length(); i++) {
      char c = host.charAt(i);
      if (!(Character.isLetterOrDigit(c) || c == '-' || c == '.')) return false;
    }
    return true;
  }

  /** "javascript:", "file:", "mailto:" and so on (but not "example.com:8080"). */
  static boolean hasScheme(String text) {
    int colon = text.indexOf(':');
    if (colon <= 0) return false;
    String scheme = text.substring(0, colon);
    for (int i = 0; i < scheme.length(); i++) {
      char c = scheme.charAt(i);
      if (!(Character.isLetter(c) || (i > 0 && (Character.isDigit(c) || c == '+' || c == '-' || c == '.')))) return false;
    }
    // "localhost:8080" or "example.com:443/path": what follows the colon is a port number.
    String rest = text.substring(colon + 1);
    int end = 0;
    while (end < rest.length() && Character.isDigit(rest.charAt(end))) end++;
    boolean port = end > 0 && (end == rest.length() || rest.charAt(end) == '/' || rest.charAt(end) == '?' || rest.charAt(end) == '#');
    return !port;
  }

  private static String hostPart(String text) {
    int end = text.length();
    for (char c : new char[]{'/', '?', '#'}) { int i = text.indexOf(c); if (i >= 0 && i < end) end = i; }
    String authority = text.substring(0, end);
    int at = authority.lastIndexOf('@');
    if (at >= 0) authority = authority.substring(at + 1);
    int colon = authority.lastIndexOf(':');
    if (colon >= 0) authority = authority.substring(0, colon);
    return authority.toLowerCase(Locale.ROOT);
  }

  static boolean isIpv4(String host) {
    String[] parts = host.split("\\.", -1);
    if (parts.length != 4) return false;
    for (String p : parts) {
      if (p.isEmpty() || p.length() > 3) return false;
      for (int i = 0; i < p.length(); i++) if (!Character.isDigit(p.charAt(i))) return false;
      if (Integer.parseInt(p) > 255) return false;
    }
    return true;
  }

  static String encode(String text) {
    try { return URLEncoder.encode(text, "UTF-8"); } catch (UnsupportedEncodingException e) { throw new AssertionError(e); }
  }

  /** Lower-case host of a web URL without port or user info, or "" if there is none. */
  static String host(String url) {
    if (url == null) return "";
    int start = url.indexOf("://");
    if (start < 0) return "";
    return hostPart(url.substring(start + 3));
  }

  /** scheme://host[:port] of a web URL, or "" if it isn't one. */
  static String origin(String url) {
    if (!isWebUrl(url)) return "";
    int start = url.indexOf("://") + 3;
    int end = url.length();
    for (char c : new char[]{'/', '?', '#'}) { int i = url.indexOf(c, start); if (i >= 0 && i < end) end = i; }
    String authority = url.substring(start, end);
    int at = authority.lastIndexOf('@');
    if (at >= 0) authority = authority.substring(at + 1);
    return url.substring(0, start).toLowerCase(Locale.ROOT) + authority.toLowerCase(Locale.ROOT);
  }

  /** Short form for display: no scheme, no "www.", no trailing slash. */
  static String display(String url) {
    if (url == null) return "";
    String s = url;
    int scheme = s.indexOf("://");
    if (scheme >= 0) s = s.substring(scheme + 3);
    if (s.startsWith("www.")) s = s.substring(4);
    if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
    return s;
  }

  /** Removes known tracking parameters from a web URL. Returns the same string if nothing was removed. */
  static String stripTrackingParams(String url) {
    if (!isWebUrl(url)) return url;
    int q = url.indexOf('?');
    if (q < 0) return url;
    int hash = url.indexOf('#', q);
    String query = hash < 0 ? url.substring(q + 1) : url.substring(q + 1, hash);
    String fragment = hash < 0 ? "" : url.substring(hash);
    StringBuilder kept = new StringBuilder();
    boolean removed = false;
    for (String pair : query.split("&", -1)) {
      if (pair.isEmpty()) continue;
      int eq = pair.indexOf('=');
      String key = (eq < 0 ? pair : pair.substring(0, eq)).toLowerCase(Locale.ROOT);
      if (TRACKING_PARAMS.contains(key)) { removed = true; continue; }
      if (kept.length() > 0) kept.append('&');
      kept.append(pair);
    }
    if (!removed) return url;
    return url.substring(0, q) + (kept.length() > 0 ? "?" + kept : "") + fragment;
  }
}

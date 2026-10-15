package com.example.blockbrowser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bookmark import and export, with no Android dependencies so it can be unit-tested.
 * Supports Block Browser JSON (old exports included) and the Netscape bookmark HTML format used by Chrome,
 * Firefox, Edge and Safari. Only http/https bookmarks are accepted; everything else is skipped.
 */
final class BookmarkIO {
  static final int MAX_IMPORT = 20000, MAX_TITLE = 500, MAX_URL = 4096, MAX_FOLDER = 300;

  static final class Bookmark {
    final String url, title, folder;
    Bookmark(String url, String title, String folder) {
      this.url = url;
      this.title = title == null ? "" : title;
      this.folder = cleanFolder(folder);
    }
  }

  /** Result of parsing a file: what was usable, and how many entries were rejected. */
  static final class Parsed {
    final List<Bookmark> bookmarks = new ArrayList<>();
    int rejected;
  }

  private BookmarkIO() {}

  /** Folder paths use "/" between levels; empty means the top level. */
  static String cleanFolder(String folder) {
    if (folder == null) return "";
    StringBuilder out = new StringBuilder();
    for (String part : folder.split("/")) {
      String p = part.trim();
      if (p.isEmpty()) continue;
      if (out.length() > 0) out.append('/');
      out.append(p);
    }
    String s = out.toString();
    return s.length() > MAX_FOLDER ? s.substring(0, MAX_FOLDER) : s;
  }

  static boolean acceptableUrl(String url) {
    return url != null && url.length() <= MAX_URL && UrlTools.isWebUrl(url) && !UrlTools.host(url).isEmpty()
        && url.indexOf(' ') < 0 && url.indexOf('\n') < 0;
  }

  private static String cleanTitle(String title, String url) {
    String t = title == null ? "" : title.replace('\n', ' ').replace('\r', ' ').trim();
    if (t.isEmpty()) t = UrlTools.display(url);
    return t.length() > MAX_TITLE ? t.substring(0, MAX_TITLE) : t;
  }

  /** Detects the format from the content. Anything unreadable gives an empty result. */
  static Parsed parse(String content) {
    if (content == null) return new Parsed();
    String t = content.trim();
    if (t.startsWith("[") || t.startsWith("{")) return parseJson(t);
    return parseNetscapeHtml(content);
  }

  static Parsed parseJson(String json) {
    Parsed result = new Parsed();
    try {
      JSONArray items;
      String t = json.trim();
      if (t.startsWith("{")) {
        JSONObject root = new JSONObject(t);
        items = root.optJSONArray("bookmarks");
        if (items == null) return result;
      } else items = new JSONArray(t);
      for (int i = 0; i < items.length(); i++) {
        if (result.bookmarks.size() >= MAX_IMPORT) { result.rejected += items.length() - i; break; }
        JSONObject item = items.optJSONObject(i);
        String url = item == null ? null : item.optString("url", null);
        if (!acceptableUrl(url)) { result.rejected++; continue; }
        result.bookmarks.add(new Bookmark(url, cleanTitle(item.optString("title", ""), url), item.optString("folder", "")));
      }
    } catch (Exception e) {
      result.rejected++;
    }
    return result;
  }

  private static final Pattern TAG = Pattern.compile(
      "<\\s*(/?)\\s*(dl|h3|a)\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
  private static final Pattern HREF = Pattern.compile("\\bhref\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", Pattern.CASE_INSENSITIVE);

  /** Netscape bookmark file: nested &lt;DL&gt; lists, &lt;H3&gt; folder names and &lt;A HREF&gt; links. */
  static Parsed parseNetscapeHtml(String html) {
    Parsed result = new Parsed();
    List<String> path = new ArrayList<>();
    String pendingFolder = null;
    Matcher m = TAG.matcher(html);
    while (m.find()) {
      boolean closing = !m.group(1).isEmpty();
      String tag = m.group(2).toLowerCase(Locale.ROOT);
      if (tag.equals("dl")) {
        if (!closing) { if (pendingFolder != null) { path.add(pendingFolder); pendingFolder = null; } else path.add(null); }
        else if (!path.isEmpty()) path.remove(path.size() - 1);
      } else if (tag.equals("h3") && !closing) {
        int end = indexOfIgnoreCase(html, "</h3", m.end());
        if (end < 0) continue;
        pendingFolder = decode(stripTags(html.substring(m.end(), end))).trim().replace('/', '-');
      } else if (tag.equals("a") && !closing) {
        if (result.bookmarks.size() >= MAX_IMPORT) { result.rejected++; continue; }
        Matcher h = HREF.matcher(m.group(3));
        String url = null;
        if (h.find()) url = decode(h.group(2) != null ? h.group(2) : h.group(3) != null ? h.group(3) : h.group(4)).trim();
        int end = indexOfIgnoreCase(html, "</a", m.end());
        String title = end < 0 ? "" : decode(stripTags(html.substring(m.end(), end)));
        if (!acceptableUrl(url)) { result.rejected++; continue; }
        result.bookmarks.add(new Bookmark(url, cleanTitle(title, url), folderPath(path)));
      }
    }
    return result;
  }

  private static String folderPath(List<String> path) {
    StringBuilder out = new StringBuilder();
    // The outermost list is the file's root; folders such as "Bookmarks bar" stay as top-level folders.
    for (String p : path) {
      if (p == null || p.isEmpty()) continue;
      if (out.length() > 0) out.append('/');
      out.append(p);
    }
    return out.toString();
  }

  private static int indexOfIgnoreCase(String s, String what, int from) {
    return s.toLowerCase(Locale.ROOT).indexOf(what, from);
  }

  private static String stripTags(String s) { return s.replaceAll("<[^>]*>", ""); }

  static String decode(String s) {
    if (s == null) return null;
    if (s.indexOf('&') < 0) return s;
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < s.length()) {
      char c = s.charAt(i);
      int semi = c == '&' ? s.indexOf(';', i) : -1;
      if (semi > i && semi - i <= 10) {
        String entity = s.substring(i + 1, semi);
        String rep = null;
        switch (entity.toLowerCase(Locale.ROOT)) {
          case "amp": rep = "&"; break;
          case "lt": rep = "<"; break;
          case "gt": rep = ">"; break;
          case "quot": rep = "\""; break;
          case "apos": rep = "'"; break;
          case "nbsp": rep = " "; break;
          default:
            try {
              if (entity.startsWith("#x") || entity.startsWith("#X")) rep = new String(Character.toChars(Integer.parseInt(entity.substring(2), 16)));
              else if (entity.startsWith("#")) rep = new String(Character.toChars(Integer.parseInt(entity.substring(1))));
            } catch (Exception ignored) { rep = null; }
        }
        if (rep != null) { out.append(rep); i = semi + 1; continue; }
      }
      out.append(c);
      i++;
    }
    return out.toString();
  }

  static String escape(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
  }

  /** Netscape bookmark HTML, importable by Chrome, Firefox, Edge and Safari. */
  static String toNetscapeHtml(List<Bookmark> bookmarks) {
    // Folder tree in insertion order.
    Node root = new Node();
    for (Bookmark b : bookmarks) {
      Node n = root;
      if (!b.folder.isEmpty()) for (String part : b.folder.split("/")) n = n.child(part);
      n.links.add(b);
    }
    StringBuilder out = new StringBuilder();
    out.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
       .append("<!-- This is an automatically generated file. Exported by Block Browser. -->\n")
       .append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
       .append("<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n");
    write(root, out, "");
    return out.toString();
  }

  private static final class Node {
    final Map<String, Node> folders = new LinkedHashMap<>();
    final List<Bookmark> links = new ArrayList<>();
    Node child(String name) { Node n = folders.get(name); if (n == null) { n = new Node(); folders.put(name, n); } return n; }
  }

  private static void write(Node node, StringBuilder out, String indent) {
    out.append(indent).append("<DL><p>\n");
    for (Map.Entry<String, Node> f : node.folders.entrySet()) {
      out.append(indent).append("    <DT><H3>").append(escape(f.getKey())).append("</H3>\n");
      write(f.getValue(), out, indent + "    ");
    }
    for (Bookmark b : node.links) {
      out.append(indent).append("    <DT><A HREF=\"").append(escape(b.url)).append("\">").append(escape(b.title)).append("</A>\n");
    }
    out.append(indent).append("</DL><p>\n");
  }

  static String toJson(List<Bookmark> bookmarks) {
    JSONArray out = new JSONArray();
    for (Bookmark b : bookmarks) {
      try {
        JSONObject o = new JSONObject();
        o.put("url", b.url); o.put("title", b.title);
        if (!b.folder.isEmpty()) o.put("folder", b.folder);
        out.put(o);
      } catch (Exception ignored) {}
    }
    return out.toString();
  }

  /**
   * Adds incoming bookmarks whose URL is not already saved (in any folder). Duplicates inside the file are
   * skipped too. Returns the merged list; {@code added[0]} receives the number added.
   */
  static List<Bookmark> merge(List<Bookmark> existing, List<Bookmark> incoming, int[] added) {
    List<Bookmark> out = new ArrayList<>(existing);
    Set<String> urls = new HashSet<>();
    for (Bookmark b : existing) urls.add(b.url);
    int n = 0;
    for (Bookmark b : incoming) if (urls.add(b.url)) { out.add(b); n++; }
    if (added != null && added.length > 0) added[0] = n;
    return out;
  }
}

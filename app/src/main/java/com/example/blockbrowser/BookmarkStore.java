package com.example.blockbrowser;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Bookmarks and bookmark folders, stored as JSON in the browser preferences ("bookmarks", "bookmark_folders").
 * Existing bookmarks without a folder stay at the top level. In private windows the preferences object is
 * read-only, so saving shows a message and changes nothing.
 */
final class BookmarkStore {
  private final SharedPreferences prefs;

  BookmarkStore(SharedPreferences prefs) { this.prefs = prefs; }

  List<BookmarkIO.Bookmark> all() {
    List<BookmarkIO.Bookmark> out = new ArrayList<>();
    try {
      JSONArray a = new JSONArray(prefs.getString("bookmarks", "[]"));
      for (int i = 0; i < a.length(); i++) {
        JSONObject o = a.optJSONObject(i);
        if (o == null) continue;
        String url = o.optString("url");
        if (!UrlTools.isWebUrl(url)) continue;
        out.add(new BookmarkIO.Bookmark(url, o.optString("title", url), o.optString("folder", "")));
      }
    } catch (Exception ignored) {}
    return out;
  }

  /** Returns false when the store is read-only (private window) and nothing was saved. */
  boolean save(List<BookmarkIO.Bookmark> bookmarks, Iterable<String> extraFolders) {
    JSONArray a = new JSONArray();
    TreeSet<String> folders = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    for (BookmarkIO.Bookmark b : bookmarks) {
      try {
        JSONObject o = new JSONObject();
        o.put("url", b.url); o.put("title", b.title);
        if (!b.folder.isEmpty()) { o.put("folder", b.folder); addWithParents(folders, b.folder); }
        a.put(o);
      } catch (Exception ignored) {}
    }
    for (String f : extraFolders) addWithParents(folders, BookmarkIO.cleanFolder(f));
    folders.remove("");
    if (prefs instanceof ReadOnlyPreferences) { prefs.edit().apply(); return false; }
    prefs.edit().putString("bookmarks", a.toString()).putString("bookmark_folders", new JSONArray(folders).toString()).apply();
    return true;
  }

  boolean save(List<BookmarkIO.Bookmark> bookmarks) { return save(bookmarks, folders()); }

  private static void addWithParents(TreeSet<String> folders, String folder) {
    String f = BookmarkIO.cleanFolder(folder);
    while (!f.isEmpty()) {
      folders.add(f);
      int slash = f.lastIndexOf('/');
      f = slash < 0 ? "" : f.substring(0, slash);
    }
  }

  /** All folders, including empty ones the user created. */
  List<String> folders() {
    TreeSet<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    try {
      JSONArray a = new JSONArray(prefs.getString("bookmark_folders", "[]"));
      for (int i = 0; i < a.length(); i++) addWithParents(out, a.optString(i));
    } catch (Exception ignored) {}
    for (BookmarkIO.Bookmark b : all()) addWithParents(out, b.folder);
    out.remove("");
    return new ArrayList<>(out);
  }

  /** Direct sub-folders of {@code parent} ("" = top level), sorted. */
  List<String> subfolders(String parent) {
    List<String> out = new ArrayList<>();
    String prefix = parent.isEmpty() ? "" : parent + "/";
    for (String f : folders()) {
      if (!f.startsWith(prefix) || f.length() == prefix.length()) continue;
      String rest = f.substring(prefix.length());
      if (rest.indexOf('/') < 0) out.add(f);
    }
    Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
    return out;
  }

  int indexOf(List<BookmarkIO.Bookmark> list, String url) {
    for (int i = 0; i < list.size(); i++) if (list.get(i).url.equals(url)) return i;
    return -1;
  }

  boolean contains(String url) { return indexOf(all(), url) >= 0; }

  boolean add(String url, String title, String folder) {
    List<BookmarkIO.Bookmark> list = all();
    if (indexOf(list, url) >= 0) return true;
    list.add(new BookmarkIO.Bookmark(url, title, folder));
    return save(list);
  }

  boolean remove(String url) {
    List<BookmarkIO.Bookmark> list = all();
    int i = indexOf(list, url);
    if (i < 0) return true;
    list.remove(i);
    return save(list);
  }

  boolean update(String oldUrl, String url, String title, String folder) {
    List<BookmarkIO.Bookmark> list = all();
    int i = indexOf(list, oldUrl);
    if (i < 0) return false;
    int clash = indexOf(list, url);
    if (clash >= 0 && clash != i) list.remove(clash > i ? clash : i);
    i = indexOf(list, oldUrl);
    if (i < 0) i = indexOf(list, url);
    list.set(i, new BookmarkIO.Bookmark(url, title, folder));
    return save(list);
  }

  boolean createFolder(String folder) {
    List<String> folders = folders();
    folders.add(BookmarkIO.cleanFolder(folder));
    return save(all(), folders);
  }

  /** Renames or moves a folder and everything inside it. */
  boolean renameFolder(String from, String to) {
    String target = BookmarkIO.cleanFolder(to);
    if (target.isEmpty() || target.equalsIgnoreCase(from) || target.toLowerCase(Locale.ROOT).startsWith(from.toLowerCase(Locale.ROOT) + "/")) return false;
    List<BookmarkIO.Bookmark> out = new ArrayList<>();
    for (BookmarkIO.Bookmark b : all()) out.add(new BookmarkIO.Bookmark(b.url, b.title, rebase(b.folder, from, target)));
    List<String> folders = new ArrayList<>();
    for (String f : folders()) folders.add(rebase(f, from, target));
    prefsRemoveFolders();
    return save(out, folders);
  }

  /** Deletes a folder and the bookmarks in it (including sub-folders). */
  boolean deleteFolder(String folder) {
    List<BookmarkIO.Bookmark> out = new ArrayList<>();
    for (BookmarkIO.Bookmark b : all()) if (!inFolder(b.folder, folder)) out.add(b);
    List<String> folders = new ArrayList<>();
    for (String f : folders()) if (!inFolder(f, folder)) folders.add(f);
    prefsRemoveFolders();
    return save(out, folders);
  }

  int countIn(String folder) {
    int n = 0;
    for (BookmarkIO.Bookmark b : all()) if (inFolder(b.folder, folder)) n++;
    return n;
  }

  private void prefsRemoveFolders() {
    if (!(prefs instanceof ReadOnlyPreferences)) prefs.edit().remove("bookmark_folders").commit();
  }

  static boolean inFolder(String path, String folder) {
    return path.equals(folder) || path.startsWith(folder + "/");
  }

  private static String rebase(String path, String from, String to) {
    if (path.equals(from)) return to;
    if (path.startsWith(from + "/")) return to + path.substring(from.length());
    return path;
  }
}

package com.example.blockbrowser;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.text.InputType;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bookmarks (with folders, import and export), history (by date and by site) and recently closed tabs. */
final class LibraryPanels {
  private LibraryPanels() {}

  static final int MAX_IMPORT_BYTES = 20 * 1024 * 1024;

  // ===================================================================================================
  // History
  // ===================================================================================================

  static void showHistory(MainActivity a) {
    ListPanel panel = new ListPanel(a, "History");
    panel.setEmptyText(a.isPrivate() ? "Pages visited in private windows are never added to history." : "No history yet.");
    Runnable[] load = new Runnable[1];
    load[0] = () -> {
      String q = panel.query();
      a.background.execute(() -> {
        List<HistoryStore.Entry> entries = a.history().recent(q, 1000);
        Map<String, Bitmap> icons = icons(a, entries);
        a.runOnUiThread(() -> panel.setItems(historyItems(a, entries, icons, load[0])));
      });
    };
    panel.withSearch("Search history", q -> load[0].run());
    panel.action("▤", "History by website", () -> showHistoryBySite(a));
    if (!a.isPrivate()) panel.action("⌫", "Delete history", () -> deleteHistoryDialog(a, load[0]));
    panel.show();
    load[0].run();
  }

  private static Map<String, Bitmap> icons(MainActivity a, List<HistoryStore.Entry> entries) {
    Map<String, Bitmap> icons = new HashMap<>();
    for (HistoryStore.Entry e : entries) if (!icons.containsKey(e.host)) icons.put(e.host, a.favicons().get(e.host));
    return icons;
  }

  static String dayLabel(MainActivity a, long time) {
    Calendar c = Calendar.getInstance();
    long startToday = startOfDay(System.currentTimeMillis());
    if (time >= startToday) return "Today";
    if (time >= startToday - DateUtils.DAY_IN_MILLIS) return "Yesterday";
    c.setTimeInMillis(time);
    return DateFormat.format("EEEE d MMMM yyyy", c).toString();
  }

  static long startOfDay(long time) {
    Calendar c = Calendar.getInstance();
    c.setTimeInMillis(time);
    c.set(Calendar.HOUR_OF_DAY, 0);
    c.set(Calendar.MINUTE, 0);
    c.set(Calendar.SECOND, 0);
    c.set(Calendar.MILLISECOND, 0);
    return c.getTimeInMillis();
  }

  private static List<ListPanel.Item> historyItems(MainActivity a, List<HistoryStore.Entry> entries, Map<String, Bitmap> icons, Runnable reload) {
    List<ListPanel.Item> items = new ArrayList<>();
    String lastDay = null;
    for (HistoryStore.Entry e : entries) {
      String day = dayLabel(a, e.lastVisit);
      if (!day.equals(lastDay)) {
        ListPanel.Item header = ListPanel.Item.header(day);
        long start = startOfDay(e.lastVisit);
        items.add(header);
        if (!a.isPrivate()) {
          ListPanel.Item clearDay = new ListPanel.Item("⌫", "Delete " + (day.equals("Today") || day.equals("Yesterday") ? day.toLowerCase(Locale.ROOT) + "'s" : "this day's") + " history", null, () ->
              confirm(a, "Delete history from " + day + "?", "Delete", () -> a.history().deleteRange(start, start + DateUtils.DAY_IN_MILLIS, n -> done(a, n, reload))));
          items.add(clearDay);
        }
        lastDay = day;
      }
      ListPanel.Item it = new ListPanel.Item();
      it.title = e.title == null || e.title.isEmpty() ? UrlTools.display(e.url) : e.title;
      it.subtitle = DateFormat.getTimeFormat(a).format(new java.util.Date(e.lastVisit)) + " · " + UrlTools.display(e.url);
      it.icon = icons.get(e.host);
      it.glyph = "◷";
      it.click = () -> { a.loadInCurrent(e.url); closePanels(a); };
      it.longClick = () -> pageActions(a, e.url, it.title.toString(), () -> a.history().deleteUrl(e.url, n -> done(a, n, reload)),
          () -> confirm(a, "Delete all history from " + e.host + "?", "Delete", () -> a.history().deleteHost(e.host, n -> done(a, n, reload))));
      items.add(it);
    }
    return items;
  }

  /** Called on the history writer thread after a deletion; -1 means read-only (private window). */
  private static void done(MainActivity a, int removed, Runnable reload) {
    a.runOnUiThread(() -> {
      if (removed < 0) a.toast("History can't be changed in a private window");
      else a.toast(removed == 1 ? "1 page deleted" : removed + " pages deleted");
      if (reload != null) reload.run();
    });
  }

  static void showHistoryBySite(MainActivity a) {
    ListPanel panel = new ListPanel(a, "History by website");
    panel.setEmptyText("No history yet.");
    Runnable[] load = new Runnable[1];
    load[0] = () -> a.background.execute(() -> {
      List<HistoryStore.Entry> entries = a.history().recent(panel.query(), HistoryStore.MAX_ENTRIES);
      LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
      for (HistoryStore.Entry e : entries) counts.put(e.host, counts.containsKey(e.host) ? counts.get(e.host) + 1 : 1);
      List<String> hosts = new ArrayList<>(counts.keySet());
      java.util.Collections.sort(hosts);
      Map<String, Bitmap> icons = new HashMap<>();
      for (String h : hosts) icons.put(h, a.favicons().get(h));
      a.runOnUiThread(() -> {
        List<ListPanel.Item> items = new ArrayList<>();
        for (String host : hosts) {
          ListPanel.Item it = new ListPanel.Item("◷", host, counts.get(host) == 1 ? "1 page" : counts.get(host) + " pages", () -> showSiteHistory(a, host, load[0]));
          it.icon = icons.get(host);
          if (!a.isPrivate()) it.longClick = () -> confirm(a, "Delete all history from " + host + "?", "Delete", () -> a.history().deleteHost(host, n -> done(a, n, load[0])));
          items.add(it);
        }
        panel.setItems(items);
      });
    });
    panel.withSearch("Search websites", q -> load[0].run());
    panel.show();
    load[0].run();
  }

  static void showSiteHistory(MainActivity a, String host, Runnable after) {
    ListPanel panel = new ListPanel(a, host);
    Runnable[] load = new Runnable[1];
    load[0] = () -> a.background.execute(() -> {
      List<HistoryStore.Entry> entries = a.history().forHost(host, 2000);
      Map<String, Bitmap> icons = icons(a, entries);
      a.runOnUiThread(() -> panel.setItems(historyItems(a, entries, icons, load[0])));
    });
    if (!a.isPrivate()) panel.action("⌫", "Delete all history from this site", () -> confirm(a, "Delete all history from " + host + "?", "Delete",
        () -> a.history().deleteHost(host, n -> { done(a, n, after); a.runOnUiThread(panel::dismiss); })));
    panel.show();
    load[0].run();
  }

  static void deleteHistoryDialog(MainActivity a, Runnable reload) {
    String[] ranges = {"Last hour", "Last 24 hours", "Last 7 days", "Last 4 weeks", "All time"};
    long[] spans = {DateUtils.HOUR_IN_MILLIS, DateUtils.DAY_IN_MILLIS, 7 * DateUtils.DAY_IN_MILLIS, 28 * DateUtils.DAY_IN_MILLIS, 0};
    new AlertDialog.Builder(a).setTitle("Delete history from").setItems(ranges, (d, i) -> confirm(a, "Delete history from " + ranges[i].toLowerCase(Locale.ROOT) + "?", "Delete", () -> {
      if (i == 4) a.history().clear(n -> done(a, n, reload));
      else a.history().deleteRange(System.currentTimeMillis() - spans[i], Long.MAX_VALUE, n -> done(a, n, reload));
    })).setNegativeButton("Cancel", null).show();
  }

  // ===================================================================================================
  // Shared actions
  // ===================================================================================================

  static void confirm(MainActivity a, String title, String action, Runnable r) {
    new AlertDialog.Builder(a).setTitle(title).setPositiveButton(action, (d, n) -> r.run()).setNegativeButton("Cancel", null).show();
  }

  /** Panels are dialogs on top of the browser; opening a page closes them all. */
  static void closePanels(MainActivity a) { a.closeAllPanels(); }

  static void pageActions(MainActivity a, String url, String title, Runnable delete, Runnable deleteSite) {
    List<String> labels = new ArrayList<>();
    List<Runnable> acts = new ArrayList<>();
    labels.add("Open in new tab"); acts.add(() -> { a.newTab(url, false); closePanels(a); });
    labels.add("Open in background tab"); acts.add(() -> a.newTab(url, true));
    if (!a.isPrivate() && android.os.Build.VERSION.SDK_INT >= 28) { labels.add("Open in private window"); acts.add(() -> a.openInPrivateWindow(url)); }
    labels.add("Copy link"); acts.add(() -> a.copyText("Link", url));
    labels.add("Share link"); acts.add(() -> a.shareLink(url));
    if (!a.isPrivate() && !a.bookmarks().contains(url)) { labels.add("Bookmark"); acts.add(() -> { a.bookmarks().add(url, title, ""); a.toast("Bookmarked"); }); }
    if (!a.isPrivate() && delete != null) { labels.add("Delete from history"); acts.add(delete); }
    if (!a.isPrivate() && deleteSite != null) { labels.add("Delete all from " + UrlTools.host(url)); acts.add(deleteSite); }
    new AlertDialog.Builder(a).setTitle(title).setItems(labels.toArray(new String[0]), (d, i) -> acts.get(i).run()).show();
  }

  // ===================================================================================================
  // Bookmarks
  // ===================================================================================================

  static void showBookmarks(MainActivity a, String startFolder) {
    ListPanel panel = new ListPanel(a, "Bookmarks");
    final String[] folder = {startFolder == null ? "" : startFolder};
    Runnable[] load = new Runnable[1];
    load[0] = () -> {
      BookmarkStore store = a.bookmarks();
      String q = panel.query().trim().toLowerCase(Locale.ROOT);
      panel.setTitle(q.isEmpty() ? (folder[0].isEmpty() ? "Bookmarks" : folder[0].substring(folder[0].lastIndexOf('/') + 1)) : "Search bookmarks");
      List<BookmarkIO.Bookmark> all = store.all();
      List<ListPanel.Item> items = new ArrayList<>();
      if (q.isEmpty()) {
        if (!folder[0].isEmpty()) {
          String parent = folder[0].contains("/") ? folder[0].substring(0, folder[0].lastIndexOf('/')) : "";
          items.add(new ListPanel.Item("↰", "Up to " + (parent.isEmpty() ? "Bookmarks" : parent.substring(parent.lastIndexOf('/') + 1)), null, () -> { folder[0] = parent; load[0].run(); }));
        }
        for (String f : store.subfolders(folder[0])) {
          int n = store.countIn(f);
          ListPanel.Item it = new ListPanel.Item("▸", f.substring(f.lastIndexOf('/') + 1), n == 1 ? "1 bookmark" : n + " bookmarks", () -> { folder[0] = f; load[0].run(); });
          it.description = "Folder " + it.title;
          it.longClick = () -> folderActions(a, f, load[0]);
          items.add(it);
        }
      }
      for (BookmarkIO.Bookmark b : all) {
        boolean show = q.isEmpty() ? b.folder.equals(folder[0])
            : b.title.toLowerCase(Locale.ROOT).contains(q) || b.url.toLowerCase(Locale.ROOT).contains(q);
        if (!show) continue;
        ListPanel.Item it = new ListPanel.Item("★", b.title, UrlTools.display(b.url) + (q.isEmpty() || b.folder.isEmpty() ? "" : " · " + b.folder), null);
        it.icon = a.favicons().peek(UrlTools.host(b.url));
        it.click = () -> { a.loadInCurrent(b.url); closePanels(a); };
        it.longClick = () -> bookmarkActions(a, b, load[0]);
        items.add(it);
      }
      panel.setEmptyText(q.isEmpty() ? (folder[0].isEmpty() ? "No bookmarks yet.\n\nUse ☆ in the ⋮ menu to bookmark a page." : "This folder is empty.") : "No matching bookmarks.");
      panel.setItems(items);
    };
    panel.withSearch("Search bookmarks", q -> load[0].run());
    if (!a.isPrivate()) panel.action("+", "New folder", () -> textPrompt(a, "New folder", "Folder name", "", name -> {
      String path = BookmarkIO.cleanFolder((folder[0].isEmpty() ? "" : folder[0] + "/") + name.replace('/', '-'));
      if (!path.isEmpty()) { a.bookmarks().createFolder(path); load[0].run(); }
    }));
    panel.action("⇅", "Import or export bookmarks", () -> importExportMenu(a, load[0]));
    panel.show();
    load[0].run();
  }

  static void bookmarkActions(MainActivity a, BookmarkIO.Bookmark b, Runnable reload) {
    List<String> labels = new ArrayList<>();
    List<Runnable> acts = new ArrayList<>();
    labels.add("Open in new tab"); acts.add(() -> { a.newTab(b.url, false); closePanels(a); });
    labels.add("Open in background tab"); acts.add(() -> a.newTab(b.url, true));
    if (!a.isPrivate() && android.os.Build.VERSION.SDK_INT >= 28) { labels.add("Open in private window"); acts.add(() -> a.openInPrivateWindow(b.url)); }
    labels.add("Copy link"); acts.add(() -> a.copyText("Link", b.url));
    if (!a.isPrivate()) {
      labels.add("Edit"); acts.add(() -> editBookmark(a, b.url, reload));
      labels.add("Move to folder"); acts.add(() -> chooseFolder(a, b.folder, f -> { a.bookmarks().update(b.url, b.url, b.title, f); reload.run(); }));
      labels.add("Delete"); acts.add(() -> {
        a.bookmarks().remove(b.url);
        reload.run();
        a.showSnackbar("Bookmark deleted", "Undo", () -> { a.bookmarks().add(b.url, b.title, b.folder); reload.run(); }, null);
      });
    }
    new AlertDialog.Builder(a).setTitle(b.title).setItems(labels.toArray(new String[0]), (d, i) -> acts.get(i).run()).show();
  }

  static void folderActions(MainActivity a, String folder, Runnable reload) {
    if (a.isPrivate()) { a.toast("Bookmarks can't be changed in a private window"); return; }
    String name = folder.substring(folder.lastIndexOf('/') + 1);
    String parent = folder.contains("/") ? folder.substring(0, folder.lastIndexOf('/')) : "";
    String[] labels = {"Rename", "Move", "Delete folder"};
    new AlertDialog.Builder(a).setTitle(name).setItems(labels, (d, i) -> {
      if (i == 0) textPrompt(a, "Rename folder", "Folder name", name, n -> {
        String clean = n.replace('/', '-').trim();
        if (clean.isEmpty()) return;
        if (!a.bookmarks().renameFolder(folder, (parent.isEmpty() ? "" : parent + "/") + clean)) a.toast("Can't rename to that name");
        reload.run();
      });
      else if (i == 1) chooseFolder(a, parent, target -> {
        if (BookmarkStore.inFolder(target, folder)) { a.toast("A folder can't be moved into itself"); return; }
        if (!a.bookmarks().renameFolder(folder, (target.isEmpty() ? "" : target + "/") + name)) a.toast("Can't move the folder there");
        reload.run();
      });
      else {
        int n = a.bookmarks().countIn(folder);
        confirm(a, n == 0 ? "Delete folder " + name + "?" : "Delete " + name + " and its " + (n == 1 ? "1 bookmark" : n + " bookmarks") + "?", "Delete", () -> {
          a.bookmarks().deleteFolder(folder);
          reload.run();
        });
      }
    }).show();
  }

  interface TextResult { void accept(String text); }

  static void textPrompt(MainActivity a, String title, String hint, String initial, TextResult result) {
    EditText input = new EditText(a);
    input.setSingleLine(true);
    input.setHint(hint);
    input.setText(initial);
    input.setSelectAllOnFocus(true);
    LinearLayout box = new LinearLayout(a);
    box.setPadding(a.dp(20), a.dp(8), a.dp(20), 0);
    box.addView(input, new LinearLayout.LayoutParams(-1, -2));
    new AlertDialog.Builder(a).setTitle(title).setView(box)
        .setPositiveButton("OK", (d, n) -> result.accept(input.getText().toString().trim()))
        .setNegativeButton("Cancel", null).show();
  }

  /** Lists every folder; "New folder…" creates one inside the selected location. */
  static void chooseFolder(MainActivity a, String currentFolder, TextResult result) {
    List<String> folders = a.bookmarks().folders();
    List<String> labels = new ArrayList<>();
    labels.add("Bookmarks (top level)");
    for (String f : folders) {
      int depth = f.length() - f.replace("/", "").length();
      StringBuilder indent = new StringBuilder();
      for (int i = 0; i < depth; i++) indent.append("    ");
      labels.add(indent + "▸ " + f.substring(f.lastIndexOf('/') + 1));
    }
    labels.add("+ New folder…");
    int checked = currentFolder == null || currentFolder.isEmpty() ? 0 : folders.indexOf(currentFolder) + 1;
    new AlertDialog.Builder(a).setTitle("Choose folder").setSingleChoiceItems(labels.toArray(new String[0]), checked, (d, i) -> {
      d.dismiss();
      if (i == 0) result.accept("");
      else if (i <= folders.size()) result.accept(folders.get(i - 1));
      else textPrompt(a, "New folder", "Folder name", "", name -> {
        String clean = BookmarkIO.cleanFolder(name.replace('/', '-'));
        if (clean.isEmpty()) return;
        String path = BookmarkIO.cleanFolder((currentFolder == null || currentFolder.isEmpty() ? "" : currentFolder + "/") + clean);
        a.bookmarks().createFolder(path);
        result.accept(path);
      });
    }).setNegativeButton("Cancel", null).show();
  }

  /** Bookmarks the page, then offers to edit it (title, folder). */
  static void addBookmarkFor(MainActivity a, Tab t) {
    if (t == null) return;
    String url = t.currentUrl();
    if (!UrlTools.isWebUrl(url)) return;
    if (!a.bookmarks().add(url, t.displayTitle(), "")) return; // private window: message already shown
    a.showSnackbar("Bookmarked", "Edit", () -> editBookmark(a, url, null), null);
  }

  static void editBookmark(MainActivity a, String url, Runnable after) {
    BookmarkStore store = a.bookmarks();
    BookmarkIO.Bookmark b = null;
    for (BookmarkIO.Bookmark x : store.all()) if (x.url.equals(url)) b = x;
    if (b == null) return;
    final BookmarkIO.Bookmark original = b;
    LinearLayout layout = new LinearLayout(a);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(a.dp(20), a.dp(8), a.dp(20), 0);
    EditText title = new EditText(a), address = new EditText(a);
    title.setSingleLine(true);
    title.setHint("Name");
    title.setText(b.title);
    address.setSingleLine(true);
    address.setHint("Address");
    address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    address.setText(b.url);
    final String[] folder = {b.folder};
    TextView folderButton = new TextView(a);
    folderButton.setPadding(0, a.dp(14), 0, a.dp(14));
    folderButton.setTextSize(15);
    folderButton.setText("Folder: " + (folder[0].isEmpty() ? "Bookmarks" : folder[0]));
    folderButton.setOnClickListener(v -> chooseFolder(a, folder[0], f -> { folder[0] = f; folderButton.setText("Folder: " + (f.isEmpty() ? "Bookmarks" : f)); }));
    layout.addView(title);
    layout.addView(address);
    layout.addView(folderButton);
    new AlertDialog.Builder(a).setTitle("Edit bookmark").setView(layout)
        .setPositiveButton("Save", (d, n) -> {
          String newUrl = address.getText().toString().trim();
          if (!BookmarkIO.acceptableUrl(newUrl)) { a.toast("Enter a valid http or https address"); return; }
          String newTitle = title.getText().toString().trim();
          store.update(original.url, newUrl, newTitle.isEmpty() ? UrlTools.display(newUrl) : newTitle, folder[0]);
          if (after != null) after.run();
        })
        .setNeutralButton("Delete", (d, n) -> {
          store.remove(original.url);
          if (after != null) after.run();
          a.showSnackbar("Bookmark deleted", "Undo", () -> { store.add(original.url, original.title, original.folder); if (after != null) after.run(); }, null);
        })
        .setNegativeButton("Cancel", null).show();
  }

  // ---- Import / export ----

  static void importExportMenu(MainActivity a, Runnable reload) {
    List<String> labels = new ArrayList<>();
    List<Runnable> acts = new ArrayList<>();
    if (!a.isPrivate()) { labels.add("Import bookmarks from file (HTML or JSON)"); acts.add(() -> importBookmarks(a, reload)); }
    labels.add("Export bookmarks as HTML (Chrome, Firefox, Edge, Safari)"); acts.add(() -> exportFile(a, "bookmarks.html", "text/html", () -> BookmarkIO.toNetscapeHtml(a.bookmarks().all())));
    labels.add("Export bookmarks as JSON"); acts.add(() -> exportFile(a, "bookmarks.json", "application/json", () -> BookmarkIO.toJson(a.bookmarks().all())));
    if (!a.isPrivate()) { labels.add("Export history as JSON"); acts.add(() -> exportFile(a, "history.json", "application/json", () -> a.history().exportJson())); }
    new AlertDialog.Builder(a).setTitle("Import and export").setItems(labels.toArray(new String[0]), (d, i) -> acts.get(i).run()).show();
  }

  interface Content { String get(); }

  static void exportFile(MainActivity a, String name, String mime, Content content) {
    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType(mime);
    intent.putExtra(Intent.EXTRA_TITLE, name);
    boolean started = a.startForResult(intent, (result, data) -> {
      if (result != android.app.Activity.RESULT_OK || data == null || data.getData() == null) return;
      Uri target = data.getData();
      a.background.execute(() -> {
        try (OutputStream out = a.getContentResolver().openOutputStream(target, "wt")) {
          if (out == null) throw new IllegalStateException("cannot write");
          out.write(content.get().getBytes(StandardCharsets.UTF_8));
          a.runOnUiThread(() -> a.toast("Saved " + name));
        } catch (Exception e) {
          a.runOnUiThread(() -> a.toast("Could not save the file"));
        }
      });
    });
    if (!started) a.toast("No file manager available to save the file");
  }

  static void importBookmarks(MainActivity a, Runnable reload) {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("*/*");
    intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/html", "application/json", "text/plain", "application/octet-stream"});
    boolean started = a.startForResult(intent, (result, data) -> {
      if (result != android.app.Activity.RESULT_OK || data == null || data.getData() == null) return;
      Uri source = data.getData();
      a.background.execute(() -> {
        String text;
        try (InputStream in = a.getContentResolver().openInputStream(source)) {
          if (in == null) throw new IllegalStateException("cannot read");
          ByteArrayOutputStream buf = new ByteArrayOutputStream();
          byte[] chunk = new byte[16384];
          int n, total = 0;
          while ((n = in.read(chunk)) > 0) {
            total += n;
            if (total > MAX_IMPORT_BYTES) throw new IllegalStateException("too large");
            buf.write(chunk, 0, n);
          }
          text = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
          a.runOnUiThread(() -> a.toast("Could not read that file (bookmark files must be under 20 MB)"));
          return;
        }
        BookmarkIO.Parsed parsed = BookmarkIO.parse(text);
        a.runOnUiThread(() -> {
          if (parsed.bookmarks.isEmpty()) { a.toastLong("No bookmarks found. Use an HTML bookmarks export from a browser, or a Block Browser JSON export."); return; }
          int[] added = new int[1];
          List<BookmarkIO.Bookmark> merged = BookmarkIO.merge(a.bookmarks().all(), parsed.bookmarks, added);
          if (!a.bookmarks().save(merged)) return;
          int duplicates = parsed.bookmarks.size() - added[0];
          String msg = "Imported " + added[0] + (added[0] == 1 ? " bookmark" : " bookmarks");
          if (duplicates > 0) msg += ", skipped " + duplicates + " already saved";
          if (parsed.rejected > 0) msg += ", ignored " + parsed.rejected + " invalid";
          a.toastLong(msg);
          if (reload != null) reload.run();
        });
      });
    });
    if (!started) a.toast("No file manager available to choose a file");
  }

  // ===================================================================================================
  // Recently closed tabs
  // ===================================================================================================

  static void showRecentlyClosed(MainActivity a) {
    ListPanel panel = new ListPanel(a, "Recently closed tabs");
    panel.setEmptyText("No recently closed tabs.");
    Runnable[] load = new Runnable[1];
    load[0] = () -> {
      List<ListPanel.Item> items = new ArrayList<>();
      try {
        JSONArray saved = new JSONArray(a.prefs.getString("closed_tabs", "[]"));
        for (int i = 0; i < saved.length(); i++) {
          Object o = saved.opt(i);
          String url = o instanceof JSONObject ? ((JSONObject) o).optString("url") : String.valueOf(o);
          String title = o instanceof JSONObject ? ((JSONObject) o).optString("title", "") : "";
          long at = o instanceof JSONObject ? ((JSONObject) o).optLong("closedAt", 0) : 0;
          if (!UrlTools.isWebUrl(url)) continue;
          ListPanel.Item it = new ListPanel.Item("↶", title.isEmpty() ? UrlTools.display(url) : title,
              UrlTools.display(url) + (at > 0 ? " · " + DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) : ""), null);
          it.icon = a.favicons().peek(UrlTools.host(url));
          it.click = () -> { a.forgetClosedTab(url); a.newTab(url); closePanels(a); };
          it.longClick = () -> { a.forgetClosedTab(url); load[0].run(); };
          it.description = "Reopen " + it.title;
          items.add(it);
        }
      } catch (Exception ignored) {}
      panel.setItems(items);
    };
    panel.action("⌫", "Clear list", () -> confirm(a, "Clear recently closed tabs?", "Clear", () -> {
      a.prefs.edit().remove("closed_tabs").remove("recently_closed_url").apply();
      load[0].run();
    }));
    panel.show();
    load[0].run();
  }
}

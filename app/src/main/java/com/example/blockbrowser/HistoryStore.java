package com.example.blockbrowser;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Browsing history in SQLite: one row per page with its title, visit count and last visit time.
 * Replaces the old 150-entry JSON list in preferences (migrated once). Writes run on a background thread.
 * Private windows open it read-only: they can use it for suggestions but never add to or change it.
 */
final class HistoryStore extends SQLiteOpenHelper {
  static final int MAX_ENTRIES = 10000;
  private static final String TAG = "BlockBrowserHistory";
  private static HistoryStore instance;
  private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> new Thread(r, "history-writer"));
  final boolean readOnly;
  private int writesSincePrune = 0;
  /** SQLite before 3.24 (Android 10 and older) has no upsert; detected on first use. */
  private volatile boolean upsert = true;

  static final class Entry {
    final String url, title, host; final int visits; final long lastVisit;
    Entry(String url, String title, String host, int visits, long lastVisit) {
      this.url = url; this.title = title; this.host = host; this.visits = visits; this.lastVisit = lastVisit;
    }
  }

  /** One store per process. The private process gets a read-only one. */
  static synchronized HistoryStore get(Context context, boolean readOnly) {
    if (instance == null) instance = new HistoryStore(context.getApplicationContext(), readOnly);
    return instance;
  }

  private HistoryStore(Context context, boolean readOnly) {
    super(context, "history.db", null, 1);
    this.readOnly = readOnly;
  }

  @Override public void onCreate(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE history (url TEXT PRIMARY KEY, title TEXT NOT NULL DEFAULT '', host TEXT NOT NULL DEFAULT '', "
        + "visits INTEGER NOT NULL DEFAULT 1, last_visit INTEGER NOT NULL)");
    db.execSQL("CREATE INDEX history_last_visit ON history(last_visit)");
    db.execSQL("CREATE INDEX history_host ON history(host)");
  }

  @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

  /** Moves the old preferences history (no timestamps) into the database, newest first, then removes it. */
  void migrateFrom(SharedPreferences prefs) {
    if (readOnly || !prefs.contains("history")) return;
    String json = prefs.getString("history", "[]");
    writer.execute(() -> {
      try {
        JSONArray old = new JSONArray(json);
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        db.beginTransaction();
        try {
          for (int i = 0; i < old.length(); i++) {
            JSONObject e = old.optJSONObject(i);
            if (e == null) continue;
            String url = e.optString("url");
            if (!UrlTools.isWebUrl(url)) continue;
            ContentValues v = new ContentValues();
            v.put("url", url);
            v.put("title", e.optString("title", ""));
            v.put("host", UrlTools.host(url));
            v.put("visits", 1);
            v.put("last_visit", now - i * 1000L); // keep the old order
            db.insertWithOnConflict("history", null, v, SQLiteDatabase.CONFLICT_IGNORE);
          }
          db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        prefs.edit().remove("history").apply();
        Log.i(TAG, "migrated " + old.length() + " history entries");
      } catch (Exception e) {
        Log.w(TAG, "history migration failed; keeping the old list", e);
      }
    });
  }

  void recordVisit(String url, String title) {
    if (readOnly || !UrlTools.isWebUrl(url) || HomePage.isHome(url)) return;
    long now = System.currentTimeMillis();
    writer.execute(() -> {
      try {
        if (!upsert) throw new IllegalStateException("upsert unsupported");
        SQLiteDatabase db = getWritableDatabase();
        String t = title == null ? "" : title;
        db.execSQL("INSERT INTO history(url,title,host,visits,last_visit) VALUES(?,?,?,1,?) "
            + "ON CONFLICT(url) DO UPDATE SET visits=visits+1, last_visit=excluded.last_visit, "
            + "title=CASE WHEN excluded.title<>'' THEN excluded.title ELSE title END",
            new Object[]{url, t, UrlTools.host(url), now});
        if (++writesSincePrune >= 200) { writesSincePrune = 0; prune(db); }
      } catch (Exception e) {
        upsert = false; // fall back to update-then-insert
        try {
          SQLiteDatabase db = getWritableDatabase();
          ContentValues v = new ContentValues();
          v.put("last_visit", now);
          if (title != null && !title.isEmpty()) v.put("title", title);
          db.execSQL("UPDATE history SET visits=visits+1 WHERE url=?", new Object[]{url});
          int updated = db.update("history", v, "url=?", new String[]{url});
          if (updated == 0) {
            v.put("url", url); v.put("host", UrlTools.host(url)); v.put("visits", 1);
            if (!v.containsKey("title")) v.put("title", "");
            db.insertWithOnConflict("history", null, v, SQLiteDatabase.CONFLICT_IGNORE);
          }
          if (++writesSincePrune >= 200) { writesSincePrune = 0; prune(db); }
        } catch (Exception e2) { Log.w(TAG, "could not save visit", e2); }
      }
    });
  }

  /** Pages loaded in the background may get their title after the visit was recorded. */
  void updateTitle(String url, String title) {
    if (readOnly || !UrlTools.isWebUrl(url) || title == null || title.isEmpty()) return;
    writer.execute(() -> {
      try {
        ContentValues v = new ContentValues();
        v.put("title", title);
        getWritableDatabase().update("history", v, "url=?", new String[]{url});
      } catch (Exception e) { Log.w(TAG, "could not update title", e); }
    });
  }

  private void prune(SQLiteDatabase db) {
    db.execSQL("DELETE FROM history WHERE url NOT IN (SELECT url FROM history ORDER BY last_visit DESC LIMIT " + MAX_ENTRIES + ")");
  }

  /** Candidates for address-bar suggestions. Call off the UI thread. */
  List<Entry> matching(String query, int limit) {
    String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    if (q.isEmpty()) return new ArrayList<>();
    String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    return query("(lower(url) LIKE ? ESCAPE '\\' OR lower(title) LIKE ? ESCAPE '\\')", new String[]{like, like},
        "visits DESC, last_visit DESC", limit);
  }

  /** Newest first, optionally filtered by text. Call off the UI thread. */
  List<Entry> recent(String filter, int limit) {
    if (filter == null || filter.trim().isEmpty()) return query(null, null, "last_visit DESC", limit);
    List<Entry> found = matching(filter, limit);
    java.util.Collections.sort(found, (a, b) -> Long.compare(b.lastVisit, a.lastVisit));
    return found;
  }

  /** Most visited pages, for home-page shortcuts. Call off the UI thread. */
  List<Entry> topSites(int limit) {
    return query("url NOT LIKE ?", new String[]{HomePage.ORIGIN + "%"}, "visits DESC, last_visit DESC", limit);
  }

  List<Entry> forHost(String host, int limit) {
    return query("host=?", new String[]{host}, "last_visit DESC", limit);
  }

  private List<Entry> query(String where, String[] args, String order, int limit) {
    List<Entry> out = new ArrayList<>();
    try (Cursor c = getReadableDatabase().query("history", new String[]{"url", "title", "host", "visits", "last_visit"},
        where, args, null, null, order, String.valueOf(limit))) {
      while (c.moveToNext()) out.add(new Entry(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getLong(4)));
    } catch (Exception e) {
      Log.w(TAG, "history query failed", e);
    }
    return out;
  }

  int count() {
    try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM history", null)) {
      return c.moveToFirst() ? c.getInt(0) : 0;
    } catch (Exception e) { return 0; }
  }

  /** Runs a deletion on the writer thread, then {@code done} (on that thread) with the number of rows removed. */
  private void delete(String where, String[] args, java.util.function.IntConsumer done) {
    if (readOnly) { if (done != null) done.accept(-1); return; }
    writer.execute(() -> {
      int n = 0;
      try { n = getWritableDatabase().delete("history", where, args); } catch (Exception e) { Log.w(TAG, "delete failed", e); }
      if (done != null) done.accept(n);
    });
  }

  void deleteUrl(String url, java.util.function.IntConsumer done) { delete("url=?", new String[]{url}, done); }
  /** A page failed to load: remove it if this was its only visit; earlier successful visits stay. */
  void forgetFailedVisit(String url) {
    if (readOnly || !UrlTools.isWebUrl(url)) return;
    writer.execute(() -> {
      try {
        SQLiteDatabase db = getWritableDatabase();
        if (db.delete("history", "url=? AND visits<=1", new String[]{url}) == 0)
          db.execSQL("UPDATE history SET visits=visits-1 WHERE url=? AND visits>1", new Object[]{url});
      } catch (Exception e) { Log.w(TAG, "could not forget failed visit", e); }
    });
  }
  void deleteHost(String host, java.util.function.IntConsumer done) { delete("host=?", new String[]{host}, done); }
  /** Deletes visits whose last visit is in [from, to). */
  void deleteRange(long from, long to, java.util.function.IntConsumer done) {
    delete("last_visit>=? AND last_visit<?", new String[]{String.valueOf(from), String.valueOf(to)}, done);
  }
  void clear(java.util.function.IntConsumer done) { delete(null, null, done); }

  /** JSON array of {url, title, visits, lastVisit}, newest first. Call off the UI thread. */
  String exportJson() {
    JSONArray out = new JSONArray();
    for (Entry e : query(null, null, "last_visit DESC", MAX_ENTRIES)) {
      try {
        JSONObject o = new JSONObject();
        o.put("url", e.url); o.put("title", e.title); o.put("visits", e.visits); o.put("lastVisit", e.lastVisit);
        out.put(o);
      } catch (Exception ignored) {}
    }
    return out.toString();
  }
}

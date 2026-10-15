package com.example.blockbrowser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Site icons by host. Normal windows keep them in memory and on disk (files/favicons); private windows keep new
 * icons in memory only, so a private visit leaves no icon behind, but can show icons saved by normal browsing.
 */
final class FaviconCache {
  private static FaviconCache instance;
  private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
    @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
  };
  private final File dir;
  private final boolean persist;
  private final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "favicon-io"));

  static synchronized FaviconCache get(Context context, boolean persist) {
    if (instance == null) instance = new FaviconCache(context.getApplicationContext(), persist);
    return instance;
  }

  private FaviconCache(Context context, boolean persist) {
    this.dir = new File(context.getFilesDir(), "favicons");
    this.persist = persist;
  }

  private static String key(String host) {
    String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
    if (h.startsWith("www.")) h = h.substring(4);
    return h.replaceAll("[^a-z0-9.-]", "_");
  }

  void put(String host, Bitmap icon) {
    if (icon == null || host == null || host.isEmpty()) return;
    String k = key(host);
    Bitmap small = icon.getWidth() > 64 || icon.getHeight() > 64 ? Bitmap.createScaledBitmap(icon, 64, 64, true) : icon;
    memory.put(k, small);
    if (!persist) return;
    io.execute(() -> {
      try {
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        try (FileOutputStream out = new FileOutputStream(new File(dir, k + ".png"))) { small.compress(Bitmap.CompressFormat.PNG, 100, out); }
      } catch (Exception ignored) {}
    });
  }

  /** Memory first, then disk. May read a file, so call off the UI thread when possible. */
  Bitmap get(String host) {
    if (host == null || host.isEmpty()) return null;
    String k = key(host);
    Bitmap b = memory.get(k);
    if (b != null) return b;
    File f = new File(dir, k + ".png");
    if (!f.isFile()) return null;
    b = BitmapFactory.decodeFile(f.getPath());
    if (b != null) memory.put(k, b);
    return b;
  }

  /** Memory only; never touches disk. Safe on the UI thread. */
  Bitmap peek(String host) { return host == null ? null : memory.get(key(host)); }

  void clear() {
    memory.evictAll();
    if (!persist) return;
    io.execute(() -> {
      File[] files = dir.listFiles();
      if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
        f.delete();
    });
  }
}

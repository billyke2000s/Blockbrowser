package com.example.blockbrowser;

import android.content.SharedPreferences;

import java.util.Map;
import java.util.Set;

/**
 * Lets private windows read normal bookmarks and history (for suggestions and the bookmarks list) without ever
 * writing to them. Any attempt to save runs {@code onBlockedWrite} instead, so the user is told rather than the
 * change silently disappearing.
 */
final class ReadOnlyPreferences implements SharedPreferences {
  private final SharedPreferences base;
  private final Runnable onBlockedWrite;

  ReadOnlyPreferences(SharedPreferences base, Runnable onBlockedWrite) {
    this.base = base;
    this.onBlockedWrite = onBlockedWrite;
  }

  @Override public Map<String, ?> getAll() { return base.getAll(); }
  @Override public String getString(String key, String defValue) { return base.getString(key, defValue); }
  @Override public Set<String> getStringSet(String key, Set<String> defValues) { return base.getStringSet(key, defValues); }
  @Override public int getInt(String key, int defValue) { return base.getInt(key, defValue); }
  @Override public long getLong(String key, long defValue) { return base.getLong(key, defValue); }
  @Override public float getFloat(String key, float defValue) { return base.getFloat(key, defValue); }
  @Override public boolean getBoolean(String key, boolean defValue) { return base.getBoolean(key, defValue); }
  @Override public boolean contains(String key) { return base.contains(key); }
  @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
  @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}

  @Override public Editor edit() {
    return new Editor() {
      @Override public Editor putString(String key, String value) { return this; }
      @Override public Editor putStringSet(String key, Set<String> values) { return this; }
      @Override public Editor putInt(String key, int value) { return this; }
      @Override public Editor putLong(String key, long value) { return this; }
      @Override public Editor putFloat(String key, float value) { return this; }
      @Override public Editor putBoolean(String key, boolean value) { return this; }
      @Override public Editor remove(String key) { return this; }
      @Override public Editor clear() { return this; }
      @Override public boolean commit() { onBlockedWrite.run(); return false; }
      @Override public void apply() { onBlockedWrite.run(); }
    };
  }
}

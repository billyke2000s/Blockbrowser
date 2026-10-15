package com.example.blockbrowser;

import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Address-bar suggestions. Matching runs on the filter's background thread (history database, bookmarks,
 * optional search-engine suggestions, favicons from disk), so typing never waits on it. The list is only
 * replaced when the results actually change.
 */
final class AddressAdapter extends BaseAdapter implements Filterable {
  interface Source {
    List<SuggestionRanker.Page> pagesMatching(String query);
    /** Search-engine suggestions, or an empty list when they are off. Called on a background thread. */
    List<String> searchSuggestions(String query);
    String searchPrefix();
    String searchEngineName();
    Bitmap favicon(String host);
    /** Puts the text into the address bar without navigating ("↖" button). */
    void fill(String text);
  }

  static final class Row {
    final SuggestionRanker.Suggestion s; final Bitmap icon;
    Row(SuggestionRanker.Suggestion s, Bitmap icon) { this.s = s; this.icon = icon; }
  }

  private final MainActivity activity;
  private final Source source;
  private List<Row> rows = new ArrayList<>();

  AddressAdapter(MainActivity activity, Source source) {
    this.activity = activity;
    this.source = source;
  }

  @Override public int getCount() { return rows.size(); }
  @Override public Row getItem(int position) { return rows.get(position); }
  @Override public long getItemId(int position) { return position; }

  @Override public View getView(int position, View convert, ViewGroup parent) {
    Holder h = convert != null && convert.getTag() instanceof Holder ? (Holder) convert.getTag() : new Holder();
    Row r = rows.get(position);
    h.bind(r);
    return h.root;
  }

  private final class Holder {
    final LinearLayout root = new LinearLayout(activity);
    final ImageView icon = new ImageView(activity);
    final TextView glyph = new TextView(activity), title = new TextView(activity), url = new TextView(activity), fill = new TextView(activity);

    Holder() {
      root.setTag(this);
      root.setGravity(Gravity.CENTER_VERTICAL);
      root.setMinimumHeight(activity.dp(54));
      root.setPadding(activity.dp(14), activity.dp(4), activity.dp(4), activity.dp(4));
      FrameLayout box = new FrameLayout(activity);
      glyph.setTextColor(ListPanel.MUTED);
      glyph.setTextSize(17);
      glyph.setGravity(Gravity.CENTER);
      box.addView(glyph, new FrameLayout.LayoutParams(-1, -1));
      box.addView(icon, new FrameLayout.LayoutParams(-1, -1));
      LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(activity.dp(22), activity.dp(22));
      blp.setMargins(0, 0, activity.dp(14), 0);
      root.addView(box, blp);
      LinearLayout texts = new LinearLayout(activity);
      texts.setOrientation(LinearLayout.VERTICAL);
      title.setTextColor(ListPanel.TEXT);
      title.setTextSize(15);
      title.setSingleLine(true);
      title.setEllipsize(android.text.TextUtils.TruncateAt.END);
      url.setTextColor(ListPanel.ACCENT);
      url.setTextSize(12.5f);
      url.setSingleLine(true);
      url.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
      texts.addView(title);
      texts.addView(url);
      root.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
      fill.setText("↖");
      fill.setTextColor(ListPanel.MUTED);
      fill.setTextSize(19);
      fill.setGravity(Gravity.CENTER);
      root.addView(fill, new LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)));
    }

    void bind(Row r) {
      SuggestionRanker.Suggestion s = r.s;
      boolean search = s.type == SuggestionRanker.SEARCH || s.type == SuggestionRanker.SEARCH_SUGGESTION;
      title.setText(s.title);
      url.setText(search ? "Search " + source.searchEngineName() : UrlTools.display(s.url));
      icon.setImageBitmap(search ? null : r.icon);
      icon.setVisibility(!search && r.icon != null ? View.VISIBLE : View.GONE);
      glyph.setText(search ? "⌕" : s.type == SuggestionRanker.BOOKMARK ? "★" : s.type == SuggestionRanker.TYPED_URL ? "◍" : "◷");
      String kind = search ? "Search" : s.type == SuggestionRanker.BOOKMARK ? "Bookmark" : s.type == SuggestionRanker.TYPED_URL ? "Go to" : "History";
      root.setContentDescription(kind + ": " + s.title + (search ? "" : ", " + UrlTools.display(s.url)));
      String fillText = search ? s.title : s.url;
      fill.setContentDescription("Edit " + s.title + " in the address bar");
      fill.setOnClickListener(v -> source.fill(fillText));
    }
  }

  @Override public Filter getFilter() { return filter; }

  private final Filter filter = new Filter() {
    @Override protected FilterResults performFiltering(CharSequence constraint) {
      FilterResults results = new FilterResults();
      String q = constraint == null ? "" : constraint.toString();
      List<Row> out = new ArrayList<>();
      if (!q.trim().isEmpty()) {
        String prefix = source.searchPrefix();
        String typed = UrlTools.toNavigableUrl(q, prefix);
        List<SuggestionRanker.Page> pages = source.pagesMatching(q);
        List<String> remote = q.trim().length() >= 2 ? source.searchSuggestions(q) : Collections.emptyList();
        for (SuggestionRanker.Suggestion s : SuggestionRanker.rank(q, typed, pages, remote, prefix, System.currentTimeMillis(), 8)) {
          boolean search = s.type == SuggestionRanker.SEARCH || s.type == SuggestionRanker.SEARCH_SUGGESTION;
          out.add(new Row(s, search ? null : source.favicon(UrlTools.host(s.url))));
        }
      }
      results.values = out;
      results.count = out.size();
      return results;
    }

    @SuppressWarnings("unchecked")
    @Override protected void publishResults(CharSequence constraint, FilterResults results) {
      List<Row> next = results.values == null ? new ArrayList<>() : (List<Row>) results.values;
      if (same(rows, next)) return; // nothing changed: keep the list as it is
      rows = next;
      if (BuildConfig.TEST_LOGS) {
        StringBuilder log = new StringBuilder();
        for (Row r : rows) log.append(r.s.type).append(':').append(r.s.title).append(" | ");
        android.util.Log.i("BlockBrowserTest", "suggestions for '" + constraint + "': " + log);
      }
      if (rows.isEmpty()) notifyDataSetInvalidated(); else notifyDataSetChanged();
    }

    @Override public CharSequence convertResultToString(Object resultValue) {
      Row r = (Row) resultValue;
      boolean search = r.s.type == SuggestionRanker.SEARCH || r.s.type == SuggestionRanker.SEARCH_SUGGESTION;
      return search ? r.s.title : r.s.url;
    }
  };

  private static boolean same(List<Row> a, List<Row> b) {
    if (a.size() != b.size()) return false;
    for (int i = 0; i < a.size(); i++) {
      SuggestionRanker.Suggestion x = a.get(i).s, y = b.get(i).s;
      if (x.type != y.type || !x.url.equals(y.url) || !x.title.equals(y.title) || a.get(i).icon != b.get(i).icon) return false;
    }
    return true;
  }
}

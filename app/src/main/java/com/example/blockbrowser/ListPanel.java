package com.example.blockbrowser;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen list used for history, bookmarks, downloads, site settings and settings pages, in the browser's
 * dark style: a header with Back and optional actions, an optional search field, and rows with an icon, title,
 * subtitle and trailing text. Rows can be tapped and long-pressed.
 */
final class ListPanel {
  static final int SURFACE = 0xff111827, PANEL = 0xff1b2638, ACCENT = 0xff83b9ff, TEXT = 0xffeaf1fc, MUTED = 0xffaab8cc, DIVIDER = 0xff26324a;

  static final class Item {
    CharSequence title, subtitle, trailing;
    String glyph;            // text icon when there is no bitmap
    Bitmap icon;
    Runnable click, longClick;
    boolean header;          // section heading row
    int progress = -1;       // 0-100 shows a progress bar, -1 hides it
    String description;      // accessibility label; defaults to title
    Item() {}
    Item(String glyph, CharSequence title, CharSequence subtitle, Runnable click) { this.glyph = glyph; this.title = title; this.subtitle = subtitle; this.click = click; }
    static Item header(String title) { Item i = new Item(); i.header = true; i.title = title; return i; }
  }

  interface QueryListener { void onQuery(String query); }

  private final Activity activity;
  private final Dialog dialog;
  private final LinearLayout actions;
  private final TextView titleView, emptyView;
  private final EditText search;
  private final ListView list;
  private final Adapter adapter = new Adapter();
  private List<Item> items = new ArrayList<>();
  private Runnable onDismiss;

  ListPanel(Activity activity, String title) {
    this.activity = activity;
    dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar);
    LinearLayout root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(SURFACE);
    root.setFitsSystemWindows(true);

    LinearLayout header = new LinearLayout(activity);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(dp(8), dp(12), dp(12), dp(12));
    TextView back = iconButton("←", "Back", this::dismiss);
    header.addView(back);
    titleView = new TextView(activity);
    titleView.setText(title);
    titleView.setTextColor(TEXT);
    titleView.setTextSize(22);
    titleView.setTypeface(null, Typeface.BOLD);
    titleView.setSingleLine(true);
    titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
    header.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    actions = new LinearLayout(activity);
    header.addView(actions);
    root.addView(header);

    search = new EditText(activity);
    search.setSingleLine(true);
    search.setTextColor(TEXT);
    search.setHintTextColor(MUTED);
    search.setTextSize(15);
    search.setBackground(rounded(PANEL, 24));
    search.setPadding(dp(16), 0, dp(16), 0);
    search.setVisibility(View.GONE);
    LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
    slp.setMargins(dp(12), dp(2), dp(12), dp(8));
    root.addView(search, slp);

    FrameLayout body = new FrameLayout(activity);
    list = new ListView(activity);
    list.setDivider(null);
    list.setPadding(dp(10), dp(4), dp(10), dp(16));
    list.setClipToPadding(false);
    list.setSelector(android.R.color.transparent);
    list.setAdapter(adapter);
    list.setOnItemClickListener((p, v, pos, id) -> { Item it = items.get(pos); if (it.click != null) it.click.run(); });
    list.setOnItemLongClickListener((p, v, pos, id) -> { Item it = items.get(pos); if (it.longClick == null) return false; it.longClick.run(); return true; });
    body.addView(list, new FrameLayout.LayoutParams(-1, -1));
    emptyView = new TextView(activity);
    emptyView.setTextColor(MUTED);
    emptyView.setTextSize(15);
    emptyView.setGravity(Gravity.CENTER);
    emptyView.setPadding(dp(32), dp(32), dp(32), dp(32));
    body.addView(emptyView, new FrameLayout.LayoutParams(-1, -1));
    list.setEmptyView(emptyView);
    root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

    dialog.setContentView(root);
    Window w = dialog.getWindow();
    if (w != null) {
      w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
      w.setStatusBarColor(SURFACE);
      w.setNavigationBarColor(SURFACE);
      w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
    }
    dialog.setOnDismissListener(d -> { if (onDismiss != null) onDismiss.run(); });
    if (activity instanceof MainActivity) ((MainActivity) activity).registerPanel(this);
  }

  int dp(int n) { return (int) (n * activity.getResources().getDisplayMetrics().density); }

  GradientDrawable rounded(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  TextView iconButton(String glyph, String description, Runnable r) {
    TextView t = new TextView(activity);
    t.setText(glyph);
    t.setTextColor(TEXT);
    t.setTextSize(21);
    t.setGravity(Gravity.CENTER);
    t.setMinWidth(dp(48));
    t.setMinHeight(dp(48));
    t.setContentDescription(description);
    t.setOnClickListener(v -> r.run());
    return t;
  }

  ListPanel action(String glyph, String description, Runnable r) { actions.addView(iconButton(glyph, description, r)); return this; }

  ListPanel withSearch(String hint, QueryListener listener) {
    search.setHint(hint);
    search.setContentDescription(hint);
    search.setVisibility(View.VISIBLE);
    search.addTextChangedListener(new TextWatcher() {
      public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
      public void onTextChanged(CharSequence s, int a, int b, int c) {}
      public void afterTextChanged(Editable e) { listener.onQuery(e.toString()); }
    });
    return this;
  }

  String query() { return search.getText().toString(); }
  void setTitle(String title) { titleView.setText(title); }
  void setEmptyText(String text) { emptyView.setText(text); }
  void setOnDismiss(Runnable r) { onDismiss = r; }

  void setItems(List<Item> newItems) {
    int first = list.getFirstVisiblePosition();
    View top = list.getChildAt(0);
    int offset = top == null ? 0 : top.getTop();
    items = new ArrayList<>(newItems);
    adapter.notifyDataSetChanged();
    list.setSelectionFromTop(Math.min(first, Math.max(0, items.size() - 1)), offset);
  }

  void show() { dialog.show(); }
  void dismiss() { if (dialog.isShowing()) dialog.dismiss(); }
  boolean isShowing() { return dialog.isShowing(); }

  private final class Adapter extends BaseAdapter {
    @Override public int getCount() { return items.size(); }
    @Override public Object getItem(int i) { return items.get(i); }
    @Override public long getItemId(int i) { return i; }
    @Override public int getViewTypeCount() { return 2; }
    @Override public int getItemViewType(int i) { return items.get(i).header ? 1 : 0; }
    @Override public boolean isEnabled(int i) { return !items.get(i).header; }

    @Override public View getView(int i, View convert, ViewGroup parent) {
      Item it = items.get(i);
      if (it.header) {
        TextView t = convert instanceof TextView ? (TextView) convert : new TextView(activity);
        t.setText(it.title);
        t.setTextColor(ACCENT);
        t.setTextSize(13);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(dp(18), dp(14), dp(18), dp(6));
        return t;
      }
      Row row = convert instanceof Row ? (Row) convert : new Row();
      row.bind(it);
      return row;
    }
  }

  private final class Row extends LinearLayout {
    final ImageView icon = new ImageView(activity);
    final TextView glyph = new TextView(activity), title = new TextView(activity), subtitle = new TextView(activity), trailing = new TextView(activity);
    final ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);

    Row() {
      super(activity);
      setGravity(Gravity.CENTER_VERTICAL);
      setMinimumHeight(dp(70));
      setBackground(rounded(PANEL, 18));
      setElevation(dp(1));
      setPadding(dp(14), dp(12), dp(14), dp(12));
      FrameLayout iconBox = new FrameLayout(activity);
      glyph.setTextColor(ACCENT);
      glyph.setTextSize(19);
      glyph.setGravity(Gravity.CENTER);
      iconBox.addView(glyph, new FrameLayout.LayoutParams(-1, -1));
      iconBox.addView(icon, new FrameLayout.LayoutParams(-1, -1));
      LayoutParams ilp = new LayoutParams(dp(34), dp(34));
      ilp.setMargins(0, 0, dp(14), 0);
      addView(iconBox, ilp);
      LinearLayout texts = new LinearLayout(activity);
      texts.setOrientation(VERTICAL);
      title.setTextColor(TEXT);
      title.setTextSize(16);
      title.setMaxLines(2);
      title.setEllipsize(android.text.TextUtils.TruncateAt.END);
      subtitle.setTextColor(MUTED);
      subtitle.setTextSize(13);
      subtitle.setMaxLines(2);
      subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
      texts.addView(title);
      texts.addView(subtitle);
      texts.addView(progress, new LayoutParams(-1, dp(6)));
      addView(texts, new LayoutParams(0, -2, 1));
      trailing.setTextColor(ACCENT);
      trailing.setTextSize(13);
      trailing.setPadding(dp(10), 0, 0, 0);
      addView(trailing);
      progress.setMax(100);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
      super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    void bind(Item it) {
      title.setText(it.title);
      subtitle.setText(it.subtitle);
      subtitle.setVisibility(it.subtitle == null || it.subtitle.length() == 0 ? GONE : VISIBLE);
      trailing.setText(it.trailing);
      trailing.setVisibility(it.trailing == null ? GONE : VISIBLE);
      icon.setImageBitmap(it.icon);
      icon.setVisibility(it.icon == null ? GONE : VISIBLE);
      glyph.setText(it.icon == null && it.glyph != null ? it.glyph : "");
      if (it.progress >= 0) { progress.setVisibility(VISIBLE); progress.setIndeterminate(it.progress > 100); progress.setProgress(Math.min(100, it.progress)); }
      else progress.setVisibility(GONE);
      CharSequence desc = it.description != null ? it.description : it.title;
      setContentDescription(it.subtitle == null ? desc : desc + ", " + it.subtitle);
    }
  }
}

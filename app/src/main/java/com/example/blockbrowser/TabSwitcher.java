package com.example.blockbrowser;

import android.app.Dialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Grid of open tabs with page thumbnails. Tap to switch, × or swipe sideways to close, long-press and drag to
 * reorder. The header has New tab, a menu (duplicate, close others, close all, recently closed) and Done.
 */
final class TabSwitcher {
  interface Host {
    List<Tab> tabs();
    int currentIndex();
    void select(int index);
    void close(int index);
    void move(int from, int to);
    void newTab();
    void showMenu(View anchor);
    boolean isPrivate();
  }

  private final MainActivity activity;
  private final Host host;
  private final Dialog dialog;
  private final RecyclerView grid;
  private final TextView title;
  private final Adapter adapter = new Adapter();

  TabSwitcher(MainActivity activity, Host host) {
    this.activity = activity;
    this.host = host;
    int surface = host.isPrivate() ? IncognitoActivity.PRIVATE_SURFACE : ListPanel.SURFACE;
    dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar);
    LinearLayout root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(surface);
    root.setFitsSystemWindows(true);

    LinearLayout header = new LinearLayout(activity);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(activity.dp(8), activity.dp(4), activity.dp(8), activity.dp(4));
    title = new TextView(activity);
    title.setTextColor(ListPanel.TEXT);
    title.setTextSize(19);
    title.setTypeface(null, Typeface.BOLD);
    title.setPadding(activity.dp(8), 0, 0, 0);
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    TextView add = headerButton("+", "New tab", () -> { dialog.dismiss(); host.newTab(); });
    TextView more = headerButton("⋮", "Tab options", null);
    more.setOnClickListener(host::showMenu);
    TextView done = headerButton("Done", "Done", dialog::dismiss);
    done.setTextSize(15);
    header.addView(add);
    header.addView(more);
    header.addView(done);
    root.addView(header);

    grid = new RecyclerView(activity);
    grid.setLayoutManager(new GridLayoutManager(activity, activity.getResources().getConfiguration().screenWidthDp >= 600 ? 3 : 2));
    grid.setPadding(activity.dp(8), 0, activity.dp(8), activity.dp(8));
    grid.setClipToPadding(false);
    grid.setAdapter(adapter);
    new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP | ItemTouchHelper.DOWN | ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT,
        ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
      @Override public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder from, @NonNull RecyclerView.ViewHolder to) {
        int a = from.getBindingAdapterPosition(), b = to.getBindingAdapterPosition();
        if (a < 0 || b < 0) return false;
        host.move(a, b);
        adapter.notifyItemMoved(a, b);
        return true;
      }
      @Override public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
        int i = vh.getBindingAdapterPosition();
        if (i >= 0) closeAt(i);
      }
      @Override public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
        super.clearView(rv, vh);
        refresh(); // redraw the "current tab" outline after a move
      }
    }).attachToRecyclerView(grid);
    root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
    snack = new LinearLayout(activity);
    snack.setGravity(Gravity.CENTER_VERTICAL);
    snack.setBackground(activity.rounded(0xff2b3a55, 12));
    snack.setPadding(activity.dp(16), activity.dp(2), activity.dp(6), activity.dp(2));
    snack.setVisibility(View.GONE);
    snackText = new TextView(activity);
    snackText.setTextColor(ListPanel.TEXT);
    snackText.setTextSize(14);
    snack.addView(snackText, new LinearLayout.LayoutParams(0, -2, 1));
    snackAction = new TextView(activity);
    snackAction.setTextColor(ListPanel.ACCENT);
    snackAction.setTextSize(14);
    snackAction.setTypeface(null, Typeface.BOLD);
    snackAction.setGravity(Gravity.CENTER);
    snackAction.setPadding(activity.dp(12), 0, activity.dp(12), 0);
    snack.addView(snackAction, new LinearLayout.LayoutParams(-2, activity.dp(48)));
    LinearLayout.LayoutParams snackLp = new LinearLayout.LayoutParams(-1, -2);
    snackLp.setMargins(activity.dp(12), activity.dp(4), activity.dp(12), activity.dp(12));
    root.addView(snack, snackLp);
    dialog.setOnDismissListener(d -> finishSnack());

    dialog.setContentView(root);
    Window w = dialog.getWindow();
    if (w != null) {
      w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
      w.setStatusBarColor(surface);
      w.setNavigationBarColor(surface);
    }
  }

  private final LinearLayout snack;
  private final TextView snackText, snackAction;
  private Runnable snackExpire;
  private final android.os.Handler snackHandler = new android.os.Handler(android.os.Looper.getMainLooper());

  /** Undo bar inside the switcher (the browser's own bar is hidden behind it). */
  void showSnackbar(String text, String action, Runnable onAction, Runnable onExpire) {
    finishSnack();
    snackText.setText(text);
    snackAction.setText(action);
    snackAction.setVisibility(action == null ? View.GONE : View.VISIBLE);
    snack.setVisibility(View.VISIBLE);
    snackExpire = onExpire;
    snackAction.setOnClickListener(v -> {
      snackHandler.removeCallbacksAndMessages(null);
      snack.setVisibility(View.GONE);
      snackExpire = null;
      if (onAction != null) onAction.run();
      refresh();
    });
    snackHandler.postDelayed(this::finishSnack, 6000);
  }

  private void finishSnack() {
    snackHandler.removeCallbacksAndMessages(null);
    snack.setVisibility(View.GONE);
    Runnable r = snackExpire;
    snackExpire = null;
    if (r != null) r.run();
  }

  private TextView headerButton(String text, String description, Runnable r) {
    TextView t = new TextView(activity);
    t.setText(text);
    t.setTextColor(ListPanel.TEXT);
    t.setTextSize(22);
    t.setGravity(Gravity.CENTER);
    t.setMinWidth(activity.dp(48));
    t.setMinHeight(activity.dp(48));
    t.setPadding(activity.dp(8), 0, activity.dp(8), 0);
    t.setContentDescription(description);
    if (r != null) t.setOnClickListener(v -> r.run());
    return t;
  }

  private void closeAt(int i) {
    host.close(i);
    if (host.tabs().isEmpty()) { dialog.dismiss(); return; }
    refresh();
  }

  void refresh() {
    int n = host.tabs().size();
    title.setText(n == 1 ? "1 tab" : n + " tabs");
    adapter.notifyDataSetChanged();
  }

  void show() {
    refresh();
    dialog.show();
    int current = host.currentIndex();
    if (current >= 0) grid.scrollToPosition(current);
  }

  boolean isShowing() { return dialog.isShowing(); }
  void dismiss() { dialog.dismiss(); }

  private final class Holder extends RecyclerView.ViewHolder {
    final LinearLayout card;
    final ImageView favicon = new ImageView(activity), thumb = new ImageView(activity);
    final TextView title = new TextView(activity), close = new TextView(activity), placeholder = new TextView(activity);

    Holder(LinearLayout card) {
      super(card);
      this.card = card;
      card.setOrientation(LinearLayout.VERTICAL);
      card.setPadding(activity.dp(3), activity.dp(3), activity.dp(3), activity.dp(3));
      LinearLayout top = new LinearLayout(activity);
      top.setGravity(Gravity.CENTER_VERTICAL);
      top.setPadding(activity.dp(8), 0, 0, 0);
      top.addView(favicon, new LinearLayout.LayoutParams(activity.dp(16), activity.dp(16)));
      title.setTextColor(ListPanel.TEXT);
      title.setTextSize(13);
      title.setSingleLine(true);
      title.setEllipsize(android.text.TextUtils.TruncateAt.END);
      title.setPadding(activity.dp(6), 0, 0, 0);
      top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
      close.setText("×");
      close.setTextColor(ListPanel.MUTED);
      close.setTextSize(20);
      close.setGravity(Gravity.CENTER);
      top.addView(close, new LinearLayout.LayoutParams(activity.dp(40), activity.dp(40)));
      card.addView(top);
      FrameLayout preview = new FrameLayout(activity);
      preview.setBackgroundColor(0xffffffff);
      thumb.setScaleType(ImageView.ScaleType.MATRIX);
      preview.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
      placeholder.setTextColor(0xff5d6b80);
      placeholder.setTextSize(13);
      placeholder.setGravity(Gravity.CENTER);
      placeholder.setPadding(activity.dp(10), 0, activity.dp(10), 0);
      preview.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
      preview.setClipToOutline(true);
      preview.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
      preview.setBackground(activity.rounded(0xffffffff, 10));
      card.addView(preview, new LinearLayout.LayoutParams(-1, activity.dp(190)));
    }

    void bind(Tab tab, boolean current) {
      title.setText(tab.displayTitle());
      favicon.setImageBitmap(tab.favicon);
      favicon.setVisibility(tab.favicon == null ? View.GONE : View.VISIBLE);
      if (tab.thumbnail != null) {
        thumb.setImageBitmap(tab.thumbnail);
        // Scale the thumbnail to the card width, top-aligned like a page preview.
        thumb.post(() -> {
          if (tab.thumbnail == null || thumb.getWidth() == 0) return;
          float s = thumb.getWidth() / (float) tab.thumbnail.getWidth();
          android.graphics.Matrix m = new android.graphics.Matrix();
          m.setScale(s, s);
          thumb.setImageMatrix(m);
        });
        placeholder.setText("");
      } else {
        thumb.setImageBitmap(null);
        placeholder.setText(UrlTools.display(tab.currentUrl()));
      }
      int fill = current ? 0xff2c4a78 : (host.isPrivate() ? IncognitoActivity.PRIVATE_BADGE : ListPanel.PANEL);
      card.setBackground(activity.rounded(fill, 14));
      card.setContentDescription((current ? "Current tab: " : "Tab: ") + tab.displayTitle());
      close.setContentDescription("Close " + tab.displayTitle());
    }
  }

  private final class Adapter extends RecyclerView.Adapter<Holder> {
    @Override public int getItemCount() { return host.tabs().size(); }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
      LinearLayout card = new LinearLayout(activity);
      RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-1, -2);
      lp.setMargins(activity.dp(6), activity.dp(6), activity.dp(6), activity.dp(6));
      card.setLayoutParams(lp);
      return new Holder(card);
    }

    @Override public void onBindViewHolder(@NonNull Holder h, int position) {
      Tab tab = host.tabs().get(position);
      h.bind(tab, position == host.currentIndex());
      h.card.setOnClickListener(v -> {
        int i = h.getBindingAdapterPosition();
        if (i < 0) return;
        dialog.dismiss();
        host.select(i);
      });
      h.close.setOnClickListener(v -> { int i = h.getBindingAdapterPosition(); if (i >= 0) closeAt(i); });
    }
  }
}

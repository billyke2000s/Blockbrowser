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
    void groupDropped(Tab source, Tab target);
    void newTab();
    void groupTab(int index);
    void showMenu(View anchor);
    boolean isPrivate();
    void renameGroup(String oldName);
    void ungroup(String groupName);
    void closeGroup(String groupName);
    void newTabInGroup(String groupName);
    int groupColor(String name);
    void chooseGroupColor(String name);
    void removeTabFromGroup(Tab tab);
  }

  private final MainActivity activity;
  private final Host host;
  private final Dialog dialog;
  private final RecyclerView grid;
  private final TextView title;
  private final Adapter adapter = new Adapter();
  private String selectedGroup = null;
  private Tab dragSource, dragTarget;
  private final java.util.ArrayList<Object> entries = new java.util.ArrayList<>();

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
    TextView add = headerButton("+", "New tab", () -> {
      String group = selectedGroup;
      dialog.dismiss();
      if (group == null) host.newTab(); else host.newTabInGroup(group);
    });
    title.setOnClickListener(v -> {
      if (selectedGroup == null) return;
      new android.app.AlertDialog.Builder(activity).setTitle(selectedGroup)
          .setItems(new String[]{"Rename group", "Ungroup tabs", "Show all tabs"}, (d, which) -> {
            if (which == 0) host.renameGroup(selectedGroup);
            else if (which == 1) { host.ungroup(selectedGroup); selectedGroup = null; }
            else selectedGroup = null;
            refresh();
          }).show();
    });
    TextView more = headerButton("⋮", "Tab options", null);
    more.setOnClickListener(v -> {
      if (selectedGroup == null) host.showMenu(v);
      else openGroupMenu(selectedGroup);
    });
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
        if (a < 0 || b < 0 || a >= entries.size() || b >= entries.size()) return false;
        Object source = entries.get(a), target = entries.get(b);
        if (!(source instanceof Tab)) return false;
        if (dragSource == null) dragSource = (Tab) source;
        if (target instanceof String) {
          // A group card is a drop target; hovering over ordinary tabs is
          // reordering, not an implicit request to group them.
          dragTarget = firstTabInGroup((String) target);
          return dragTarget != null;
        }
        dragTarget = null;
        Tab fromTab = (Tab) source, toTab = (Tab) target;
        int fromIndex = host.tabs().indexOf(fromTab), toIndex = host.tabs().indexOf(toTab);
        if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return false;
        host.move(fromIndex, toIndex);
        // Host.move removes and inserts, rather than swapping. Rebuild the
        // visible group view from the actual tab order so cards and positions
        // cannot diverge (especially while reordering inside a group).
        refresh();
        return true;
      }
      @Override public int getDragDirs(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
        int i = vh.getBindingAdapterPosition();
        // Group cards are containers. Only individual tabs can be dragged.
        if (i < 0 || i >= entries.size() || !(entries.get(i) instanceof Tab)) return 0;
        return super.getDragDirs(rv, vh);
      }
      @Override public int getSwipeDirs(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
        int i = vh.getBindingAdapterPosition();
        // A group is a container, not a single tab. Never allow it to be
        // dismissed by a gesture intended for individual tabs.
        if (i < 0 || i >= entries.size() || !(entries.get(i) instanceof Tab)) return 0;
        return super.getSwipeDirs(rv, vh);
      }
      @Override public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
        int i = vh.getBindingAdapterPosition();
        if (i >= 0 && i < entries.size() && entries.get(i) instanceof Tab) {
          Tab tab = (Tab) entries.get(i);
          if (selectedGroup != null) { host.removeTabFromGroup(tab); refresh(); }
          else closeAt(host.tabs().indexOf(tab));
        }
      }
      @Override public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
        super.clearView(rv, vh);
        if (selectedGroup == null && dragSource != null && dragTarget != null
            && dragSource != dragTarget) host.groupDropped(dragSource, dragTarget);
        dragSource = null;
        dragTarget = null;
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

  private Tab firstTabInGroup(String name) {
    for (Tab t : host.tabs()) if (name.equals(t.groupId)) return t;
    return null;
  }

  private void openGroupMenu(String name) {
    new android.app.AlertDialog.Builder(activity).setTitle(name)
        .setItems(new String[]{"Open group", "Rename group", "Change colour", "Ungroup tabs", "Close group"}, (d, which) -> {
          if (which == 0) showGroup(name);
          else if (which == 1) host.renameGroup(name);
          else if (which == 2) host.chooseGroupColor(name);
          else if (which == 3) {
            host.ungroup(name);
            if (name.equals(selectedGroup)) selectedGroup = null;
          } else {
            host.closeGroup(name);
            if (name.equals(selectedGroup)) selectedGroup = null;
          }
          refresh();
        }).show();
  }

  private void closeAt(int i) {
    host.close(i);
    if (host.tabs().isEmpty()) { dialog.dismiss(); return; }
    refresh();
  }

  void refresh() {
    int n = host.tabs().size();
    entries.clear();
    java.util.HashSet<String> seen = new java.util.HashSet<>();
    for (Tab tab : host.tabs()) {
      if (selectedGroup != null) { if (selectedGroup.equals(tab.groupId)) entries.add(tab); }
      else if (tab.groupId == null) entries.add(tab);
      else if (seen.add(tab.groupId)) entries.add(tab.groupId);
    }
    title.setText(selectedGroup == null ? (n == 1 ? "1 tab" : n + " tabs") : selectedGroup + " ▾");
    adapter.notifyDataSetChanged();
  }

  void show() {
    selectedGroup = null;
    refresh();
    dialog.show();
    int current = host.currentIndex();
    if (current >= 0 && current < host.tabs().size()) {
      Tab active = host.tabs().get(current);
      Object visibleEntry = active.groupId == null ? active : active.groupId;
      int visibleIndex = entries.indexOf(visibleEntry);
      if (visibleIndex >= 0) grid.scrollToPosition(visibleIndex);
    }
  }

  void showGroup(String group) {
    selectedGroup = group;
    refresh();
    dialog.show();
  }

  boolean isShowing() { return dialog.isShowing(); }
  void dismiss() { dialog.dismiss(); }

  private final class Holder extends RecyclerView.ViewHolder {
    final LinearLayout card;
    final ImageView favicon = new ImageView(activity), thumb = new ImageView(activity);
    final TextView title = new TextView(activity), close = new TextView(activity), group = new TextView(activity), placeholder = new TextView(activity);

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
      group.setText("⊞");
      group.setTextColor(ListPanel.TEXT);
      group.setTextSize(21);
      group.setGravity(Gravity.CENTER);
      group.setContentDescription("Add tab to group");
      top.addView(group, new LinearLayout.LayoutParams(activity.dp(40), activity.dp(40)));
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
      title.setText(tab.groupId == null ? tab.displayTitle() : "[" + tab.groupId + "] " + tab.displayTitle());
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
    @Override public int getItemCount() { return entries.size(); }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
      LinearLayout card = new LinearLayout(activity);
      RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-1, -2);
      lp.setMargins(activity.dp(6), activity.dp(6), activity.dp(6), activity.dp(6));
      card.setLayoutParams(lp);
      return new Holder(card);
    }

    @Override public void onBindViewHolder(@NonNull Holder h, int position) {
      Object entry = entries.get(position);
      if (entry instanceof String) {
        String name = (String) entry;
        java.util.ArrayList<Tab> members = new java.util.ArrayList<>();
        for (Tab t : host.tabs()) if (name.equals(t.groupId)) members.add(t);
        h.title.setText(name + " · " + members.size() + " tabs");
        h.favicon.setVisibility(View.GONE);
        h.group.setText("⋮");
        h.group.setContentDescription("Group options");
        h.close.setVisibility(View.GONE);
        h.thumb.setVisibility(View.GONE);
        h.placeholder.setVisibility(View.VISIBLE);
        h.placeholder.setText("");
        FrameLayout preview = (FrameLayout) h.thumb.getParent();
        for (int i = preview.getChildCount() - 1; i >= 2; i--) preview.removeViewAt(i);
        android.widget.GridLayout mosaic = new android.widget.GridLayout(activity);
        mosaic.setColumnCount(2);
        mosaic.setRowCount(2);
        for (int j = 0; j < 4; j++) {
          FrameLayout cell = new FrameLayout(activity);
          cell.setBackground(activity.rounded(host.groupColor(name), 8));
          android.widget.ImageView image = new android.widget.ImageView(activity);
          image.setScaleType(ImageView.ScaleType.CENTER_CROP);
          if (j < members.size() && members.get(j).thumbnail != null) image.setImageBitmap(members.get(j).thumbnail);
          cell.addView(image, new FrameLayout.LayoutParams(-1, -1));
          android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams(
              android.widget.GridLayout.spec(j / 2, 1f), android.widget.GridLayout.spec(j % 2, 1f));
          lp.width = 0;
          lp.height = 0;
          lp.setMargins(activity.dp(3), activity.dp(3), activity.dp(3), activity.dp(3));
          mosaic.addView(cell, lp);
        }
        preview.addView(mosaic, new FrameLayout.LayoutParams(-1, -1));
        h.card.setBackground(activity.rounded(host.groupColor(name), 14));
        h.card.setContentDescription("Group " + name + ", " + members.size() + " tabs");
        h.card.setOnClickListener(v -> showGroup(name));
        h.group.setOnClickListener(v -> openGroupMenu(name));
      } else {
        Tab tab = (Tab) entry;
        h.thumb.setVisibility(View.VISIBLE);
        h.placeholder.setVisibility(View.VISIBLE);
        h.close.setVisibility(View.VISIBLE);
        h.group.setText("⊞");
        h.group.setContentDescription(selectedGroup == null ? "Add tab to group" : "Move or remove tab from group");
        FrameLayout preview = (FrameLayout) h.thumb.getParent();
        for (int i = preview.getChildCount() - 1; i >= 2; i--) preview.removeViewAt(i);
        h.bind(tab, host.tabs().indexOf(tab) == host.currentIndex());
        h.card.setOnClickListener(v -> {
          int i = host.tabs().indexOf(tab);
          if (i < 0) return;
          dialog.dismiss();
          host.select(i);
        });
        h.close.setOnClickListener(v -> { int i = host.tabs().indexOf(tab); if (i >= 0) closeAt(i); });
        h.group.setOnClickListener(v -> { int i = host.tabs().indexOf(tab); if (i >= 0) host.groupTab(i); });
      }
    }
  }
}

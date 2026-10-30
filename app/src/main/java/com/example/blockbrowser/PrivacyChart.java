package com.example.blockbrowser;

import android.app.Dialog;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Blocking statistics: a doughnut of blocked requests on the open pages, plus the saved totals from
 * {@link BlockingStats} (normal windows only). Everything stays on the device. Never estimates categories
 * (ads vs trackers vs malware) it cannot measure.
 */
final class PrivacyChart {
  private PrivacyChart() {}
  static void show(MainActivity a) {
    Dialog dialog = new Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar);
    LinearLayout root = new LinearLayout(a);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(a.dp(20), 0, a.dp(20), a.dp(16));
    root.setBackgroundColor(ListPanel.SURFACE);
    // Same header as the other screens: ← and a bold title, below the status bar.
    LinearLayout header = new LinearLayout(a);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(0, a.dp(12), 0, a.dp(4));
    TextView back = text(a, "←", 21, ListPanel.TEXT);
    back.setGravity(Gravity.CENTER);
    back.setMinWidth(a.dp(48));
    back.setMinHeight(a.dp(48));
    back.setContentDescription("Back");
    back.setOnClickListener(v -> dialog.dismiss());
    header.addView(back);
    TextView heading = text(a, "Blocking statistics", 22, ListPanel.TEXT);
    heading.setTypeface(null, android.graphics.Typeface.BOLD);
    header.addView(heading);
    root.addView(header);
    TextView note = text(a, a.blockingStats == null ? "Private window · nothing is saved" : "Kept on this device · site names only, never full addresses", 13, ListPanel.MUTED);
    note.setPadding(a.dp(4), 0, 0, 0);
    root.addView(note);
    if (a.blockingStats != null) a.blockingStats.flush();
    long total = a.blockingStats == null ? a.blockedCount.get() : a.blockingStats.total();
    TextView count = text(a, String.format(Locale.ROOT, "%,d requests blocked", total), 21, ListPanel.ACCENT);
    count.setPadding(0, a.dp(24), 0, a.dp(12));
    root.addView(count);
    List<Tab> tabs = new ArrayList<>(a.tabs);
    tabs.sort(Comparator.comparingInt((Tab t) -> t.blocked.get()).reversed());
    int visible = 0;
    for (Tab t : tabs) if (t.blocked.get() > 0) visible++;
    // Closed tabs and navigations reset per-page counters. Fall back to saved
    // blocked-domain counts so the chart remains useful across sessions.
    if (a.blockingStats != null) {
      Map<String, Long> saved = a.blockingStats.topDomains();
      List<Map.Entry<String, Long>> rankedChart = new ArrayList<>(saved.entrySet());
      rankedChart.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
      int n = Math.min(6, rankedChart.size());
      if (n > 0) {
        long[] values = new long[n];
        String[] labels = new String[n];
        for (int i = 0; i < n; i++) {
          values[i] = rankedChart.get(i).getValue();
          labels[i] = rankedChart.get(i).getKey();
        }
        addDoughnut(a, root, values, labels, "Blocked requests by domain (saved totals)");
      }
    }
    if (visible == 0) {
      if (a.blockingStats == null || a.blockingStats.topDomains().isEmpty()) root.addView(text(a, "No blocked requests recorded yet.", 15, ListPanel.MUTED));
    } else {
      int[] values = new int[visible];
      int k = 0;
      for (Tab t : tabs) if (t.blocked.get() > 0) values[k++] = t.blocked.get();
      View chart = new View(a) {
        final Paint p = new Paint(3);
        final int[] colors = {0xff83b9ff,0xff60d6bb,0xffffbc76,0xffbc9bff,0xffff829e,0xff8facc9};
        @Override protected void onDraw(Canvas c) {
          super.onDraw(c);
          float side = Math.min(getWidth(), getHeight()) - a.dp(16);
          float left = (getWidth()-side)/2f, top = (getHeight()-side)/2f;
          RectF bounds = new RectF(left,top,left+side,top+side);
          long sum=0; for(int v:values) sum+=v;
          float start=-90;
          for(int i=0;i<values.length;i++) {
            float sweep=360f*values[i]/sum;
            p.setColor(colors[i%colors.length]);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(a.dp(30));
            c.drawArc(new RectF(bounds.left+a.dp(20),bounds.top+a.dp(20),bounds.right-a.dp(20),bounds.bottom-a.dp(20)),start,sweep,false,p);
            start+=sweep;
          }
          p.setStyle(Paint.Style.FILL);
        }
      };
      root.addView(chart, new LinearLayout.LayoutParams(-1,a.dp(230)));
      root.addView(text(a, "Blocked requests by currently open page", 17, ListPanel.TEXT));
      int index=0;
      for (Tab t:tabs) {
        if(t.blocked.get()==0) continue;
        String label = t.pageHost.isEmpty() ? t.displayTitle() : t.pageHost;
        TextView item=text(a, "●  "+label+"  ·  "+t.blocked.get(), 14, new int[]{0xff83b9ff,0xff60d6bb,0xffffbc76,0xffbc9bff,0xffff829e,0xff8facc9}[index++%6]);
        item.setPadding(0,a.dp(12),0,a.dp(4));
        root.addView(item);
      }
    }
    if (a.blockingStats != null) {
      Map<String,Long> domains=a.blockingStats.topDomains();
      List<Map.Entry<String,Long>> ranked=new ArrayList<>(domains.entrySet());
      ranked.sort((x,y)->Long.compare(y.getValue(),x.getValue()));
      TextView domainsTitle=text(a,"Most blocked domains",19,ListPanel.TEXT);
      domainsTitle.setPadding(0,a.dp(22),0,a.dp(8));
      root.addView(domainsTitle);
      if(ranked.isEmpty()) root.addView(text(a,"No domains recorded yet.",14,ListPanel.MUTED));
      for(int i=0;i<Math.min(10,ranked.size());i++){
        Map.Entry<String,Long> e=ranked.get(i);
        TextView row=text(a,(i+1)+".  "+e.getKey()+"  ·  "+e.getValue(),14,ListPanel.TEXT);
        row.setPadding(0,a.dp(7),0,a.dp(7));
        root.addView(row);
      }
      Map<String,Long> days=a.blockingStats.daily();
      List<String> dates=new ArrayList<>(days.keySet());
      dates.sort(java.util.Collections.reverseOrder());
      TextView dailyTitle=text(a,"Recent daily activity",19,ListPanel.TEXT);
      dailyTitle.setPadding(0,a.dp(22),0,a.dp(8));
      root.addView(dailyTitle);
      for(int i=0;i<Math.min(7,dates.size());i++){
        String date=dates.get(i);
        root.addView(text(a,date+"  ·  "+days.get(date)+" blocked",14,ListPanel.MUTED));
      }
    }
    TextView explanation=text(a,"Saved blocked-domain totals appear above whenever available, alongside current-page counters when present. Open-page counters reset on navigation. The saved domain list retains up to 200 domains, so its chart may not cover the full lifetime total. Ads, trackers and malware cannot be accurately separated using the current combined blocklist.",13,ListPanel.MUTED);
    explanation.setPadding(0,a.dp(24),0,a.dp(12));
    root.addView(explanation);
    LinearLayout buttons=new LinearLayout(a);
    buttons.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
    if (a.blockingStats != null) {
      TextView reset=button(a,"Reset statistics");
      reset.setOnClickListener(v->new android.app.AlertDialog.Builder(a).setTitle("Reset blocking statistics?")
          .setMessage("Deletes the saved totals, blocked addresses and daily counts.")
          .setPositiveButton("Reset",(d,n)->{a.blockingStats.clear();dialog.dismiss();show(a);})
          .setNegativeButton("Cancel",null).show());
      buttons.addView(reset);
    }
    TextView close=button(a,"Done");
    close.setOnClickListener(v->dialog.dismiss());
    buttons.addView(close);
    root.addView(buttons);
    ScrollView scroll=new ScrollView(a);
    scroll.setFillViewport(true);
    scroll.setFitsSystemWindows(true);   // keep the content clear of the status and navigation bars
    scroll.setBackgroundColor(ListPanel.SURFACE);
    scroll.addView(root);
    dialog.setContentView(scroll);
    Window w=dialog.getWindow();
    if(w!=null){w.setLayout(-1,-1);w.setStatusBarColor(ListPanel.SURFACE);w.setNavigationBarColor(ListPanel.SURFACE);}
    dialog.show();
    if(dialog.getWindow()!=null) dialog.getWindow().setLayout(-1,-1);
  }
  private static void addDoughnut(MainActivity a, LinearLayout root, long[] values, String[] labels, String heading) {
    final int[] colors = {0xff83b9ff,0xff60d6bb,0xffffbc76,0xffbc9bff,0xffff829e,0xff8facc9};
    View chart = new View(a) {
      final Paint paint = new Paint(3);
      @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float side = Math.min(getWidth(), getHeight()) - a.dp(26);
        float inset = a.dp(25);
        RectF arc = new RectF((getWidth()-side)/2+inset,(getHeight()-side)/2+inset,
            (getWidth()+side)/2-inset,(getHeight()+side)/2-inset);
        long sum=0; for(long v:values) sum+=v;
        if(sum<=0)return;
        float start=-90;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(a.dp(29));
        for(int i=0;i<values.length;i++) {
          float sweep=360f*values[i]/sum;
          paint.setColor(colors[i%colors.length]);
          canvas.drawArc(arc,start,sweep,false,paint);
          start+=sweep;
        }
        paint.setStyle(Paint.Style.FILL);
      }
    };
    root.addView(chart,new LinearLayout.LayoutParams(-1,a.dp(230)));
    root.addView(text(a,heading,17,ListPanel.TEXT));
    for(int i=0;i<labels.length;i++) {
      TextView line=text(a,"●  "+labels[i]+"  ·  "+values[i],14,colors[i%colors.length]);
      line.setPadding(0,a.dp(9),0,a.dp(4));
      root.addView(line);
    }
  }

  private static TextView button(MainActivity a,String s){
    TextView b=text(a,s,15,ListPanel.ACCENT);
    b.setGravity(Gravity.CENTER);
    b.setMinHeight(a.dp(48));
    b.setPadding(a.dp(16),0,a.dp(16),0);
    b.setTypeface(null,android.graphics.Typeface.BOLD);
    return b;
  }
  private static TextView text(MainActivity a,String s,int size,int color){
    TextView v=new TextView(a);v.setText(s);v.setTextSize(size);v.setTextColor(color);return v;
  }
}

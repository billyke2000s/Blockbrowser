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

/** Local, session-only privacy statistics. Never estimates categories it cannot measure. */
final class PrivacyChart {
  private PrivacyChart() {}
  static void show(MainActivity a) {
    Dialog dialog = new Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar);
    LinearLayout root = new LinearLayout(a);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(a.dp(20), a.dp(24), a.dp(20), a.dp(16));
    root.setBackgroundColor(ListPanel.SURFACE);
    TextView heading = text(a, "Blocking statistics", 24, ListPanel.TEXT);
    root.addView(heading);
    TextView note = text(a, "This session · stored only in memory", 13, ListPanel.MUTED);
    root.addView(note);
    long total = a.blockedCount.get();
    TextView count = text(a, String.format(Locale.ROOT, "%,d requests blocked", total), 21, ListPanel.ACCENT);
    count.setPadding(0, a.dp(24), 0, a.dp(12));
    root.addView(count);
    List<Tab> tabs = new ArrayList<>(a.tabs);
    tabs.sort(Comparator.comparingInt((Tab t) -> t.blocked.get()).reversed());
    int visible = 0;
    for (Tab t : tabs) if (t.blocked.get() > 0) visible++;
    if (visible == 0) {
      root.addView(text(a, "No blocked requests on currently open pages yet. Browse with blocking enabled to see the chart.", 15, ListPanel.MUTED));
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
    TextView explanation=text(a,"The chart shows current-page counters, which reset on navigation. The session total also includes pages you have left or closed. Block Browser does not currently record ad-versus-tracker categories or top blocked domains.",13,ListPanel.MUTED);
    explanation.setPadding(0,a.dp(24),0,a.dp(12));
    root.addView(explanation);
    TextView close=text(a,"DONE",15,ListPanel.ACCENT);
    close.setGravity(Gravity.END);
    close.setPadding(0,a.dp(12),0,a.dp(12));
    close.setOnClickListener(v->dialog.dismiss());
    root.addView(close);
    ScrollView scroll=new ScrollView(a);
    scroll.setFillViewport(true);
    scroll.addView(root);
    dialog.setContentView(scroll);
    Window w=dialog.getWindow();
    if(w!=null){w.setLayout(-1,-1);w.setStatusBarColor(ListPanel.SURFACE);w.setNavigationBarColor(ListPanel.SURFACE);}
    dialog.show();
    if(dialog.getWindow()!=null) dialog.getWindow().setLayout(-1,-1);
  }
  private static TextView text(MainActivity a,String s,int size,int color){
    TextView v=new TextView(a);v.setText(s);v.setTextSize(size);v.setTextColor(color);return v;
  }
}

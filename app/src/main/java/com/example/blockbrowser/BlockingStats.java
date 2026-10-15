package com.example.blockbrowser;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bounded, local blocking telemetry. Never records full URLs or browsing history. */
final class BlockingStats {
  private static final String KEY = "blocking_stats_v1";
  private static final int MAX_DOMAINS = 200;
  private final SharedPreferences prefs;
  private final Map<String,Long> domains = new HashMap<>();
  private final Map<String,Long> days = new HashMap<>();
  private long total;
  private boolean dirty;
  BlockingStats(Context context) {
    prefs=context.getSharedPreferences("blocking_statistics",Context.MODE_PRIVATE);
    try {
      JSONObject root=new JSONObject(prefs.getString(KEY,"{}"));
      total=root.optLong("total",0);
      JSONObject d=root.optJSONObject("domains");
      if(d!=null) for(java.util.Iterator<String> it=d.keys();it.hasNext();) {String k=it.next();domains.put(k,d.optLong(k));}
      JSONObject dates=root.optJSONObject("days");
      if(dates!=null) for(java.util.Iterator<String> it=dates.keys();it.hasNext();) {String k=it.next();days.put(k,dates.optLong(k));}
    } catch(Exception ignored) { domains.clear();days.clear();total=0; }
  }
  synchronized void record(String host) {
    if(host==null || host.isEmpty()) return;
    String domain=host.toLowerCase(Locale.ROOT);
    if(!domain.matches("[a-z0-9.-]{1,253}")) return;
    total++;
    domains.put(domain,domains.getOrDefault(domain,0L)+1);
    String today=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(new Date());
    days.put(today,days.getOrDefault(today,0L)+1);
    trim();
    dirty=true;
  }
  private void trim() {
    if(domains.size()>MAX_DOMAINS) {
      String smallest=Collections.min(domains.keySet(),(x,y)->Long.compare(domains.get(x),domains.get(y)));
      domains.remove(smallest);
    }
    if(days.size()>90) {
      String oldest=Collections.min(days.keySet());
      days.remove(oldest);
    }
  }
  synchronized long total(){return total;}
  synchronized Map<String,Long> topDomains(){return new HashMap<>(domains);}
  synchronized Map<String,Long> daily(){return new HashMap<>(days);}
  synchronized void flush(){
    if(!dirty)return;
    try {
      JSONObject root=new JSONObject(),d=new JSONObject(),dates=new JSONObject();
      for(Map.Entry<String,Long> e:domains.entrySet())d.put(e.getKey(),e.getValue());
      for(Map.Entry<String,Long> e:days.entrySet())dates.put(e.getKey(),e.getValue());
      root.put("total",total);root.put("domains",d);root.put("days",dates);
      prefs.edit().putString(KEY,root.toString()).apply();
      dirty=false;
    }catch(Exception ignored){}
  }
  synchronized void clear(){total=0;domains.clear();days.clear();dirty=true;flush();}
}

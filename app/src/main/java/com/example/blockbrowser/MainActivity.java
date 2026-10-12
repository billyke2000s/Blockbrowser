package com.example.blockbrowser;

import android.app.*;
import android.os.*;
import android.graphics.Color;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.content.*;
import android.webkit.*;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
  LinearLayout root, toolbar, tabbar; EditText address; FrameLayout frame, outer; HorizontalScrollView tabScroll; TextView exitFs;
  boolean fullscreen = false, fullscreenBeforeVideo = false; View customView; WebChromeClient.CustomViewCallback customViewCallback;
  ArrayList<WebView> tabs = new ArrayList<>(); int current = -1; volatile boolean blockAds = true;
  static final List<String> BUILTIN_BLOCKED = Arrays.asList("doubleclick.net","googlesyndication.com","googleadservices.com","adnxs.com","adsrvr.org","taboola.com","outbrain.com","scorecardresearch.com","criteo.com","pubmatic.com","rubiconproject.com","openx.net","amazon-adsystem.com","facebook.net");
  // Replaced in one step once assets/blocklist.txt has loaded, so request threads never see a half-built set.
  volatile Set<String> blocked = new HashSet<>(BUILTIN_BLOCKED); volatile boolean listLoaded = false;
  void loadBlocklist(){new Thread(()->{HashSet<String> set=new HashSet<>(300000);set.addAll(BUILTIN_BLOCKED);
    try(java.io.BufferedReader r=new java.io.BufferedReader(new java.io.InputStreamReader(getAssets().open("blocklist.txt"),"UTF-8"))){String line;while((line=r.readLine())!=null){line=line.trim();if(!line.isEmpty()&&line.charAt(0)!='#')set.add(line.toLowerCase(Locale.ROOT));}}
    catch(java.io.IOException e){android.util.Log.w("BlockBrowser","blocklist.txt not loaded, using built-in list only",e);}
    blocked=set;listLoaded=true;},"blocklist-loader").start();}
  int dp(int n){return (int)(n*getResources().getDisplayMetrics().density);}
  TextView button(String s, Runnable r){TextView t=new TextView(this);t.setText(s);t.setTextColor(Color.WHITE);t.setTextSize(17);t.setGravity(Gravity.CENTER);t.setPadding(dp(10),dp(12),dp(10),dp(12));t.setOnClickListener(v->r.run());return t;}
  @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(0xff10131a);getWindow().setNavigationBarColor(0xff10131a);
    loadBlocklist();
    root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(0xff10131a);
    outer=new FrameLayout(this);outer.setBackgroundColor(0xff10131a);outer.addView(root,new FrameLayout.LayoutParams(-1,-1));setContentView(outer);
    setupEdgeToEdge();
    toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);root.addView(toolbar);
    toolbar.addView(button("‹",()->{if(current>=0&&tabs.get(current).canGoBack())tabs.get(current).goBack();}));
    toolbar.addView(button("›",()->{if(current>=0&&tabs.get(current).canGoForward())tabs.get(current).goForward();}));
    address=new EditText(this);address.setContentDescription("Address bar");address.setSingleLine(true);address.setTextColor(Color.WHITE);address.setHintTextColor(0xffa0a0a0);address.setHint("Search or enter URL");address.setTextSize(14);address.setSelectAllOnFocus(true);toolbar.addView(address,new LinearLayout.LayoutParams(0,dp(52),1));
    address.setOnEditorActionListener((v,id,event)->{navigate(address.getText().toString());return true;});
    toolbar.addView(button("↻",()->{if(current>=0)tabs.get(current).reload();}));
    toolbar.addView(button("⋮",this::menu));
    tabbar=new LinearLayout(this);tabScroll=new HorizontalScrollView(this);tabScroll.setHorizontalScrollBarEnabled(false);tabScroll.addView(tabbar);root.addView(tabScroll);
    frame=new FrameLayout(this);root.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
    exitFs=button("Exit full screen",()->setFullscreen(false));exitFs.setTextSize(13);exitFs.setPadding(dp(14),dp(8),dp(14),dp(8));
    android.graphics.drawable.GradientDrawable pill=new android.graphics.drawable.GradientDrawable();pill.setColor(0xcc10131a);pill.setCornerRadius(dp(20));exitFs.setBackground(pill);exitFs.setAlpha(0.8f);exitFs.setVisibility(View.GONE);
    FrameLayout.LayoutParams exitLp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.END);exitLp.setMargins(0,dp(24),dp(16),0);outer.addView(exitFs,exitLp);
    newTab("https://www.google.com");
  }
  // Android 15+ draws apps behind the status and navigation bars (edge-to-edge). Draw edge-to-edge on every
  // version and pad the UI by the bars' size ourselves, so the toolbar sits below the status bar everywhere.
  void setupEdgeToEdge(){getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);else outer.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    outer.setOnApplyWindowInsetsListener((v,ins)->{int l,t,r,bars,keyboard;
      if(Build.VERSION.SDK_INT>=30){android.graphics.Insets b=ins.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());l=b.left;t=b.top;r=b.right;bars=b.bottom;keyboard=ins.getInsets(WindowInsets.Type.ime()).bottom;}
      else{l=ins.getSystemWindowInsetLeft();t=ins.getSystemWindowInsetTop();r=ins.getSystemWindowInsetRight();bars=ins.getSystemWindowInsetBottom();keyboard=bars;}
      android.util.Log.i("BlockBrowserInsets","top="+t+" bottom="+bars+" keyboard="+keyboard+" fullscreen="+fullscreen); // read by scripts/ui-check.sh
      if(fullscreen)root.setPadding(0,0,0,keyboard);else root.setPadding(l,t,r,Math.max(bars,keyboard));return ins;});}
  void setFullscreen(boolean on){fullscreen=on;int vis=on?View.GONE:View.VISIBLE;toolbar.setVisibility(vis);tabScroll.setVisibility(vis);
    exitFs.setVisibility(on&&customView==null?View.VISIBLE:View.GONE);if(on)exitFs.bringToFront();
    if(on){InputMethodManager imm=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);imm.hideSoftInputFromWindow(address.getWindowToken(),0);address.clearFocus();}
    if(Build.VERSION.SDK_INT>=28){WindowManager.LayoutParams lp=getWindow().getAttributes();lp.layoutInDisplayCutoutMode=on?WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES:WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;getWindow().setAttributes(lp);}
    applySystemBars();outer.requestApplyInsets();}
  void applySystemBars(){if(Build.VERSION.SDK_INT>=30){WindowInsetsController c=getWindow().getInsetsController();if(c==null)return;
      if(fullscreen){c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);c.hide(WindowInsets.Type.systemBars());}else c.show(WindowInsets.Type.systemBars());}
    else{int f=View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;if(fullscreen)f|=View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;outer.setSystemUiVisibility(f);}}
  // Older Android versions can drop immersive mode after a dialog; re-apply when the window regains focus.
  @Override public void onWindowFocusChanged(boolean hasFocus){super.onWindowFocusChanged(hasFocus);if(hasFocus&&fullscreen)applySystemBars();}
  // Site-requested full screen (e.g. the full-screen button on a YouTube video).
  final WebChromeClient chromeClient=new WebChromeClient(){
    @Override public void onShowCustomView(View view,CustomViewCallback cb){if(customView!=null){cb.onCustomViewHidden();return;}
      customView=view;customViewCallback=cb;fullscreenBeforeVideo=fullscreen;view.setBackgroundColor(Color.BLACK);outer.addView(view,new FrameLayout.LayoutParams(-1,-1));setFullscreen(true);}
    @Override public void onHideCustomView(){hideCustomView();}};
  void hideCustomView(){if(customView==null)return;outer.removeView(customView);customView=null;if(customViewCallback!=null)customViewCallback.onCustomViewHidden();customViewCallback=null;setFullscreen(fullscreenBeforeVideo);}
  boolean isBlocked(String url){if(!blockAds)return false;try{String host=android.net.Uri.parse(url).getHost();if(host==null)return false;host=host.toLowerCase(Locale.ROOT);Set<String> set=blocked;while(true){if(set.contains(host))return true;int dot=host.indexOf('.');if(dot<0||dot==host.length()-1)break;host=host.substring(dot+1);}}catch(Exception ignored){}return false;}
  void newTab(String url){WebView w=new WebView(this);w.setBackgroundColor(Color.WHITE);WebSettings s=w.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setSupportMultipleWindows(false);s.setBuiltInZoomControls(true);s.setDisplayZoomControls(false);
    w.setWebViewClient(new WebViewClient(){@Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest req){if(!isBlocked(req.getUrl().toString()))return null;
      if(req.isForMainFrame()){String html="<html><body style=\"font-family:sans-serif;padding:24px\"><h3>Blocked by Block Browser</h3><p>"+android.text.TextUtils.htmlEncode(String.valueOf(req.getUrl().getHost()))+" is on the ad/tracker blocklist.</p><p>Turn adblock off in the \u22ee menu to open it anyway.</p></body></html>";
        return new WebResourceResponse("text/html","UTF-8",new java.io.ByteArrayInputStream(html.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
      // Make the request FAIL (net::ERR_FAILED, like uBlock or a DNS blocker) instead of answering with an
      // empty 200 OK. WebView calls available() before sending headers; if it throws, the request fails
      // outright (see Chromium components/embedder_support/android/util/input_stream_reader.cc).
      return new WebResourceResponse("text/plain","UTF-8",new FailingStream());}
    @Override public void onPageFinished(WebView view,String url){if(current>=0&&tabs.get(current)==view)address.setText(url);refreshTabs();}});
    w.setWebChromeClient(chromeClient);tabs.add(w);switchTab(tabs.size()-1);w.loadUrl(url);
  }
  void switchTab(int n){if(n<0||n>=tabs.size())return;current=n;frame.removeAllViews();WebView w=tabs.get(n);if(w.getParent()!=null)((android.view.ViewGroup)w.getParent()).removeView(w);frame.addView(w);address.setText(w.getUrl()==null?"":w.getUrl());refreshTabs();}
  void refreshTabs(){tabbar.removeAllViews();for(int i=0;i<tabs.size();i++){final int j=i;String title=tabs.get(i).getTitle();if(title==null||title.isEmpty())title="Tab "+(i+1);if(title.length()>13)title=title.substring(0,13)+"…";TextView t=button((i==current?"● ":"")+title,()->switchTab(j));t.setTextSize(12);tabbar.addView(t);}tabbar.addView(button("+",()->newTab("https://www.google.com")));}
  void navigate(String text){text=text.trim();if(text.isEmpty())return;String url;if(text.startsWith("https://")||text.startsWith("http://"))url=text;else if(text.contains(".")&&!text.contains(" "))url="https://"+text;else url="https://www.google.com/search?q="+android.net.Uri.encode(text);tabs.get(current).loadUrl(url);((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(),0);address.clearFocus();}
  void menu(){String[] items={blockAds?"Disable adblock":"Enable adblock","New tab","Close tab","Home","Full screen",listLoaded?"Blocklist: "+blocked.size()+" domains":"Blocklist: loading…"};new AlertDialog.Builder(this).setItems(items,(d,which)->{switch(which){case 0:blockAds=!blockAds;Toast.makeText(this,blockAds?"Adblock on":"Adblock off",Toast.LENGTH_SHORT).show();break;case 1:newTab("https://www.google.com");break;case 2:if(tabs.size()>1){WebView w=tabs.remove(current);frame.removeView(w);w.destroy();switchTab(Math.max(0,current-1));}break;case 3:navigate("https://www.google.com");break;case 4:setFullscreen(true);Toast.makeText(this,"Press Back or tap Exit to leave full screen",Toast.LENGTH_SHORT).show();break;}}).show();}
  @Override public void onBackPressed(){if(customView!=null){hideCustomView();return;}if(fullscreen){setFullscreen(false);return;}if(current>=0&&tabs.get(current).canGoBack())tabs.get(current).goBack();else super.onBackPressed();}
  @Override protected void onDestroy(){for(WebView w:tabs){if(w.getParent()!=null)((android.view.ViewGroup)w.getParent()).removeView(w);w.destroy();}super.onDestroy();}
  /** Stream that throws as soon as WebView touches it, so the blocked request ends in a network error. */
  static final class FailingStream extends java.io.InputStream{
    @Override public int available() throws java.io.IOException{throw new java.io.IOException("Blocked by Block Browser");}
    @Override public int read() throws java.io.IOException{throw new java.io.IOException("Blocked by Block Browser");}
    @Override public int read(byte[] b,int off,int len) throws java.io.IOException{throw new java.io.IOException("Blocked by Block Browser");}
  }
}

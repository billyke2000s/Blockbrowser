package com.example.blockbrowser;

import android.app.*;
import android.os.*;
import android.graphics.Color;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.content.*;
import android.webkit.*;
import android.widget.*;
import android.net.Uri;
import android.database.Cursor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.res.Configuration;
import java.util.*;

public class MainActivity extends Activity {
  LinearLayout root, toolbar, tabbar; EditText address; FrameLayout frame, outer; HorizontalScrollView tabScroll; TextView exitFs; Handler handler=new Handler(Looper.getMainLooper()); Runnable fadeTask; long blockedCount=0; android.content.SharedPreferences prefs; java.util.HashMap<String,Boolean> siteAdblock=new java.util.HashMap<>(); float downX,downY;
  boolean fullscreen = false, fullscreenBeforeVideo = false; View customView; WebChromeClient.CustomViewCallback customViewCallback;
  boolean restoringSession=false; Handler sessionHandler=new Handler(Looper.getMainLooper()); Runnable sessionSave; HashMap<WebView,Integer> restoreY=new HashMap<>(); HashMap<WebView,android.util.SparseIntArray> pagePositions=new HashMap<>(); HashSet<WebView> pageLoading=new HashSet<>();
  ArrayList<WebView> tabs = new ArrayList<>(); int current = -1; volatile boolean blockAds = true;
  static final List<String> BUILTIN_BLOCKED = Arrays.asList("doubleclick.net","googlesyndication.com","googleadservices.com","adnxs.com","adsrvr.org","taboola.com","outbrain.com","scorecardresearch.com","criteo.com","pubmatic.com","rubiconproject.com","openx.net","amazon-adsystem.com","facebook.net");
  // Replaced in one step once assets/blocklist.txt has loaded, so request threads never see a half-built set.
  volatile Set<String> blocked = new HashSet<>(BUILTIN_BLOCKED); volatile boolean listLoaded = false;
  void loadBlocklist(){new Thread(()->{HashSet<String> set=new HashSet<>(300000);set.addAll(BUILTIN_BLOCKED);
    try(java.io.BufferedReader r=new java.io.BufferedReader(new java.io.InputStreamReader(getAssets().open("blocklist.txt"),"UTF-8"))){String line;while((line=r.readLine())!=null){line=line.trim();if(!line.isEmpty()&&line.charAt(0)!='#')set.add(line.toLowerCase(Locale.ROOT));}}
    catch(java.io.IOException e){android.util.Log.w("BlockBrowser","blocklist.txt not loaded, using built-in list only",e);}
    blocked=set;listLoaded=true;},"blocklist-loader").start();}
  int dp(int n){return (int)(n*getResources().getDisplayMetrics().density);}
  static final int SURFACE=0xff111827, PANEL=0xff1b2638, ACCENT=0xff83b9ff, MUTED=0xffaab8cc;
  GradientDrawable rounded(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
  TextView button(String s,Runnable r){TextView t=new TextView(this);t.setText(s);t.setTextColor(0xffeaf1fc);t.setTextSize(21);t.setGravity(Gravity.CENTER);t.setMinWidth(dp(43));t.setMinHeight(dp(44));t.setPadding(dp(9),dp(8),dp(9),dp(8));t.setBackground(rounded(PANEL,13));t.setOnClickListener(v->r.run());return t;}
  void space(LinearLayout layout,int width){View v=new View(this);layout.addView(v,new LinearLayout.LayoutParams(dp(width),1));}
  void styleDialog(AlertDialog dialog){dialog.getWindow().setBackgroundDrawable(rounded(PANEL,22));}

  @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(0xff10131a);getWindow().setNavigationBarColor(0xff10131a);
    prefs=getSharedPreferences("browser",MODE_PRIVATE);blockAds=prefs.getBoolean("adblock",true);loadBlocklist();
    root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(SURFACE);
    outer=new FrameLayout(this);outer.setBackgroundColor(SURFACE);outer.addView(root,new FrameLayout.LayoutParams(-1,-1));setContentView(outer);
    setupEdgeToEdge();
    toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setPadding(dp(8),dp(7),dp(8),dp(7));toolbar.setBackgroundColor(SURFACE);root.addView(toolbar);
    toolbar.addView(button("‹",()->{if(current>=0&&tabs.get(current).canGoBack())tabs.get(current).goBack();}));
    toolbar.addView(button("›",()->{if(current>=0&&tabs.get(current).canGoForward())tabs.get(current).goForward();}));
    address=new EditText(this);address.setContentDescription("Address bar");address.setSingleLine(true);address.setTextColor(0xfff4f7ff);address.setHintTextColor(MUTED);address.setBackground(rounded(PANEL,16));address.setPadding(dp(14),0,dp(14),0);address.setHint("Search or enter URL");address.setTextSize(14);address.setSelectAllOnFocus(true);LinearLayout.LayoutParams addressLp=new LinearLayout.LayoutParams(0,dp(48),1);addressLp.setMargins(dp(5),0,dp(5),0);toolbar.addView(address,addressLp);
    address.setOnEditorActionListener((v,id,event)->{navigate(address.getText().toString());return true;});
    toolbar.addView(button("↻",()->{if(current>=0)tabs.get(current).reload();}));
    TextView videoButton=button("↓",this::videoDownload);videoButton.setContentDescription("Download video");toolbar.addView(videoButton);
    toolbar.addView(button("⋮",this::menu));
    tabbar=new LinearLayout(this);tabbar.setPadding(dp(8),dp(3),dp(8),dp(7));tabScroll=new HorizontalScrollView(this);tabScroll.setBackgroundColor(SURFACE);tabScroll.setHorizontalScrollBarEnabled(false);tabScroll.addView(tabbar);root.addView(tabScroll);
    frame=new FrameLayout(this);root.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
    exitFs=button("⛶",()->{if(exitFs.getAlpha()<0.1f){showFsControl();}else setFullscreen(false);});exitFs.setContentDescription("Fullscreen control");exitFs.setTextSize(23);exitFs.setPadding(dp(14),dp(8),dp(14),dp(8));
    android.graphics.drawable.GradientDrawable pill=new android.graphics.drawable.GradientDrawable();pill.setColor(0xcc10131a);pill.setCornerRadius(dp(20));exitFs.setBackground(rounded(PANEL,18));exitFs.setAlpha(0.8f);exitFs.setVisibility(View.GONE);
    FrameLayout.LayoutParams exitLp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.END);exitLp.setMargins(0,dp(24),dp(16),0);outer.addView(exitFs,exitLp);
    restoreSession();
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
    exitFs.setVisibility(on&&customView==null?View.VISIBLE:View.GONE);if(on&&customView==null){exitFs.bringToFront();showFsControl();}else handler.removeCallbacksAndMessages(null);
    if(on){InputMethodManager imm=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);imm.hideSoftInputFromWindow(address.getWindowToken(),0);address.clearFocus();}
    if(Build.VERSION.SDK_INT>=28){WindowManager.LayoutParams lp=getWindow().getAttributes();lp.layoutInDisplayCutoutMode=on?WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES:WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;getWindow().setAttributes(lp);}
    applySystemBars();outer.requestApplyInsets();}
  void showFsControl(){handler.removeCallbacksAndMessages(null);exitFs.animate().cancel();exitFs.setAlpha(0.85f);fadeTask=()->exitFs.animate().alpha(0f).setDuration(350).start();handler.postDelayed(fadeTask,3000);}
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
  boolean isBlocked(String url){if(!blockAds)return false;try{String host=android.net.Uri.parse(url).getHost();if(host==null)return false;host=host.toLowerCase(Locale.ROOT);Set<String> set=blocked;while(true){if(set.contains(host)){blockedCount++;return true;}int dot=host.indexOf('.');if(dot<0||dot==host.length()-1)break;host=host.substring(dot+1);}}catch(Exception ignored){}return false;}
  void newTab(String url){WebView w=new WebView(this);w.setBackgroundColor(Color.WHITE);WebSettings s=w.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setSupportMultipleWindows(false);s.setBuiltInZoomControls(true);s.setDisplayZoomControls(false);s.setMediaPlaybackRequiresUserGesture(false);if(Build.VERSION.SDK_INT>=29)s.setForceDark(prefs.getBoolean("dark",false)?WebSettings.FORCE_DARK_ON:WebSettings.FORCE_DARK_OFF);
    w.setWebViewClient(new WebViewClient(){@Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest req){if(!isBlocked(req.getUrl().toString()))return null;
      if(req.isForMainFrame()){String html="<html><body style=\"font-family:sans-serif;padding:24px\"><h3>Blocked by Block Browser</h3><p>"+android.text.TextUtils.htmlEncode(String.valueOf(req.getUrl().getHost()))+" is on the ad/tracker blocklist.</p><p>Turn adblock off in the \u22ee menu to open it anyway.</p></body></html>";
        return new WebResourceResponse("text/html","UTF-8",new java.io.ByteArrayInputStream(html.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
      // Make the request FAIL (net::ERR_FAILED, like uBlock or a DNS blocker) instead of answering with an
      // empty 200 OK. WebView calls available() before sending headers; if it throws, the request fails
      // outright (see Chromium components/embedder_support/android/util/input_stream_reader.cc).
      return new WebResourceResponse("text/plain","UTF-8",new FailingStream());}
    @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap favicon){pageLoading.add(view);}
    @Override public void onPageFinished(WebView view,String url){if(current>=0&&tabs.get(current)==view)address.setText(url);saveVisit(url,view.getTitle());pageLoading.remove(view);Integer y=restoreY.remove(view);if(y==null){android.util.SparseIntArray positions=pagePositions.get(view);WebBackForwardList history=view.copyBackForwardList();if(positions!=null)y=positions.get(history.getCurrentIndex(),0);}if(y!=null&&y>0){final int targetY=y;view.postDelayed(()->view.scrollTo(0,targetY),200);view.postDelayed(()->view.scrollTo(0,targetY),850);}refreshTabs();scheduleSessionSave();}});
    w.setDownloadListener((downloadLink,userAgent,disposition,mime,length)->downloadUrl(downloadLink,userAgent,disposition,mime));
    w.setOnTouchListener((v,event)->{if(event.getAction()==MotionEvent.ACTION_DOWN){downX=event.getX();downY=event.getY();}else if(event.getAction()==MotionEvent.ACTION_UP){float dx=event.getX()-downX;if(Math.abs(dx)>dp(110)&&Math.abs(event.getY()-downY)<dp(90)&&downX<dp(35)){if(dx>0&&w.canGoBack())w.goBack();else if(dx<0&&w.canGoForward())w.goForward();return true;}}return false;});
    w.setWebChromeClient(chromeClient);pagePositions.put(w,new android.util.SparseIntArray());w.setOnScrollChangeListener((v,x,y,oldX,oldY)->{if(!pageLoading.contains(w)){int index=w.copyBackForwardList().getCurrentIndex();if(index>=0)pagePositions.get(w).put(index,y);}scheduleSessionSave();});tabs.add(w);switchTab(tabs.size()-1);w.loadUrl(url);scheduleSessionSave();
  }
  void switchTab(int n){if(n<0||n>=tabs.size())return;current=n;frame.removeAllViews();WebView w=tabs.get(n);if(w.getParent()!=null)((android.view.ViewGroup)w.getParent()).removeView(w);frame.addView(w);address.setText(w.getUrl()==null?"":w.getUrl());refreshTabs();scheduleSessionSave();}
  void scheduleSessionSave(){if(restoringSession)return;if(sessionSave!=null)sessionHandler.removeCallbacks(sessionSave);sessionSave=this::saveSession;sessionHandler.postDelayed(sessionSave,650);}
  void saveSession(){if(restoringSession)return;try{org.json.JSONArray a=new org.json.JSONArray();for(WebView w:tabs){String url=w.getUrl();if(url==null||(!url.startsWith("https://")&&!url.startsWith("http://")))continue;org.json.JSONObject item=new org.json.JSONObject();item.put("url",url);item.put("scrollY",w.getScrollY());a.put(item);}prefs.edit().putString("session_tabs",a.toString()).putInt("session_selected",current).apply();}catch(Exception e){android.util.Log.w("BlockBrowser","Could not save session",e);}}
  void restoreSession(){restoringSession=true;try{org.json.JSONArray a=new org.json.JSONArray(prefs.getString("session_tabs","[]"));for(int i=0;i<a.length()&&i<30;i++){org.json.JSONObject item=a.optJSONObject(i);if(item==null)continue;String url=item.optString("url");if(!url.startsWith("https://")&&!url.startsWith("http://"))continue;newTab(url);restoreY.put(tabs.get(tabs.size()-1),Math.max(0,item.optInt("scrollY",0)));}if(tabs.isEmpty())newTab("https://www.google.com");switchTab(Math.min(Math.max(0,prefs.getInt("session_selected",0)),tabs.size()-1));}catch(Exception e){android.util.Log.w("BlockBrowser","Could not restore session",e);if(tabs.isEmpty())newTab("https://www.google.com");}finally{restoringSession=false;}scheduleSessionSave();}
  @Override protected void onPause(){saveSession();super.onPause();}
  @Override protected void onStop(){saveSession();super.onStop();}
  void refreshTabs(){tabbar.removeAllViews();for(int i=0;i<tabs.size();i++){final int j=i;String title=tabs.get(i).getTitle();if(title==null||title.isEmpty())title="Tab "+(i+1);if(title.length()>13)title=title.substring(0,13)+"…";TextView t=button((i==current?"● ":"")+title,()->switchTab(j));t.setTextSize(12);t.setPadding(dp(13),dp(7),dp(13),dp(7));t.setTextColor(i==current?ACCENT:0xffc3cede);LinearLayout.LayoutParams tabLp=new LinearLayout.LayoutParams(-2,dp(38));tabLp.setMargins(0,0,dp(6),0);tabbar.addView(t,tabLp);}tabbar.addView(button("+",()->newTab("https://www.google.com")));}
  static final String[] ENGINE_NAMES={"Google","Bing","DuckDuckGo","Brave Search","Startpage","Ecosia","Yahoo","Qwant","Mojeek","SearXNG"};
  static final String[] ENGINE_URLS={"https://www.google.com/search?q=","https://www.bing.com/search?q=","https://duckduckgo.com/?q=","https://search.brave.com/search?q=","https://www.startpage.com/sp/search?query=","https://www.ecosia.org/search?q=","https://search.yahoo.com/search?p=","https://www.qwant.com/?q=","https://www.mojeek.com/search?q=","https://searx.be/search?q="};
  static final String[] ENGINE_DOMAINS={"google.com","bing.com","duckduckgo.com","search.brave.com","startpage.com","ecosia.org","yahoo.com","qwant.com","mojeek.com","searx.be"};
  void searchEngineMenu(){
    int selected=prefs.getInt("search_engine",0);if(selected<0||selected>=ENGINE_NAMES.length)selected=0;
    LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);
    ScrollView scroll=new ScrollView(this);scroll.addView(list);
    final int currentSelection=selected;
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Choose search engine").setView(scroll).setNegativeButton("Cancel",null).create();
    for(int i=0;i<ENGINE_NAMES.length;i++){final int index=i;LinearLayout item=new LinearLayout(this);item.setGravity(Gravity.CENTER_VERTICAL);item.setPadding(dp(16),dp(7),dp(16),dp(7));
      ImageView logo=new ImageView(this);logo.setContentDescription(ENGINE_NAMES[i]+" logo");logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
      LinearLayout.LayoutParams iconLp=new LinearLayout.LayoutParams(dp(28),dp(28));iconLp.setMargins(0,0,dp(16),0);item.addView(logo,iconLp);
      // Site favicons are displayed when available, with a local lettermark fallback.
      TextView letter=new TextView(this);letter.setText(ENGINE_NAMES[i].substring(0,1));letter.setTextColor(Color.WHITE);letter.setTextSize(20);letter.setTypeface(null,Typeface.BOLD);letter.setGravity(Gravity.CENTER);
      FrameLayout iconHolder=new FrameLayout(this);item.removeView(logo);iconHolder.addView(letter,new FrameLayout.LayoutParams(-1,-1));iconHolder.addView(logo,new FrameLayout.LayoutParams(-1,-1));item.addView(iconHolder,0,iconLp);
      String iconUrl="https://www.google.com/s2/favicons?domain="+ENGINE_DOMAINS[i]+"&sz=64";
      new Thread(()->{try(java.io.InputStream in=new java.net.URL(iconUrl).openStream()){android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeStream(in);if(bitmap!=null)runOnUiThread(()->logo.setImageBitmap(bitmap));}catch(Exception ignored){} }).start();
      TextView name=new TextView(this);name.setText(ENGINE_NAMES[i]+(i==currentSelection?"  ✓":""));name.setTextColor(Color.WHITE);name.setTextSize(16);item.addView(name);
      item.setOnClickListener(v->{prefs.edit().putInt("search_engine",index).apply();Toast.makeText(this,"Search engine: "+ENGINE_NAMES[index],Toast.LENGTH_SHORT).show();dialog.dismiss();});list.addView(item);
    }
    dialog.show();
  }
  void navigate(String text){text=text.trim();if(text.isEmpty())return;String url;if(text.startsWith("https://")||text.startsWith("http://"))url=text;else if(text.contains(".")&&!text.contains(" "))url="https://"+text;else {int engine=prefs.getInt("search_engine",0);if(engine<0||engine>=ENGINE_URLS.length)engine=0;url=ENGINE_URLS[engine]+android.net.Uri.encode(text);}tabs.get(current).loadUrl(url);((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(),0);address.clearFocus();}

  void downloadUrl(String url,String agent,String disposition,String mime){
    if(!url.startsWith("https://")&&!url.startsWith("http://")){Toast.makeText(this,"This video cannot be downloaded directly",Toast.LENGTH_LONG).show();return;}
    try{DownloadManager.Request req=new DownloadManager.Request(Uri.parse(url));req.setMimeType(mime);req.addRequestHeader("User-Agent",agent==null?"Block Browser":agent);
      String cookies=CookieManager.getInstance().getCookie(url);if(cookies!=null)req.addRequestHeader("Cookie",cookies);
      req.setTitle(URLUtil.guessFileName(url,disposition,mime));req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
      req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,URLUtil.guessFileName(url,disposition,mime));
      ((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(req);Toast.makeText(this,"Download started",Toast.LENGTH_SHORT).show();
    }catch(Exception e){Toast.makeText(this,"Download failed: "+e.getMessage(),Toast.LENGTH_LONG).show();}
  }
  void videoDownload(){if(current<0)return;WebView w=tabs.get(current);
    w.evaluateJavascript("(function(){return Array.from(document.querySelectorAll('video')).map(v=>v.currentSrc||v.src||v.querySelector('source')?.src).filter(Boolean)})()",json->{
      try{org.json.JSONArray a=new org.json.JSONArray(json);ArrayList<String> urls=new ArrayList<>();
        for(int i=0;i<a.length();i++){String u=a.optString(i);if(u.startsWith("http"))urls.add(u);}
        if(urls.isEmpty()){Toast.makeText(this,"No directly downloadable video found on this page",Toast.LENGTH_LONG).show();return;}
        new AlertDialog.Builder(this).setTitle("Download video").setItems(urls.toArray(new String[0]),(d,n)->downloadUrl(urls.get(n),w.getSettings().getUserAgentString(),null,"video/mp4")).show();
      }catch(Exception e){Toast.makeText(this,"Could not detect video",Toast.LENGTH_SHORT).show();}
    });
  }
  void downloads(){DownloadManager dm=(DownloadManager)getSystemService(DOWNLOAD_SERVICE);ArrayList<Long> ids=new ArrayList<>();ArrayList<String> names=new ArrayList<>();
    try(Cursor c=dm.query(new DownloadManager.Query())){int id=c.getColumnIndex(DownloadManager.COLUMN_ID),title=c.getColumnIndex(DownloadManager.COLUMN_TITLE),status=c.getColumnIndex(DownloadManager.COLUMN_STATUS);
      while(c.moveToNext()&&ids.size()<100){ids.add(c.getLong(id));names.add(c.getString(title)+(c.getInt(status)==DownloadManager.STATUS_SUCCESSFUL?"":" (downloading)"));}}
    if(ids.isEmpty()){new AlertDialog.Builder(this).setTitle("Downloads").setMessage("No downloads yet").setPositiveButton("OK",null).show();return;}
    new AlertDialog.Builder(this).setTitle("Downloads").setItems(names.toArray(new String[0]),(d,n)->{
      Uri uri=dm.getUriForDownloadedFile(ids.get(n));if(uri==null){Toast.makeText(this,"Download not finished",Toast.LENGTH_SHORT).show();return;}
      String mime=dm.getMimeTypeForDownloadedFile(ids.get(n));String[] choices={"Play in Block Browser","Open in another app"};
      new AlertDialog.Builder(this).setTitle(names.get(n)).setItems(choices,(dialog,choice)->{
        if(choice==0){Intent intent=new Intent(this,VideoPlayerActivity.class);intent.setDataAndType(uri,mime==null?"video/*":mime);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(intent);}
        else{Intent intent=new Intent(Intent.ACTION_VIEW);intent.setDataAndType(uri,mime==null?"*/*":mime);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);try{startActivity(Intent.createChooser(intent,"Open download with"));}catch(Exception e){Toast.makeText(this,"No compatible app installed",Toast.LENGTH_SHORT).show();}}
      }).show();
    }).show();
  }
  void adblockMenu(){String[] options={blockAds?"Turn adblock off":"Turn adblock on","Blocked requests this session: "+blockedCount,listLoaded?"Domains loaded: "+blocked.size():"Blocklist loading"};
    new AlertDialog.Builder(this).setTitle("Adblock").setItems(options,(d,n)->{if(n==0){blockAds=!blockAds;prefs.edit().putBoolean("adblock",blockAds).apply();Toast.makeText(this,blockAds?"Adblock enabled":"Adblock disabled",Toast.LENGTH_SHORT).show();}}).show();
  }

  void saveVisit(String url,String title){if(url==null||!url.startsWith("http"))return;try{org.json.JSONArray old=new org.json.JSONArray(prefs.getString("history","[]"));org.json.JSONArray next=new org.json.JSONArray();org.json.JSONObject entry=new org.json.JSONObject();entry.put("url",url);entry.put("title",title==null||title.isEmpty()?url:title);next.put(entry);for(int i=0;i<old.length()&&next.length()<150;i++){org.json.JSONObject v=old.optJSONObject(i);if(v!=null&&!url.equals(v.optString("url")))next.put(v);}prefs.edit().putString("history",next.toString()).apply();}catch(Exception ignored){}}
  void savedPages(String key,String heading){try{org.json.JSONArray a=new org.json.JSONArray(prefs.getString(key,"[]"));if(a.length()==0){new AlertDialog.Builder(this).setTitle(heading).setMessage("Nothing here yet").setPositiveButton("OK",null).show();return;}String[] labels=new String[a.length()];for(int i=0;i<a.length();i++){org.json.JSONObject e=a.optJSONObject(i);labels[i]=e==null?"Unknown":e.optString("title",e.optString("url"));}new AlertDialog.Builder(this).setTitle(heading).setItems(labels,(d,n)->{org.json.JSONObject e=a.optJSONObject(n);if(e!=null&&current>=0)tabs.get(current).loadUrl(e.optString("url"));}).setNeutralButton("Clear all",(d,n)->new AlertDialog.Builder(this).setTitle("Clear "+heading+"?").setPositiveButton("Clear",(dd,nn)->prefs.edit().remove(key).apply()).setNegativeButton("Cancel",null).show()).setNegativeButton("Close",null).show();}catch(Exception ignored){}}
  void addBookmark(){if(current<0)return;WebView w=tabs.get(current);String url=w.getUrl();if(url==null||!url.startsWith("http"))return;try{org.json.JSONArray a=new org.json.JSONArray(prefs.getString("bookmarks","[]"));for(int i=0;i<a.length();i++)if(url.equals(a.getJSONObject(i).optString("url"))){Toast.makeText(this,"Already bookmarked",Toast.LENGTH_SHORT).show();return;}org.json.JSONObject e=new org.json.JSONObject();e.put("url",url);e.put("title",w.getTitle()==null?url:w.getTitle());a.put(e);prefs.edit().putString("bookmarks",a.toString()).apply();Toast.makeText(this,"Bookmark saved",Toast.LENGTH_SHORT).show();}catch(Exception ignored){}}
  void findOnPage(){if(current<0)return;EditText input=new EditText(this);input.setSingleLine(true);input.setHint("Find text");input.setPadding(dp(20),dp(12),dp(20),dp(12));new AlertDialog.Builder(this).setTitle("Find in page").setView(input).setPositiveButton("Find",(d,n)->tabs.get(current).findAllAsync(input.getText().toString())).setNegativeButton("Cancel",null).show();}
  void sharePage(){if(current<0)return;String url=tabs.get(current).getUrl();if(url==null)return;Intent intent=new Intent(Intent.ACTION_SEND);intent.setType("text/plain");intent.putExtra(Intent.EXTRA_TEXT,url);startActivity(Intent.createChooser(intent,"Share page"));}
  void clearBrowsingData(){new AlertDialog.Builder(this).setTitle("Clear browsing data?").setMessage("Delete history, cookies, cache and saved web storage? Bookmarks and downloads are kept.").setPositiveButton("Clear",(d,n)->{prefs.edit().remove("history").apply();CookieManager.getInstance().removeAllCookies(null);CookieManager.getInstance().flush();WebStorage.getInstance().deleteAllData();for(WebView w:tabs)w.clearCache(true);Toast.makeText(this,"Browsing data cleared",Toast.LENGTH_SHORT).show();}).setNegativeButton("Cancel",null).show();}
  void menu(){String[] items={"Adblock","Downloads","Search engine","New tab","Close tab","Home","Full screen","Desktop site","Dark websites","Privacy dashboard","Add bookmark","Bookmarks","History","Find in page","Share page","Clear browsing data"};
    new AlertDialog.Builder(this).setTitle("Block Browser").setItems(items,(d,which)->{switch(which){
      case 0:adblockMenu();break;case 1:downloads();break;
      case 2:searchEngineMenu();break;case 3:newTab("https://www.google.com");break;
      case 4:if(tabs.size()>1){WebView w=tabs.remove(current);frame.removeView(w);pagePositions.remove(w);pageLoading.remove(w);restoreY.remove(w);w.destroy();switchTab(Math.max(0,current-1));saveSession();}break;
      case 5:navigate("https://www.google.com");break;case 6:setFullscreen(true);break;
      case 7:if(current>=0){WebSettings settings=tabs.get(current).getSettings();boolean desktop=!settings.getUserAgentString().contains("Desktop BlockBrowser");settings.setUserAgentString(desktop?"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Desktop BlockBrowser":null);tabs.get(current).reload();}break;
      case 8:if(Build.VERSION.SDK_INT>=29&&current>=0){boolean dark=!prefs.getBoolean("dark",false);prefs.edit().putBoolean("dark",dark).apply();tabs.get(current).getSettings().setForceDark(dark?WebSettings.FORCE_DARK_ON:WebSettings.FORCE_DARK_OFF);tabs.get(current).reload();}else Toast.makeText(this,"Dark websites requires Android 10+",Toast.LENGTH_SHORT).show();break;
      case 9:new AlertDialog.Builder(this).setTitle("Privacy dashboard").setMessage("Blocked requests this session: "+blockedCount+"\\nBlocklist domains: "+blocked.size()).setPositiveButton("OK",null).show();break;
      case 10:addBookmark();break;case 11:savedPages("bookmarks","Bookmarks");break;case 12:savedPages("history","History");break;case 13:findOnPage();break;case 14:sharePage();break;case 15:clearBrowsingData();break;
    }}).show();}
  @Override public void onBackPressed(){if(customView!=null){hideCustomView();return;}if(fullscreen){setFullscreen(false);return;}if(current>=0&&tabs.get(current).canGoBack())tabs.get(current).goBack();else super.onBackPressed();}
  @Override protected void onDestroy(){saveSession();sessionHandler.removeCallbacksAndMessages(null);for(WebView w:tabs){if(w.getParent()!=null)((android.view.ViewGroup)w.getParent()).removeView(w);w.destroy();}super.onDestroy();}
  /** Stream that throws as soon as WebView touches it, so the blocked request ends in a network error. */
  static final class FailingStream extends java.io.InputStream{
    @Override public int available() throws java.io.IOException{throw new java.io.IOException("Blocked by Block Browser");}
    @Override public int read() throws java.io.IOException{throw new java.io.IOException("Blocked by Block Browser");}
    @Override public int read(byte[] b,int off,int len) throws java.io.IOException{throw new java.io.IOException("Blocked by Block Browser");}
  }
}

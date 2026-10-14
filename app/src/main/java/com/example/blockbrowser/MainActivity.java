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
  LinearLayout root, toolbar, tabbar; EditText address; FrameLayout frame, outer; HorizontalScrollView tabScroll; TextView exitFs, tabCount, moreButton; Handler handler=new Handler(Looper.getMainLooper()); Runnable fadeTask; long blockedCount=0; android.content.SharedPreferences prefs; java.util.HashMap<String,Boolean> siteAdblock=new java.util.HashMap<>(); float downX,downY;
  boolean fullscreen = false, fullscreenBeforeVideo = false, immersiveRequested = false; View customView; WebChromeClient.CustomViewCallback customViewCallback;
  boolean restoringSession=false; Handler sessionHandler=new Handler(Looper.getMainLooper()); Runnable sessionSave; HashMap<WebView,Integer> restoreY=new HashMap<>(); HashMap<WebView,android.util.SparseIntArray> pagePositions=new HashMap<>(); HashSet<WebView> pageLoading=new HashSet<>();
  PermissionRequest pendingPermission; GeolocationPermissions.Callback pendingGeo; String pendingGeoOrigin; ValueCallback<Uri[]> pendingUpload; static final int FILE_PICKER=5201;
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
    prefs=getSharedPreferences("browser",MODE_PRIVATE);blockAds=prefs.getBoolean("adblock",true);immersiveRequested=prefs.getBoolean("fullscreen",false);loadBlocklist();
    root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(SURFACE);
    outer=new FrameLayout(this);outer.setBackgroundColor(SURFACE);outer.addView(root,new FrameLayout.LayoutParams(-1,-1));setContentView(outer);
    setupEdgeToEdge();
    toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setPadding(dp(8),dp(8),dp(8),dp(8));toolbar.setBackgroundColor(SURFACE);root.addView(toolbar);
    TextView home=button("⌂",()->navigate(homeUrl()));home.setContentDescription("Home");home.setBackgroundColor(Color.TRANSPARENT);home.setTextSize(29);toolbar.addView(home);
    address=new EditText(this);address.setContentDescription("Address bar");address.setSingleLine(true);address.setTextColor(0xfff4f7ff);address.setHintTextColor(MUTED);address.setBackground(rounded(PANEL,28));address.setPadding(dp(16),0,dp(12),0);address.setHint("Search or enter URL");address.setTextSize(15);address.setSelectAllOnFocus(true);LinearLayout.LayoutParams addressLp=new LinearLayout.LayoutParams(0,dp(48),1);addressLp.setMargins(dp(4),0,dp(4),0);toolbar.addView(address,addressLp);
    address.setOnEditorActionListener((v,id,event)->{navigate(address.getText().toString());return true;});
    tabCount=button("1",this::showTabSwitcher);tabCount.setContentDescription("Switch tabs");tabCount.setTextSize(17);tabCount.setBackgroundColor(Color.TRANSPARENT);toolbar.addView(tabCount);
    moreButton=button("⋮",this::menu);moreButton.setContentDescription("Browser menu");moreButton.setBackgroundColor(Color.TRANSPARENT);toolbar.addView(moreButton);
    tabbar=new LinearLayout(this);tabScroll=new HorizontalScrollView(this);tabScroll.addView(tabbar);tabScroll.setVisibility(View.GONE);
    frame=new FrameLayout(this);root.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
    exitFs=button("⛶",()->{if(exitFs.getAlpha()<0.35f){showFsControl();}else setFullscreen(false);});exitFs.setContentDescription("Fullscreen control");exitFs.setTextSize(23);exitFs.setPadding(dp(14),dp(8),dp(14),dp(8));
    android.graphics.drawable.GradientDrawable pill=new android.graphics.drawable.GradientDrawable();pill.setColor(0xcc10131a);pill.setCornerRadius(dp(20));exitFs.setBackground(rounded(PANEL,18));exitFs.setAlpha(0.8f);exitFs.setVisibility(View.GONE);
    FrameLayout.LayoutParams exitLp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.END);exitLp.setMargins(0,dp(24),dp(16),0);outer.addView(exitFs,exitLp);
    restoreSession();if(immersiveRequested)setFullscreen(true);handleExternalIntent(getIntent());
  }
  // Android 15+ draws apps behind the status and navigation bars (edge-to-edge). Draw edge-to-edge on every
  // version and pad the UI by the bars' size ourselves, so the toolbar sits below the status bar everywhere.
  void handleExternalIntent(Intent intent){if(intent==null)return;Uri uri=intent.getData();if(uri==null)return;String scheme=uri.getScheme();if(!"http".equalsIgnoreCase(scheme)&&!"https".equalsIgnoreCase(scheme))return;if(current>=0)tabs.get(current).loadUrl(uri.toString());}
  @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleExternalIntent(intent);}
  void setupEdgeToEdge(){getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);else outer.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    outer.setOnApplyWindowInsetsListener((v,ins)->{int l,t,r,bars,keyboard;
      if(Build.VERSION.SDK_INT>=30){android.graphics.Insets b=ins.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());l=b.left;t=b.top;r=b.right;bars=b.bottom;keyboard=ins.getInsets(WindowInsets.Type.ime()).bottom;}
      else{l=ins.getSystemWindowInsetLeft();t=ins.getSystemWindowInsetTop();r=ins.getSystemWindowInsetRight();bars=ins.getSystemWindowInsetBottom();keyboard=bars;}
      android.util.Log.i("BlockBrowserInsets","top="+t+" bottom="+bars+" keyboard="+keyboard+" fullscreen="+fullscreen); // read by scripts/ui-check.sh
      if(fullscreen)root.setPadding(0,0,0,keyboard);else root.setPadding(l,t,r,Math.max(bars,keyboard));return ins;});}
  void setFullscreen(boolean on){fullscreen=on;if(customView==null){immersiveRequested=on;prefs.edit().putBoolean("fullscreen",on).apply();}int vis=on?View.GONE:View.VISIBLE;toolbar.setVisibility(vis);tabScroll.setVisibility(vis);
    exitFs.setVisibility(on&&customView==null?View.VISIBLE:View.GONE);if(on&&customView==null){exitFs.bringToFront();showFsControl();}else handler.removeCallbacksAndMessages(null);
    if(on){InputMethodManager imm=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);imm.hideSoftInputFromWindow(address.getWindowToken(),0);address.clearFocus();}
    if(Build.VERSION.SDK_INT>=28){WindowManager.LayoutParams lp=getWindow().getAttributes();lp.layoutInDisplayCutoutMode=on?WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES:WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;getWindow().setAttributes(lp);}
    applySystemBars();outer.requestApplyInsets();}
  void showFsControl(){handler.removeCallbacksAndMessages(null);exitFs.animate().cancel();exitFs.setAlpha(0.85f);fadeTask=()->exitFs.animate().alpha(0.24f).setDuration(350).start();handler.postDelayed(fadeTask,3000);}
  void applySystemBars(){if(Build.VERSION.SDK_INT>=30){WindowInsetsController c=getWindow().getInsetsController();if(c==null)return;
      if(fullscreen){c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);c.hide(WindowInsets.Type.systemBars());}else c.show(WindowInsets.Type.systemBars());}
    else{int f=View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;if(fullscreen)f|=View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;outer.setSystemUiVisibility(f);}}
  // Older Android versions can drop immersive mode after a dialog; re-apply when the window regains focus.
  @Override public void onWindowFocusChanged(boolean hasFocus){super.onWindowFocusChanged(hasFocus);if(hasFocus&&fullscreen)applySystemBars();}
  // Site-requested full screen (e.g. the full-screen button on a YouTube video).
  final WebChromeClient chromeClient=new WebChromeClient(){
    @Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> callback,FileChooserParams params){
      if(pendingUpload!=null)pendingUpload.onReceiveValue(null);pendingUpload=callback;
      try{startActivityForResult(params.createIntent(),FILE_PICKER);return true;}
      catch(Exception e){pendingUpload=null;callback.onReceiveValue(null);Toast.makeText(MainActivity.this,"File picker unavailable",Toast.LENGTH_SHORT).show();return true;}
    }
    @Override public void onPermissionRequest(PermissionRequest request){runOnUiThread(()->{
      new AlertDialog.Builder(MainActivity.this).setTitle("Website permission").setMessage(request.getOrigin()+" requests camera or microphone access.").setPositiveButton("Allow",(d,n)->{
        ArrayList<String> needed=new ArrayList<>();for(String resource:request.getResources()){if(PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource))needed.add(android.Manifest.permission.CAMERA);if(PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource))needed.add(android.Manifest.permission.RECORD_AUDIO);}
        if(needed.isEmpty()){request.deny();return;}
        pendingPermission=request;requestPermissions(needed.toArray(new String[0]),5202);
      }).setNegativeButton("Deny",(d,n)->request.deny()).setOnCancelListener(d->request.deny()).show();
    });}
    @Override public void onPermissionRequestCanceled(PermissionRequest request){if(pendingPermission==request)pendingPermission=null;}
    @Override public void onGeolocationPermissionsShowPrompt(String origin,GeolocationPermissions.Callback callback){runOnUiThread(()->{
      new AlertDialog.Builder(MainActivity.this).setTitle("Location permission").setMessage(origin+" requests your location.").setPositiveButton("Allow",(d,n)->{
        pendingGeo=callback;pendingGeoOrigin=origin;requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION},5203);
      }).setNegativeButton("Deny",(d,n)->callback.invoke(origin,false,false)).setOnCancelListener(d->callback.invoke(origin,false,false)).show();
    });}

    @Override public boolean onCreateWindow(WebView source,boolean isDialog,boolean isUserGesture,Message resultMsg){
      if(!isUserGesture)return false;
      WebView temporary=new WebView(MainActivity.this);
      temporary.getSettings().setJavaScriptEnabled(true);
      temporary.setWebViewClient(new WebViewClient(){
        boolean opened=false;
        @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap favicon){
          if(opened)return;opened=true;
          if(url.startsWith("https://")||url.startsWith("http://"))newTab(url,true);
          view.post(()->{view.stopLoading();view.destroy();});
        }
      });
      WebView.WebViewTransport transport=(WebView.WebViewTransport)resultMsg.obj;
      transport.setWebView(temporary);resultMsg.sendToTarget();return true;
    }
    @Override public void onShowCustomView(View view,CustomViewCallback cb){if(customView!=null){cb.onCustomViewHidden();return;}
      customView=view;customViewCallback=cb;fullscreenBeforeVideo=fullscreen;view.setBackgroundColor(Color.BLACK);outer.addView(view,new FrameLayout.LayoutParams(-1,-1));setFullscreen(true);}
    @Override public void onHideCustomView(){hideCustomView();}};
  @Override protected void onActivityResult(int code,int result,Intent data){super.onActivityResult(code,result,data);if(code==FILE_PICKER&&pendingUpload!=null){ValueCallback<Uri[]> cb=pendingUpload;pendingUpload=null;cb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result,data));}}
  @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){super.onRequestPermissionsResult(code,permissions,grants);
    if(code==5202&&pendingPermission!=null){PermissionRequest r=pendingPermission;pendingPermission=null;ArrayList<String> allowed=new ArrayList<>();for(String resource:r.getResources()){
      if(PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)&&checkSelfPermission(android.Manifest.permission.CAMERA)==android.content.pm.PackageManager.PERMISSION_GRANTED)allowed.add(resource);
      if(PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)&&checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED)allowed.add(resource);
    }if(allowed.isEmpty())r.deny();else r.grant(allowed.toArray(new String[0]));}
    if(code==5203&&pendingGeo!=null){pendingGeo.invoke(pendingGeoOrigin,grants.length>0&&grants[0]==android.content.pm.PackageManager.PERMISSION_GRANTED,false);pendingGeo=null;pendingGeoOrigin=null;}
  }
  void hideCustomView(){if(customView==null)return;outer.removeView(customView);customView=null;if(customViewCallback!=null)customViewCallback.onCustomViewHidden();customViewCallback=null;setFullscreen(immersiveRequested);}
  boolean isBlocked(String url){if(!blockAds)return false;try{String host=android.net.Uri.parse(url).getHost();if(host==null)return false;host=host.toLowerCase(Locale.ROOT);Set<String> set=blocked;while(true){if(set.contains(host)){blockedCount++;return true;}int dot=host.indexOf('.');if(dot<0||dot==host.length()-1)break;host=host.substring(dot+1);}}catch(Exception ignored){}return false;}

  void copyText(String label,String value){android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText(label,value));Toast.makeText(this,"Copied "+label.toLowerCase(Locale.ROOT),Toast.LENGTH_SHORT).show();}
  void shareLink(String url){Intent intent=new Intent(Intent.ACTION_SEND);intent.setType("text/plain");intent.putExtra(Intent.EXTRA_TEXT,url);startActivity(Intent.createChooser(intent,"Share link"));}
  boolean showPageContextMenu(WebView w){
    WebView.HitTestResult hit=w.getHitTestResult();if(hit==null)return false;
    int type=hit.getType();String url=hit.getExtra();boolean image=type==WebView.HitTestResult.IMAGE_TYPE||type==WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE;
    boolean link=type==WebView.HitTestResult.SRC_ANCHOR_TYPE||type==WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE;
    if(!image&&!link)return false;
    if(url==null||url.isEmpty())return false;
    final String target=url;
    ArrayList<String> actions=new ArrayList<>();
    if(link){actions.add("Open link");actions.add("Open link in new tab");actions.add("Open in background tab");actions.add("Copy link address");actions.add("Share link");}
    if(image){actions.add("Open image");actions.add("Save image");actions.add("Copy image address");}
    new AlertDialog.Builder(this).setTitle(image?"Image options":"Link options").setItems(actions.toArray(new String[0]),(dialog,choice)->{
      String action=actions.get(choice);
      switch(action){
        case "Open link":w.loadUrl(target);break;
        case "Open link in new tab":newTab(target);break;case "Open in background tab":newTab(target,true);break;
        case "Copy link address":copyText("Link",target);break;
        case "Share link":shareLink(target);break;
        case "Open image":newTab(target);break;
        case "Save image":downloadUrl(target,w.getSettings().getUserAgentString(),null,null);break;
        case "Copy image address":copyText("Image address",target);break;
      }
    }).show();
    return true;
  }
  void newTab(String url){newTab(url,false);}
  void newTab(String url,boolean background){int previous=current;WebView w=new WebView(this);w.setBackgroundColor(Color.WHITE);WebSettings s=w.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setGeolocationEnabled(true);CookieManager.getInstance().setAcceptThirdPartyCookies(w,prefs.getBoolean("third_party_cookies",false));s.setTextZoom(prefs.getInt("text_zoom",100));s.setSupportMultipleWindows(true);s.setBuiltInZoomControls(true);s.setDisplayZoomControls(false);s.setMediaPlaybackRequiresUserGesture(false);if(Build.VERSION.SDK_INT>=29)s.setForceDark(prefs.getBoolean("dark",false)?WebSettings.FORCE_DARK_ON:WebSettings.FORCE_DARK_OFF);
    w.setWebViewClient(new WebViewClient(){@Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest req){if(!isBlocked(req.getUrl().toString()))return null;
      if(req.isForMainFrame()){String html="<html><body style=\"font-family:sans-serif;padding:24px\"><h3>Blocked by Block Browser</h3><p>"+android.text.TextUtils.htmlEncode(String.valueOf(req.getUrl().getHost()))+" is on the ad/tracker blocklist.</p><p>Turn adblock off in the \u22ee menu to open it anyway.</p></body></html>";
        return new WebResourceResponse("text/html","UTF-8",new java.io.ByteArrayInputStream(html.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
      // Make the request FAIL (net::ERR_FAILED, like uBlock or a DNS blocker) instead of answering with an
      // empty 200 OK. WebView calls available() before sending headers; if it throws, the request fails
      // outright (see Chromium components/embedder_support/android/util/input_stream_reader.cc).
      return new WebResourceResponse("text/plain","UTF-8",new FailingStream());}
    @Override public void onReceivedSslError(WebView view,SslErrorHandler sslHandler,android.net.http.SslError error){sslHandler.cancel();runOnUiThread(()->Toast.makeText(MainActivity.this,"Invalid HTTPS certificate — blocked",Toast.LENGTH_LONG).show());}
    @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){Uri uri=request.getUrl();String scheme=uri.getScheme();if("http".equalsIgnoreCase(scheme)||"https".equalsIgnoreCase(scheme))return false;
      if(!request.isForMainFrame()||(!"mailto".equalsIgnoreCase(scheme)&&!"tel".equalsIgnoreCase(scheme)))return true;
      try{startActivity(new Intent(Intent.ACTION_VIEW,uri));}catch(Exception ignored){}return true;}
    @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap favicon){pageLoading.add(view);}
    @Override public void onPageFinished(WebView view,String url){if(current>=0&&tabs.get(current)==view)address.setText(url);saveVisit(url,view.getTitle());pageLoading.remove(view);Integer y=restoreY.remove(view);if(y==null){android.util.SparseIntArray positions=pagePositions.get(view);WebBackForwardList history=view.copyBackForwardList();if(positions!=null)y=positions.get(history.getCurrentIndex(),0);}if(y!=null&&y>0){final int targetY=y;view.postDelayed(()->view.scrollTo(0,targetY),200);view.postDelayed(()->view.scrollTo(0,targetY),850);}refreshTabs();scheduleSessionSave();}});
    w.setOnLongClickListener(v->showPageContextMenu(w));
    w.setDownloadListener((downloadLink,userAgent,disposition,mime,length)->downloadUrl(downloadLink,userAgent,disposition,mime));
    w.setOnTouchListener((v,event)->{if(event.getAction()==MotionEvent.ACTION_DOWN){downX=event.getX();downY=event.getY();}else if(event.getAction()==MotionEvent.ACTION_UP){float dx=event.getX()-downX;if(Math.abs(dx)>dp(110)&&Math.abs(event.getY()-downY)<dp(90)&&downX<dp(35)){if(dx>0&&w.canGoBack())w.goBack();else if(dx<0&&w.canGoForward())w.goForward();return true;}}return false;});
    w.setWebChromeClient(chromeClient);pagePositions.put(w,new android.util.SparseIntArray());w.setOnScrollChangeListener((v,x,y,oldX,oldY)->{if(!pageLoading.contains(w)){int index=w.copyBackForwardList().getCurrentIndex();if(index>=0)pagePositions.get(w).put(index,y);}scheduleSessionSave();});tabs.add(w);if(!background||previous<0)switchTab(tabs.size()-1);else refreshTabs();w.loadUrl(url);scheduleSessionSave();
  }
  void switchTab(int n){if(n<0||n>=tabs.size())return;current=n;frame.removeAllViews();WebView w=tabs.get(n);if(w.getParent()!=null)((android.view.ViewGroup)w.getParent()).removeView(w);frame.addView(w);address.setText(w.getUrl()==null?"":w.getUrl());refreshTabs();scheduleSessionSave();}
  void scheduleSessionSave(){if(restoringSession)return;if(sessionSave!=null)sessionHandler.removeCallbacks(sessionSave);sessionSave=this::saveSession;sessionHandler.postDelayed(sessionSave,650);}
  void saveSession(){if(restoringSession)return;try{org.json.JSONArray a=new org.json.JSONArray();for(WebView w:tabs){String url=w.getUrl();if(url==null||(!url.startsWith("https://")&&!url.startsWith("http://")))continue;org.json.JSONObject item=new org.json.JSONObject();item.put("url",url);item.put("scrollY",w.getScrollY());a.put(item);}prefs.edit().putString("session_tabs",a.toString()).putInt("session_selected",current).apply();}catch(Exception e){android.util.Log.w("BlockBrowser","Could not save session",e);}}
  void restoreSession(){restoringSession=true;try{org.json.JSONArray a=new org.json.JSONArray(prefs.getString("session_tabs","[]"));for(int i=0;i<a.length()&&i<30;i++){org.json.JSONObject item=a.optJSONObject(i);if(item==null)continue;String url=item.optString("url");if(!url.startsWith("https://")&&!url.startsWith("http://"))continue;newTab(url);restoreY.put(tabs.get(tabs.size()-1),Math.max(0,item.optInt("scrollY",0)));}if(tabs.isEmpty())newTab(homeUrl());switchTab(Math.min(Math.max(0,prefs.getInt("session_selected",0)),tabs.size()-1));}catch(Exception e){android.util.Log.w("BlockBrowser","Could not restore session",e);if(tabs.isEmpty())newTab(homeUrl());}finally{restoringSession=false;}scheduleSessionSave();}
  @Override protected void onPause(){saveSession();super.onPause();}
  @Override protected void onStop(){saveSession();super.onStop();}
  void addToHomeScreen(){if(current<0)return;WebView w=tabs.get(current);String url=w.getUrl();if(url==null||!url.startsWith("https://")&&!url.startsWith("http://"))return;String title=w.getTitle()==null?"Website":w.getTitle();if(Build.VERSION.SDK_INT<26){Toast.makeText(this,"Requires Android 8+",Toast.LENGTH_SHORT).show();return;}
    android.content.pm.ShortcutManager manager=getSystemService(android.content.pm.ShortcutManager.class);if(manager==null||!manager.isRequestPinShortcutSupported()){Toast.makeText(this,"Home screen shortcuts unavailable",Toast.LENGTH_SHORT).show();return;}
    Intent open=new Intent(Intent.ACTION_VIEW,Uri.parse(url),this,MainActivity.class);open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
    android.content.pm.ShortcutInfo shortcut=new android.content.pm.ShortcutInfo.Builder(this,"page_"+Integer.toHexString(url.hashCode())).setShortLabel(title.length()>25?title.substring(0,25):title).setLongLabel(title).setIcon(android.graphics.drawable.Icon.createWithResource(this,android.R.drawable.ic_menu_view)).setIntent(open).build();
    manager.requestPinShortcut(shortcut,null);
  }
  void deleteSavedEntry(String key){try{org.json.JSONArray entries=new org.json.JSONArray(prefs.getString(key,"[]"));if(entries.length()==0){Toast.makeText(this,"Nothing to delete",Toast.LENGTH_SHORT).show();return;}String[] labels=new String[entries.length()];for(int i=0;i<entries.length();i++){org.json.JSONObject e=entries.optJSONObject(i);labels[i]=e==null?"Unknown":e.optString("title",e.optString("url"));}new AlertDialog.Builder(this).setTitle("Delete from "+key).setItems(labels,(d,selected)->{new AlertDialog.Builder(this).setTitle("Remove entry?").setMessage(labels[selected]).setPositiveButton("Remove",(dd,n)->{org.json.JSONArray remaining=new org.json.JSONArray();for(int i=0;i<entries.length();i++)if(i!=selected)remaining.put(entries.opt(i));prefs.edit().putString(key,remaining.toString()).apply();}).setNegativeButton("Cancel",null).show();}).setNegativeButton("Cancel",null).show();}catch(Exception ignored){}}
  void showSiteInfo(){if(current<0)return;String raw=tabs.get(current).getUrl();if(raw==null)return;Uri uri=Uri.parse(raw);boolean secure="https".equalsIgnoreCase(uri.getScheme());String host=uri.getHost()==null?raw:uri.getHost();String message=(secure?"Encrypted connection (HTTPS)":"Connection is not encrypted (HTTP)")+"\n\nSite: "+host+"\n\nPermissions are requested when a website needs them.";new AlertDialog.Builder(this).setTitle("Site information").setMessage(message).setPositiveButton("OK",null).show();}
  void showTabSwitcher(){String[] names=new String[tabs.size()+1];for(int i=0;i<tabs.size();i++){String title=tabs.get(i).getTitle();names[i]=(i==current?"✓  ":"")+((title==null||title.isEmpty())?"Tab "+(i+1):title);}names[tabs.size()]="+ New tab";new AlertDialog.Builder(this).setTitle("Tabs ("+tabs.size()+")").setItems(names,(d,n)->{if(n==tabs.size())newTab(homeUrl());else switchTab(n);}).setNeutralButton("Close current tab",(d,n)->closeCurrentTab()).setNegativeButton("Cancel",null).show();}
  void closeOtherTabs(){if(current<0)return;WebView keep=tabs.get(current);for(WebView w:new ArrayList<>(tabs)){if(w==keep)continue;frame.removeView(w);pagePositions.remove(w);pageLoading.remove(w);restoreY.remove(w);w.destroy();}tabs.clear();tabs.add(keep);switchTab(0);saveSession();}
  void readerMode(){if(current<0)return;WebView web=tabs.get(current);String js="(function(){var a=document.querySelector('article,main,[role=main]');if(!a){alert('No article content detected');return;}var title=document.title;var content=a.cloneNode(true);document.head.innerHTML='';document.body.innerHTML='';document.body.style.cssText='max-width:760px;margin:35px auto;padding:20px;background:#f9f9f9;color:#202020;font:19px/1.7 sans-serif';var h=document.createElement('h1');h.textContent=title;document.body.appendChild(h);document.body.appendChild(content);document.querySelectorAll('script,iframe,video,button,form').forEach(function(e){e.remove()});})();";new AlertDialog.Builder(this).setTitle("Reader mode").setMessage("Simplify this article? Reload the page to restore its original appearance.").setPositiveButton("Read",(d,n)->web.evaluateJavascript(js,null)).setNegativeButton("Cancel",null).show();}
  void copyCurrentUrl(){if(current<0)return;String url=tabs.get(current).getUrl();if(url!=null)copyText("Page address",url);}
  void pageSource(){if(current<0)return;String url=tabs.get(current).getUrl();if(url==null||!url.startsWith("http"))return;newTab("view-source:"+url);}
  void findAddressSuggestion(){String query=address.getText().toString().trim().toLowerCase(Locale.ROOT);if(query.isEmpty())return;ArrayList<String> suggestions=new ArrayList<>();for(String key:new String[]{"history","bookmarks"}){try{org.json.JSONArray a=new org.json.JSONArray(prefs.getString(key,"[]"));for(int i=0;i<a.length()&&suggestions.size()<15;i++){org.json.JSONObject item=a.optJSONObject(i);if(item==null)continue;String url=item.optString("url");if(url.toLowerCase(Locale.ROOT).contains(query)&&!suggestions.contains(url))suggestions.add(url);}}catch(Exception ignored){}}if(suggestions.isEmpty()){Toast.makeText(this,"No matching saved pages",Toast.LENGTH_SHORT).show();return;}new AlertDialog.Builder(this).setTitle("Address suggestions").setItems(suggestions.toArray(new String[0]),(d,n)->navigate(suggestions.get(n))).setNegativeButton("Cancel",null).show();}
  void clearSiteCookies(){if(current<0)return;String raw=tabs.get(current).getUrl();if(raw==null)return;String host=Uri.parse(raw).getHost();if(host==null)return;new AlertDialog.Builder(this).setTitle("Clear cookies for "+host+"?").setMessage("Removes matching host cookies with the default path. Some cookies using other paths or parent domains may remain.").setPositiveButton("Clear",(d,n)->{CookieManager manager=CookieManager.getInstance();for(String scheme:new String[]{"https://","http://"}){String origin=scheme+host;String cookie=manager.getCookie(origin);if(cookie==null)continue;for(String part:cookie.split(";")){int equal=part.indexOf('=');if(equal<1)continue;String name=part.substring(0,equal).trim();manager.setCookie(origin,name+"=; Max-Age=0; Path=/");}}manager.flush();tabs.get(current).reload();}).setNegativeButton("Cancel",null).show();}
  void duplicateCurrentTab(){if(current<0)return;String url=tabs.get(current).getUrl();if(url!=null)newTab(url);}
  void closeCurrentTab(){if(current<0)return;if(tabs.size()==1){tabs.get(0).loadUrl(homeUrl());return;}WebView w=tabs.remove(current);frame.removeView(w);pagePositions.remove(w);pageLoading.remove(w);restoreY.remove(w);w.destroy();switchTab(Math.min(current,tabs.size()-1));saveSession();}
  void refreshTabs(){if(tabCount!=null){tabCount.setText(String.valueOf(tabs.size()));tabCount.setContentDescription(tabs.size()+" open tabs");}tabbar.removeAllViews();for(int i=0;i<tabs.size();i++){final int j=i;String title=tabs.get(i).getTitle();if(title==null||title.isEmpty())title="Tab "+(i+1);if(title.length()>13)title=title.substring(0,13)+"…";TextView t=button((i==current?"● ":"")+title,()->switchTab(j));t.setTextSize(12);t.setPadding(dp(13),dp(7),dp(13),dp(7));t.setTextColor(i==current?ACCENT:0xffc3cede);LinearLayout.LayoutParams tabLp=new LinearLayout.LayoutParams(-2,dp(38));tabLp.setMargins(0,0,dp(6),0);tabbar.addView(t,tabLp);}tabbar.addView(button("+",()->newTab("https://www.google.com")));}
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
  void downloads(){DownloadManager dm=(DownloadManager)getSystemService(DOWNLOAD_SERVICE);ArrayList<Long> ids=new ArrayList<>();ArrayList<String> names=new ArrayList<>();ArrayList<Integer> states=new ArrayList<>();
    try(Cursor c=dm.query(new DownloadManager.Query())){int id=c.getColumnIndex(DownloadManager.COLUMN_ID),title=c.getColumnIndex(DownloadManager.COLUMN_TITLE),status=c.getColumnIndex(DownloadManager.COLUMN_STATUS);
      while(c.moveToNext()&&ids.size()<100){ids.add(c.getLong(id));int state=c.getInt(status);states.add(state);names.add(c.getString(title)+(state==DownloadManager.STATUS_SUCCESSFUL?"":state==DownloadManager.STATUS_FAILED?" (failed)":" (in progress)"));}}
    if(ids.isEmpty()){new AlertDialog.Builder(this).setTitle("Downloads").setMessage("No downloads yet").setPositiveButton("OK",null).show();return;}
    new AlertDialog.Builder(this).setTitle("Downloads").setItems(names.toArray(new String[0]),(d,n)->{
      Uri uri=dm.getUriForDownloadedFile(ids.get(n));if(uri==null){new AlertDialog.Builder(this).setTitle(names.get(n)).setItems(new String[]{"Cancel / remove download"},(dialog,choice)->{dm.remove(ids.get(n));Toast.makeText(this,"Download removed",Toast.LENGTH_SHORT).show();}).setNegativeButton("Back",null).show();return;}
      String mime=dm.getMimeTypeForDownloadedFile(ids.get(n));String[] choices={"Play in Block Browser","Open in another app","Remove download"};
      new AlertDialog.Builder(this).setTitle(names.get(n)).setItems(choices,(dialog,choice)->{
        if(choice==0){Intent intent=new Intent(this,VideoPlayerActivity.class);intent.setDataAndType(uri,mime==null?"video/*":mime);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(intent);}
        else if(choice==2){new AlertDialog.Builder(this).setTitle("Remove download?").setPositiveButton("Remove",(dd,nn)->dm.remove(ids.get(n))).setNegativeButton("Cancel",null).show();}
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
  void searchSavedPages(String key){EditText input=new EditText(this);input.setSingleLine(true);input.setHint("Search "+key);new AlertDialog.Builder(this).setTitle("Search "+key).setView(input).setPositiveButton("Search",(d,n)->{String query=input.getText().toString().toLowerCase(Locale.ROOT);try{org.json.JSONArray entries=new org.json.JSONArray(prefs.getString(key,"[]"));ArrayList<String> labels=new ArrayList<>();ArrayList<String> urls=new ArrayList<>();for(int i=0;i<entries.length();i++){org.json.JSONObject e=entries.optJSONObject(i);if(e==null)continue;String title=e.optString("title"),url=e.optString("url");if(title.toLowerCase(Locale.ROOT).contains(query)||url.toLowerCase(Locale.ROOT).contains(query)){labels.add(title);urls.add(url);}}new AlertDialog.Builder(this).setTitle("Results ("+labels.size()+")").setItems(labels.toArray(new String[0]),(dd,j)->{if(current>=0)tabs.get(current).loadUrl(urls.get(j));}).setNegativeButton("Close",null).show();}catch(Exception ignored){}}).setNegativeButton("Cancel",null).show();}
  void findOnPage(){if(current<0)return;EditText input=new EditText(this);input.setSingleLine(true);input.setHint("Find text");input.setPadding(dp(20),dp(12),dp(20),dp(12));new AlertDialog.Builder(this).setTitle("Find in page").setView(input).setPositiveButton("Find",(d,n)->tabs.get(current).findAllAsync(input.getText().toString())).setNegativeButton("Cancel",null).show();}
  void sharePage(){if(current<0)return;String url=tabs.get(current).getUrl();if(url==null)return;Intent intent=new Intent(Intent.ACTION_SEND);intent.setType("text/plain");intent.putExtra(Intent.EXTRA_TEXT,url);startActivity(Intent.createChooser(intent,"Share page"));}
  void clearBrowsingData(){new AlertDialog.Builder(this).setTitle("Clear browsing data?").setMessage("Delete history, cookies, cache and saved web storage? Bookmarks and downloads are kept.").setPositiveButton("Clear",(d,n)->{prefs.edit().remove("history").apply();CookieManager.getInstance().removeAllCookies(null);CookieManager.getInstance().flush();WebStorage.getInstance().deleteAllData();for(WebView w:tabs)w.clearCache(true);Toast.makeText(this,"Browsing data cleared",Toast.LENGTH_SHORT).show();}).setNegativeButton("Cancel",null).show();}
  void printPage(){if(current<0)return;android.print.PrintManager manager=(android.print.PrintManager)getSystemService(PRINT_SERVICE);WebView web=tabs.get(current);String title=web.getTitle()==null?"Webpage":web.getTitle();manager.print(title,web.createPrintDocumentAdapter(title),new android.print.PrintAttributes.Builder().build());}
  void textZoomMenu(){if(current<0)return;WebSettings settings=tabs.get(current).getSettings();String[] sizes={"75%","90%","100%","110%","125%","150%","200%"};int[] values={75,90,100,110,125,150,200};new AlertDialog.Builder(this).setTitle("Text size").setSingleChoiceItems(sizes,-1,(d,n)->{int size=values[n];settings.setTextZoom(size);prefs.edit().putInt("text_zoom",size).apply();d.dismiss();}).setNegativeButton("Cancel",null).show();}
  void homepageMenu(){EditText input=new EditText(this);input.setSingleLine(true);input.setText(prefs.getString("homepage","https://www.google.com"));new AlertDialog.Builder(this).setTitle("Homepage URL").setView(input).setPositiveButton("Save",(d,n)->{String value=input.getText().toString().trim();if(value.startsWith("https://")||value.startsWith("http://"))prefs.edit().putString("homepage",value).apply();else Toast.makeText(this,"Enter a complete http or https URL",Toast.LENGTH_SHORT).show();}).setNegativeButton("Cancel",null).show();}
  void cookieMenu(){boolean allow=prefs.getBoolean("third_party_cookies",false);new AlertDialog.Builder(this).setTitle("Third-party cookies").setMessage(allow?"Currently allowed":"Currently blocked").setPositiveButton(allow?"Block":"Allow",(d,n)->{boolean next=!allow;prefs.edit().putBoolean("third_party_cookies",next).apply();for(WebView w:tabs)CookieManager.getInstance().setAcceptThirdPartyCookies(w,next);}).setNegativeButton("Cancel",null).show();}
  String homeUrl(){return prefs.getString("homepage","https://www.google.com");}
  void menu(){
    String[] labels={"Reader mode","Copy page address","Address suggestions","Clear site cookies","Duplicate tab","Close other tabs","Delete history entry","Delete bookmark","Add to home screen","Site information","Search history","Search bookmarks","Print / Save PDF","Text size","Homepage settings","Third-party cookies","Adblock","Download video","Privacy dashboard","Dark websites","Downloads","Search engine","Add bookmark","Bookmarks","History","Find in page","Share page","Clear browsing data","Back","Forward","Reload","New tab","Close tab","Home",immersiveRequested?"Exit full screen":"Full screen","Desktop site"};
    String[] icons={"☷","⧉","⌕","◌","▣","×","×","×","⌂","ⓘ","⌕","⌕","▤","A","⌂","◉","◈","↓","◇","◐","⇩","⌕","☆","★","◷","⌕","↗","⌫","‹","›","⟳","+","×","⌂","⛶","▣"};
    // Keep fullscreen visible without scrolling; the menu actions still retain their original IDs.
    int[] menuIds=new int[labels.length];for(int i=0;i<menuIds.length;i++)menuIds[i]=i;
    int fullscreenIndex=labels.length-2, insertAt=4;
    String fullscreenLabel=labels[fullscreenIndex],fullscreenIcon=icons[fullscreenIndex];int fullscreenId=menuIds[fullscreenIndex];
    for(int i=fullscreenIndex;i>insertAt;i--){labels[i]=labels[i-1];icons[i]=icons[i-1];menuIds[i]=menuIds[i-1];}
    labels[insertAt]=fullscreenLabel;icons[insertAt]=fullscreenIcon;menuIds[insertAt]=fullscreenId;
    LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(5),dp(5),dp(5),dp(5));content.setBackground(rounded(PANEL,16));
    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.setVerticalScrollBarEnabled(false);scroll.addView(content);
    PopupWindow popup=new PopupWindow(scroll,dp(264),Math.min(dp(580),getResources().getDisplayMetrics().heightPixels-dp(100)),true);
    popup.setBackgroundDrawable(rounded(PANEL,16));popup.setElevation(dp(12));popup.setOutsideTouchable(true);popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
    for(int i=0;i<labels.length;i++){final int choice=i;LinearLayout line=new LinearLayout(this);line.setGravity(Gravity.CENTER_VERTICAL);line.setPadding(dp(14),0,dp(8),0);
      TextView symbol=new TextView(this);symbol.setText(icons[i]);symbol.setTextColor(ACCENT);symbol.setTextSize(21);symbol.setGravity(Gravity.CENTER_VERTICAL);line.addView(symbol,new LinearLayout.LayoutParams(dp(38),dp(47)));
      TextView label=new TextView(this);label.setText(labels[i]);label.setTextColor(0xffeaf1fc);label.setTextSize(15);label.setGravity(Gravity.CENTER_VERTICAL);line.addView(label,new LinearLayout.LayoutParams(-1,dp(47)));
      line.setOnClickListener(v->{popup.dismiss();handleMenuChoice(menuIds[choice]);});content.addView(line);
      if(i==3||i==11){View separator=new View(this);separator.setBackgroundColor(0xff354359);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(1));lp.setMargins(dp(12),dp(5),dp(12),dp(5));content.addView(separator,lp);}
    }
    popup.showAtLocation(outer,Gravity.TOP|Gravity.END,dp(8),Math.max(dp(8),root.getPaddingTop()+dp(56)));
  }
  void handleMenuChoice(int which){if(which==0){readerMode();return;}which--;if(which==0){copyCurrentUrl();return;}which--;if(which==0){findAddressSuggestion();return;}which--;if(which==0){clearSiteCookies();return;}which--;if(which==0){duplicateCurrentTab();return;}if(which==1){new AlertDialog.Builder(this).setTitle("Close all other tabs?").setPositiveButton("Close",(d,n)->closeOtherTabs()).setNegativeButton("Cancel",null).show();return;}if(which==2){deleteSavedEntry("history");return;}if(which==3){deleteSavedEntry("bookmarks");return;}if(which==4){addToHomeScreen();return;}if(which==5){showSiteInfo();return;}if(which==6){searchSavedPages("history");return;}if(which==7){searchSavedPages("bookmarks");return;}if(which==8){printPage();return;}if(which==9){textZoomMenu();return;}if(which==10){homepageMenu();return;}if(which==11){cookieMenu();return;}which-=12;switch(which){case 12:if(current>=0&&tabs.get(current).canGoBack())tabs.get(current).goBack();break;case 13:if(current>=0&&tabs.get(current).canGoForward())tabs.get(current).goForward();break;case 14:if(current>=0)tabs.get(current).reload();break;case 1:videoDownload();break;
      case 0:adblockMenu();break;case 4:downloads();break;
      case 5:searchEngineMenu();break;case 15:newTab("https://www.google.com");break;
      case 16:closeCurrentTab();break;
      case 17:navigate("https://www.google.com");break;case 18:setFullscreen(!immersiveRequested);break;
      case 19:if(current>=0){WebSettings settings=tabs.get(current).getSettings();boolean desktop=!settings.getUserAgentString().contains("Desktop BlockBrowser");settings.setUserAgentString(desktop?"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Desktop BlockBrowser":null);tabs.get(current).reload();}break;
      case 3:if(Build.VERSION.SDK_INT>=29&&current>=0){boolean dark=!prefs.getBoolean("dark",false);prefs.edit().putBoolean("dark",dark).apply();tabs.get(current).getSettings().setForceDark(dark?WebSettings.FORCE_DARK_ON:WebSettings.FORCE_DARK_OFF);tabs.get(current).reload();}else Toast.makeText(this,"Dark websites requires Android 10+",Toast.LENGTH_SHORT).show();break;
      case 2:new AlertDialog.Builder(this).setTitle("Privacy dashboard").setMessage("Blocked requests this session: "+blockedCount+"\\nBlocklist domains: "+blocked.size()).setPositiveButton("OK",null).show();break;
      case 6:addBookmark();break;case 7:savedPages("bookmarks","Bookmarks");break;case 8:savedPages("history","History");break;case 9:findOnPage();break;case 10:sharePage();break;case 11:clearBrowsingData();break;
    
    }}
  @Override public void onBackPressed(){if(customView!=null){hideCustomView();return;}if(fullscreen){setFullscreen(false);return;}if(current>=0&&tabs.get(current).canGoBack())tabs.get(current).goBack();else super.onBackPressed();}
  @Override protected void onDestroy(){if(pendingUpload!=null){pendingUpload.onReceiveValue(null);pendingUpload=null;}if(pendingPermission!=null){pendingPermission.deny();pendingPermission=null;}if(pendingGeo!=null){pendingGeo.invoke(pendingGeoOrigin,false,false);pendingGeo=null;}saveSession();sessionHandler.removeCallbacksAndMessages(null);for(WebView w:tabs){if(w.getParent()!=null)((android.view.ViewGroup)w.getParent()).removeView(w);w.destroy();}super.onDestroy();}
  /** Stream that throws as soon as WebView touches it, so the blocked request ends in a network error. */
  static final class FailingStream extends java.io.InputStream{
    @Override public int available() throws java.io.IOException{throw new java.io.IOException("Blocked by Block Browser");}
    @Override public int read() throws java.io.IOException{throw new java.io.IOException("Blocked by Block Browser");}
    @Override public int read(byte[] b,int off,int len) throws java.io.IOException{throw new java.io.IOException("Blocked by Block Browser");}
  }
}

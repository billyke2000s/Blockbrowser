package com.example.blockbrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.util.Rational;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AutoCompleteTextView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The browser window: toolbar, tabs, page loading, ad blocking, permissions and menus.
 * Private windows ({@link IncognitoActivity}) reuse all of this and override the hooks that touch saved data.
 * Larger screens live in separate classes: {@link TabSwitcher}, {@link LibraryPanels} (bookmarks, history,
 * recently closed), {@link Downloads}, {@link SitePanels} and {@link SettingsPanels}.
 */
public class MainActivity extends Activity {
  static final String TAG = "BlockBrowser";
  static final int SURFACE = 0xff111827, PANEL = 0xff1b2638, ACCENT = 0xff83b9ff, MUTED = 0xffaab8cc, TEXT = 0xffeaf1fc;
  /** WebViews kept alive at once; older background tabs are released and reload when shown again. */
  static final int MAX_LIVE_TABS = 8;
  static final int MAX_RESTORED_TABS = 100;

  // ---- Views ----
  FrameLayout outer, frame;
  LinearLayout root, toolbar, addressBox, findBar, snackbar;
  AutoCompleteTextView address;
  AddressAdapter addressAdapter;
  TextView tabCount, moreButton, exitFs, siteButton, homeButton, findCount, snackbarText, snackbarAction;
  EditText findInput;
  ProgressBar progress;
  TabSwitcher switcher;

  // ---- State ----
  SharedPreferences prefs;
  final ArrayList<Tab> tabs = new ArrayList<>();
  int current = -1;
  final Handler handler = new Handler(Looper.getMainLooper());        // full-screen control fade only
  final Handler sessionHandler = new Handler(Looper.getMainLooper()); // saving, undo, cleanup
  final ExecutorService background = Executors.newFixedThreadPool(2, r -> new Thread(r, "browser-bg"));

  /** Runs work off the UI thread; silently skipped once the window has closed (the executor is shut down then). */
  void runInBackground(Runnable r) {
    try { background.execute(r); } catch (java.util.concurrent.RejectedExecutionException ignored) {}
  }
  Runnable fadeTask, sessionSave, snackbarHide;
  boolean fullscreen = false, fullscreenBeforeVideo = false, immersiveRequested = false, restoringSession = false;
  View customView;
  WebChromeClient.CustomViewCallback customViewCallback;
  TextView websiteAspectButton;
  Runnable websiteAspectFade;
  AlertDialog websiteAspectDialog;
  WebView websiteVideoWebView;
  int websiteAspectMode;
  static final String[] WEBSITE_ASPECT_LABELS = {"Fit (original)", "Fill screen (stretch)", "Zoom (crop)", "16:9", "4:3"};
  // Installed in each frame so embedded players work without accessing cross-origin documents.
  // This only adjusts video CSS; it exposes no Android bridge or device permissions.
  static final String WEBSITE_ASPECT_SCRIPT = "(function(){"
      + "if(window.__bbVideoAspect)return;let mode=-1;const saved=new Map();"
      + "const keys=['position','left','top','width','height','max-width','max-height','transform','object-fit','object-position'];"
      + "function restore(){saved.forEach((old,v)=>keys.forEach((k,i)=>{"
      + "if(old[i][0])v.style.setProperty(k,old[i][0],old[i][1]);else v.style.removeProperty(k);}));saved.clear();}"
      + "function apply(){restore();if(mode<0)return;"
      + "const videos=Array.from(document.querySelectorAll('video')).filter(v=>{const r=v.getBoundingClientRect();return r.width>0&&r.height>0;});"
      + "videos.sort((a,b)=>{const x=a.getBoundingClientRect(),y=b.getBoundingClientRect();return y.width*y.height-x.width*x.height;});"
      + "const v=videos[0];if(!v)return;"
      + "saved.set(v,keys.map(k=>[v.style.getPropertyValue(k),v.style.getPropertyPriority(k)]));"
      + "const ratio=mode===3?16/9:mode===4?4/3:innerWidth/innerHeight;"
      + "const width=Math.min(innerWidth,innerHeight*ratio),height=width/ratio;"
      + "const values=['fixed','50%','50%',width+'px',height+'px','none','none','translate(-50%, -50%)',"
      + "mode===2?'cover':mode===0?'contain':'fill','center'];"
      + "keys.forEach((k,i)=>v.style.setProperty(k,values[i],'important'));}"
      + "window.__bbVideoAspect=function(m){if(!Number.isInteger(m)||m< -1||m>4)return;mode=m;apply();"
      + "for(let i=0;i<frames.length;i++)frames[i].postMessage({bbVideoAspect:m},'*');};"
      + "addEventListener('message',e=>{if(e.source===parent&&e.source!==window&&e.data)window.__bbVideoAspect(e.data.bbVideoAspect);});"
      + "addEventListener('resize',apply);document.addEventListener('loadedmetadata',apply,true);"
      + "document.addEventListener('fullscreenchange',apply);"
      + "})();";
  float downX, downY;
  SiteSettings siteSettings;

  // Pending page requests waiting for the user or Android.
  PermissionRequest pendingPermission;
  AlertDialog permissionDialog;
  ValueCallback<Uri[]> pendingUpload;
  /** Tabs closed in the last few seconds, kept alive so Undo restores them exactly. */
  final ArrayList<Tab> undoTabs = new ArrayList<>();
  final ArrayList<Integer> undoIndexes = new ArrayList<>();
  /** Whether the tab being undone was the one on screen (then Undo shows it again). */
  boolean undoWasCurrent;

  // ---- Ad blocking ----
  volatile boolean blockAds = true;
  static final List<String> BUILTIN_BLOCKED = Arrays.asList("doubleclick.net", "googlesyndication.com", "googleadservices.com", "adnxs.com", "adsrvr.org", "taboola.com", "outbrain.com", "scorecardresearch.com", "criteo.com", "pubmatic.com", "rubiconproject.com", "openx.net", "amazon-adsystem.com", "facebook.net");
  // Replaced in one step once assets/blocklist.txt has loaded, so request threads never see a half-built set.
  volatile Set<String> blocked = new HashSet<>(BUILTIN_BLOCKED);
  volatile boolean listLoaded = false;
  final AtomicLong blockedCount = new AtomicLong();
  BlockingStats blockingStats;

  static final String[] ENGINE_NAMES = {"Google", "Bing", "DuckDuckGo", "Brave Search", "Startpage", "Ecosia", "Yahoo", "Qwant", "Mojeek", "SearXNG"};
  static final String[] ENGINE_URLS = {"https://www.google.com/search?q=", "https://www.bing.com/search?q=", "https://duckduckgo.com/?q=", "https://search.brave.com/search?q=", "https://www.startpage.com/sp/search?query=", "https://www.ecosia.org/search?q=", "https://search.yahoo.com/search?p=", "https://www.qwant.com/?q=", "https://www.mojeek.com/search?q=", "https://searx.be/search?q="};

  // =====================================================================================================
  // Profile hooks (overridden by IncognitoActivity)
  // =====================================================================================================

  SharedPreferences browserPreferences() { return getSharedPreferences("browser", MODE_PRIVATE); }
  /** Bookmarks. The same file as {@link #browserPreferences()} in normal windows; read-only in private windows. */
  SharedPreferences library() { return getSharedPreferences("browser", MODE_PRIVATE); }
  boolean isPrivate() { return false; }
  HistoryStore history() { return HistoryStore.get(this, false); }
  BookmarkStore bookmarks() { return new BookmarkStore(library()); }
  FaviconCache favicons() { return FaviconCache.get(this, true); }
  SiteSettings createSiteSettings() { return new SiteSettings(prefs); }
  SiteSettings siteSettings() { return siteSettings; }
  /** Hook for private windows to restyle the toolbar; normal windows keep the default look. */
  void applyProfileStyle() {}
  /** Android Autofill for page forms. Private windows opt out so autofill services are not offered private form data. */
  int autofillMode() { return Build.VERSION.SDK_INT >= 26 ? View.IMPORTANT_FOR_AUTOFILL_YES : 0; }
  void saveVisit(String url, String title) { history().recordVisit(url, title); }
  /** Back pressed with nothing left to go back to: keep the tabs and move the app to the background. */
  void onBackAtRoot() { moveTaskToBack(true); }

  /** Used by private windows that must not start a browser (no isolated profile available). */
  void onCreateWithoutBrowser(Bundle b) { super.onCreate(b); }

  // =====================================================================================================
  // Small helpers
  // =====================================================================================================

  int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density); }

  GradientDrawable rounded(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  TextView button(String s, Runnable r) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextColor(TEXT);
    t.setTextSize(21);
    t.setGravity(Gravity.CENTER);
    t.setMinWidth(dp(44));
    t.setMinHeight(dp(48));
    t.setPadding(dp(9), dp(8), dp(9), dp(8));
    t.setBackground(rounded(PANEL, 13));
    t.setOnClickListener(v -> r.run());
    return t;
  }

  void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
  void toastLong(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

  void hideKeyboard() {
    InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
    if (imm != null) imm.hideSoftInputFromWindow(outer.getWindowToken(), 0);
  }

  void copyText(String label, String value) {
    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
    ClipData clip = ClipData.newPlainText(label, value);
    if (isPrivate() && Build.VERSION.SDK_INT >= 33) {
      // Keeps copied private addresses out of the clipboard preview Android shows.
      android.os.PersistableBundle extras = new android.os.PersistableBundle();
      extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
      clip.getDescription().setExtras(extras);
    }
    clipboard.setPrimaryClip(clip);
    if (Build.VERSION.SDK_INT < 33) toast("Copied " + label.toLowerCase(Locale.ROOT)); // Android 13+ shows its own confirmation
  }

  void shareLink(String url) {
    Intent intent = new Intent(Intent.ACTION_SEND);
    intent.setType("text/plain");
    intent.putExtra(Intent.EXTRA_TEXT, url);
    startActivity(Intent.createChooser(intent, "Share link"));
  }

  int engine() {
    int e = prefs.getInt("search_engine", 0);
    return e < 0 || e >= ENGINE_URLS.length ? 0 : e;
  }

  String searchPrefix() { return ENGINE_URLS[engine()]; }
  /** Block Browser's own start page unless the user chose another address (the old google.com default counts as unset). */
  String homeUrl() {
    String h = prefs.getString("homepage", null);
    if (h == null || h.equals("https://www.google.com") || h.equals("https://www.google.com/")) return HomePage.URL;
    return h;
  }
  boolean stripTrackingOn() { return prefs.getBoolean("strip_tracking", true); }
  boolean searchSuggestionsOn() { return !isPrivate() && prefs.getBoolean("search_suggestions", false); }

  // ---- Activity results and runtime permissions, by callback ----
  interface ResultCallback { void onResult(int resultCode, Intent data); }
  interface PermissionCallback { void onResult(boolean[] granted); }
  private final SparseArray<ResultCallback> resultCallbacks = new SparseArray<>();
  private final SparseArray<PermissionCallback> permissionCallbacks = new SparseArray<>();
  private int nextRequestCode = 6000;

  boolean startForResult(Intent intent, ResultCallback callback) {
    int code = nextRequestCode++;
    resultCallbacks.put(code, callback);
    try {
      startActivityForResult(intent, code);
      return true;
    } catch (ActivityNotFoundException | SecurityException e) {
      resultCallbacks.remove(code);
      return false;
    }
  }

  void requestRuntime(String[] permissions, PermissionCallback callback) {
    boolean[] granted = new boolean[permissions.length];
    boolean all = true;
    for (int i = 0; i < permissions.length; i++) {
      granted[i] = checkSelfPermission(permissions[i]) == PackageManager.PERMISSION_GRANTED;
      all &= granted[i];
    }
    if (all) { callback.onResult(granted); return; }
    int code = nextRequestCode++;
    permissionCallbacks.put(code, callback);
    requestPermissions(permissions, code);
  }

  @Override protected void onActivityResult(int code, int result, Intent data) {
    super.onActivityResult(code, result, data);
    ResultCallback cb = resultCallbacks.get(code);
    if (cb == null) return;
    resultCallbacks.remove(code);
    cb.onResult(result, data);
  }

  @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(code, permissions, grants);
    PermissionCallback cb = permissionCallbacks.get(code);
    if (cb == null) return;
    permissionCallbacks.remove(code);
    boolean[] granted = new boolean[permissions.length];
    for (int i = 0; i < permissions.length; i++) granted[i] = i < grants.length && grants[i] == PackageManager.PERMISSION_GRANTED;
    cb.onResult(granted);
  }

  // =====================================================================================================
  // Startup and layout
  // =====================================================================================================

  void loadBlocklist() {
    new Thread(() -> {
      HashSet<String> set = new HashSet<>(300000);
      set.addAll(BUILTIN_BLOCKED);
      try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(getAssets().open("blocklist.txt"), "UTF-8"))) {
        String line;
        while ((line = r.readLine()) != null) {
          line = line.trim();
          if (!line.isEmpty() && line.charAt(0) != '#') set.add(line.toLowerCase(Locale.ROOT));
        }
      } catch (java.io.IOException e) {
        Log.w(TAG, "blocklist.txt not loaded, using built-in list only", e);
      }
      blocked = set;
      listLoaded = true;
    }, "blocklist-loader").start();
  }

  @Override public void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().setStatusBarColor(0xff10131a);
    getWindow().setNavigationBarColor(0xff10131a);
    prefs = browserPreferences();
    blockAds = prefs.getBoolean("adblock", true);
    immersiveRequested = prefs.getBoolean("fullscreen", false);
    siteSettings = createSiteSettings();
    blockingStats = isPrivate() ? null : new BlockingStats(this);
    loadBlocklist();
    if (!isPrivate()) history().migrateFrom(prefs);
    buildUi();
    setupEdgeToEdge();
    applyProfileStyle();
    restoreSession();
    if (immersiveRequested) setFullscreen(true);
    // Only a fresh launch carries a new link. When Android recreates the window (after closing the app in the
    // background, or reopening it from recent apps) it hands back the original intent, and opening that link
    // again would add a duplicate tab: the restored session already has it.
    boolean fromHistory = (getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0;
    if (b == null && !fromHistory) handleExternalIntent(getIntent());
  }

  void buildUi() {
    root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(SURFACE);
    outer = new FrameLayout(this);
    outer.setBackgroundColor(SURFACE);
    outer.addView(root, new FrameLayout.LayoutParams(-1, -1));
    setContentView(outer);

    toolbar = new LinearLayout(this);
    toolbar.setId(View.generateViewId());
    toolbar.setGravity(Gravity.CENTER_VERTICAL);
    toolbar.setPadding(dp(6), dp(8), dp(6), dp(8));
    toolbar.setBackgroundColor(SURFACE);
    root.addView(toolbar);

    homeButton = button("⌂", () -> loadInCurrent(homeUrl()));
    homeButton.setContentDescription("Home");
    homeButton.setBackgroundColor(Color.TRANSPARENT);
    homeButton.setTextSize(27);
    toolbar.addView(homeButton);

    addressBox = new LinearLayout(this);
    addressBox.setGravity(Gravity.CENTER_VERTICAL);
    addressBox.setBackground(rounded(PANEL, 28));
    siteButton = new TextView(this);
    siteButton.setTextSize(14);
    siteButton.setGravity(Gravity.CENTER);
    siteButton.setTextColor(MUTED);
    siteButton.setOnClickListener(v -> SitePanels.showSiteInfo(this));
    addressBox.addView(siteButton, new LinearLayout.LayoutParams(dp(40), dp(48)));
    address = new AutoCompleteTextView(this);
    address.setContentDescription("Address bar");
    address.setSingleLine(true);
    address.setTextColor(0xfff4f7ff);
    address.setHintTextColor(MUTED);
    address.setBackgroundColor(Color.TRANSPARENT);
    address.setPadding(0, 0, dp(12), 0);
    address.setHint("Search or enter URL");
    address.setTextSize(15);
    address.setSelectAllOnFocus(true);
    address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    address.setImeOptions(EditorInfo.IME_ACTION_GO);
    address.setThreshold(1);
    address.setDropDownAnchor(toolbar.getId());
    address.setDropDownWidth(ViewGroup.LayoutParams.MATCH_PARENT);
    address.setDropDownBackgroundDrawable(rounded(PANEL, 16));
    addressAdapter = new AddressAdapter(this, new AddressAdapter.Source() {
      @Override public List<SuggestionRanker.Page> pagesMatching(String query) { return suggestionPages(query); }
      @Override public List<String> searchSuggestions(String query) {
        return searchSuggestionsOn() ? SearchSuggestions.fetch(engine(), query, 1500) : Collections.<String>emptyList();
      }
      @Override public String searchPrefix() { return MainActivity.this.searchPrefix(); }
      @Override public String searchEngineName() { return ENGINE_NAMES[engine()]; }
      @Override public Bitmap favicon(String host) { return favicons().get(host); }
      @Override public void fill(String text) { address.setText(text); address.setSelection(address.getText().length()); }
    });
    address.setAdapter(addressAdapter);
    address.setOnItemClickListener((parent, view, index, id) -> loadInCurrent(addressAdapter.getItem(index).s.url));
    address.setOnEditorActionListener((v, id, event) -> {
      if (id == EditorInfo.IME_ACTION_GO || id == EditorInfo.IME_ACTION_DONE
          || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
        navigate(address.getText().toString());
        return true;
      }
      return false;
    });
    address.setOnFocusChangeListener((v, focused) -> {
      if (!focused) { address.dismissDropDown(); Tab t = currentTab(); setAddressText(t == null ? "" : t.currentUrl()); }
    });
    address.setMinHeight(dp(48));
    addressBox.addView(address, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(0, dp(48), 1);
    boxLp.setMargins(dp(4), 0, dp(4), 0);
    toolbar.addView(addressBox, boxLp);

    tabCount = button("1", this::showTabSwitcher);
    tabCount.setContentDescription("Switch tabs");
    tabCount.setTextSize(14);
    tabCount.setTypeface(null, Typeface.BOLD);
    GradientDrawable tabBox = new GradientDrawable();
    tabBox.setStroke(dp(2), TEXT);
    tabBox.setCornerRadius(dp(6));
    // The square outline sits inside the 48dp touch target.
    tabCount.setBackground(new android.graphics.drawable.InsetDrawable(tabBox, dp(10), dp(12), dp(10), dp(12)));
    tabCount.setPadding(0, 0, 0, 0);
    toolbar.addView(tabCount, new LinearLayout.LayoutParams(dp(46), dp(48)));
    moreButton = button("⋮", this::menu);
    moreButton.setContentDescription("Browser menu");
    moreButton.setBackgroundColor(Color.TRANSPARENT);
    toolbar.addView(moreButton);

    progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progress.setMax(100);
    progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
    progress.setProgressBackgroundTintList(ColorStateList.valueOf(SURFACE));
    progress.setVisibility(View.INVISIBLE);
    progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));

    frame = new FrameLayout(this);
    frame.setBackgroundColor(Color.WHITE);
    root.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));

    buildFindBar();
    root.addView(findBar);

    exitFs = button("⛶", () -> { if (exitFs.getAlpha() < 0.35f) showFsControl(); else setFullscreen(false); });
    exitFs.setContentDescription("Fullscreen control");
    exitFs.setTextSize(23);
    exitFs.setPadding(dp(14), dp(8), dp(14), dp(8));
    exitFs.setBackground(rounded(PANEL, 18));
    exitFs.setAlpha(0.8f);
    exitFs.setVisibility(View.GONE);
    FrameLayout.LayoutParams exitLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
    exitLp.setMargins(0, dp(24), dp(16), 0);
    outer.addView(exitFs, exitLp);

    buildSnackbar();
    // Keep focus off the address bar when a window opens, so the keyboard stays hidden and Back acts on the page.
    root.setFocusableInTouchMode(true);
    root.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
    root.requestFocus();
  }

  void buildFindBar() {
    findBar = new LinearLayout(this);
    findBar.setGravity(Gravity.CENTER_VERTICAL);
    findBar.setBackground(rounded(PANEL, 20));
    findBar.setPadding(dp(12), dp(6), dp(8), dp(6));
    findBar.setElevation(dp(6));
    findBar.setVisibility(View.GONE);
    findInput = new EditText(this);
    findInput.setSingleLine(true);
    findInput.setHint("Search this page");
    findInput.setContentDescription("Find in page");
    findInput.setTextColor(TEXT);
    findInput.setHintTextColor(MUTED);
    findInput.setBackgroundColor(Color.TRANSPARENT);
    findInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
    findInput.addTextChangedListener(new TextWatcher() {
      public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
      public void onTextChanged(CharSequence s, int a, int b, int c) {}
      public void afterTextChanged(Editable e) { Tab t = currentTab(); if (t != null && t.web != null) t.web.findAllAsync(e.toString()); }
    });
    findInput.setOnEditorActionListener((v, id, e) -> { Tab t = currentTab(); if (t != null && t.web != null) t.web.findNext(true); return true; });
    findBar.addView(findInput, new LinearLayout.LayoutParams(0, dp(48), 1));
    findCount = new TextView(this);
    findCount.setTextColor(MUTED);
    findCount.setPadding(dp(8), 0, dp(8), 0);
    findBar.addView(findCount);
    findBar.addView(smallButton("▲", "Previous match", () -> { Tab t = currentTab(); if (t != null && t.web != null) t.web.findNext(false); }));
    findBar.addView(smallButton("▼", "Next match", () -> { Tab t = currentTab(); if (t != null && t.web != null) t.web.findNext(true); }));
    findBar.addView(smallButton("×", "Close find in page", this::closeFind));
  }

  TextView smallButton(String glyph, String description, Runnable r) {
    TextView t = new TextView(this);
    t.setText(glyph);
    t.setTextColor(TEXT);
    t.setTextSize(18);
    t.setGravity(Gravity.CENTER);
    t.setMinWidth(dp(48));
    t.setMinHeight(dp(48));
    t.setContentDescription(description);
    t.setOnClickListener(v -> r.run());
    return t;
  }

  void showFind() {
    Tab t = currentTab();
    if (t == null || t.web == null) return;
    if (fullscreen) setFullscreen(false);
    findBar.setVisibility(View.VISIBLE);
    findCount.setText("");
    t.web.setFindListener((active, count, done) -> {
      if (done) findCount.setText(count == 0 ? (findInput.getText().length() == 0 ? "" : "0/0") : (active + 1) + "/" + count);
    });
    findInput.requestFocus();
    InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
    if (imm != null) imm.showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT);
    if (findInput.length() > 0) t.web.findAllAsync(findInput.getText().toString());
  }

  void closeFind() {
    Tab t = currentTab();
    if (t != null && t.web != null) { t.web.clearMatches(); t.web.setFindListener(null); }
    findBar.setVisibility(View.GONE);
    hideKeyboard();
  }

  void buildSnackbar() {
    snackbar = new LinearLayout(this);
    snackbar.setGravity(Gravity.CENTER_VERTICAL);
    snackbar.setBackground(rounded(0xff2b3a55, 12));
    snackbar.setPadding(dp(16), dp(4), dp(6), dp(4));
    snackbar.setElevation(dp(8));
    snackbar.setVisibility(View.GONE);
    snackbarText = new TextView(this);
    snackbarText.setTextColor(TEXT);
    snackbarText.setTextSize(14);
    snackbar.addView(snackbarText, new LinearLayout.LayoutParams(0, -2, 1));
    snackbarAction = new TextView(this);
    snackbarAction.setTextColor(ACCENT);
    snackbarAction.setTextSize(14);
    snackbarAction.setTypeface(null, Typeface.BOLD);
    snackbarAction.setGravity(Gravity.CENTER);
    snackbarAction.setPadding(dp(12), 0, dp(12), 0);
    snackbar.addView(snackbarAction, new LinearLayout.LayoutParams(-2, dp(48)));
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
    lp.setMargins(dp(12), 0, dp(12), dp(20));
    outer.addView(snackbar, lp);
  }

  /** A short message at the bottom with one action, hidden after a few seconds. */
  void showSnackbar(String text, String action, Runnable onAction, Runnable onExpire) {
    if (switcher != null && switcher.isShowing()) { switcher.showSnackbar(text, action, onAction, onExpire); return; }
    if (snackbarHide != null) { sessionHandler.removeCallbacks(snackbarHide); snackbarHide.run(); }
    snackbarText.setText(text);
    snackbarAction.setText(action);
    snackbarAction.setVisibility(action == null ? View.GONE : View.VISIBLE);
    snackbar.setVisibility(View.VISIBLE);
    snackbar.bringToFront();
    final boolean[] done = {false};
    Runnable hide = () -> {
      if (done[0]) return;
      done[0] = true;
      snackbar.setVisibility(View.GONE);
      snackbarHide = null;
      if (onExpire != null) onExpire.run();
    };
    snackbarHide = hide;
    snackbarAction.setOnClickListener(v -> {
      if (done[0]) return;
      done[0] = true;
      sessionHandler.removeCallbacks(hide);
      snackbar.setVisibility(View.GONE);
      snackbarHide = null;
      if (onAction != null) onAction.run();
    });
    sessionHandler.postDelayed(hide, 6000);
    // Root padding includes the navigation bar; keep the bar above it.
    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) snackbar.getLayoutParams();
    lp.bottomMargin = root.getPaddingBottom() + dp(20);
    snackbar.setLayoutParams(lp);
  }

  // Android 15+ draws apps behind the status and navigation bars (edge-to-edge). Draw edge-to-edge on every
  // version and pad the UI by the bars' size ourselves, so the toolbar sits below the status bar everywhere.
  void setupEdgeToEdge() {
    getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
    else outer.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    outer.setOnApplyWindowInsetsListener((v, ins) -> {
      int l, t, r, bars, keyboard;
      if (Build.VERSION.SDK_INT >= 30) {
        android.graphics.Insets b = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        l = b.left; t = b.top; r = b.right; bars = b.bottom;
        keyboard = ins.getInsets(WindowInsets.Type.ime()).bottom;
      } else {
        l = ins.getSystemWindowInsetLeft(); t = ins.getSystemWindowInsetTop(); r = ins.getSystemWindowInsetRight();
        bars = ins.getSystemWindowInsetBottom(); keyboard = bars;
      }
      Log.i("BlockBrowserInsets", "top=" + t + " bottom=" + bars + " keyboard=" + keyboard + " fullscreen=" + fullscreen); // read by scripts/ui-check.sh
      if (fullscreen) root.setPadding(0, 0, 0, keyboard);
      else root.setPadding(l, t, r, Math.max(bars, keyboard));
      return ins;
    });
  }

  // =====================================================================================================
  // Full screen
  // =====================================================================================================

  void setFullscreen(boolean on) {
    fullscreen = on;
    if (customView == null) { immersiveRequested = on; prefs.edit().putBoolean("fullscreen", on).apply(); }
    toolbar.setVisibility(on ? View.GONE : View.VISIBLE);
    if (on) { progress.setVisibility(View.GONE); if (findBar.getVisibility() == View.VISIBLE) closeFind(); }
    else updateProgress(currentTab());
    exitFs.setVisibility(on && customView == null ? View.VISIBLE : View.GONE);
    if (on && customView == null) { exitFs.bringToFront(); showFsControl(); }
    else handler.removeCallbacksAndMessages(null);
    if (on) { hideKeyboard(); address.clearFocus(); }
    if (Build.VERSION.SDK_INT >= 28) {
      WindowManager.LayoutParams lp = getWindow().getAttributes();
      lp.layoutInDisplayCutoutMode = on ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
      getWindow().setAttributes(lp);
    }
    applySystemBars();
    outer.requestApplyInsets();
  }

  void showFsControl() {
    handler.removeCallbacksAndMessages(null);
    exitFs.animate().cancel();
    exitFs.setAlpha(0.85f);
    fadeTask = () -> exitFs.animate().alpha(0.24f).setDuration(350).start();
    handler.postDelayed(fadeTask, 3000);
  }

  void applySystemBars() {
    if (Build.VERSION.SDK_INT >= 30) {
      WindowInsetsController c = getWindow().getInsetsController();
      if (c == null) return;
      if (fullscreen) { c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); c.hide(WindowInsets.Type.systemBars()); }
      else c.show(WindowInsets.Type.systemBars());
    } else {
      int f = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
      if (fullscreen) f |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
      outer.setSystemUiVisibility(f);
    }
  }

  // Older Android versions can drop immersive mode after a dialog; re-apply when the window regains focus.
  @Override public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus && fullscreen) applySystemBars();
  }

  void hideCustomView() {
    if (customView == null) return;
    if (websiteVideoWebView != null) setWebsiteVideoAspect(-1);
    if (websiteAspectFade != null) handler.removeCallbacks(websiteAspectFade);
    if (websiteAspectButton != null) {
      websiteAspectButton.animate().cancel();
      outer.removeView(websiteAspectButton);
    }
    websiteAspectButton = null;
    if (websiteAspectDialog != null) websiteAspectDialog.dismiss();
    websiteAspectDialog = null;
    websiteVideoWebView = null;
    outer.removeView(customView);
    customView = null;
    if (customViewCallback != null) customViewCallback.onCustomViewHidden();
    customViewCallback = null;
    setFullscreen(immersiveRequested);
  }

  void setWebsiteVideoAspect(int mode) {
    if (websiteVideoWebView == null) return;
    websiteVideoWebView.evaluateJavascript(WEBSITE_ASPECT_SCRIPT
        + "window.__bbVideoAspect(" + mode + ");", null);
  }

  void showWebsiteAspectControl(WebView web) {
    websiteVideoWebView = web;
    websiteAspectMode = 0;
    websiteAspectButton = new TextView(this);
    websiteAspectButton.setText("\u25ad");
    websiteAspectButton.setContentDescription("Aspect ratio: " + WEBSITE_ASPECT_LABELS[0]);
    websiteAspectButton.setTextColor(Color.WHITE);
    websiteAspectButton.setTextSize(24);
    // Small visual control, with a 48dp touch target for reliable tapping.
    GradientDrawable background = new GradientDrawable();
    background.setColor(0x66111827);
    background.setCornerRadius(dp(24));
    websiteAspectButton.setBackground(background);
    websiteAspectButton.setGravity(Gravity.CENTER);
    websiteAspectButton.setFocusable(true);
    websiteAspectButton.setOnClickListener(v -> {
      if (websiteAspectFade != null) handler.removeCallbacks(websiteAspectFade);
      websiteAspectDialog = new AlertDialog.Builder(this)
        .setTitle("Aspect ratio")
        .setSingleChoiceItems(WEBSITE_ASPECT_LABELS, websiteAspectMode, (dialog, which) -> {
          websiteAspectMode = which;
          setWebsiteVideoAspect(which);
          if (websiteAspectButton != null) {
            websiteAspectButton.setContentDescription("Aspect ratio: " + WEBSITE_ASPECT_LABELS[which]);
          }
          dialog.dismiss();
        }).setNegativeButton("Cancel", null).create();
      websiteAspectDialog.setOnDismissListener(dialog -> {
        websiteAspectDialog = null;
        showWebsiteAspectButton();
      });
      websiteAspectDialog.show();
    });
    FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.END);
    layout.setMargins(dp(16), dp(24), dp(16), 0);
    outer.addView(websiteAspectButton, layout);
    setWebsiteVideoAspect(0);
    showWebsiteAspectButton();
  }

  void showWebsiteAspectButton() {
    if (websiteAspectButton == null || isInPictureInPictureMode()) return;
    if (websiteAspectFade != null) handler.removeCallbacks(websiteAspectFade);
    TextView button = websiteAspectButton;
    button.animate().cancel();
    button.setVisibility(View.VISIBLE);
    button.setAlpha(0.8f);
    if (websiteAspectDialog != null) return;
    websiteAspectFade = () -> button.animate().alpha(0f).setDuration(250)
        .withEndAction(() -> button.setVisibility(View.INVISIBLE)).start();
    handler.postDelayed(websiteAspectFade, 2500);
  }

  @Override public boolean dispatchTouchEvent(MotionEvent event) {
    // Observe taps without consuming them, so the website's playback controls still work.
    if (customView != null && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
      showWebsiteAspectButton();
    }
    return super.dispatchTouchEvent(event);
  }

  @Override public void onPictureInPictureModeChanged(boolean inPictureInPictureMode,
      android.content.res.Configuration configuration) {
    super.onPictureInPictureModeChanged(inPictureInPictureMode, configuration);
    if (websiteAspectButton != null) {
      if (inPictureInPictureMode) {
        if (websiteAspectFade != null) handler.removeCallbacks(websiteAspectFade);
        websiteAspectButton.animate().cancel();
        websiteAspectButton.setVisibility(View.GONE);
      } else showWebsiteAspectButton();
    }
  }

  /** Leaving the app while a video plays full screen continues it in a picture-in-picture window. */
  @Override protected void onUserLeaveHint() {
    super.onUserLeaveHint();
    if (customView != null && Build.VERSION.SDK_INT >= 26 && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
      try {
        enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(new Rational(16, 9)).build());
      } catch (Exception e) {
        Log.w(TAG, "picture-in-picture unavailable", e);
      }
    }
  }

  // =====================================================================================================
  // Tabs
  // =====================================================================================================

  Tab currentTab() { return current >= 0 && current < tabs.size() ? tabs.get(current) : null; }

  Tab tabFor(WebView w) {
    for (Tab t : tabs) if (t.web == w) return t;
    return null;
  }

  /** Opens a new tab at the end, in front. */
  Tab newTab(String url) { return addTab(url, true, tabs.size(), null); }

  /** Opens a tab next to the current one (links), in front or in the background. */
  Tab newTab(String url, boolean background) {
    return addTab(url, !background, current < 0 ? tabs.size() : current + 1, currentTab());
  }

  Tab addTab(String url, boolean foreground, int index, Tab parent) {
    Tab t = new Tab(url);
    t.parent = parent;
    int i = Math.max(0, Math.min(index, tabs.size()));
    tabs.add(i, t);
    if (i <= current) current++;
    if (foreground || current < 0) selectTab(i);
    else {
      activate(t); // background tabs start loading right away
      refreshTabCount();
      scheduleSessionSave();
      showSnackbar("Opened in background", "Switch", () -> { int j = tabs.indexOf(t); if (j >= 0) selectTab(j); }, null);
    }
    return t;
  }

  /** Creates the tab's WebView if needed, loading its saved address. */
  void activate(Tab t) {
    if (t.web != null) return;
    t.web = createWebView(t);
    if (t.url != null) t.web.loadUrl(t.url);
  }

  void selectTab(int n) {
    if (n < 0 || n >= tabs.size()) return;
    Tab old = currentTab();
    Tab t = tabs.get(n);
    if (old != null && old != t) captureThumbnail(old);
    if (findBar.getVisibility() == View.VISIBLE) closeFind();
    current = n;
    t.lastUsed = System.currentTimeMillis();
    boolean wasLive = t.web != null;
    activate(t);
    if (wasLive && old != t && HomePage.isHome(t.currentUrl()) && !t.loading) t.web.reload(); // fresh blocking counts
    frame.removeAllViews();
    if (t.web.getParent() != null) ((ViewGroup) t.web.getParent()).removeView(t.web);
    frame.addView(t.web, new FrameLayout.LayoutParams(-1, -1));
    updateToolbar(t);
    trimLiveTabs();
    scheduleSessionSave();
  }

  /** Kept for code that refers to tabs by position. */
  void switchTab(int n) { selectTab(n); }

  void moveTab(int from, int to) {
    if (from < 0 || to < 0 || from >= tabs.size() || to >= tabs.size() || from == to) return;
    Tab cur = currentTab();
    Tab t = tabs.remove(from);
    tabs.add(to, t);
    current = tabs.indexOf(cur);
    scheduleSessionSave();
  }

  void releaseWebView(Tab t) {
    if (t.web == null) return;
    WebView w = t.web;
    t.web = null;
    t.loading = false;
    if (w.getParent() != null) ((ViewGroup) w.getParent()).removeView(w);
    w.stopLoading();
    w.setWebChromeClient(null);
    w.setWebViewClient(new WebViewClient());
    w.destroy();
  }

  /** Releases a background tab's WebView; it reloads (with its scroll position) when shown again. */
  void discard(Tab t) {
    if (t.web == null || t == currentTab()) return;
    t.url = t.currentUrl();
    t.restoreY = t.web.getScrollY();
    t.errorUrl = null;
    releaseWebView(t);
  }

  void trimLiveTabs() {
    ArrayList<Tab> live = new ArrayList<>();
    for (Tab t : tabs) if (t.web != null && t != currentTab()) live.add(t);
    if (live.size() + 1 <= MAX_LIVE_TABS) return;
    Collections.sort(live, (a, b) -> Long.compare(a.lastUsed, b.lastUsed));
    for (int i = 0; i < live.size() + 1 - MAX_LIVE_TABS; i++) discard(live.get(i));
  }

  @Override public void onTrimMemory(int level) {
    super.onTrimMemory(level);
    if (level >= TRIM_MEMORY_BACKGROUND || level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL) {
      for (Tab t : new ArrayList<>(tabs)) discard(t);
      Log.i(TAG, "memory low (level " + level + "): released background tabs");
    }
  }

  @Override public void onLowMemory() {
    super.onLowMemory();
    for (Tab t : new ArrayList<>(tabs)) discard(t);
  }

  void captureThumbnail(Tab t) {
    WebView w = t.web;
    if (w == null || w.getWidth() == 0 || w.getHeight() == 0 || w.getParent() == null) return;
    try {
      float s = 0.4f;
      Bitmap b = Bitmap.createBitmap(Math.max(1, (int) (w.getWidth() * s)), Math.max(1, (int) (w.getHeight() * s)), Bitmap.Config.RGB_565);
      Canvas c = new Canvas(b);
      c.scale(s, s);
      c.translate(-w.getScrollX(), -w.getScrollY());
      w.draw(c);
      t.thumbnail = b;
    } catch (Throwable e) {
      Log.w(TAG, "could not capture tab preview", e);
    }
  }

  /** Closes a tab. With undo, the tab stays alive for a few seconds so Undo restores it exactly. */
  void closeTab(int index, boolean allowUndo) {
    if (index < 0 || index >= tabs.size()) return;
    finishPendingUndo();
    Tab t = tabs.remove(index);
    boolean wasCurrent = index == current;
    if (index < current) current--;
    else if (wasCurrent) current = -1;
    if (t.web != null) {
      if (t.web.getParent() != null) ((ViewGroup) t.web.getParent()).removeView(t.web);
      t.web.onPause(); // stop media while the tab waits for Undo
    }
    rememberClosedTab(t.currentUrl(), t.displayTitle());
    if (tabs.isEmpty()) addTab(homeUrl(), true, 0, null);
    else if (wasCurrent) {
      int parent = t.parent == null ? -1 : tabs.indexOf(t.parent);
      selectTab(parent >= 0 ? parent : Math.min(index, tabs.size() - 1));
    } else refreshTabCount();
    if (allowUndo) {
      undoTabs.add(t);
      undoIndexes.add(index);
      undoWasCurrent = wasCurrent;
      showSnackbar("Closed " + shortTitle(t), "Undo", this::undoClose, this::finishPendingUndo);
    } else releaseWebView(t);
    scheduleSessionSave();
  }

  String shortTitle(Tab t) {
    String s = t.displayTitle();
    return s.length() > 28 ? s.substring(0, 27) + "…" : s;
  }

  void closeCurrentTab() { closeTab(current, true); }

  void closeOtherTabs() {
    Tab keep = currentTab();
    if (keep == null || tabs.size() < 2) return;
    finishPendingUndo();
    for (int i = tabs.size() - 1; i >= 0; i--) {
      Tab t = tabs.get(i);
      if (t == keep) continue;
      tabs.remove(i);
      rememberClosedTab(t.currentUrl(), t.displayTitle());
      if (t.web != null) t.web.onPause();
      undoTabs.add(0, t);
      undoIndexes.add(0, i);
    }
    current = 0;
    undoWasCurrent = false;
    refreshTabCount();
    scheduleSessionSave();
    showSnackbar("Closed " + undoTabs.size() + " tabs", "Undo", this::undoClose, this::finishPendingUndo);
  }

  void closeAllTabs() {
    finishPendingUndo();
    for (int i = 0; i < tabs.size(); i++) {
      Tab t = tabs.get(i);
      rememberClosedTab(t.currentUrl(), t.displayTitle());
      if (t.web != null) { if (t.web.getParent() != null) ((ViewGroup) t.web.getParent()).removeView(t.web); t.web.onPause(); }
      undoTabs.add(t);
      undoIndexes.add(i);
    }
    int n = tabs.size();
    tabs.clear();
    current = -1;
    undoWasCurrent = true;
    Tab home = addTab(homeUrl(), true, 0, null);
    scheduleSessionSave();
    showSnackbar("Closed " + n + (n == 1 ? " tab" : " tabs"), "Undo", () -> {
      undoClose();
      // Drop the fresh home tab that replaced them, unless it has been used.
      int h = tabs.indexOf(home);
      if (h >= 0 && tabs.size() > 1 && (home.web == null || !home.web.canGoBack())) closeTab(h, false);
    }, this::finishPendingUndo);
  }

  void undoClose() {
    Tab last = null;
    for (int k = 0; k < undoTabs.size(); k++) {
      Tab t = undoTabs.get(k);
      int i = Math.min(undoIndexes.get(k), tabs.size());
      tabs.add(i, t);
      if (i <= current) current++;
      if (t.web != null) t.web.onResume();
      forgetClosedTab(t.currentUrl());
      last = t;
    }
    undoTabs.clear();
    undoIndexes.clear();
    if (last != null && undoWasCurrent) selectTab(tabs.indexOf(last));
    else refreshTabCount();
    if (switcher != null && switcher.isShowing()) switcher.refresh();
    scheduleSessionSave();
  }

  void finishPendingUndo() {
    for (Tab t : undoTabs) releaseWebView(t);
    undoTabs.clear();
    undoIndexes.clear();
  }

  void duplicateCurrentTab() {
    Tab t = currentTab();
    if (t == null) return;
    Tab copy = new Tab(t.currentUrl());
    copy.desktop = t.desktop;
    copy.parent = t;
    tabs.add(current + 1, copy);
    selectTab(current + 1);
  }

  void refreshTabCount() {
    if (tabCount == null) return;
    int n = tabs.size();
    tabCount.setText(n > 99 ? ":)" : String.valueOf(n));
    tabCount.setContentDescription(n + (n == 1 ? " open tab, switch tabs" : " open tabs, switch tabs"));
  }

  /** Full-screen panels (history, bookmarks...) currently open, so opening a page can close them all. */
  final ArrayList<ListPanel> panels = new ArrayList<>();
  void registerPanel(ListPanel p) {
    for (int i = panels.size() - 1; i >= 0; i--) if (!panels.get(i).isShowing()) panels.remove(i);
    panels.add(p);
  }
  void closeAllPanels() {
    for (ListPanel p : new ArrayList<>(panels)) p.dismiss();
    panels.clear();
    if (switcher != null && switcher.isShowing()) switcher.dismiss();
  }

  void showTabSwitcher() {
    Tab t = currentTab();
    if (t != null) captureThumbnail(t);
    address.clearFocus();
    if (switcher == null) switcher = new TabSwitcher(this, new TabSwitcher.Host() {
      @Override public List<Tab> tabs() { return tabs; }
      @Override public int currentIndex() { return current; }
      @Override public void select(int index) { selectTab(index); }
      @Override public void close(int index) { closeTab(index, true); }
      @Override public void move(int from, int to) { moveTab(from, to); }
      @Override public void newTab() { MainActivity.this.newTab(homeUrl()); }
      @Override public void showMenu(View anchor) { tabMenu(anchor); }
      @Override public boolean isPrivate() { return MainActivity.this.isPrivate(); }
    });
    switcher.show();
  }

  void tabMenu(View anchor) {
    List<MenuEntry> entries = new ArrayList<>();
    entries.add(new MenuEntry("+", "New tab", () -> { if (switcher != null) switcher.dismiss(); newTab(homeUrl()); }));
    if (!isPrivate()) entries.add(new MenuEntry("◈", "New private window", () -> { if (switcher != null) switcher.dismiss(); openPrivateWindow(); }));
    entries.add(new MenuEntry("▣", "Duplicate current tab", () -> { duplicateCurrentTab(); if (switcher != null) switcher.refresh(); }));
    entries.add(new MenuEntry("×", "Close other tabs", () -> { closeOtherTabs(); if (switcher != null) switcher.refresh(); }));
    entries.add(new MenuEntry("⊗", "Close all tabs", () -> new AlertDialog.Builder(this).setTitle("Close all " + tabs.size() + " tabs?")
        .setPositiveButton("Close all", (d, n) -> { closeAllTabs(); if (switcher != null) switcher.dismiss(); })
        .setNegativeButton("Cancel", null).show()));
    if (!isPrivate()) entries.add(new MenuEntry("↶", "Recently closed tabs", () -> LibraryPanels.showRecentlyClosed(this)));
    showPopup(entries, null, anchor);
  }

  // =====================================================================================================
  // Session
  // =====================================================================================================

  void scheduleSessionSave() {
    if (restoringSession) return;
    if (sessionSave != null) sessionHandler.removeCallbacks(sessionSave);
    sessionSave = this::saveSession;
    sessionHandler.postDelayed(sessionSave, 800);
  }

  void saveSession() {
    if (restoringSession) return;
    try {
      JSONArray a = new JSONArray();
      int selected = 0;
      for (int i = 0; i < tabs.size(); i++) {
        Tab t = tabs.get(i);
        String url = t.currentUrl();
        if (!UrlTools.isWebUrl(url)) continue;
        if (i == current) selected = a.length();
        JSONObject item = new JSONObject();
        item.put("url", url);
        item.put("title", t.title == null ? "" : t.title);
        item.put("scrollY", t.web != null ? t.web.getScrollY() : (t.restoreY == null ? 0 : t.restoreY));
        if (t.desktop) item.put("desktop", true);
        a.put(item);
      }
      String json = a.toString();
      // Skip the write when nothing changed (scrolling triggers frequent saves).
      if (json.equals(prefs.getString("session_tabs", null)) && prefs.getInt("session_selected", -1) == selected) return;
      prefs.edit().putString("session_tabs", json).putInt("session_selected", selected).apply();
    } catch (Exception e) {
      Log.w(TAG, "Could not save session", e);
    }
  }

  /** Restores the saved tabs. Only the selected tab loads now; the others load when first shown. */
  void restoreSession() {
    restoringSession = true;
    try {
      JSONArray a = new JSONArray(prefs.getString("session_tabs", "[]"));
      for (int i = 0; i < a.length() && tabs.size() < MAX_RESTORED_TABS; i++) {
        JSONObject item = a.optJSONObject(i);
        if (item == null) continue;
        String url = item.optString("url");
        if (!UrlTools.isWebUrl(url)) continue;
        Tab t = new Tab(url);
        t.title = item.optString("title", "");
        t.restoreY = Math.max(0, item.optInt("scrollY", 0));
        t.desktop = item.optBoolean("desktop", false);
        tabs.add(t);
      }
      if (tabs.isEmpty()) tabs.add(new Tab(homeUrl()));
      selectTab(Math.min(Math.max(0, prefs.getInt("session_selected", 0)), tabs.size() - 1));
    } catch (Exception e) {
      Log.w(TAG, "Could not restore session", e);
      if (tabs.isEmpty()) { tabs.add(new Tab(homeUrl())); selectTab(0); }
    } finally {
      restoringSession = false;
    }
    refreshTabCount();
  }

  // ---- Recently closed tabs (persistent list of up to 25) ----

  void rememberClosedTab(String url, String title) {
    if (!UrlTools.isWebUrl(url)) return;
    try {
      JSONArray old = new JSONArray(prefs.getString("closed_tabs", "[]"));
      JSONArray next = new JSONArray();
      JSONObject entry = new JSONObject();
      entry.put("url", url);
      entry.put("title", title == null ? "" : title);
      entry.put("closedAt", System.currentTimeMillis());
      next.put(entry);
      for (int i = 0; i < old.length() && next.length() < 25; i++) {
        Object o = old.opt(i);
        String u = o instanceof JSONObject ? ((JSONObject) o).optString("url") : String.valueOf(o);
        if (!url.equals(u)) next.put(o);
      }
      prefs.edit().putString("closed_tabs", next.toString()).remove("recently_closed_url").apply();
    } catch (Exception ignored) {}
  }

  void forgetClosedTab(String url) {
    try {
      JSONArray old = new JSONArray(prefs.getString("closed_tabs", "[]"));
      JSONArray next = new JSONArray();
      boolean removed = false;
      for (int i = 0; i < old.length(); i++) {
        Object o = old.opt(i);
        String u = o instanceof JSONObject ? ((JSONObject) o).optString("url") : String.valueOf(o);
        if (!removed && url != null && url.equals(u)) { removed = true; continue; }
        next.put(o);
      }
      prefs.edit().putString("closed_tabs", next.toString()).apply();
    } catch (Exception ignored) {}
  }

  @Override protected void onPause() {
    Tab t = currentTab();
    if (t != null) captureThumbnail(t);
    saveSession();
    CookieManager.getInstance().flush(); // write cookies so logins survive Android killing the process in the background
    if (blockingStats != null) blockingStats.flush(); // otherwise up to 24 blocked requests since the last save are lost
    super.onPause();
  }

  @Override protected void onStop() { saveSession(); super.onStop(); }

  // When a normal window comes back (e.g. after a private window closed), delete any private files left on disk.
  // Waits briefly so a just-closed private process has exited; skipped while a private window is running.
  final Runnable privateCleanup = () -> PrivateProfile.wipeFromMainProcess(this);
  void schedulePrivateCleanup() { sessionHandler.removeCallbacks(privateCleanup); sessionHandler.postDelayed(privateCleanup, 2000); }
  @Override protected void onResume() {
    super.onResume();
    schedulePrivateCleanup();
    // The home page shows live blocking counts; refresh it when coming back to the app.
    Tab t = currentTab();
    if (t != null && t.web != null && HomePage.isHome(t.currentUrl()) && !t.loading) t.web.reload();
  }

  // =====================================================================================================
  // Links into the browser
  // =====================================================================================================

  // Links from other apps and home-screen shortcuts open in a new tab instead of replacing the current page.
  void handleExternalIntent(Intent intent) {
    if (intent == null) return;
    Uri uri = intent.getData();
    if (uri == null) return;
    String url = uri.toString();
    if (!UrlTools.isWebUrl(url)) return;
    addTab(url, true, tabs.size(), null);
  }

  @Override protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    if (switcher != null && switcher.isShowing()) switcher.dismiss();
    handleExternalIntent(intent);
  }

  void openPrivateWindow() {
    if (Build.VERSION.SDK_INT < 28) { toastLong("Isolated private browsing requires Android 9 or newer"); return; }
    sessionHandler.removeCallbacks(privateCleanup);
    PrivateProfile.awaitMainProcessWipe();
    startActivity(new Intent(this, IncognitoActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  }

  void openInPrivateWindow(String url) {
    if (Build.VERSION.SDK_INT < 28) { toastLong("Isolated private browsing requires Android 9 or newer"); return; }
    if (isPrivate()) { newTab(url, false); return; }
    sessionHandler.removeCallbacks(privateCleanup);
    PrivateProfile.awaitMainProcessWipe();
    startActivity(new Intent(this, IncognitoActivity.class).setData(Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  }

  // =====================================================================================================
  // Navigation and the address bar
  // =====================================================================================================

  /** Loads what was typed: a web address or a search. */
  void navigate(String text) {
    String url = UrlTools.toNavigableUrl(text, searchPrefix());
    if (url == null) return;
    loadInCurrent(url);
  }

  void loadInCurrent(String url) {
    address.dismissDropDown();
    hideKeyboard();
    address.clearFocus();
    Tab t = currentTab();
    if (t == null) { newTab(url); return; }
    loadInTab(t, url);
  }

  void loadInTab(Tab t, String url) {
    String target = stripTrackingOn() ? UrlTools.stripTrackingParams(url) : url;
    t.errorUrl = null;
    t.url = target;
    if (t.web == null) activate(t); else t.web.loadUrl(target);
    if (t == currentTab()) setAddressText(target);
  }

  void reloadTab(Tab t) {
    if (t == null) return;
    if (t.errorUrl != null) { loadInTab(t, t.errorUrl); return; }
    if (t.web == null) activate(t); else t.web.reload();
  }

  void setAddressText(String url) {
    if (address.hasFocus()) return; // never overwrite what the user is typing
    // On the home page the address bar stays empty, ready for a search (like a new tab in other browsers).
    address.setText(url == null || HomePage.isHome(url) ? "" : url, false);
  }

  void updateToolbar(Tab t) {
    if (t == null) return;
    String url = t.currentUrl();
    setAddressText(url);
    if (HomePage.isHome(url)) { siteButton.setText(isPrivate() ? "◈" : "⛨"); siteButton.setContentDescription("Block Browser home page"); }
    else if (t.errorUrl != null) { siteButton.setText("⚠"); siteButton.setContentDescription("Site information: page could not be loaded"); }
    else if (url != null && url.startsWith("https://")) { siteButton.setText("🔒"); siteButton.setContentDescription("Site information: secure connection"); }
    else if (url != null && url.startsWith("http://")) { siteButton.setText("⚠"); siteButton.setContentDescription("Site information: connection is not secure"); }
    else { siteButton.setText("ⓘ"); siteButton.setContentDescription("Site information"); }
    updateProgress(t);
    refreshTabCount();
  }

  void updateProgress(Tab t) {
    if (fullscreen || t == null || !t.loading) { progress.setVisibility(fullscreen ? View.GONE : View.INVISIBLE); return; }
    progress.setVisibility(View.VISIBLE);
    progress.setProgress(Math.max(5, t.progress));
  }

  /** Bookmarks and history matching the text, for suggestions. Runs on the suggestion filter's thread. */
  List<SuggestionRanker.Page> suggestionPages(String query) {
    List<SuggestionRanker.Page> pages = new ArrayList<>();
    String q = query.trim().toLowerCase(Locale.ROOT);
    for (BookmarkIO.Bookmark b : bookmarks().all()) {
      if (b.url.toLowerCase(Locale.ROOT).contains(q) || b.title.toLowerCase(Locale.ROOT).contains(q))
        pages.add(new SuggestionRanker.Page(b.url, b.title, true, 0, 0));
    }
    for (HistoryStore.Entry e : history().matching(q, 200)) pages.add(new SuggestionRanker.Page(e.url, e.title, false, e.visits, e.lastVisit));
    return pages;
  }

  // =====================================================================================================
  // WebView setup
  // =====================================================================================================

  WebView createWebView(Tab tab) {
    WebView w = new WebView(this);
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
      WebViewCompat.addDocumentStartJavaScript(w, WEBSITE_ASPECT_SCRIPT, Collections.singleton("*"));
    }
    w.setBackgroundColor(Color.WHITE);
    if (Build.VERSION.SDK_INT >= 26) w.setImportantForAutofill(autofillMode());
    WebSettings s = w.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setGeolocationEnabled(true);
    s.setSupportMultipleWindows(true);
    s.setBuiltInZoomControls(true);
    s.setDisplayZoomControls(false);
    s.setMediaPlaybackRequiresUserGesture(false);
    s.setTextZoom(prefs.getInt("text_zoom", 100));
    // Pages never need the app's own files or content providers.
    s.setAllowFileAccess(false);
    s.setAllowContentAccess(false);
    if (Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(true);
    CookieManager.getInstance().setAcceptThirdPartyCookies(w, prefs.getBoolean("third_party_cookies", false));
    applyDarkening(s, prefs.getBoolean("dark", false));
    applyUserAgent(tab, s);
    w.setWebViewClient(new TabClient(tab));
    w.setWebChromeClient(new TabChromeClient(tab));
    w.setOnLongClickListener(v -> showPageContextMenu(tab));
    w.setDownloadListener((url, userAgent, disposition, mime, length) -> Downloads.start(this, url, userAgent, disposition, mime, tab.currentUrl()));
    w.setOnTouchListener((v, event) -> {
      if (event.getAction() == MotionEvent.ACTION_DOWN) { downX = event.getX(); downY = event.getY(); }
      else if (event.getAction() == MotionEvent.ACTION_UP) {
        float dx = event.getX() - downX;
        if (Math.abs(dx) > dp(110) && Math.abs(event.getY() - downY) < dp(90) && downX < dp(35)) {
          if (dx > 0 && w.canGoBack()) w.goBack();
          else if (dx < 0 && w.canGoForward()) w.goForward();
          return true;
        }
      }
      return false;
    });
    w.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
      if (!tab.loading) {
        int index = w.copyBackForwardList().getCurrentIndex();
        if (index >= 0) tab.positions.put(index, y);
      }
      scheduleSessionSave();
    });
    return w;
  }

  /** "Darken websites": algorithmic darkening where supported (WebView with Android 13+ rules), otherwise force-dark (Android 10-12). */
  static void applyDarkening(WebSettings s, boolean dark) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
      WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, dark);
    } else if (Build.VERSION.SDK_INT >= 29) {
      //noinspection deprecation
      s.setForceDark(dark ? WebSettings.FORCE_DARK_ON : WebSettings.FORCE_DARK_OFF);
    }
  }

  static boolean darkeningSupported() {
    return WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING) || Build.VERSION.SDK_INT >= 29;
  }

  /** Desktop mode: the WebView's own user agent with the Android/mobile parts swapped for a desktop Linux one. */
  void applyUserAgent(Tab tab, WebSettings s) {
    if (!tab.desktop) {
      s.setUserAgentString(null);
      s.setUseWideViewPort(false);
      s.setLoadWithOverviewMode(false);
      return;
    }
    String ua = WebSettings.getDefaultUserAgent(this);
    ua = ua.replaceAll("\\(Linux; Android [^)]*\\)", "(X11; Linux x86_64)").replace(" Mobile", "").replace("; wv", "");
    s.setUserAgentString(ua);
    s.setUseWideViewPort(true);
    s.setLoadWithOverviewMode(true);
  }

  void toggleDesktopSite() {
    Tab t = currentTab();
    if (t == null) return;
    t.desktop = !t.desktop;
    if (t.web != null) { applyUserAgent(t, t.web.getSettings()); t.web.reload(); }
    scheduleSessionSave();
  }

  /** Called on WebView's request threads. */
  boolean shouldBlock(Tab tab, String host, boolean mainFrame) {
    if (!blockAds || host == null) return false;
    String h = host.toLowerCase(Locale.ROOT);
    if (siteSettings.adblockDisabledFor(mainFrame ? h : tab.pageHost)) return false;
    Set<String> set = blocked;
    while (true) {
      if (set.contains(h)) return true;
      int dot = h.indexOf('.');
      if (dot < 0 || dot == h.length() - 1) return false;
      h = h.substring(dot + 1);
    }
  }

  final class TabClient extends WebViewClient {
    final Tab tab;
    TabClient(Tab tab) { this.tab = tab; }

    @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
      String host = req.getUrl().getHost();
      boolean main = req.isForMainFrame();
      if (main && HomePage.isHome(req.getUrl().toString())) { tab.pageHost = ""; tab.blocked.set(0); return HomePage.response(MainActivity.this); }
      if (main && host != null) tab.pageHost = host.toLowerCase(Locale.ROOT);
      if (main) tab.media.clear();
      if (!shouldBlock(tab, host, main)) {
        if (!main) tab.media.offer(req.getUrl().toString());
        return null;
      }
      blockedCount.incrementAndGet();
      if (blockingStats != null) { blockingStats.record(host); if (blockedCount.get() % 25 == 0) blockingStats.flush(); }
      tab.blocked.incrementAndGet();
      if (main) {
        String html = "<html><head><meta name=viewport content='width=device-width'></head><body style=\"font-family:sans-serif;padding:24px\"><h3>Blocked by Block Browser</h3><p>"
            + android.text.TextUtils.htmlEncode(String.valueOf(host)) + " is on the ad/tracker blocklist.</p><p>To open it anyway, turn ad blocking off for this site with the button left of the address, or everywhere in ⋮ → Privacy and security.</p></body></html>";
        return new WebResourceResponse("text/html", "UTF-8", new java.io.ByteArrayInputStream(html.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      }
      // Make the request FAIL (net::ERR_FAILED, like uBlock or a DNS blocker) instead of answering with an
      // empty 200 OK. WebView calls available() before sending headers; if it throws, the request fails
      // outright (see Chromium components/embedder_support/android/util/input_stream_reader.cc).
      return new WebResourceResponse("text/plain", "UTF-8", new FailingStream());
    }

    @Override public void onReceivedSslError(WebView view, SslErrorHandler sslHandler, android.net.http.SslError error) {
      sslHandler.cancel(); // never bypassed
      String failing = error.getUrl();
      String expectedHost = UrlTools.host(tab.url);
      boolean mainFrame = !expectedHost.isEmpty() && UrlTools.host(failing).equals(expectedHost);
      if (mainFrame) {
        showErrorPage(tab, failing, "Your connection is not private",
            "The site's security certificate is " + sslReason(error) + ". Block Browser does not allow continuing to sites with certificate errors, because someone could be intercepting the connection.");
      } else {
        runOnUiThread(() -> toast("Blocked insecure content from " + UrlTools.host(failing)));
      }
    }

    @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
      if (!request.isForMainFrame()) return;
      String url = request.getUrl().toString();
      String title, message;
      switch (error.getErrorCode()) {
        case ERROR_HOST_LOOKUP: title = "Site can't be found"; message = "Check the address, or check that you are connected to the internet."; break;
        case ERROR_CONNECT: title = "Can't connect"; message = "The site refused the connection or your network is offline."; break;
        case ERROR_TIMEOUT: title = "The site took too long to respond"; message = "Check your connection and try again."; break;
        case ERROR_FAILED_SSL_HANDSHAKE: title = "Secure connection failed"; message = "The site's secure connection could not be set up."; break;
        case ERROR_IO: title = "Connection interrupted"; message = "The connection was lost while loading the page."; break;
        case ERROR_BAD_URL: title = "Invalid address"; message = "This address can't be opened."; break;
        case ERROR_TOO_MANY_REQUESTS: title = "Too many requests"; message = "Wait a moment and try again."; break;
        case ERROR_UNSAFE_RESOURCE: title = "Dangerous site blocked"; message = "Google Safe Browsing reported this site as unsafe."; break;
        default: return; // cancelled navigations and similar: nothing to show
      }
      // Mark the failure now, before WebView's own "Webpage not available" title arrives, and drop the failed
      // address from history (a page that never loaded isn't a visit).
      tab.errorUrl = url;
      tab.title = title;
      history().forgetFailedVisit(url);
      showErrorPage(tab, url, title, message + "\n\n(" + error.getDescription() + ")");
    }

    @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
      Uri uri = request.getUrl();
      String s = uri.toString();
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      if (HomePage.isStatsLink(s)) { runOnUiThread(() -> PrivacyChart.show(MainActivity.this)); return true; }
      if (scheme.equals("http") || scheme.equals("https")) {
        if (request.isForMainFrame()) {
          tab.url = s;
          if (stripTrackingOn()) {
            String clean = UrlTools.stripTrackingParams(s);
            if (!clean.equals(s)) { tab.url = clean; view.loadUrl(clean); return true; }
          }
        }
        return false;
      }
      return ExternalLinks.handle(MainActivity.this, uri, request.isForMainFrame(), request.hasGesture(), isPrivate(), u -> loadInTab(tab, u));
    }

    @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
      tab.loading = true;
      tab.progress = 5;
      if (UrlTools.isWebUrl(url)) {
        tab.errorUrl = null;
        tab.url = url;
        tab.pageHost = UrlTools.host(url);
        tab.blocked.set(0);
        tab.favicon = favicons().peek(tab.pageHost);
      }
      if (tab == currentTab()) updateToolbar(tab);
    }

    @Override public void onPageFinished(WebView view, String url) {
      tab.loading = false;
      if (view.getTitle() != null && UrlTools.isWebUrl(url)) tab.title = view.getTitle();
      Integer y = tab.restoreY;
      tab.restoreY = null;
      if (y == null) y = tab.positions.get(view.copyBackForwardList().getCurrentIndex(), 0);
      if (y > 0) {
        // Layout can finish after onPageFinished (slower devices, older WebView): retry until the position sticks.
        restoreScroll(tab, view, y, 0);
      }
      if (tab == currentTab()) updateToolbar(tab);
      scheduleSessionSave();
    }

    @Override public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
      if (!UrlTools.isWebUrl(url)) return;
      if (url.equals(tab.errorUrl)) return; // our error page for a failed address, not a visit
      tab.url = url;
      if (!isReload) saveVisit(url, view.getTitle());
      if (tab == currentTab()) setAddressText(url);
    }

    @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
      handleRendererGone(tab, view, detail.didCrash());
      return true; // keep the app running; the tab is rebuilt
    }
  }

  /**
   * Scrolls a freshly loaded page back to where it was. The page may not be tall enough yet when loading
   * finishes (slow devices, Android 9's WebView), so this retries every 250 ms for up to 6 seconds until the
   * position sticks. It stops as soon as the user scrolls or the tab shows something else.
   */
  void restoreScroll(Tab tab, WebView view, int targetY, int attempt) {
    if (tab.web != view) return;
    int y = view.getScrollY();
    boolean done = Math.abs(y - targetY) <= 4, userScrolled = attempt > 0 && y > 4 && y != tab.restoreAttemptY;
    if (done || userScrolled || attempt > 24) {
      if (BuildConfig.TEST_LOGS) Log.i("BlockBrowserTest", "scroll restore target=" + targetY + " now=" + y + " attempts=" + attempt + (done ? " done" : userScrolled ? " user-scrolled" : " gave-up") + " contentHeight=" + view.getContentHeight());
      return;
    }
    view.scrollTo(0, targetY);
    if (view.getScrollY() < targetY - 4) {
      // Older WebView versions can ignore scrollTo right after loading; ask the page itself (CSS pixels).
      float scale = view.getScale();
      int cssY = Math.round(targetY / (scale > 0 ? scale : getResources().getDisplayMetrics().density));
      view.evaluateJavascript("window.scrollTo(0," + cssY + ")", null);
    }
    view.postDelayed(() -> { tab.restoreAttemptY = view.getScrollY(); restoreScroll(tab, view, targetY, attempt + 1); }, 250);
  }

  static String sslReason(android.net.http.SslError e) {
    if (e.hasError(android.net.http.SslError.SSL_EXPIRED)) return "expired";
    if (e.hasError(android.net.http.SslError.SSL_NOTYETVALID)) return "not yet valid";
    if (e.hasError(android.net.http.SslError.SSL_IDMISMATCH)) return "for a different site";
    if (e.hasError(android.net.http.SslError.SSL_UNTRUSTED)) return "not from a trusted authority";
    if (e.hasError(android.net.http.SslError.SSL_DATE_INVALID)) return "not valid at this date";
    return "invalid";
  }

  /** Error page inside the tab. "Try again" reloads the failed address; Reload in the menu does the same. */
  void showErrorPage(Tab tab, String failingUrl, String title, String message) {
    runOnUiThread(() -> {
      if (tab.web == null) return;
      tab.errorUrl = failingUrl;
      tab.title = title;
      tab.loading = false;
      String safeUrl = android.text.TextUtils.htmlEncode(failingUrl == null ? "" : failingUrl);
      String html = "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'><style>"
          + "body{font-family:sans-serif;background:#111827;color:#eaf1fc;margin:0;padding:32px 24px;line-height:1.5}"
          + "h2{margin-top:40px}p{color:#aab8cc;white-space:pre-wrap}code{word-break:break-all;color:#83b9ff}"
          + "a.b{display:inline-block;margin-top:20px;padding:12px 22px;border-radius:22px;background:#83b9ff;color:#111827;text-decoration:none;font-weight:bold}"
          + "</style></head><body><h2>" + android.text.TextUtils.htmlEncode(title) + "</h2><p>" + android.text.TextUtils.htmlEncode(message)
          + "</p><p><code>" + safeUrl + "</code></p>"
          + (UrlTools.isWebUrl(failingUrl) ? "<a class=b href=\"" + safeUrl + "\">Try again</a>" : "")
          + "</body></html>";
      tab.web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", failingUrl);
      if (tab == currentTab()) updateToolbar(tab);
    });
  }

  /** A page's renderer crashed or was killed for memory: rebuild the tab instead of crashing the app. */
  void handleRendererGone(Tab tab, WebView view, boolean crashed) {
    Log.w(TAG, "renderer gone (crashed=" + crashed + ")");
    if (tab == null || tab.web != view) {
      if (view.getParent() != null) ((ViewGroup) view.getParent()).removeView(view);
      view.destroy();
      return;
    }
    String url = tab.currentUrl();
    releaseWebView(tab);
    tab.url = url;
    if (tab == currentTab()) {
      tab.web = createWebView(tab);
      frame.removeAllViews();
      frame.addView(tab.web, new FrameLayout.LayoutParams(-1, -1));
      showErrorPage(tab, url, crashed ? "This page crashed" : "This page was closed to free memory",
          crashed ? "Something on the page made it stop working." : "Android needed the memory for something else.");
    }
  }

  final class TabChromeClient extends WebChromeClient {
    final Tab tab;
    TabChromeClient(Tab tab) { this.tab = tab; }

    @Override public void onProgressChanged(WebView view, int newProgress) {
      tab.progress = newProgress;
      if (newProgress >= 100) tab.loading = false;
      if (tab == currentTab()) updateProgress(tab);
    }

    @Override public void onReceivedTitle(WebView view, String title) {
      if (title == null || tab.errorUrl != null) return;
      tab.title = title;
      history().updateTitle(view.getUrl(), title);
      if (switcher != null && switcher.isShowing()) switcher.refresh();
    }

    @Override public void onReceivedIcon(WebView view, Bitmap icon) {
      if (HomePage.isHome(view.getUrl())) return;
      tab.favicon = icon;
      String url = view.getUrl();
      if (UrlTools.isWebUrl(url)) favicons().put(UrlTools.host(url), icon);
    }

    @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
      if (pendingUpload != null) pendingUpload.onReceiveValue(null);
      pendingUpload = callback;
      Intent intent;
      try {
        intent = params.createIntent();
      } catch (Exception e) {
        pendingUpload = null;
        callback.onReceiveValue(null);
        return true;
      }
      if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
      boolean started = startForResult(intent, (result, data) -> {
        ValueCallback<Uri[]> cb = pendingUpload;
        pendingUpload = null;
        if (cb != null) cb.onReceiveValue(uploadResult(result, data));
      });
      if (!started) {
        pendingUpload = null;
        callback.onReceiveValue(null);
        toast("File picker unavailable");
      }
      return true;
    }

    @Override public void onPermissionRequest(PermissionRequest request) { runOnUiThread(() -> handlePermissionRequest(request)); }

    @Override public void onPermissionRequestCanceled(PermissionRequest request) {
      if (pendingPermission == request) {
        pendingPermission = null;
        if (permissionDialog != null) permissionDialog.dismiss();
      }
    }

    @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
      runOnUiThread(() -> handleGeolocationRequest(origin, callback));
    }

    @Override public boolean onCreateWindow(WebView source, boolean isDialog, boolean isUserGesture, Message resultMsg) {
      if (!isUserGesture) {
        toast("Pop-up blocked");
        return false;
      }
      // A real tab that keeps its opener, so sign-in pop-ups can report back to the page that opened them.
      Tab popup = new Tab(null);
      popup.openedByScript = true;
      popup.parent = tab;
      int at = Math.min(tabs.size(), Math.max(0, tabs.indexOf(tab)) + 1);
      tabs.add(at, popup);
      if (at <= current) current++;
      popup.web = createWebView(popup);
      selectTab(at);
      WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
      transport.setWebView(popup.web);
      resultMsg.sendToTarget();
      return true;
    }

    @Override public void onCloseWindow(WebView window) {
      Tab t = tabFor(window);
      if (t != null && t.openedByScript) closeTab(tabs.indexOf(t), false);
    }

    @Override public void onShowCustomView(View view, CustomViewCallback cb) {
      if (customView != null) { cb.onCustomViewHidden(); return; }
      customView = view;
      customViewCallback = cb;
      fullscreenBeforeVideo = fullscreen;
      view.setBackgroundColor(Color.BLACK);
      outer.addView(view, new FrameLayout.LayoutParams(-1, -1));
      setFullscreen(true);
      showWebsiteAspectControl(tab.web);
    }

    @Override public void onHideCustomView() { hideCustomView(); }
  }

  static Uri[] uploadResult(int result, Intent data) {
    if (result != RESULT_OK || data == null) return null;
    ClipData clip = data.getClipData();
    if (clip != null && clip.getItemCount() > 0) {
      Uri[] out = new Uri[clip.getItemCount()];
      for (int i = 0; i < out.length; i++) out[i] = clip.getItemAt(i).getUri();
      return out;
    }
    return data.getData() == null ? null : new Uri[]{data.getData()};
  }

  // =====================================================================================================
  // Website permissions
  // =====================================================================================================

  static String originOf(String raw) {
    if (raw == null) return "";
    String s = raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
    return UrlTools.origin(s.contains("://") ? s : "https://" + s);
  }

  static String kindForResource(String resource) {
    switch (resource) {
      case PermissionRequest.RESOURCE_VIDEO_CAPTURE: return SiteSettings.CAMERA;
      case PermissionRequest.RESOURCE_AUDIO_CAPTURE: return SiteSettings.MICROPHONE;
      case PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID: return SiteSettings.PROTECTED_MEDIA;
      default: return null; // e.g. MIDI system-exclusive messages: never granted
    }
  }

  CheckBox rememberBox(FrameLayout container) {
    CheckBox remember = new CheckBox(this);
    remember.setText(isPrivate() ? "Remember until this private window closes" : "Remember for this site");
    remember.setChecked(true);
    container.setPadding(dp(20), dp(4), dp(20), 0);
    container.addView(remember);
    return remember;
  }

  void handlePermissionRequest(PermissionRequest request) {
    String origin = originOf(request.getOrigin().toString());
    ArrayList<String> grant = new ArrayList<>(), ask = new ArrayList<>();
    for (String r : request.getResources()) {
      String kind = kindForResource(r);
      if (kind == null) continue;
      int decision = siteSettings.get(origin, kind);
      if (decision == SiteSettings.ALLOW) grant.add(r);
      else if (decision == SiteSettings.ASK) ask.add(r);
    }
    if (ask.isEmpty()) { finishPermission(request, grant); return; }
    if (pendingPermission != null) { request.deny(); return; } // one question at a time
    pendingPermission = request;
    StringBuilder what = new StringBuilder();
    for (int i = 0; i < ask.size(); i++) {
      if (i > 0) what.append(i == ask.size() - 1 ? " and " : ", ");
      what.append(SiteSettings.label(kindForResource(ask.get(i))).toLowerCase(Locale.ROOT));
    }
    FrameLayout box = new FrameLayout(this);
    CheckBox remember = rememberBox(box);
    permissionDialog = new AlertDialog.Builder(this)
        .setTitle("Allow " + UrlTools.display(origin) + " to use your " + what + "?")
        .setView(box)
        .setPositiveButton("Allow", (d, n) -> {
          if (remember.isChecked()) for (String r : ask) siteSettings.set(origin, kindForResource(r), SiteSettings.ALLOW);
          grant.addAll(ask);
          finishPermission(request, grant);
        })
        .setNegativeButton("Block", (d, n) -> {
          if (remember.isChecked()) for (String r : ask) siteSettings.set(origin, kindForResource(r), SiteSettings.BLOCK);
          finishPermission(request, grant);
        })
        .setOnCancelListener(d -> finishPermission(request, grant))
        .show();
  }

  /** Grants what both the user and Android allow; asks Android for camera/microphone first when needed. */
  void finishPermission(PermissionRequest request, List<String> resources) {
    if (pendingPermission == request) pendingPermission = null;
    permissionDialog = null;
    ArrayList<String> runtime = new ArrayList<>();
    for (String r : resources) {
      if (r.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) runtime.add(android.Manifest.permission.CAMERA);
      if (r.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) runtime.add(android.Manifest.permission.RECORD_AUDIO);
    }
    if (runtime.isEmpty()) { grantOrDeny(request, resources); return; }
    requestRuntime(runtime.toArray(new String[0]), granted -> {
      ArrayList<String> allowed = new ArrayList<>();
      for (String r : resources) {
        String needed = r.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE) ? android.Manifest.permission.CAMERA
            : r.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE) ? android.Manifest.permission.RECORD_AUDIO : null;
        if (needed == null || checkSelfPermission(needed) == PackageManager.PERMISSION_GRANTED) allowed.add(r);
      }
      if (allowed.size() < resources.size()) explainRuntimeDenied(runtime);
      grantOrDeny(request, allowed);
    });
  }

  static void grantOrDeny(PermissionRequest request, List<String> resources) {
    try {
      if (resources.isEmpty()) request.deny();
      else request.grant(resources.toArray(new String[0]));
    } catch (IllegalStateException ignored) {
      // The page already cancelled the request.
    }
  }

  /** Location requests waiting for the user's answer, by site; repeated requests share one prompt. */
  final java.util.HashMap<String, ArrayList<GeolocationPermissions.Callback>> pendingGeo = new java.util.HashMap<>();

  void handleGeolocationRequest(String rawOrigin, GeolocationPermissions.Callback callback) {
    String origin = originOf(rawOrigin);
    int decision = siteSettings.get(origin, SiteSettings.LOCATION);
    if (BuildConfig.TEST_LOGS) Log.i("BlockBrowserTest", "location request raw='" + rawOrigin + "' origin='" + origin + "' decision=" + decision + " pending=" + pendingGeo.containsKey(origin));
    if (decision == SiteSettings.BLOCK) { callback.invoke(rawOrigin, false, false); return; }
    if (decision == SiteSettings.ALLOW) { finishGeolocation(rawOrigin, callback); return; }
    ArrayList<GeolocationPermissions.Callback> waiting = pendingGeo.get(origin);
    if (waiting != null) { waiting.add(callback); return; }
    waiting = new ArrayList<>();
    waiting.add(callback);
    pendingGeo.put(origin, waiting);
    if (BuildConfig.TEST_LOGS) Log.i("BlockBrowserTest", "location prompt shown for '" + origin + "'");
    FrameLayout box = new FrameLayout(this);
    CheckBox remember = rememberBox(box);
    new AlertDialog.Builder(this)
        .setTitle("Allow " + UrlTools.display(origin) + " to use your location?")
        .setView(box)
        .setPositiveButton("Allow", (d, n) -> {
          if (BuildConfig.TEST_LOGS) Log.i("BlockBrowserTest", "location allowed for '" + origin + "' remember=" + remember.isChecked());
          if (remember.isChecked()) siteSettings.set(origin, SiteSettings.LOCATION, SiteSettings.ALLOW);
          ArrayList<GeolocationPermissions.Callback> all = pendingGeo.remove(origin);
          if (all != null) for (GeolocationPermissions.Callback c : all) finishGeolocation(rawOrigin, c);
        })
        .setNegativeButton("Block", (d, n) -> {
          if (remember.isChecked()) siteSettings.set(origin, SiteSettings.LOCATION, SiteSettings.BLOCK);
          answerGeo(origin, rawOrigin, false);
        })
        .setOnCancelListener(d -> answerGeo(origin, rawOrigin, false))
        .show();
  }

  void answerGeo(String origin, String rawOrigin, boolean allow) {
    ArrayList<GeolocationPermissions.Callback> all = pendingGeo.remove(origin);
    if (all != null) for (GeolocationPermissions.Callback c : all) c.invoke(rawOrigin, allow, false);
  }

  void finishGeolocation(String rawOrigin, GeolocationPermissions.Callback callback) {
    String[] perms = {android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION};
    requestRuntime(perms, granted -> {
      boolean any = granted[0] || granted[1];
      if (!any) explainRuntimeDenied(Arrays.asList(perms));
      callback.invoke(rawOrigin, any, false); // not retained by WebView; Block Browser remembers decisions itself
    });
  }

  /** When Android won't ask again, say where the permission can be turned on. */
  void explainRuntimeDenied(List<String> perms) {
    boolean permanentlyDenied = false;
    for (String p : perms) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED && !shouldShowRequestPermissionRationale(p)) permanentlyDenied = true;
    if (!permanentlyDenied) return;
    new AlertDialog.Builder(this)
        .setTitle("Permission turned off")
        .setMessage("Android has blocked this permission for Block Browser. You can allow it in Android settings → Apps → Block Browser → Permissions.")
        .setPositiveButton("Open settings", (d, n) -> {
          try { startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
          catch (Exception ignored) {}
        })
        .setNegativeButton("Not now", null)
        .show();
  }

  // =====================================================================================================
  // Long-press menu on links and images
  // =====================================================================================================

  boolean showPageContextMenu(Tab tab) {
    WebView w = tab.web;
    if (w == null) return false;
    WebView.HitTestResult hit = w.getHitTestResult();
    if (hit == null) return false;
    int type = hit.getType();
    String extra = hit.getExtra();
    if (extra == null || extra.isEmpty()) return false;
    switch (type) {
      case WebView.HitTestResult.SRC_ANCHOR_TYPE: showLinkMenu(tab, extra, null); return true;
      case WebView.HitTestResult.IMAGE_TYPE: showLinkMenu(tab, null, extra); return true;
      case WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE:
        // The hit test gives the image; ask the page for the link around it.
        Message msg = new Handler(Looper.getMainLooper(), m -> {
          String href = m.getData() == null ? null : m.getData().getString("url");
          showLinkMenu(tab, href == null || href.isEmpty() ? null : href, extra);
          return true;
        }).obtainMessage();
        w.requestFocusNodeHref(msg);
        return true;
      case WebView.HitTestResult.EMAIL_TYPE: copyText("Email address", extra); return true;
      case WebView.HitTestResult.PHONE_TYPE: copyText("Phone number", extra); return true;
      default: return false;
    }
  }

  void showLinkMenu(Tab tab, String link, String image) {
    ArrayList<String> labels = new ArrayList<>();
    ArrayList<Runnable> actions = new ArrayList<>();
    String agent = tab.web == null ? null : tab.web.getSettings().getUserAgentString();
    if (link != null && UrlTools.isWebUrl(link)) {
      labels.add("Open in new tab"); actions.add(() -> newTab(link, false));
      labels.add("Open in background tab"); actions.add(() -> newTab(link, true));
      if (!isPrivate() && Build.VERSION.SDK_INT >= 28) { labels.add("Open in private window"); actions.add(() -> openInPrivateWindow(link)); }
      labels.add("Copy link address"); actions.add(() -> copyText("Link", link));
      labels.add("Share link"); actions.add(() -> shareLink(link));
      labels.add("Download link"); actions.add(() -> Downloads.start(this, link, agent, null, null, tab.currentUrl()));
    } else if (link != null) {
      labels.add("Copy link address"); actions.add(() -> copyText("Link", link));
    }
    if (image != null) {
      if (UrlTools.isWebUrl(image)) { labels.add("Open image in new tab"); actions.add(() -> newTab(image, false)); }
      labels.add("Save image"); actions.add(() -> Downloads.start(this, image, agent, null, null, tab.currentUrl()));
      if (UrlTools.isWebUrl(image)) { labels.add("Copy image address"); actions.add(() -> copyText("Image address", image)); }
    }
    if (labels.isEmpty()) return;
    String title = link != null ? UrlTools.display(link) : UrlTools.isWebUrl(image) ? UrlTools.display(image) : "Image";
    new AlertDialog.Builder(this).setTitle(title.length() > 80 ? title.substring(0, 80) + "…" : title)
        .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run()).show();
  }

  // =====================================================================================================
  // Menu
  // =====================================================================================================

  static final class MenuEntry {
    final String icon, label; final Runnable action; boolean separatorBefore;
    MenuEntry(String icon, String label, Runnable action) { this.icon = icon; this.label = label; this.action = action; }
  }

  void menu() {
    Tab t = currentTab();
    String url = t == null ? null : t.currentUrl();
    boolean web = UrlTools.isWebUrl(url) && !HomePage.isHome(url);
    boolean bookmarked = web && bookmarks().contains(url);

    // Quick actions along the top: back, forward, reload, bookmark, site information.
    List<MenuEntry> top = new ArrayList<>();
    top.add(new MenuEntry("‹", "Back", () -> { if (t != null && t.web != null && t.web.canGoBack()) t.web.goBack(); }));
    top.add(new MenuEntry("›", "Forward", () -> { if (t != null && t.web != null && t.web.canGoForward()) t.web.goForward(); }));
    top.add(t != null && t.loading ? new MenuEntry("✕", "Stop loading", () -> { if (t.web != null) t.web.stopLoading(); })
        : new MenuEntry("⟳", "Reload", () -> reloadTab(t)));
    top.add(new MenuEntry(bookmarked ? "★" : "☆", bookmarked ? "Edit bookmark" : "Add bookmark", () -> {
      if (!web) return;
      if (bookmarked) LibraryPanels.editBookmark(this, url, null);
      else LibraryPanels.addBookmarkFor(this, t);
    }));
    top.add(new MenuEntry("ⓘ", "Site information", () -> SitePanels.showSiteInfo(this)));

    List<MenuEntry> e = new ArrayList<>();
    e.add(new MenuEntry("+", "New tab", () -> newTab(homeUrl())));
    e.add(new MenuEntry("◈", isPrivate() ? "New private tab" : "New private window", () -> { if (isPrivate()) newTab(homeUrl()); else openPrivateWindow(); }));
    e.add(new MenuEntry("★", "Bookmarks", () -> LibraryPanels.showBookmarks(this, "")));
    e.add(new MenuEntry("◷", "History", () -> LibraryPanels.showHistory(this)));
    e.add(new MenuEntry("⇩", "Downloads", () -> Downloads.showPanel(this)));
    e.add(new MenuEntry("⛶", immersiveRequested ? "Exit full screen" : "Full screen", () -> setFullscreen(!immersiveRequested)));
    e.add(new MenuEntry("×", "Close tab", this::closeCurrentTab));
    if (!isPrivate()) e.add(new MenuEntry("↶", "Recently closed tabs", () -> LibraryPanels.showRecentlyClosed(this)));
    MenuEntry find = new MenuEntry("⌕", "Find in page", this::showFind);
    find.separatorBefore = true;
    e.add(find);
    e.add(new MenuEntry("↗", "Share page", () -> { if (web) shareLink(url); }));
    e.add(new MenuEntry("⧉", "Copy page address", () -> { if (url != null) copyText("Page address", url); }));
    e.add(new MenuEntry("▣", (t != null && t.desktop ? "✓ " : "") + "Desktop site", this::toggleDesktopSite));
    e.add(new MenuEntry("文", "Translate page", this::translatePage));
    e.add(new MenuEntry("☷", "Reader mode", this::readerMode));
    e.add(new MenuEntry("↓", "Download video", this::videoDownload));
    e.add(new MenuEntry("▤", "Print / Save PDF", this::printPage));
    if (!isPrivate()) e.add(new MenuEntry("⌂", "Add to home screen", this::addToHomeScreen));
    // Everyday settings straight from the menu, as in earlier versions (also in Settings and Privacy and security).
    MenuEntry engineEntry = new MenuEntry("⌕", "Search engine: " + ENGINE_NAMES[engine()], () -> searchEngineMenu(null));
    engineEntry.separatorBefore = true;
    e.add(engineEntry);
    e.add(new MenuEntry("⛨", "Ad blocking: " + (blockAds ? "On" : "Off"), () -> {
      setAdblock(!blockAds);
      toast(blockAds ? "Ad blocking on" : "Ad blocking off");
      Tab cur = currentTab();
      if (cur != null) reloadTab(cur);
    }));
    e.add(new MenuEntry("◔", "Blocking statistics", () -> PrivacyChart.show(this)));
    if (darkeningSupported()) {
      boolean dark = prefs.getBoolean("dark", false);
      e.add(new MenuEntry("◐", "Dark websites: " + (dark ? "On" : "Off"), () -> setDarkWebsites(!dark)));
    }
    MenuEntry settings = new MenuEntry("⚙", "Settings", () -> SettingsPanels.showSettings(this));
    settings.separatorBefore = true;
    e.add(settings);
    e.add(new MenuEntry("◉", "Privacy and security", () -> SettingsPanels.showPrivacy(this)));
    e.add(new MenuEntry("⌫", "Clear browsing data", this::clearBrowsingData));
    showPopup(e, top, null);
  }

  /** The browser's popup menu, anchored top-right, or below {@code anchor} when given. */
  void showPopup(List<MenuEntry> entries, List<MenuEntry> topRow, View anchor) {
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(5), dp(5), dp(5), dp(5));
    ScrollView scroll = new ScrollView(this);
    scroll.setVerticalScrollBarEnabled(false);
    LinearLayout whole = new LinearLayout(this);
    whole.setOrientation(LinearLayout.VERTICAL);
    int maxHeight = Math.min(dp(600), getResources().getDisplayMetrics().heightPixels - dp(100));
    PopupWindow popup = new PopupWindow(whole, dp(264), ViewGroup.LayoutParams.WRAP_CONTENT, true);
    if (topRow != null) {
      LinearLayout row = new LinearLayout(this);
      row.setPadding(dp(4), dp(4), dp(4), dp(2));
      for (MenuEntry m : topRow) {
        TextView b = new TextView(this);
        b.setText(m.icon);
        b.setTextColor(TEXT);
        b.setTextSize(21);
        b.setGravity(Gravity.CENTER);
        b.setContentDescription(m.label);
        b.setOnClickListener(v -> { popup.dismiss(); m.action.run(); });
        row.addView(b, new LinearLayout.LayoutParams(0, dp(48), 1));
      }
      whole.addView(row);
      View sep = new View(this);
      sep.setBackgroundColor(0xff354359);
      LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(1));
      slp.setMargins(dp(12), 0, dp(12), 0);
      whole.addView(sep, slp);
    }
    for (MenuEntry m : entries) {
      if (m.separatorBefore) {
        View separator = new View(this);
        separator.setBackgroundColor(0xff354359);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.setMargins(dp(12), dp(4), dp(12), dp(4));
        content.addView(separator, lp);
      }
      LinearLayout line = new LinearLayout(this);
      line.setGravity(Gravity.CENTER_VERTICAL);
      line.setPadding(dp(14), 0, dp(8), 0);
      TextView symbol = new TextView(this);
      symbol.setText(m.icon);
      symbol.setTextColor(ACCENT);
      symbol.setTextSize(19);
      symbol.setGravity(Gravity.CENTER_VERTICAL);
      symbol.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      line.addView(symbol, new LinearLayout.LayoutParams(dp(38), dp(48)));
      TextView label = new TextView(this);
      label.setText(m.label);
      label.setTextColor(TEXT);
      label.setTextSize(15);
      label.setGravity(Gravity.CENTER_VERTICAL);
      line.addView(label, new LinearLayout.LayoutParams(-1, dp(48)));
      line.setOnClickListener(v -> { popup.dismiss(); m.action.run(); });
      content.addView(line);
    }
    scroll.addView(content);
    whole.addView(scroll);
    whole.measure(View.MeasureSpec.makeMeasureSpec(dp(264), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
    popup.setHeight(Math.min(maxHeight, whole.getMeasuredHeight()));
    popup.setBackgroundDrawable(rounded(isPrivate() ? IncognitoActivity.PRIVATE_BADGE : PANEL, 16));
    popup.setElevation(dp(12));
    popup.setOutsideTouchable(true);
    popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
    if (anchor != null) popup.showAsDropDown(anchor, -dp(200), 0);
    else popup.showAtLocation(outer, Gravity.TOP | Gravity.END, dp(8), Math.max(dp(8), root.getPaddingTop() + dp(56)));
  }

  // =====================================================================================================
  // Page tools
  // =====================================================================================================

  void translatePage() {
    Tab t = currentTab();
    String url = t == null ? null : t.currentUrl();
    if (!UrlTools.isWebUrl(url)) return;
    EditText language = new EditText(this);
    language.setSingleLine(true);
    language.setText(Locale.getDefault().getLanguage());
    language.setHint("Language code, e.g. en, es, fr");
    new AlertDialog.Builder(this).setTitle("Translate page")
        .setMessage("The page address is sent to Google Translate, which opens the translation in a new tab.")
        .setView(language)
        .setPositiveButton("Translate", (d, n) -> {
          String lang = language.getText().toString().trim().toLowerCase(Locale.ROOT);
          if (!lang.matches("[a-z]{2,3}")) { toast("Use a 2–3 letter language code"); return; }
          newTab("https://translate.google.com/translate?sl=auto&tl=" + lang + "&u=" + Uri.encode(url), false);
        }).setNegativeButton("Cancel", null).show();
  }

  void readerMode() {
    Tab t = currentTab();
    if (t == null || t.web == null) return;
    String js = "(function(){var a=document.querySelector('article,main,[role=main]');if(!a){alert('No article content detected');return;}var title=document.title;var content=a.cloneNode(true);document.head.innerHTML='<meta name=viewport content=\"width=device-width,initial-scale=1\">';document.body.innerHTML='';document.body.style.cssText='max-width:760px;margin:35px auto;padding:20px;background:#f9f9f9;color:#202020;font:19px/1.7 sans-serif';var h=document.createElement('h1');h.textContent=title;document.body.appendChild(h);document.body.appendChild(content);document.querySelectorAll('script,iframe,video,button,form').forEach(function(e){e.remove()});document.querySelectorAll('img').forEach(function(e){e.style.maxWidth='100%';e.style.height='auto'});})();";
    new AlertDialog.Builder(this).setTitle("Reader mode").setMessage("Simplify this article? Reload the page to restore its original appearance.")
        .setPositiveButton("Read", (d, n) -> t.web.evaluateJavascript(js, null)).setNegativeButton("Cancel", null).show();
  }

  /**
   * ⋮ → Download video. Offers the videos the page has requested (streams a player loads piece by piece, and video
   * files) plus any {@code <video>} with a plain address. HLS streams are downloaded by StreamDownloadService and
   * saved as MP4; video files go through Android's download manager.
   */
  void videoDownload() {
    Tab t = currentTab();
    if (t == null || t.web == null) return;
    WebView w = t.web;
    String agent = w.getSettings().getUserAgentString();
    String page = t.currentUrl();
    w.evaluateJavascript("(function(){return Array.from(document.querySelectorAll('video')).map(function(v){return v.currentSrc||v.src||(v.querySelector('source')||{}).src}).filter(Boolean)})()", json -> {
      java.util.LinkedHashMap<String, MediaSniffer.Kind> found = new java.util.LinkedHashMap<>();
      for (MediaSniffer.Found f : t.media.snapshot()) found.put(f.url, f.kind);
      try {
        JSONArray a = new JSONArray(json);
        for (int i = 0; i < a.length(); i++) {
          String u = a.optString(i);
          if (!UrlTools.isWebUrl(u) || found.containsKey(u)) continue; // blob: players are covered by the requests above
          MediaSniffer.Kind k = MediaSniffer.classify(u);
          found.put(u, k == null ? MediaSniffer.Kind.FILE : k);
        }
      } catch (Exception ignored) {}
      if (BuildConfig.TEST_LOGS) Log.i("BlockBrowserTest", "video candidates: " + found);
      if (found.isEmpty()) {
        toastLong("No video found on this page. Start the video playing, then try again. Copy-protected videos (Netflix and similar) can't be saved.");
        return;
      }
      List<String> urls = new ArrayList<>(found.keySet());
      if (urls.size() == 1) { downloadVideo(t, urls.get(0), found.get(urls.get(0)), agent, page); return; }
      String[] labels = new String[urls.size()];
      for (int i = 0; i < labels.length; i++) labels[i] = videoLabel(urls.get(i), found.get(urls.get(i)));
      new AlertDialog.Builder(this).setTitle("Download video")
          .setItems(labels, (d, n) -> downloadVideo(t, urls.get(n), found.get(urls.get(n)), agent, page)).show();
    });
  }

  static String videoLabel(String url, MediaSniffer.Kind kind) {
    String host = UrlTools.host(url);
    String path = url;
    int q = path.indexOf('?');
    if (q >= 0) path = path.substring(0, q);
    String name = Uri.decode(path.substring(path.lastIndexOf('/') + 1));
    if (name.length() > 40) name = "…" + name.substring(name.length() - 39);
    switch (kind) {
      case HLS: return "Video stream (" + name + ") · " + host;
      case DASH: return "DASH stream, not supported (" + name + ") · " + host;
      default: return "Video file " + name + " · " + host;
    }
  }

  void downloadVideo(Tab t, String url, MediaSniffer.Kind kind, String agent, String page) {
    if (kind == MediaSniffer.Kind.FILE) { Downloads.start(this, url, agent, null, "video/mp4", page); return; }
    if (kind == MediaSniffer.Kind.DASH) {
      toastLong("This site streams in DASH format, which Block Browser can't download yet");
      return;
    }
    // This window's own cookie for the stream's site (a private window has its own cookie store).
    String host = UrlTools.host(url);
    String cookie = CookieManager.getInstance().getCookie(url);
    String title = t.title;
    toast("Checking video…");
    runInBackground(() -> {
      try {
        HlsDownload.Http http = new HlsDownload.Http(agent, page, u -> host.equalsIgnoreCase(UrlTools.host(u)) ? cookie : null);
        HlsPlaylist p = HlsDownload.load(http, url);
        if (p.encryption != null) throw new HlsDownload.Refused("This video is copy-protected, so it can't be downloaded");
        if (!p.isMaster) {
          HlsDownload.check(p);
          runOnUiThread(() -> startStreamDownload(url, null, title, null, agent, page, host, cookie));
          return;
        }
        List<HlsPlaylist.Variant> qualities = p.qualities();
        runOnUiThread(() -> {
          if (qualities.size() == 1) {
            HlsPlaylist.Variant v = qualities.get(0);
            HlsPlaylist.Rendition audio = p.audioFor(v);
            startStreamDownload(v.uri, audio == null ? null : audio.uri, title, v.label(), agent, page, host, cookie);
            return;
          }
          String[] labels = new String[qualities.size()];
          for (int i = 0; i < labels.length; i++) labels[i] = qualities.get(i).label();
          new AlertDialog.Builder(this).setTitle("Choose quality").setItems(labels, (d, n) -> {
            HlsPlaylist.Variant v = qualities.get(n);
            HlsPlaylist.Rendition audio = p.audioFor(v);
            startStreamDownload(v.uri, audio == null ? null : audio.uri, title, v.label(), agent, page, host, cookie);
          }).setNegativeButton("Cancel", null).show();
        });
      } catch (HlsDownload.Refused e) {
        if (BuildConfig.TEST_LOGS) Log.i("BlockBrowserTest", "stream refused: " + e.getMessage());
        runOnUiThread(() -> toastLong(e.getMessage()));
      } catch (Exception e) {
        Log.w("BlockBrowser", "couldn't read video stream", e);
        runOnUiThread(() -> toastLong("Couldn't read this video stream (" + e.getMessage() + ")"));
      }
    });
  }

  void startStreamDownload(String video, String audio, String title, String quality, String agent, String page, String host, String cookie) {
    Runnable go = () -> Downloads.withStorage(this, () -> {
      try {
        // A private window passes its own cookie; the service never uses the normal cookie store for it.
        StreamDownloadService.start(this, video, audio, title, quality, agent, page, isPrivate(), host, isPrivate() ? cookie : null);
        showSnackbar("Downloading video", "View", () -> Downloads.showPanel(this), null);
      } catch (Exception e) {
        toastLong("Couldn't start the download: " + e.getMessage());
      }
    });
    // Android 13+ needs permission to show the download's progress notification; ask once, download either way.
    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        && !prefs.getBoolean("asked_notifications", false)) {
      prefs.edit().putBoolean("asked_notifications", true).apply();
      requestRuntime(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, granted -> go.run());
    } else {
      go.run();
    }
  }

  void printPage() {
    Tab t = currentTab();
    if (t == null || t.web == null) return;
    android.print.PrintManager manager = (android.print.PrintManager) getSystemService(PRINT_SERVICE);
    String title = t.title == null || t.title.isEmpty() ? "Webpage" : t.title;
    manager.print(title, t.web.createPrintDocumentAdapter(title), new android.print.PrintAttributes.Builder().build());
  }

  void addToHomeScreen() {
    Tab t = currentTab();
    String url = t == null ? null : t.currentUrl();
    if (!UrlTools.isWebUrl(url)) return;
    String title = t.displayTitle();
    android.content.pm.ShortcutManager manager = getSystemService(android.content.pm.ShortcutManager.class);
    if (manager == null || !manager.isRequestPinShortcutSupported()) { toast("Home screen shortcuts unavailable"); return; }
    Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(url), this, MainActivity.class);
    open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    android.graphics.drawable.Icon icon = t.favicon != null ? android.graphics.drawable.Icon.createWithBitmap(t.favicon)
        : android.graphics.drawable.Icon.createWithResource(this, R.mipmap.ic_launcher);
    android.content.pm.ShortcutInfo shortcut = new android.content.pm.ShortcutInfo.Builder(this, "page_" + Integer.toHexString(url.hashCode()))
        .setShortLabel(title.length() > 25 ? title.substring(0, 25) : title).setLongLabel(title).setIcon(icon).setIntent(open).build();
    manager.requestPinShortcut(shortcut, null);
  }

  // =====================================================================================================
  // Settings actions (used by SettingsPanels)
  // =====================================================================================================

  /** Each engine's own site, used to show its logo in the chooser. */
  static final String[] ENGINE_SITES = {"www.google.com", "www.bing.com", "duckduckgo.com", "search.brave.com", "www.startpage.com", "www.ecosia.org", "search.yahoo.com", "www.qwant.com", "www.mojeek.com", "searx.be"};
  static final int[] ENGINE_COLORS = {0xff4285f4, 0xff00809d, 0xffde5833, 0xfffb542b, 0xff6573ff, 0xff36acb8, 0xff6001d2, 0xff5c97ff, 0xff7ab93c, 0xff3050ff};

  /**
   * Search engine chooser: every engine with its logo, the current one ticked. Logos come from the icon cache
   * (saved when you visit the engine) or, in normal windows, are fetched once from the engine's own site and
   * cached. Nothing is sent to a third party, and private windows never fetch.
   */
  void searchEngineMenu(Runnable after) {
    ListPanel panel = new ListPanel(this, "Search engine");
    String[] details = {
      "google.com", "bing.com", "duckduckgo.com · private search", "search.brave.com · independent index",
      "startpage.com · Google results, privately", "ecosia.org · plants trees", "search.yahoo.com", "qwant.com · private search",
      "mojeek.com · independent index", "searx.be · community metasearch"
    };
    Bitmap[] logos = new Bitmap[ENGINE_NAMES.length];
    Runnable[] render = new Runnable[1];
    render[0] = () -> {
      java.util.List<ListPanel.Item> choices = new java.util.ArrayList<>();
      int currentEngine = engine();
      for (int i = 0; i < ENGINE_NAMES.length; i++) {
        final int selected = i;
        ListPanel.Item item = new ListPanel.Item(ENGINE_NAMES[i].substring(0, 1), ENGINE_NAMES[i], details[i], () -> {
          prefs.edit().putInt("search_engine", selected).commit();
          toast("Search engine: " + ENGINE_NAMES[selected]);
          panel.dismiss();
          if (after != null) after.run();
        });
        item.icon = logos[i] != null ? logos[i] : letterBadge(ENGINE_NAMES[i].substring(0, 1), ENGINE_COLORS[i]);
        if (i == currentEngine) { item.trailing = "✓"; item.description = ENGINE_NAMES[i] + ", selected"; }
        choices.add(item);
      }
      panel.setItems(choices);
    };
    render[0].run();
    panel.show();
    runInBackground(() -> {
      boolean changed = false;
      for (int i = 0; i < ENGINE_SITES.length; i++) {
        Bitmap b = favicons().get(ENGINE_SITES[i]);
        if (b == null && !isPrivate()) {
          b = fetchIcon(ENGINE_SITES[i]);
          if (b != null) favicons().put(ENGINE_SITES[i], b);
        }
        if (b != null) { logos[i] = b; changed = true; }
      }
      if (changed) runOnUiThread(() -> { if (panel.isShowing()) render[0].run(); });
    });
  }

  /** Downloads a site's own icon (no third-party icon service). Returns null on any failure. */
  static Bitmap fetchIcon(String site) {
    for (String path : new String[]{"/apple-touch-icon.png", "/favicon.ico"}) {
      java.net.HttpURLConnection c = null;
      try {
        c = (java.net.HttpURLConnection) new java.net.URL("https://" + site + path).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(4000);
        c.setInstanceFollowRedirects(true);
        if (c.getResponseCode() != 200) continue;
        try (java.io.InputStream in = c.getInputStream()) {
          Bitmap b = android.graphics.BitmapFactory.decodeStream(in);
          if (b != null && b.getWidth() >= 16) return b;
        }
      } catch (Exception ignored) {
      } finally {
        if (c != null) c.disconnect();
      }
    }
    return null;
  }

  /** Round coloured badge with a letter, shown until (or instead of) a real logo. */
  Bitmap letterBadge(String letter, int color) {
    int size = dp(34);
    Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
    Canvas c = new Canvas(b);
    android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    p.setColor(color);
    c.drawCircle(size / 2f, size / 2f, size / 2f, p);
    p.setColor(Color.WHITE);
    p.setTextSize(size * 0.5f);
    p.setTypeface(Typeface.DEFAULT_BOLD);
    p.setTextAlign(android.graphics.Paint.Align.CENTER);
    c.drawText(letter, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2f, p);
    return b;
  }

  void textZoomMenu(Runnable after) {
    String[] sizes = {"75%", "90%", "100%", "110%", "125%", "150%", "200%"};
    int[] values = {75, 90, 100, 110, 125, 150, 200};
    int currentZoom = prefs.getInt("text_zoom", 100), checked = -1;
    for (int i = 0; i < values.length; i++) if (values[i] == currentZoom) checked = i;
    new AlertDialog.Builder(this).setTitle("Text size").setSingleChoiceItems(sizes, checked, (d, n) -> {
      int size = values[n];
      prefs.edit().putInt("text_zoom", size).apply();
      for (Tab t : tabs) if (t.web != null) t.web.getSettings().setTextZoom(size);
      d.dismiss();
      if (after != null) after.run();
    }).setNegativeButton("Cancel", null).show();
  }

  void homepageMenu(Runnable after) {
    boolean custom = !HomePage.isHome(homeUrl());
    String[] choices = {"Block Browser home (search + blocking stats)", "Another address…"};
    new AlertDialog.Builder(this).setTitle("Homepage").setSingleChoiceItems(choices, custom ? 1 : 0, (d, which) -> {
      d.dismiss();
      if (which == 0) { prefs.edit().remove("homepage").apply(); if (after != null) after.run(); }
      else customHomepage(after);
    }).setNegativeButton("Cancel", null).show();
  }

  void customHomepage(Runnable after) {
    EditText input = new EditText(this);
    input.setSingleLine(true);
    input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    input.setText(HomePage.isHome(homeUrl()) ? "https://" : homeUrl());
    input.setSelection(input.getText().length());
    new AlertDialog.Builder(this).setTitle("Homepage address").setView(input).setPositiveButton("Save", (d, n) -> {
      String value = input.getText().toString().trim();
      if (UrlTools.isWebUrl(value)) { prefs.edit().putString("homepage", value).apply(); if (after != null) after.run(); }
      else toast("Enter a complete http or https address");
    }).setNegativeButton("Cancel", null).show();
  }

  void setDarkWebsites(boolean dark) {
    prefs.edit().putBoolean("dark", dark).apply();
    for (Tab t : tabs) if (t.web != null) applyDarkening(t.web.getSettings(), dark);
    Tab t = currentTab();
    if (t != null && t.web != null) t.web.reload();
  }

  void setAdblock(boolean on) {
    blockAds = on;
    prefs.edit().putBoolean("adblock", on).apply();
  }

  void setThirdPartyCookies(boolean allow) {
    prefs.edit().putBoolean("third_party_cookies", allow).apply();
    for (Tab t : tabs) if (t.web != null) CookieManager.getInstance().setAcceptThirdPartyCookies(t.web, allow);
  }

  /** Lets the user pick what to delete. Cookies, site data and cache can't be limited to a time range in WebView. */
  void clearBrowsingData() {
    final String[] ranges = {"Last hour", "Last 24 hours", "Last 7 days", "Last 4 weeks", "All time"};
    final long[] spans = {3_600_000L, 86_400_000L, 7 * 86_400_000L, 28 * 86_400_000L, 0};
    final int[] range = {4};
    final int HISTORY = 0, COOKIES = 1, CACHE = 2, ICONS = 3, CLOSED = 4, STATS = 5;
    final List<String> labels = new ArrayList<>();
    final List<Integer> kinds = new ArrayList<>();
    if (!isPrivate()) { labels.add("Browsing history"); kinds.add(HISTORY); }
    labels.add("Cookies and site data (signs you out of most sites)"); kinds.add(COOKIES);
    labels.add("Cached images and files"); kinds.add(CACHE);
    if (!isPrivate()) { labels.add("Site icons"); kinds.add(ICONS); labels.add("Recently closed tabs"); kinds.add(CLOSED); }
    // Blocked addresses include sites you opened (a blocked page is counted under its own address).
    if (blockingStats != null) { labels.add("Blocking statistics (blocked addresses and daily counts)"); kinds.add(STATS); }
    final boolean[] checked = new boolean[labels.size()];
    for (int i = 0; i < checked.length; i++) checked[i] = kinds.get(i) != ICONS && kinds.get(i) != CLOSED;
    final Runnable[] show = new Runnable[1];
    show[0] = () -> {
      AlertDialog.Builder b = new AlertDialog.Builder(this)
          .setTitle(isPrivate() ? "Clear this private window's data" : "Clear browsing data")
          .setMultiChoiceItems(labels.toArray(new String[0]), checked, (d, i, on) -> checked[i] = on)
          .setPositiveButton("Clear", (d, n) -> {
            long now = System.currentTimeMillis();
            for (int i = 0; i < checked.length; i++) {
              if (!checked[i]) continue;
              int kind = kinds.get(i);
              if (kind == HISTORY) {
                if (range[0] == 4) history().clear(null);
                else history().deleteRange(now - spans[range[0]], Long.MAX_VALUE, null);
              } else if (kind == COOKIES) {
                CookieManager.getInstance().removeAllCookies(null);
                CookieManager.getInstance().flush();
                WebStorage.getInstance().deleteAllData();
                GeolocationPermissions.getInstance().clearAll();
              } else if (kind == CACHE) {
                clearCache();
              } else if (kind == ICONS) {
                favicons().clear();
                for (Tab t : tabs) t.favicon = null;
              } else if (kind == CLOSED) {
                prefs.edit().remove("closed_tabs").remove("recently_closed_url").apply();
              } else if (kind == STATS) {
                blockingStats.clear();
              }
            }
            toast("Browsing data cleared");
          })
          .setNegativeButton("Cancel", null);
      if (!isPrivate()) b.setNeutralButton("History: " + ranges[range[0]].toLowerCase(Locale.ROOT), (d, n) ->
          new AlertDialog.Builder(this).setTitle("Delete history from (cookies, site data and cache are always cleared completely)")
              .setSingleChoiceItems(ranges, range[0], (dd, which) -> { range[0] = which; dd.dismiss(); show[0].run(); }).show());
      b.show();
    };
    show[0].run();
  }

  void clearCache() {
    Tab t = currentTab();
    if (t != null && t.web != null) { t.web.clearCache(true); return; }
    WebView temp = new WebView(this);
    temp.clearCache(true);
    temp.destroy();
  }

  // =====================================================================================================
  // Back and shutdown
  // =====================================================================================================

  @Override public void onBackPressed() {
    if (customView != null) { hideCustomView(); return; }
    if (findBar.getVisibility() == View.VISIBLE) { closeFind(); return; }
    if (fullscreen) { setFullscreen(false); return; }
    if (address.hasFocus()) { address.clearFocus(); hideKeyboard(); return; }
    Tab t = currentTab();
    if (t != null && t.web != null && t.web.canGoBack()) {
      t.errorUrl = null;
      t.web.goBack();
      return;
    }
    if (t != null && t.parent != null && tabs.contains(t.parent)) {
      // A tab opened from a link or by a site: Back closes it and returns to the page that opened it.
      closeTab(current, false);
      return;
    }
    onBackAtRoot();
  }

  @Override protected void onDestroy() {
    if (pendingUpload != null) { pendingUpload.onReceiveValue(null); pendingUpload = null; }
    if (pendingPermission != null) { try { pendingPermission.deny(); } catch (Exception ignored) {} pendingPermission = null; }
    saveSession();
    sessionHandler.removeCallbacksAndMessages(null);
    handler.removeCallbacksAndMessages(null);
    finishPendingUndo();
    for (Tab t : tabs) releaseWebView(t);
    background.shutdown();
    super.onDestroy();
  }

  /** Stream that throws as soon as WebView touches it, so the blocked request ends in a network error. */
  static final class FailingStream extends java.io.InputStream {
    @Override public int available() throws java.io.IOException { throw new java.io.IOException("Blocked by Block Browser"); }
    @Override public int read() throws java.io.IOException { throw new java.io.IOException("Blocked by Block Browser"); }
    @Override public int read(byte[] b, int off, int len) throws java.io.IOException { throw new java.io.IOException("Blocked by Block Browser"); }
  }
}

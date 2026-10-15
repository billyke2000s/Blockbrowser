package com.example.blockbrowser;

import android.app.AlertDialog;

import java.util.ArrayList;
import java.util.List;

/** Settings and "Privacy and security" screens. */
final class SettingsPanels {
  private SettingsPanels() {}

  static void showSettings(MainActivity a) {
    ListPanel panel = new ListPanel(a, a.isPrivate() ? "Settings (this private window)" : "Settings");
    Runnable[] load = new Runnable[1];
    load[0] = () -> {
      List<ListPanel.Item> items = new ArrayList<>();
      if (a.isPrivate()) items.add(new ListPanel.Item("◈", "Changes here last until this private window closes", null, null));
      items.add(ListPanel.Item.header("Search"));
      ListPanel.Item engine = new ListPanel.Item("⌕", "Search engine", null, () -> a.searchEngineMenu(load[0]));
      engine.trailing = MainActivity.ENGINE_NAMES[a.engine()];
      items.add(engine);
      boolean suggest = a.prefs.getBoolean("search_suggestions", false);
      String engineName = MainActivity.ENGINE_NAMES[a.engine()];
      String suggestDetail = a.isPrivate() ? "Never used in private windows."
          : !SearchSuggestions.supported(a.engine()) ? engineName + " doesn't offer suggestions."
          : "Sends what you type in the address bar to " + engineName + " as you type.";
      ListPanel.Item suggestions = new ListPanel.Item("✎", "Search suggestions", suggestDetail, () -> {
        if (a.isPrivate()) return;
        a.prefs.edit().putBoolean("search_suggestions", !suggest).apply();
        load[0].run();
      });
      suggestions.trailing = a.isPrivate() ? "Off" : suggest ? "On" : "Off";
      items.add(suggestions);
      items.add(ListPanel.Item.header("Browsing"));
      ListPanel.Item home = new ListPanel.Item("⌂", "Homepage", UrlTools.display(a.homeUrl()), () -> a.homepageMenu(load[0]));
      items.add(home);
      ListPanel.Item zoom = new ListPanel.Item("A", "Text size", null, () -> a.textZoomMenu(load[0]));
      zoom.trailing = a.prefs.getInt("text_zoom", 100) + "%";
      items.add(zoom);
      boolean dark = a.prefs.getBoolean("dark", false);
      ListPanel.Item darken = new ListPanel.Item("◐", "Darken websites",
          MainActivity.darkeningSupported() ? "Makes pages without a dark design dark. Sites with their own dark mode already use it." : "Needs Android 10 or newer.", () -> {
            if (!MainActivity.darkeningSupported()) return;
            a.setDarkWebsites(!dark);
            load[0].run();
          });
      darken.trailing = dark ? "On" : "Off";
      items.add(darken);
      items.add(new ListPanel.Item("◉", "Privacy and security", null, () -> showPrivacy(a)));
      items.add(ListPanel.Item.header("About"));
      items.add(new ListPanel.Item("ⓘ", "About Block Browser", "Version " + version(a) + " · what this browser can and can't protect", () -> showLimits(a)));
      panel.setItems(items);
    };
    panel.show();
    load[0].run();
  }

  static String version(MainActivity a) {
    try { return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
  }

  static void showPrivacy(MainActivity a) {
    ListPanel panel = new ListPanel(a, "Privacy and security");
    Runnable[] load = new Runnable[1];
    load[0] = () -> {
      List<ListPanel.Item> items = new ArrayList<>();
      ListPanel.Item adblock = new ListPanel.Item("⛨", "Ad and tracker blocking",
          (a.listLoaded ? String.format(java.util.Locale.ROOT, "%,d", a.blocked.size()) + " domains on the blocklist" : "Blocklist loading…")
              + " · " + a.blockedCount.get() + " requests blocked this session", () -> { a.setAdblock(!a.blockAds); load[0].run(); });
      adblock.trailing = a.blockAds ? "On" : "Off";
      items.add(adblock);
      items.add(new ListPanel.Item("◉", "Blocking statistics", "Live session chart and most-blocked open pages", () -> PrivacyChart.show(a)));
      boolean strip = a.stripTrackingOn();
      ListPanel.Item tracking = new ListPanel.Item("✂", "Remove tracking from links",
          "Removes tracking codes such as utm_source, fbclid and gclid from page addresses.", () -> {
            a.prefs.edit().putBoolean("strip_tracking", !strip).apply();
            load[0].run();
          });
      tracking.trailing = strip ? "On" : "Off";
      items.add(tracking);
      boolean third = a.prefs.getBoolean("third_party_cookies", false);
      ListPanel.Item cookies = new ListPanel.Item("◌", "Third-party cookies",
          third ? "Allowed. Some sign-in and embedded content needs them; trackers use them too." : "Blocked. Some sign-in pop-ups and embedded content may not work.", () -> {
            a.setThirdPartyCookies(!third);
            load[0].run();
          });
      cookies.trailing = third ? "Allowed" : "Blocked";
      items.add(cookies);
      items.add(new ListPanel.Item("▤", "Site settings", "Permissions, ad-blocking exceptions and data for each site", () -> SitePanels.showAllSites(a)));
      items.add(new ListPanel.Item("⌫", "Clear browsing data", null, a::clearBrowsingData));
      items.add(new ListPanel.Item("ⓘ", "What Block Browser can and can't protect", null, () -> showLimits(a)));
      panel.setItems(items);
    };
    panel.show();
    load[0].run();
  }

  /** Honest summary of the protections and the Android WebView limits behind them. */
  static void showLimits(MainActivity a) {
    String text = "Ad blocking stops requests to about 215,000 known ad, tracker and malware domains. It can't remove ads "
        + "served from a site's own domain, and it isn't a full content filter like uBlock Origin.\n\n"
        + "Private windows run in a separate process with their own cookies and site storage. When the last private window closes, "
        + "that data is deleted; after a crash it is deleted before it can be used again. Downloads are kept in your Downloads folder, "
        + "and websites, your network and your internet provider can still see what you visit.\n\n"
        + "Block Browser is built on Android System WebView. Because of that:\n"
        + "• Google blocks signing in to Google accounts inside apps' web views, so Google sign-in may refuse to work.\n"
        + "• Websites can't show notifications.\n"
        + "• Downloads can't be paused and resumed.\n"
        + "• Clearing data for one site can't remove every cookie (cookies set for specific paths may remain).\n"
        + "• Browser extensions aren't supported.\n\n"
        + "HTTPS certificate errors are never bypassed. Pages can't read the app's files.\n\n"
        + "Block Browser is open source. The blocklist comes from StevenBlack hosts and HaGeZi Multi PRO; see the project's README for licences.";
    new AlertDialog.Builder(a).setTitle("Block Browser " + version(a)).setMessage(text).setPositiveButton("OK", null).show();
  }
}

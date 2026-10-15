package com.example.blockbrowser;

import android.app.AlertDialog;
import android.net.http.SslCertificate;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.WebStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Site information for the current page, and site settings for every site with saved data or choices. */
final class SitePanels {
  private SitePanels() {}

  static void showSiteInfo(MainActivity a) {
    Tab t = a.currentTab();
    String url = t == null ? null : t.currentUrl();
    if (HomePage.isHome(url)) {
      new AlertDialog.Builder(a).setTitle("Block Browser home").setMessage("This page is part of the app. It isn't loaded from the internet, and searches go to "
          + MainActivity.ENGINE_NAMES[a.engine()] + ".").setPositiveButton("OK", null).show();
      return;
    }
    if (!UrlTools.isWebUrl(url)) {
      new AlertDialog.Builder(a).setTitle("Site information").setMessage("This page has no site information.").setPositiveButton("OK", null).show();
      return;
    }
    String origin = UrlTools.origin(url);
    String host = UrlTools.host(url);
    ListPanel panel = new ListPanel(a, UrlTools.display(origin));
    Runnable[] load = new Runnable[1];
    load[0] = () -> {
      List<ListPanel.Item> items = new ArrayList<>();
      // Connection
      if (t.errorUrl != null) {
        items.add(new ListPanel.Item("⚠", "Page could not be loaded", "The address shown failed to load.", null));
      } else if (url.startsWith("https://")) {
        SslCertificate cert = t.web == null ? null : t.web.getCertificate();
        String detail = "Information you send to this site is private.";
        if (cert != null) {
          String to = cert.getIssuedTo() == null ? "" : cert.getIssuedTo().getCName();
          String by = cert.getIssuedBy() == null ? "" : (!cert.getIssuedBy().getOName().isEmpty() ? cert.getIssuedBy().getOName() : cert.getIssuedBy().getCName());
          java.util.Date until = cert.getValidNotAfterDate();
          detail += "\nCertificate for " + to + ", issued by " + by + (until == null ? "" : ", valid until " + DateFormat.getDateFormat(a).format(until));
        }
        items.add(new ListPanel.Item("🔒", "Connection is secure", detail, null));
      } else {
        items.add(new ListPanel.Item("⚠", "Connection is not secure", "Don't enter passwords or payment details on this site: others on the network could read them.", null));
      }
      // Blocking
      boolean adblockOff = a.siteSettings().adblockDisabledFor(host);
      items.add(new ListPanel.Item("◉", t.blocked.get() + (t.blocked.get() == 1 ? " ad or tracker blocked" : " ads and trackers blocked") + " on this page",
          a.blockAds ? (adblockOff ? "Ad blocking is off for this site." : "Requests to known ad, tracker and malware domains are stopped.") : "Ad blocking is turned off everywhere.", null));
      ListPanel.Item toggle = new ListPanel.Item("⛨", "Ad blocking on this site", adblockOff ? "Off — tap to turn on" : "On — tap to turn off if the site doesn't work", () -> {
        a.siteSettings().setAdblockDisabled(host, !adblockOff);
        a.reloadTab(t);
        load[0].run();
      });
      toggle.trailing = adblockOff ? "Off" : "On";
      items.add(toggle);
      // Permissions
      items.add(ListPanel.Item.header("Permissions"));
      items.addAll(permissionItems(a, origin, load[0]));
      items.add(new ListPanel.Item("✉", "Notifications", "Not supported: Android WebView can't show website notifications.", null));
      // Data
      items.add(ListPanel.Item.header("Data"));
      items.add(new ListPanel.Item("⌫", "Clear cookies and site data", "Signs you out of " + host + ". Reloads the page.", () ->
          LibraryPanels.confirm(a, "Clear cookies and site data for " + host + "?", "Clear", () -> {
            int cookies = clearSiteData(origin, host);
            a.toast(cookies == 0 ? "Site data cleared" : "Site data and " + cookies + (cookies == 1 ? " cookie" : " cookies") + " cleared");
            a.reloadTab(t);
            load[0].run();
          })));
      items.add(new ListPanel.Item("↺", "Reset permissions for this site", null, () -> { a.siteSettings().clearOrigin(origin); load[0].run(); }));
      items.add(new ListPanel.Item("▤", "All sites", "Permissions, ad-blocking exceptions and stored data", () -> showAllSites(a)));
      panel.setItems(items);
    };
    panel.show();
    load[0].run();
  }

  static List<ListPanel.Item> permissionItems(MainActivity a, String origin, Runnable reload) {
    List<ListPanel.Item> items = new ArrayList<>();
    String[] glyphs = {"◎", "♪", "⌖", "▶"};
    for (int i = 0; i < SiteSettings.KINDS.length; i++) {
      String kind = SiteSettings.KINDS[i];
      int value = a.siteSettings().get(origin, kind);
      ListPanel.Item it = new ListPanel.Item(glyphs[i], SiteSettings.label(kind), null, () -> {
        String[] choices = {"Ask every time", "Allow", "Block"};
        new AlertDialog.Builder(a).setTitle(SiteSettings.label(kind)).setSingleChoiceItems(choices, value, (d, choice) -> {
          a.siteSettings().set(origin, kind, choice);
          if (kind.equals(SiteSettings.LOCATION)) GeolocationPermissions.getInstance().clear(origin);
          d.dismiss();
          reload.run();
        }).setNegativeButton("Cancel", null).show();
      });
      it.trailing = SiteSettings.valueLabel(value);
      items.add(it);
    }
    return items;
  }

  /**
   * Clears local storage, IndexedDB and other origin storage, the WebView location permission, and the site's
   * cookies. WebView can't list cookies by path or domain, so cookies set for a specific sub-path may remain.
   * Returns the number of cookies expired.
   */
  static int clearSiteData(String origin, String host) {
    WebStorage.getInstance().deleteOrigin(origin);
    GeolocationPermissions.getInstance().clear(origin);
    CookieManager manager = CookieManager.getInstance();
    int count = 0;
    for (String scheme : new String[]{"https://", "http://"}) {
      String url = scheme + host;
      String cookie = manager.getCookie(url);
      if (cookie == null) continue;
      for (String part : cookie.split(";")) {
        int eq = part.indexOf('=');
        String name = (eq < 0 ? part : part.substring(0, eq)).trim();
        if (name.isEmpty()) continue;
        count++;
        manager.setCookie(url, name + "=; Max-Age=0; Path=/");
        // Cookies set for the host or a parent domain need the matching Domain attribute to be replaced.
        String d = host;
        while (d.indexOf('.') > 0) {
          manager.setCookie(url, name + "=; Max-Age=0; Path=/; Domain=" + d);
          d = d.substring(d.indexOf('.') + 1);
          if (d.indexOf('.') < 0) break; // never a bare top-level domain
        }
      }
    }
    manager.flush();
    return count;
  }

  static void showAllSites(MainActivity a) {
    ListPanel panel = new ListPanel(a, "All sites");
    panel.setEmptyText("No sites have saved permissions, ad-blocking exceptions or stored data.");
    Runnable[] load = new Runnable[1];
    load[0] = () -> WebStorage.getInstance().getOrigins(value -> {
      TreeMap<String, String> sites = new TreeMap<>(); // origin -> description
      Map<?, ?> origins = value instanceof Map ? (Map<?, ?>) value : null;
      if (origins != null) for (Object o : origins.values()) {
        if (!(o instanceof WebStorage.Origin)) continue;
        WebStorage.Origin origin = (WebStorage.Origin) o;
        String key = MainActivity.originOf(origin.getOrigin());
        if (key.isEmpty()) continue;
        sites.put(key, "Stored data: " + Formatter.formatShortFileSize(a, origin.getUsage()));
      }
      for (String origin : a.siteSettings().originsWithPermissions()) {
        String existing = sites.get(origin);
        sites.put(origin, (existing == null ? "" : existing + " · ") + "Permissions set");
      }
      for (String host : a.siteSettings().adblockOffSites()) {
        String origin = "https://" + host;
        String existing = sites.get(origin);
        sites.put(origin, (existing == null ? "" : existing + " · ") + "Ad blocking off");
      }
      List<ListPanel.Item> items = new ArrayList<>();
      for (Map.Entry<String, String> e : sites.entrySet()) {
        ListPanel.Item it = new ListPanel.Item("◍", UrlTools.display(e.getKey()), e.getValue(), () -> showSite(a, e.getKey(), load[0]));
        it.icon = a.favicons().peek(UrlTools.host(e.getKey()));
        items.add(it);
      }
      panel.setItems(items);
    });
    panel.textAction("Clear all", "Clear all site data", () -> LibraryPanels.confirm(a, "Clear cookies and data for all sites?", "Clear all", () -> {
      CookieManager.getInstance().removeAllCookies(null);
      CookieManager.getInstance().flush();
      WebStorage.getInstance().deleteAllData();
      GeolocationPermissions.getInstance().clearAll();
      load[0].run();
    }));
    panel.show();
    load[0].run();
  }

  static void showSite(MainActivity a, String origin, Runnable after) {
    String host = UrlTools.host(origin);
    ListPanel panel = new ListPanel(a, UrlTools.display(origin));
    Runnable[] load = new Runnable[1];
    load[0] = () -> {
      List<ListPanel.Item> items = new ArrayList<>();
      items.add(ListPanel.Item.header("Permissions"));
      items.addAll(permissionItems(a, origin, load[0]));
      items.add(ListPanel.Item.header("Ad blocking"));
      boolean off = a.siteSettings().adblockDisabledFor(host);
      ListPanel.Item toggle = new ListPanel.Item("⛨", "Ad blocking on this site", null, () -> { a.siteSettings().setAdblockDisabled(host, !off); load[0].run(); });
      toggle.trailing = off ? "Off" : "On";
      items.add(toggle);
      items.add(ListPanel.Item.header("Data"));
      items.add(new ListPanel.Item("⌫", "Clear cookies and site data", null, () -> LibraryPanels.confirm(a, "Clear cookies and site data for " + host + "?", "Clear", () -> {
        clearSiteData(origin, host);
        a.toast("Site data cleared");
        if (after != null) after.run();
      })));
      items.add(new ListPanel.Item("↺", "Reset permissions", null, () -> { a.siteSettings().clearOrigin(origin); load[0].run(); if (after != null) after.run(); }));
      panel.setItems(items);
    };
    panel.setOnDismiss(after);
    panel.show();
    load[0].run();
  }
}

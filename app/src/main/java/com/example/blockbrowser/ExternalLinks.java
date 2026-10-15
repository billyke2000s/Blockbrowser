package com.example.blockbrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.widget.Toast;

import java.util.Locale;

/**
 * Links a page wants to open outside the browser (mailto:, tel:, intent:, app schemes...).
 *
 * Safety rules: only main-frame navigations are considered; local-access schemes are never handed to other apps;
 * intent: links are stripped of explicit components, selectors and URI-permission grants and must target a
 * BROWSABLE activity; leaving for another app is confirmed by the user, except for mail, phone and SMS links
 * the user tapped. A web fallback (browser_fallback_url) is only followed when it is http/https.
 */
final class ExternalLinks {
  interface Loader { void load(String url); }

  private ExternalLinks() {}

  /** Schemes that must never leave the browser or be loaded from a page link. */
  static boolean isBlockedScheme(String scheme) {
    switch (scheme) {
      case "javascript": case "file": case "content": case "data": case "blob": case "about": case "chrome": case "view-source":
        return true;
      default:
        return false;
    }
  }

  /** Returns true when the navigation was handled here (so the WebView must not load it). */
  static boolean handle(Activity activity, Uri uri, boolean mainFrame, boolean userGesture, boolean privateWindow, Loader loader) {
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (scheme.equals("http") || scheme.equals("https")) return false;
    if (!mainFrame || isBlockedScheme(scheme)) return true;

    Intent intent;
    if (scheme.equals("intent")) {
      try {
        intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
      } catch (Exception e) {
        return true;
      }
      String fallback = intent.getStringExtra("browser_fallback_url");
      sanitize(intent);
      if (!resolves(activity, intent)) {
        if (fallback != null && UrlTools.isWebUrl(fallback)) loader.load(fallback);
        else Toast.makeText(activity, "No app can open this link", Toast.LENGTH_SHORT).show();
        return true;
      }
    } else {
      intent = new Intent(Intent.ACTION_VIEW, uri);
      sanitize(intent);
      if (!resolves(activity, intent)) {
        Toast.makeText(activity, "No app can open this link", Toast.LENGTH_SHORT).show();
        return true;
      }
    }

    boolean simple = scheme.equals("mailto") || scheme.equals("tel") || scheme.equals("sms") || scheme.equals("smsto");
    if (simple && userGesture && !privateWindow) { launch(activity, intent); return true; }
    String app = appLabel(activity, intent);
    String message = (app == null ? "This page wants to open another app." : "This page wants to open " + app + ".")
        + (privateWindow ? "\n\nThe other app is not part of private browsing." : "");
    final Intent target = intent;
    new AlertDialog.Builder(activity)
        .setTitle("Leave Block Browser?")
        .setMessage(message)
        .setPositiveButton("Open", (d, w) -> launch(activity, target))
        .setNegativeButton("Cancel", null)
        .show();
    return true;
  }

  static void sanitize(Intent intent) {
    intent.setComponent(null);
    intent.setSelector(null);
    intent.setPackage(intent.getPackage()); // keep a package hint (e.g. "open in YouTube") but never a component
    intent.addCategory(Intent.CATEGORY_BROWSABLE);
    intent.setFlags(intent.getFlags() & ~(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
    intent.setClipData(null);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
  }

  private static boolean resolves(Activity activity, Intent intent) {
    return activity.getPackageManager().resolveActivity(intent, 0) != null;
  }

  private static String appLabel(Activity activity, Intent intent) {
    try {
      ResolveInfo info = activity.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
      if (info == null || info.activityInfo == null) return null;
      if ("android".equals(info.activityInfo.packageName)) return null; // the system app chooser
      return String.valueOf(info.loadLabel(activity.getPackageManager()));
    } catch (Exception e) {
      return null;
    }
  }

  private static void launch(Activity activity, Intent intent) {
    try {
      activity.startActivity(intent);
    } catch (ActivityNotFoundException | SecurityException e) {
      Toast.makeText(activity, "No app can open this link", Toast.LENGTH_SHORT).show();
    }
  }
}

# Block Browser

A lightweight Android WebView browser with basic advertising-domain blocking.

## Features
- Address bar and Google web search
- Multiple tabs, back/forward navigation, refresh, home
- Built-in hostname-based ad/tracker denylist
- Global adblock toggle

## Download the APK
Go to **Actions → Android APK → latest successful run → Artifacts → BlockBrowser-debug-apk**. Download the artifact ZIP, extract `app-debug.apk`, and install it on Android.

## Build locally
Requires Java 17, Android SDK 35 and Gradle 8.9. Run `gradle :app:assembleDebug`. Output: `app/build/outputs/apk/debug/app-debug.apk`.

## Project layout
- `app/src/main/java/com/example/blockbrowser/MainActivity.java` — UI, navigation, tabs and request filtering
- `app/src/main/AndroidManifest.xml` — app declaration and Internet permission
- `app/build.gradle.kts` — Android build configuration
- `.github/workflows/android.yml` — automatic APK build

## Limitations
This is an early prototype, **not** a hardened privacy browser. The domain denylist is limited; ads can still appear. There is no incognito mode, bookmarks, history manager, per-site adblock or download interface yet. No external analytics SDK is included, but websites may still track visitors.

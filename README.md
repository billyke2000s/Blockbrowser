# Block Browser

A lightweight Android browser (built on Android's WebView) that blocks requests to a small list of known ad and tracker domains.

> **Status: early prototype.** It works as a basic browser, but the ad blocking is minimal. Expect to still see ads on many sites. See [Limitations](#limitations).

## Features
- Address bar: type a URL or search terms (searches go to Google)
- Multiple tabs, back/forward, refresh, home
- Built-in ad/tracker domain blocklist (14 domains, see below)
- Toggle ad blocking on/off from the ⋮ menu
- Android back button goes back in page history

## Install on your phone
1. On your phone, open the repo's **[Releases](../../releases)** page.
2. Open the newest `build-N` release and download **`BlockBrowser-debug.apk`**.
3. Open the downloaded file. Android will ask you to allow installs from your browser/file manager ("Install unknown apps") — allow it, then tap **Install**.

Requires Android 8.0 (API 26) or newer.

**Why "debug"?** The APK is signed with an automatically generated debug key, not a permanent release key. That has two consequences:
- Play Protect may warn about it. That is expected for self-built apps.
- If an update refuses to install ("App not installed" / signature conflict), uninstall the old version first. Uninstalling deletes the app's data (cookies, logins).

## How the APK gets built
Every push to `main` runs the GitHub Actions workflow in `.github/workflows/android.yml`, which:
1. Sets up Java 17 and uses the Android SDK already installed on GitHub's Ubuntu runner (adds platform 35 / build-tools 35.0.0).
2. Runs `gradle :app:assembleDebug` with Gradle 8.9.
3. Uploads the APK as a workflow artifact (`BlockBrowser-debug-apk`).
4. Creates a GitHub Release `build-<run number>` with the APK attached.

Pull requests and manual runs on other branches build and upload the artifact but **do not** create a release. You can start a build manually from **Actions → Build and publish Android APK → Run workflow**.

If a build fails, open the failed run in the Actions tab and expand the red step to see the error.

## Build locally
Requirements:
- JDK 17
- Android SDK with `platforms;android-35` and `build-tools;35.0.0` (installing Android Studio gives you these)
- Gradle 8.9 (the repo has no Gradle wrapper yet, so Gradle must be installed separately)

Tell Gradle where the SDK is, either with the `ANDROID_HOME` environment variable or a `local.properties` file in the repo root (this file is git-ignored):
```
sdk.dir=/path/to/Android/Sdk
```
Then build:
```
gradle :app:assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`. Install to a connected phone with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

Alternatively, open the folder in Android Studio and press Run.

## How the ad blocking works
`MainActivity` overrides `WebViewClient.shouldInterceptRequest`. For every request the page makes, it checks the hostname against a hard-coded set of domains (the `blocked` set near the top of `MainActivity.java`). If the host is one of them, or a subdomain of one, the request gets an empty response instead of being fetched.

Current blocklist: `doubleclick.net`, `googlesyndication.com`, `googleadservices.com`, `adnxs.com`, `adsrvr.org`, `taboola.com`, `outbrain.com`, `scorecardresearch.com`, `criteo.com`, `pubmatic.com`, `rubiconproject.com`, `openx.net`, `amazon-adsystem.com`, `facebook.net`.

To block another domain, add it to that set and rebuild.

Note: this only affects pages loaded **inside Block Browser**. It does not block ads in other apps or system-wide.

## Project layout
| File | Purpose |
|---|---|
| `app/src/main/java/com/example/blockbrowser/MainActivity.java` | All app code: UI (built in code, no XML layouts), tabs, navigation, request blocking |
| `app/src/main/AndroidManifest.xml` | App declaration, Internet permission, launcher activity |
| `app/build.gradle.kts` | App build config (package name, SDK versions, version number) |
| `build.gradle.kts`, `settings.gradle.kts` | Root Gradle config (Android Gradle Plugin 8.7.3) |
| `.github/workflows/android.yml` | CI build and release publishing |
| `CHANGELOG.md` | History of changes |

## Limitations
- **Blocklist is tiny.** 14 hard-coded domains; real ad blockers use lists with tens of thousands of entries (e.g. EasyList). Many ads will still load.
- **No cosmetic filtering.** Blocked ads can leave empty boxes; ads served from the site's own domain aren't blocked.
- Ad-block toggle and open tabs are **not saved** when the app is closed.
- No bookmarks, history screen, downloads, incognito mode or per-site settings.
- Package name is still the placeholder `com.example.blockbrowser`; change it before any public/Play Store release.
- Only debug-signed builds are produced.
- No automated tests.

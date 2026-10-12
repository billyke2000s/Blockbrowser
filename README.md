# Block Browser

A lightweight Android browser (built on Android's WebView) that blocks requests to ~72,000 known ad, tracker and malware domains.

> **Status: early prototype.** It blocks a large domain list, but it is not as thorough as uBlock Origin or Brave. Some ads will still get through. See [Limitations](#limitations).

## Features
- Address bar: type a URL or search terms (searches go to Google)
- Multiple tabs, back/forward, refresh, home
- Ad/tracker/malware blocklist of ~72,000 domains, refreshed on every build (see below)
- Toggle ad blocking on/off from the ⋮ menu; the menu also shows how many domains are loaded
- Opening a blocked site directly shows a "Blocked by Block Browser" page instead of a blank screen
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
2. Downloads the latest blocklist with `scripts/update-blocklist.sh` (falls back to the committed copy if the download fails).
3. Runs `gradle :app:assembleDebug` with Gradle 8.9.
4. Uploads the APK as a workflow artifact (`BlockBrowser-debug-apk`).
5. Creates a GitHub Release `build-<run number>` with the APK attached.

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
`MainActivity` overrides `WebViewClient.shouldInterceptRequest`. Every request a page makes (scripts, images, iframes, tracking pixels) is checked by hostname. If the host, or any parent domain of it, is on the blocklist, the request is made to fail with a network error (the same result a page sees with uBlock Origin or a DNS blocker), so nothing is downloaded and the page's ad code sees the load fail. For example, `stats.g.doubleclick.net` is blocked because `doubleclick.net` is listed.

**The list:** `app/src/main/assets/blocklist.txt`, one domain per line, generated from the [StevenBlack unified hosts list](https://github.com/StevenBlack/hosts) (ads + malware, MIT licence). It is loaded into memory in the background when the app starts, which takes a fraction of a second. Until it has loaded, a small built-in list of 14 major ad networks (`BUILTIN_BLOCKED` in `MainActivity.java`) is used.

**Updating the list:** CI refreshes it automatically on every build. To update the committed copy yourself, run from the repo root:
```
./scripts/update-blocklist.sh
```
then commit `app/src/main/assets/blocklist.txt`.

**Adding your own domains:** add them to `BUILTIN_BLOCKED` in `MainActivity.java` (edits to `blocklist.txt` are overwritten by the script).

Note: this only affects pages loaded **inside Block Browser**. It does not block ads in other apps or system-wide.

## Project layout
| File | Purpose |
|---|---|
| `app/src/main/java/com/example/blockbrowser/MainActivity.java` | All app code: UI (built in code, no XML layouts), tabs, navigation, request blocking |
| `app/src/main/AndroidManifest.xml` | App declaration, Internet permission, launcher activity |
| `app/build.gradle.kts` | App build config (package name, SDK versions, version number) |
| `build.gradle.kts`, `settings.gradle.kts` | Root Gradle config (Android Gradle Plugin 8.7.3) |
| `app/src/main/assets/blocklist.txt` | Generated domain blocklist (don't edit by hand) |
| `scripts/update-blocklist.sh` | Downloads and regenerates the blocklist |
| `.github/workflows/android.yml` | CI build and release publishing |
| `CHANGELOG.md` | History of changes |

## Limitations
- **Domain blocking only.** Ads served from the site's own domain (e.g. YouTube video ads) or matched only by URL path aren't blocked. Full ad blockers also use EasyList-style URL and element rules, which this app doesn't support yet.
- **No cosmetic filtering.** Blocked ads can leave empty boxes on the page.
- **Occasional false positives.** A site that needs a listed domain may break; turn adblock off from the ⋮ menu for that visit. There is no per-site allowlist yet.
- The list only updates when a new APK is built, not inside the app.
- Ad-block toggle and open tabs are **not saved** when the app is closed.
- No bookmarks, history screen, downloads, incognito mode or per-site settings.
- Package name is still the placeholder `com.example.blockbrowser`; change it before any public/Play Store release.
- Only debug-signed builds are produced.
- No automated tests.

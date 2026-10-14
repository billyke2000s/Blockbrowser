# Block Browser

**Browse freely. Block distractions. Stay in control.**

Block Browser is a lightweight Android web browser with built-in ad and tracker blocking, simple navigation, fullscreen browsing and convenient video downloads. It combines everyday browsing essentials with privacy-focused controls in a modern, dark interface with rounded controls and a streamlined toolbar.

## Highlights

- **Bookmarks and history** — save favourite pages, revisit recently opened websites, and clear saved entries.
- **Find and share** — search text within a page and share links through Android apps.
- **Browsing data controls** — clear cookies, cache, web storage and history without deleting bookmarks or downloads.

- **Built-in ad and tracker blocking** — filters requests against a curated blocklist of approximately 215,000 known advertising, tracking and malicious domains, including subdomains.
- **Adblock controls** — enable or disable blocking from a dedicated menu, and see how many requests have been blocked during your session.
- **Video downloads** — tap the **↓** button on the browser toolbar to find direct video files on the current page and download them.
- **Download library** — open **Downloads** from the ⋮ menu to see files saved through the browser. Play compatible videos inside Block Browser or choose another installed app.
- **Fullscreen browsing** — hide browser and system bars for a more immersive experience. The fullscreen control fades away after a few seconds; tap its position to reveal it again.
- **Search engine choice** — select Google, Bing, DuckDuckGo, Brave Search, Startpage, Ecosia, Yahoo, Qwant, Mojeek or SearXNG from **⋮ → Search engine**. The selection is saved, and the chooser displays search-engine favicons when available.
- **Multiple tabs** — switch between pages without losing your place.
- **Quick navigation** — address-bar search, back, forward, refresh, home and edge-swipe navigation.
- **Desktop mode and dark websites** — optional viewing controls, with dark rendering support depending on Android version and website.
- **Privacy dashboard** — view session-level blocking statistics.

## Get Block Browser

1. Visit the [Releases](../../releases) page.
2. Download the latest available Android APK.
3. Open the APK on your Android device and follow the installation prompts.

**Android 8.0 or newer required.** Available builds are currently debug-signed; installing a newer build signed with a different key may require uninstalling the previous version, which can remove local app data.

## Using Block Browser

**Download a video:** Open a page with a directly accessible video and tap **↓** in the main toolbar. Select the video URL to download.

**Find downloaded files:** Open the **⋮ menu → Downloads**. Select a completed download to play it in Block Browser or open it with another compatible app.

**Choose your search engine:** Open **⋮ → Search engine**, select a provider and search directly from the address bar. Google is the default.

**Control ad blocking:** Open **⋮ → Adblock** to change the blocking setting and view statistics.

**Go fullscreen:** Open **⋮ → Full screen**. The floating fullscreen control fades after three seconds. Tap its original location to show it again, then tap it to exit; Android's Back action also exits fullscreen.

## Built for a cleaner web

Block Browser checks web requests against its domain blocklist before allowing them through. This helps reduce unwanted advertising, tracking and malicious connections while browsing inside the app.

The blocklist combines [StevenBlack's hosts](https://github.com/StevenBlack/hosts) and [HaGeZi Multi PRO](https://github.com/hagezi/dns-blocklists). It is refreshed as part of the repository's build workflow.

Domain filtering is not the same as full content filtering: first-party advertisements, some embedded ads and certain video ads may still appear. If a website fails to load correctly, try temporarily disabling ad blocking.

Video downloading works with discoverable, directly accessible media URLs. Protected streams, DRM video and some streaming-site playback formats cannot be downloaded by this feature.

## Build from source

The app uses Android WebView and Java, with Gradle-based Android builds.

Requirements: JDK 17, Android SDK platform 35, Android build tools 35.0.0 and Gradle 8.9.

```bash
gradle :app:assembleDebug
```

The resulting APK is located at `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions builds APK artifacts for pull requests and publishes build releases from the main branch.

## Open-source components

Block Browser incorporates third-party domain lists, including StevenBlack hosts (MIT) and HaGeZi Multi PRO (GPL-3.0). Check the applicable upstream licences and distribution obligations when redistributing builds.

---

**Block Browser — a simpler way to browse Android.**

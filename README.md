# Block Browser

**Browse freely. Block distractions. Stay in control.**

Block Browser is a lightweight Android web browser with built-in ad and tracker blocking, simple navigation, fullscreen browsing and convenient video downloads. It combines everyday browsing essentials with privacy-focused controls in a modern, dark interface with rounded controls and a streamlined toolbar.

## Highlights

- **Built-in ad and tracker blocking** — requests to about 215,000 known advertising, tracking and malicious domains (and their subdomains) are stopped. Turn it off for a single site from the site information button if the site breaks.
- **Private windows** — a separate process with its own cookies and storage, deleted when the window closes (see below).
- **Home page** — Block Browser's own start page and new tab: a search box (your chosen search engine, Google by default), shortcuts to your most visited sites, and how many ads and trackers were blocked today, this session and in total, with a 7-day chart and the most-blocked domains. It's built into the app, never loaded from the internet. Settings → Homepage can switch to any other address.
- **Smart address bar** — suggestions from your bookmarks and history with titles and site icons, ranked by how well the address matches and how often and recently you visited. Optional suggestions from your search engine (off by default).
- **Tabs** — a grid of open tabs with page previews: swipe or tap × to close, Undo, drag to reorder, duplicate, close others or all. Links from other apps open in a new tab. Tabs are restored after a restart, and only load when you open them.
- **Bookmarks** — folders, edit, move and delete, search, and import or export as an HTML file (works with Chrome, Firefox, Edge and Safari) or JSON.
- **History** — grouped by day or by website, searchable; delete single pages, a day, a website or a time range.
- **Downloads** — live progress, open, share, retry, cancel and delete. Downloads from signed-in sites carry the site's cookies.
- **Site permissions** — camera, microphone, location and protected media are asked per site and remembered; review or change them in the site information panel or in Settings → Privacy and security → Site settings.
- **Privacy controls** — third-party cookies blocked by default, tracking parameters (utm_*, fbclid, gclid…) removed from links, choose exactly what to clear, and clear cookies and data for one site.
- **Full screen** — hides the browser and system bars and stays on as you browse until you turn it off. Full-screen video continues in picture-in-picture when you leave the app.
- **More** — find in page, share, desktop site, darken websites, translate, reader mode, print or save as PDF, add to home screen, video download, ten search engines.

## Get Block Browser

1. Visit the [Releases](../../releases) page.
2. Download the latest available Android APK.
3. Open the APK on your Android device and follow the installation prompts.

**Android 8.0 or newer required.**

**Updating:** builds published before the stable signing key was set up were each signed with a different throwaway key, so Android refuses to install a new one over them ("App not installed" or "package conflicts"). Once, uninstall the old version first. To keep your bookmarks, export them before uninstalling (old versions: **⋮ → Export browser data → Bookmarks**, save the text as a `.json` file; new versions: **Bookmarks → Import / Export**) and import the file afterwards in **Bookmarks → Import / Export**. Builds signed with the stable key update in place.

## Using Block Browser

- **Menu:** **⋮** opens the menu. Back, forward, reload, bookmark (☆) and site information are along its top.
- **Tabs:** tap the square with the tab count.
- **Site information:** tap 🔒 or ⚠ at the left of the address bar to see the connection, blocked trackers, permissions and site data.
- **Settings:** **⋮ → Settings** for the search engine, search suggestions, homepage, text size and darkening; **⋮ → Privacy and security** for ad blocking, tracking-parameter removal, third-party cookies and site settings.
- **Download a video:** start the video playing, then **⋮ → Download video**. If the page has several, pick one; for streams with several qualities, pick the quality. Streams are downloaded in the background with a progress notification and saved to Downloads as an MP4.
- **Full screen:** **⋮ → Full screen**. The floating control fades after three seconds; tap its position to show it, then tap to exit. Back also exits.

## Private browsing

**⋮ → New private window** (Android 9 or newer) opens a window marked **Private**. It runs in a separate app process with its own cookies, site storage, cache and service workers, so sites can't see your normal logins and normal windows can't see private ones.

- Nothing from a private window is saved to history, the session, recently closed tabs or site icons, and search-engine suggestions are never fetched there.
- Permission decisions last only until the window closes. Normal "Block" decisions carry over; normal "Allow" decisions don't.
- It starts with your normal settings. Settings changed inside it apply only until it closes.
- Your bookmarks and history are available for suggestions, but can't be changed from a private window.
- Android Autofill is off, and the keyboard is asked not to learn what you type (your keyboard app decides whether to honour that).
- Private pages are hidden from the recent-apps preview. On Android 12 and older this also blocks screenshots in private windows.
- When the last private window closes, its cookies and site data are deleted and its process ends. If the app crashes or is force-stopped, leftovers are deleted before they can be used again.

**Limits:** downloads from a private window are saved to your Downloads folder and appear in Android's download list and notifications like any other download. Private browsing hides your activity from this app and other people using the phone, not from websites, your network or your internet provider.

## Built for a cleaner web

Block Browser checks web requests against its domain blocklist before allowing them through. This helps reduce unwanted advertising, tracking and malicious connections while browsing inside the app.

The blocklist combines [StevenBlack's hosts](https://github.com/StevenBlack/hosts) and [HaGeZi Multi PRO](https://github.com/hagezi/dns-blocklists). It is refreshed as part of the repository's build workflow.

Domain filtering is not the same as full content filtering: first-party advertisements, some embedded ads and certain video ads may still appear. If a website fails to load correctly, try temporarily disabling ad blocking.

Video downloading works with plain video files and with **HLS streams** (`.m3u8`), the format most news, sports and social sites use for video players; Block Browser catches the stream as the page plays it, downloads every piece and saves one MP4 (combining separate video and audio tracks). It can't download: **copy-protected video** (Netflix, Disney+, Prime Video and other DRM, or encrypted HLS), **live streams**, **DASH streams** (`.mpd`), or **YouTube** (deliberately not supported). Downloading may be against a site's terms; only save videos you have the right to.

## Known limitations

Block Browser is built on Android System WebView, which sets some hard limits:

- **Google sign-in** may refuse to work: Google blocks signing in to Google accounts inside apps' web views.
- **Website notifications** aren't supported by WebView.
- **Downloads can't be paused and resumed**; Android's download manager has no public pause. Files a page creates in memory (blob: links) can't be downloaded.
- **Clearing one site's data** removes its storage and the cookies WebView can see; cookies set for specific sub-paths may remain.
- **Search suggestions** use the search engines' informal endpoints, which can change. CI checks them on every build. At the time of writing, Brave Search's endpoint returned a bot-check page to CI (untested from phones), and Startpage and Mojeek have none.
- **Private windows** need Android 9 or newer. Private downloads are saved to your Downloads folder like any other.
- **Darken websites** affects pages without their own dark design; sites with a dark mode follow the app's dark theme anyway on Android 13+.
- **Tab groups** and browser extensions aren't supported.

## Build from source

The app uses Android WebView and Java, with Gradle-based Android builds.

Requirements: JDK 17, Android SDK platform 35, Android build tools 35.0.0 and Gradle 8.9.

```bash
gradle :app:assembleDebug        # the app
gradle :app:testDebugUnitTest    # unit tests
```

Emulator tests are described in [TESTING.md](TESTING.md).

The resulting APK is located at `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions builds APK artifacts for pull requests and publishes build releases from the main branch.

## Signing

Published APKs are signed in GitHub Actions with the project's key, taken from four repository secrets (**Settings → Secrets and variables → Actions**): `SIGNING_KEYSTORE_BASE64` (the keystore file, base64-encoded), `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`. The build log shows the certificate fingerprint. Without the secrets, builds fall back to a throwaway key and the workflow warns that they can't update an installed copy.

Keep a private backup of the keystore: if it's lost, every user has to uninstall to move to a new key.

## Open-source components

Block Browser incorporates third-party domain lists, including StevenBlack hosts (MIT) and HaGeZi Multi PRO (GPL-3.0). Check the applicable upstream licences and distribution obligations when redistributing builds.

---

**Block Browser — a simpler way to browse Android.**

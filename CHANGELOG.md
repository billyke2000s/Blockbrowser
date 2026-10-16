# Changelog

## Unreleased

### Added
- **Video stream downloads (HLS).** ⋮ → Download video now finds videos that players load as streams (`.m3u8`), not only plain `<video>` files: the browser notes video requests as the page plays. Pick a quality, and the stream is downloaded in the background (progress notification with Cancel), its pieces joined and converted to MP4 without re-encoding (separate video and audio tracks are combined), and saved to Downloads. Stream downloads appear on the Downloads screen with progress, open, share, retry, cancel and delete. Encrypted/DRM and live streams are refused; DASH isn't supported yet. Private-window stream downloads only send that window's cookie for the video's site, and their addresses aren't stored.
- **Block Browser home page** replaces google.com as the start and new-tab page: search box (chosen engine, Google by default; typed addresses open directly), most-visited shortcuts, blocked counts for today / this session / all time, a 7-day chart and the most-blocked domains, with a link to the full statistics. Generated inside the app with no network access and no JavaScript bridge. A purple version in private windows shows no history or saved statistics. Existing custom homepages are kept.

### Fixed
- **Saving files on Android 8–9**: the app asked only for permission to write to storage, but those versions make the shared Downloads folder writable only for apps holding both read and write permission. It now asks for both. (Found by the new stream-download test on Android 9.)
- **Duplicate tabs**: when Android recreated the browser (after closing it in the background, or when reopened from recent apps), the link that originally opened it was opened again in an extra tab.
- **Failed pages** were saved to history and shown in tabs as "Webpage not available".
- **Blocking statistics** screen ran under the status bar and had no back button.

### Changed (look and layout)
- **Every pop-up now matches the app**: confirmations, choices and prompts use the dark rounded style instead of Android's plain grey box.
- **Back in the ⋮ menu**: Search engine (with the current one shown), Ad blocking on/off, Dark websites on/off, Blocking statistics and Close tab. They had been moved into Settings, which made them look removed.
- **Search engine list shows logos again**, fetched once from each engine's own site and cached (never from a third-party icon service, never in private windows).
- **Header buttons have labels** ("By site", "Delete", "+ Folder", "Import / Export", "Clear") instead of bare symbols.
- **History**: each day's Delete button sits on the day's heading instead of as a row among your pages; pages without a title show the site name (or "Video · site") instead of the whole address.
- **Private downloads** don't show their source address in the normal Downloads screen.

### Fixed (review of the privacy-chart and polish changes)
- **List rows had no tap feedback** after the card restyle; rows now show a ripple, and rows without an action show none.
- **Blocking statistics weren't cleared** with browsing data, although they include the addresses of blocked pages you opened. They're now an option in Clear browsing data, and the statistics screen has Reset.
- **Recent blocking statistics were lost** when the app closed (saved only every 25 blocks); they're saved when you leave the app.
- **Launcher icon** is now an adaptive icon, so launchers mask it properly instead of shrinking it onto a white background.
- **Scroll position after a restart** wasn't restored reliably on Android 9; restoring now retries until the page is tall enough, and stops if you scroll.
- **Every published APK had a different signing key**, so phones couldn't update an installed copy. CI now signs with a stable project key when the repository has it (see README → Signing), and version codes increase with each build.

### Changed
- **Code restructured.** `MainActivity` was one 65 KB file of one-line methods. Tabs, address-bar suggestions, history, bookmarks, downloads, site settings and settings screens are now separate, readable classes. All existing features were kept.
- **Menu reorganised.** Back, forward, reload, bookmark and site information sit along the top; settings and privacy controls moved into their own screens instead of 45 flat entries.
- **History is stored in a database** with visit times and counts instead of a 150-entry list (migrated automatically).
- **Links from other apps open in a new tab**, and the browser no longer opens a second copy of itself for each link.
- **The search engine chooser no longer fetches icons from Google** each time it opens (it leaked that you opened it).
- **Back** in a tab opened from a link returns to the page that opened it. With nothing left to go back to, the app goes to the background and keeps its tabs.

### Added
- **Address-bar suggestions** from bookmarks and history with titles and site icons, ranked by address match, bookmarks, frequency and recency. Optional search-engine suggestions (off by default, never in private windows).
- **Tab switcher** with page previews, swipe to close, Undo, drag to reorder, duplicate, close others and close all. Restored tabs load only when opened; at most eight pages stay in memory and older background tabs reload when shown.
- **Bookmark folders**, editing and moving, and import/export as HTML (Chrome, Firefox, Edge, Safari) or JSON files, with invalid entries and duplicates skipped.
- **History by day and by website**, search, and deleting a page, a day, a website or a time range. History export as JSON.
- **Remembered site permissions** for camera, microphone, location and protected media, with a site information panel (certificate, blocked trackers, per-site ad blocking, permissions, clear site data) and an All sites list.
- **Downloads screen** with live progress, open, share, retry, cancel and delete.
- **Find-in-page bar**, page loading bar, error pages with Try again, tracking-parameter removal, choice of what Clear browsing data deletes, picture-in-picture for full-screen video, and confirmation before a page opens another app.
- **Private browsing indicator:** private windows show a **Private** badge and a different colour, and are hidden from the recent-apps preview. Android Autofill and keyboard learning are off there.
- **Private isolation test** (`scripts/private-isolation-check.sh`) on Android 9, 14 and 15 emulators: logs in to a local test site in normal and private windows and checks cookies, localStorage and IndexedDB are never shared and are deleted after closing, a crash or a force-stop.
- **Refreshed browser UI:** modern dark surfaces, rounded toolbar controls, improved address-bar spacing and clearer tab selection.
- **Search engine picker:** choose among Google, Bing, DuckDuckGo, Brave Search, Startpage, Ecosia, Yahoo, Qwant, Mojeek and SearXNG; save the choice between launches and display provider favicons when available.
- **Video download shortcut:** dedicated ↓ toolbar action to discover direct video sources on a page.
- **Downloads library:** ⋮ → Downloads lists Android DownloadManager entries, with options to play compatible videos in-app or open files in another app.
- **Adblock and privacy controls:** dedicated menu and session-level blocked-request statistics; adblock preference persists.
- **Navigation and display controls:** edge-swipe navigation, desktop user-agent toggle and dark websites toggle (Android 10+).
- **Full-screen mode** (⋮ → Full screen): hides the toolbar, tab bar and the phone's status/navigation bars. Back or the floating ⛶ control leaves it. The control fades after three seconds and can be revealed by tapping its original position.
- **Site full screen:** pages' own full-screen buttons (e.g. videos) now work; previously they did nothing.
- **Emulator UI check** (`.github/workflows/ui-check.yml`, `scripts/ui-check.sh`) on Android 10 and 15, run on pull requests.

### Fixed
- **A crashed page took the whole app down.** A page whose renderer crashes or is killed now shows an error page in its tab.
- **Downloads failed on Android 8–9** (no storage permission was requested).
- **Typing in the address bar was overwritten** when a page finished loading.
- **Website pop-ups lost their link to the opening page**, so sign-in pop-ups couldn't report back. They now open as real tabs.
- **Scroll position wasn't restored on older WebView versions** after a restart.
- **"Dark websites" did nothing on Android 13+**; it now uses WebView's algorithmic darkening.
- **The address bar took focus when a window opened**, so the first Back only cleared it.
- **A site asking twice for its location stacked two prompts.**
- **Pages could read the app's files** (WebView file and content access were on by default); both are now off.
- **Private browsing data could come back.** Cleanup only ran when the private window closed normally, so after a crash or force-stop private cookies and site data reappeared in the next private window. Leftovers are now deleted before a private window starts and when a normal window opens, and the private process ends when its last window closes.
- **Rotating a private window cleared its logins.** Private windows now handle rotation without restarting.
- **Private windows ignored your settings and lost bookmarks.** They now start with your normal search engine, homepage and other settings, and show your bookmarks; changes to bookmarks or history are refused with a message instead of being silently discarded.
- **Links from other apps stacked extra browser windows** and replaced the current page. They now open in a new tab in the existing window.
- **New tab, + and Home** ignored the homepage setting.
- **Toolbar under the status bar.** On Android 15+ the address bar was drawn underneath the status bar and page content under the navigation buttons. The app now pads its UI by the system bar sizes on all Android versions, and the keyboard no longer covers the page.

## Build 19 (2026-10-12)

### Added
- **HaGeZi Multi PRO list (GPL-3.0)** merged with StevenBlack: ~215,000 domains after removing redundant subdomains (was ~72,500). Covers all 128 domains in the adblock.turtlecute.org test (was 100). `scripts/update-blocklist.sh` now needs `python3`.

## Build 16 (2026-10-12)

### Fixed
- **Blocked requests looked successful.** They were answered with an empty `200 OK`, so ad-block tests (e.g. adblock.turtlecute.org) and page scripts saw them as loaded: the test scored 3%. Blocked requests now fail with `net::ERR_FAILED` before any headers are sent, like uBlock Origin or a DNS blocker.

## Build 13 (2026-10-12)

### Added
- **Large blocklist:** ~72,000 ad/tracker/malware domains from the StevenBlack unified hosts list (MIT), bundled as `app/src/main/assets/blocklist.txt` and refreshed by CI on every build via `scripts/update-blocklist.sh`.
- Opening a blocked site directly shows an explanation page instead of a blank screen.
- The ⋮ menu shows how many blocklist domains are loaded.

### Changed
- Domain matching now checks the host and each parent domain against a hash set, instead of looping through the whole list on every request (needed for a list this size).

## Build 10 (2026-10-12)

### Fixed
- **APK build in GitHub Actions.** Every run was failing at the `android-actions/setup-android` step before Gradle even started. The workflow now uses the Android SDK preinstalled on GitHub's Ubuntu runner and installs only platform 35 / build-tools 35.0.0.
- **Tabs lost on screen rotation.** Rotating the phone recreated the activity and closed all tabs. The activity now handles orientation/size changes itself.
- **Tab bar overflow.** With several tabs open, the tab bar ran off-screen and the `+` button became unreachable. It now scrolls horizontally.

### Changed
- Releases attach the APK as `BlockBrowser-debug.apk` and are only created from `main` (not from other branches or PRs).
- Pinned the runner to `ubuntu-24.04` and updated `actions/setup-java` to v5.
- README rewritten: install steps from Releases, local build steps, how blocking works, known limitations.

## 1.0 (initial)
- WebView browser with tabs, address bar/search, back/forward, refresh, home.
- Hostname-based blocking of 14 ad/tracker domains with on/off toggle.

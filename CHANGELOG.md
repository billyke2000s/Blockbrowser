# Changelog

## Unreleased

### Added
- **Full-screen mode** (⋮ → Full screen): hides the toolbar, tab bar and the phone's status/navigation bars. Back or the *Exit full screen* button leaves it.
- **Site full screen:** pages' own full-screen buttons (e.g. videos) now work; previously they did nothing.
- **Emulator UI check** (`.github/workflows/ui-check.yml`, `scripts/ui-check.sh`) on Android 10 and 15, run on pull requests.

### Fixed
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

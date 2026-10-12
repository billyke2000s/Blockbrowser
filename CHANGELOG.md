# Changelog

## Unreleased

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

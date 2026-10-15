# Testing

## Automated

| What | Where | When it runs |
|---|---|---|
| APK compiles | `.github/workflows/android.yml` | Every push to `main` and every pull request |
| UI check on emulator | `.github/workflows/ui-check.yml` → `scripts/ui-check.sh` | Every pull request, or manually: Actions → *UI check on emulator* → Run workflow |
| Unit tests (URL handling, suggestion ranking, bookmark import/export, suggestion parsing) | `app/src/test/` via `gradle :app:testDebugUnitTest` in `.github/workflows/android.yml` | Every push to `main` and every pull request |
| Search-suggestion endpoints still answer | `scripts/check-suggest-endpoints.sh` in `.github/workflows/android.yml` (warnings only) | Same as unit tests |
| Browser behaviour on emulator | `.github/workflows/ui-check.yml` (job `browser-check`) → `scripts/browser-check.sh` | Same as the UI check |
| Private browsing isolation | `.github/workflows/ui-check.yml` (job `private-isolation`) → `scripts/private-isolation-check.sh` | Same as the UI check |

The UI check builds the APK and runs it on Android 10 (API 29) and Android 15 (API 35) emulators. It checks that:
1. The address bar starts below the status bar.
2. ⋮ → *Full screen* hides the address bar and the status bar, and exposes a floating fullscreen control that fades after three seconds.
3. Back leaves full screen and the address bar is back below the status bar.

Results appear as notices/errors on the workflow run. Screenshots are uploaded as artifacts (`ui-check-api-29`, `ui-check-api-35`).

Notes:
- The test finds the address bar by its accessibility label `Address bar`. Keep that label if the toolbar changes.
- If the screen dump doesn't include the system status bar, the test uses the status bar height the app logs under the `BlockBrowserInsets` log tag.
- On Android 15 the emulator shows a one-time "Viewing full screen" tip; the test dismisses it.

## Private browsing isolation

Runs on Android 9 (API 28), 14 (API 34) and 15 (API 35) emulators. It uses the test-only `uitest` build variant (`gradle :app:assembleUitest`), which differs from the published debug APK only in:
- allowing plain HTTP to `localhost`, for the local test site;
- letting `adb` open a private window directly.

`scripts/isolation-server.py` is a small local website, reached from the emulator through `adb reverse`. Its `/login` page sets a persistent cookie, localStorage and IndexedDB; its `/check` pages report back what the browser sent. The script checks that:
1. Normal and private windows never see each other's cookie, localStorage or IndexedDB, and private windows run in their own process.
2. A private login survives while the private window is open.
3. Closing the last private window ends the private process and deletes private files from disk; a new private window starts empty.
4. Killing the private process (simulated crash) and force-stopping the app leave nothing behind, and normal logins survive.
5. No private URL appears in normal history or the saved session.

All results are listed in one notice per Android version on the workflow run, and on the run's summary page.

## Browser behaviour

`scripts/browser-check.sh` runs on Android 9, 14 and 15 emulators (Pixel 6 profile) with the `uitest` build and the same local test site. It checks:
1. Links from other apps open in new tabs; the tab button shows the count.
2. The tab switcher lists tabs, closes one, and Undo brings it back without switching away.
3. After a force-stop, all tabs come back, only the selected tab reloads, and it returns to its scroll position.
4. Typing in the address bar suggests visited pages by title.
5. A download from a signed-in page carries the site's cookie and is saved to Downloads.
6. A site's location request shows Block Browser's prompt, "Allow" is remembered, and a private window asks again.
7. Full screen stays on when a new page opens.
8. Clear browsing data removes cookies, site storage and history.
9. Killing the page renderer doesn't take the app down (uses `adb root`; skipped where unavailable).

Results for every Android version, including what was on screen when a check failed, are posted as one comment on the pull request.

The `uitest` variant also logs suggestion lists and location decisions under the `BlockBrowserTest` tag (`BuildConfig.TEST_LOGS`); published builds don't.

## Not covered automatically
Check these by hand on a phone after changes in those areas:
- Ad blocking: open https://adblock.turtlecute.org and compare the score with the previous build.
- Video full screen (a site's own full-screen button, e.g. YouTube).
- The floating fullscreen control's tap-to-reveal and exit behaviour (the automated test leaves fullscreen with Back).
- Search engine selection, persistence after restart and searches from the address bar.
- ⋮ → Download video detection on direct video URLs, download completion and file playback inside/outside the app.
- Adblock preference persistence and session privacy counts.
- Desktop-site and dark-websites toggles (Android 10+), plus edge-swipe navigation.
- Picture-in-picture for full-screen video (leave the app while a video plays full screen).
- Keyboard not covering the address bar or page fields.
- Tabs, back/forward, rotation; dragging tabs to reorder in the switcher.
- Camera and microphone permissions on a real site, file uploads, printing, translate, reader mode.
- Google and other sign-in flows (Google may block sign-in inside WebView).
- Bookmark import from a real Chrome/Firefox export file.

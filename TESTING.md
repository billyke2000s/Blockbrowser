# Testing

## Automated

| What | Where | When it runs |
|---|---|---|
| APK compiles | `.github/workflows/android.yml` | Every push to `main` and every pull request |
| UI check on emulator | `.github/workflows/ui-check.yml` → `scripts/ui-check.sh` | Every pull request, or manually: Actions → *UI check on emulator* → Run workflow |

The UI check builds the APK and runs it on Android 10 (API 29) and Android 15 (API 35) emulators. It checks that:
1. The address bar starts below the status bar.
2. ⋮ → *Full screen* hides the address bar and the status bar, and shows *Exit full screen*.
3. Back leaves full screen and the address bar is back below the status bar.

Results appear as notices/errors on the workflow run. Screenshots are uploaded as artifacts (`ui-check-api-29`, `ui-check-api-35`).

Notes:
- The test finds the address bar by its accessibility label `Address bar`. Keep that label if the toolbar changes.
- If the screen dump doesn't include the system status bar, the test uses the status bar height the app logs under the `BlockBrowserInsets` log tag.
- On Android 15 the emulator shows a one-time "Viewing full screen" tip; the test dismisses it.

## Not covered automatically
Check these by hand on a phone after changes in those areas:
- Ad blocking: open https://adblock.turtlecute.org and compare the score with the previous build.
- Video full screen (a site's own full-screen button, e.g. YouTube).
- The *Exit full screen* button (the test leaves full screen with Back).
- Keyboard not covering the address bar or page fields.
- Tabs, back/forward, rotation.

#!/usr/bin/env bash
# Browser behaviour test, run on an Android emulator by .github/workflows/ui-check.yml.
# Uses the test-only "uitest" APK and the local test site (scripts/isolation-server.py via adb reverse).
#
# Checks: multiple tabs, the tab switcher (close + undo), session restore after force-stop (selected tab and
# scroll position restored, other tabs not reloaded), address-bar suggestions from history, a download that
# needs the site's cookie, remembered site permissions (and private windows not inheriting "Allow"),
# full screen kept across navigation, clearing browsing data, and recovery when a page's renderer dies.
set -uo pipefail

APK="app/build/outputs/apk/uitest/app-uitest.apk"
PKG="com.example.blockbrowser"
PORT=8765
SITE="http://localhost:$PORT"
OUT="browser-check-output"
RESULTS="$OUT/results.json"
SUMMARY="$OUT/summary.md"
mkdir -p "$OUT"; : > "$SUMMARY"
FAILED=0; PASSED=0
fail(){ echo "::error::$*"; echo "- ❌ $*" >> "$SUMMARY"; FAILED=1; }
pass(){ echo "PASS: $*"; echo "- ✅ $*" >> "$SUMMARY"; PASSED=$((PASSED+1)); }
skip(){ echo "SKIP: $*"; echo "- ⏭️ $*" >> "$SUMMARY"; }

API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
echo "### Browser check — Android API $API" >> "$SUMMARY"

python3 scripts/isolation-server.py "$PORT" "$RESULTS" 2> "$OUT/server.log" &
SERVER=$!
trap 'kill $SERVER 2>/dev/null' EXIT
sleep 1
adb reverse "tcp:$PORT" "tcp:$PORT" || { fail "adb reverse failed"; exit 1; }
sleep 15
adb uninstall "$PKG" >/dev/null 2>&1   # start from a clean profile
adb install -r "$APK" || { fail "APK install failed"; exit 1; }

# Let the site's location request reach Block Browser's own prompt instead of Android's.
adb shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION 2>/dev/null
adb shell pm grant "$PKG" android.permission.ACCESS_COARSE_LOCATION 2>/dev/null
[ "$API" -le 28 ] && adb shell pm grant "$PKG" android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null

# Homepage on the local site, so the test doesn't depend on internet access.
cat > "$OUT/browser.xml" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><string name="homepage">$SITE/page?n=home</string></map>
EOF
adb push "$OUT/browser.xml" /data/local/tmp/bb-browser.xml >/dev/null
adb shell run-as "$PKG" mkdir -p shared_prefs
adb shell run-as "$PKG" cp /data/local/tmp/bb-browser.xml shared_prefs/browser.xml
adb logcat -c
# Full device log for the whole run (published with the screenshots), to diagnose hangs and crashes.
adb logcat -v time > "$OUT/logcat.txt" 2>&1 &
LOGCAT=$!
trap 'kill $SERVER $LOGCAT 2>/dev/null' EXIT

# ---- helpers ----------------------------------------------------------------------------------------
dump_raw(){ for _ in 1 2 3 4 5; do adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1 && return 0; sleep 2; done; return 1; }
# Emulators often show "<system app> isn't responding" over everything; dismiss it (Wait) whenever it appears.
dump(){
  for _ in 1 2 3 4; do
    dump_raw "$1" || return 1
    grep -q "isn't responding\|isn&apos;t responding" "$OUT/$1.xml" || return 0
    echo "Dismissing an emulator 'isn't responding' dialog"
    local xy; xy=$(find_node "$1" text "Wait"); [ -z "$xy" ] && xy=$(find_node "$1" text "WAIT")
    [ -n "$xy" ] && adb shell input tap $xy
    sleep 2
  done
  return 0
}
shot(){ adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; }
# find NAME ATTR VALUE [contains] -> "cx cy" of the first matching node
find_node(){ python3 - "$OUT/$1.xml" "$2" "$3" "${4:-exact}" <<'PY'
import re, sys, xml.etree.ElementTree as ET
path, attr, value, mode = sys.argv[1:5]
for n in ET.parse(path).iter("node"):
    v = n.get(attr) or ""
    if (v == value) if mode == "exact" else (value in v):
        l, t, r, b = map(int, re.findall(r"\d+", n.get("bounds")))
        print((l + r) // 2, (t + b) // 2); break
PY
}
has(){ [ -n "$(find_node "$@")" ]; }
tap(){ local xy; xy=$(find_node "$@"); [ -z "$xy" ] && return 1; adb shell input tap $xy; }
# Dialog buttons may be shown in capitals depending on the Android version.
tap_btn(){ tap "$1" text "$2" || tap "$1" text "$(echo "$2" | tr '[:lower:]' '[:upper:]')"; }
has_btn(){ has "$1" text "$2" || has "$1" text "$(echo "$2" | tr '[:lower:]' '[:upper:]')"; }
open_url(){ adb shell am start -a android.intent.action.VIEW -d "'$1'" -n "$PKG/.MainActivity" >/dev/null; }
open_private(){ adb shell am start -a android.intent.action.VIEW -d "'$1'" -n "$PKG/.IncognitoActivity" >/dev/null; }
json(){ python3 -c "import json,sys; d=json.load(open('$RESULTS')); print(eval(sys.argv[1]))" "$1" 2>/dev/null; }
wait_for(){ # python expression over d, timeout seconds
  for _ in $(seq 1 "${2:-30}"); do [ "$(json "$1")" = "True" ] && return 0; sleep 1; done; return 1; }
reset_hits(){ curl -s "http://127.0.0.1:$PORT/reset" >/dev/null; }
# Prints "x startY endY" for swiping the app's scrollable list (e.g. the popup menu) upwards.
scroll_list(){ # $1 = dump name
  python3 - "$OUT/$1.xml" <<'PYEND'
import re, sys, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).iter("node"):
    if n.get("scrollable") == "true" and n.get("package") == "com.example.blockbrowser":
        l, t, r, b = map(int, re.findall(r"\d+", n.get("bounds")))
        print((l + r) // 2, b - 15, t + 15); break
PYEND
}
# Polls until the screen contains the text, so slow starts don't fail checks.
wait_text(){ # $1 = dump name, $2 = text (contains), $3 = seconds
  for _ in $(seq 1 "${3:-15}"); do dump "$1" && has "$1" text "$2" contains && return 0; sleep 1; done; return 1; }
step_result(){ json "d['steps'].get('$1',{}).get('ls','')"; }
wait_step(){ wait_for "d['steps'].get('$1',{}).get('done',False)" "${2:-30}"; }
# Lists what is on screen (texts and labels) so failures can be understood from the PR comment.
screen_text(){ python3 - "$OUT/$1.xml" <<'PYEND'
import sys, xml.etree.ElementTree as ET
items = []
try:
    for n in ET.parse(sys.argv[1]).iter("node"):
        t = (n.get("text") or "").strip(); d = (n.get("content-desc") or "").strip()
        label = t or d
        if label and label not in items: items.append(label[:40])
except Exception as e:
    items.append("(no screen dump: %s)" % e)
print(" · ".join(items[:45]))
PYEND
}
diag(){
  local crash; crash=$(adb logcat -d -b crash 2>/dev/null | grep -E "FATAL|Exception|at com\.example|Caused by" | head -12 | sed ':a;N;$!ba;s/\n/%0A/g')
  [ -n "$crash" ] && { echo "::error::[$1] crash log:%0A$crash"; echo "  - crash: $(echo "$crash" | sed 's/%0A/ ⏎ /g' | head -c 600)" >> "$SUMMARY"; }
  dump "diag-$1" >/dev/null 2>&1
  echo "  - on screen [$1]: $(screen_text "diag-$1")" >> "$SUMMARY"
  echo "  - process events: $(adb logcat -d 2>/dev/null | grep -E 'Fatal signal|has died|lowmemorykiller|lmkd|Killing [0-9]+:com.example|Force stopping com.example|ANR in com.example|Process com.example.*died' | tail -6 | sed 's/^.*: //' | tr '\n' '|' | head -c 700)" >> "$SUMMARY"
  echo "  - in front: $(adb shell dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity|mResumedActivity' | sed 's/^ *//' | head -c 200)" >> "$SUMMARY"
  echo "  - task log: $(adb logcat -d 2>/dev/null | grep -E 'ActivityTaskManager|ActivityManager|BlockBrowser' | grep -iE 'blockbrowser|moveTaskToBack|Force|Killing|finish' | tail -5 | sed 's/^.*: //' | tr '\n' '|' | head -c 600)" >> "$SUMMARY"
  shot "diag-$1"
}
# Android shows a one-time "Viewing full screen" tip over everything; close it if present.
dismiss_tip(){ dump tip >/dev/null 2>&1 || return 0; has tip text "Viewing full screen" contains && { tap_btn tip "Got it"; sleep 2; }; return 0; }
dismiss_anr(){ dump anr >/dev/null 2>&1 || return 0; if grep -q "isn't responding\|isn&apos;t responding" "$OUT/anr.xml"; then tap_btn anr "Wait"; sleep 2; fi; }
dismiss_anr

# Brings the browser to the front with no menu, panel or dialog open, so each section starts clean.
reset_ui(){
  adb shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1; sleep 2
  for _ in 1 2 3 4; do
    dump reset >/dev/null 2>&1 || break
    has reset content-desc "Address bar" && ! has reset text "Done" && return 0
    adb shell input keyevent KEYCODE_BACK; sleep 1
  done
  adb shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1; sleep 2
}

# ---- 1. Several tabs --------------------------------------------------------------------------------
open_url "$SITE/page?n=1"; sleep 6
open_url "$SITE/page?n=2"; sleep 3
open_url "$SITE/page?n=3"; sleep 4
if wait_for "all(d['hits'].get(k,0)>=1 for k in ('1','2','3'))" 30; then pass "Opened three pages from other apps, each in a new tab"; else fail "Not all pages loaded: $(json "d['hits']")"; diag tabs; fi
for _ in $(seq 1 10); do dump tabs && has tabs content-desc "4 open tabs, switch tabs" && break; sleep 1; done
if has tabs content-desc "4 open tabs, switch tabs"; then pass "Tab button shows 4 tabs (home + 3)"; else fail "Tab button doesn't show 4 tabs"; diag tabs; fi

# ---- 2. Tab switcher: close and undo --------------------------------------------------------------------
tap tabs content-desc "open tabs, switch tabs" contains
for _ in $(seq 1 8); do sleep 1; dump switcher && has switcher content-desc "Current tab: Page 3" && has switcher content-desc "Tab: Page 1" && break; done
shot 1-switcher
if has switcher content-desc "Current tab: Page 3" && has switcher content-desc "Tab: Page 1"; then pass "Tab switcher lists the tabs with the current one marked"
else fail "Tab switcher contents unexpected"; diag switcher; fi
tap switcher content-desc "Close Page 2"
for _ in $(seq 1 6); do sleep 1; dump closed && ! has closed content-desc "Tab: Page 2" && has_btn closed "Undo" && break; done
if ! has closed content-desc "Tab: Page 2" && has_btn closed "Undo"; then pass "Closing a tab removes it and offers Undo"; else fail "Close/Undo bar not as expected"; diag closed; fi
tap_btn closed "Undo"
for _ in $(seq 1 6); do sleep 1; dump undone && has undone content-desc "Tab: Page 2" && break; done
if has undone content-desc "Tab: Page 2" && has undone content-desc "Current tab: Page 3"; then pass "Undo restored the closed tab without switching away from the current one"
else fail "Undo did not restore the tab as expected"; diag undone; fi
tap undone content-desc "Done"; sleep 1

# ---- 3. Session restore after force-stop ---------------------------------------------------------------
read -r SW SH < <(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr 'x' ' ')
echo "screen ${SW}x${SH}"
dump pre-scroll >/dev/null   # clears any emulator dialog before swiping
for _ in 1 2 3; do adb shell input swipe $((SW / 2)) $((SH * 3 / 4)) $((SW / 2)) $((SH / 4)) 300; sleep 1; done
sleep 3
adb shell am force-stop "$PKG"; sleep 1
reset_hits
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
if wait_for "d['scroll'].get('3') is not None" 40; then
  sleep 10   # the page reports again at 6, 9 and 12 s; use the latest
  Y=$(json "d['scroll'].get('3')")
  echo "  - restore log: $(adb logcat -d -s BlockBrowserTest:I | grep 'scroll restore' | tail -2 | sed 's/^.*BlockBrowserTest: //' | tr '\n' '|')" >> "$SUMMARY"
  if [ "${Y:-0}" -gt 200 ]; then pass "After restart the selected tab (Page 3) reloaded at its scroll position (y=$Y)"
  else fail "Scroll position not restored (y=$Y; saved session: $(adb shell run-as "$PKG" cat shared_prefs/browser.xml | grep -o 'page?n=3[^}]*' | head -1 | head -c 120))"; fi
else fail "Selected tab did not reload after restart"; diag restore; fi
sleep 3
OTHERS=$(json "sorted(k for k in d['hits'] if k!='3')")
if [ "$OTHERS" = "[]" ]; then pass "Other restored tabs were not reloaded until opened (lazy restore)"; else fail "Background tabs reloaded on restart: $OTHERS"; fi
for _ in $(seq 1 10); do dump restored && has restored content-desc "Address bar" && break; sleep 1; done
# The emulator's Google Play services process sometimes crashes; Android then also closes apps using its
# font provider (WebView does). That isn't Block Browser failing, so note it and relaunch once.
if ! has restored content-desc "Address bar" && adb logcat -d | grep -q "dying proc com.google.android.gms"; then
  echo "  - note: Google Play services crashed on the emulator and Android closed the app with it; relaunched" >> "$SUMMARY"
  adb shell am start -W -n "$PKG/.MainActivity" >/dev/null; sleep 4
  for _ in $(seq 1 10); do dump restored && has restored content-desc "Address bar" && break; sleep 1; done
fi
if has restored content-desc "4 open tabs, switch tabs"; then pass "All 4 tabs restored"; else fail "Tab count wrong after restart"; diag restored; fi

# ---- 4. Address-bar suggestions -------------------------------------------------------------------------
adb logcat -c
dump restored >/dev/null
tap restored content-desc "Address bar"; sleep 2
adb shell input text "page"; sleep 4
SUGG=$(adb logcat -d -s BlockBrowserTest:I | grep "suggestions for 'page'" | tail -1)
echo "$SUGG"
if echo "$SUGG" | grep -q "Page 1" && echo "$SUGG" | grep -q "Page 3"; then pass "Typing 'page' suggests visited pages by title"; else fail "History suggestions missing: ${SUGG:-none}"; fi
dump suggest; shot 2-suggestions
adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_BACK; sleep 1

# ---- 5. Download from a signed-in site ----------------------------------------------------------------------
NAME="bbtest-$API-$RANDOM.txt"
open_url "$SITE/login?who=dl"
wait_for "'dl' in d['stored']" 20 || fail "Login page for the download test didn't load"
open_url "$SITE/download?name=$NAME"
if wait_for "any(x['name']=='$NAME' for x in d['downloads'])" 30; then
  COOKIE=$(json "[x['cookie'] for x in d['downloads'] if x['name']=='$NAME'][-1]")
  if echo "$COOKIE" | grep -q "bbsession=dl"; then pass "Download request carried the site's login cookie"; else fail "Download request had no login cookie (got '$COOKIE')"; fi
else fail "The download never reached the server"; diag download; fi
FOUND=""
for _ in $(seq 1 20); do FOUND=$(adb shell ls "/sdcard/Download/$NAME" 2>/dev/null | tr -d '\r'); [ -n "$FOUND" ] && break; sleep 1; done
if [ -n "$FOUND" ]; then pass "Downloaded file saved to Downloads"; else fail "Downloaded file not found in /sdcard/Download"; fi

# ---- 6. Site permissions remembered ---------------------------------------------------------------------------
adb logcat -c
open_url "$SITE/geo?step=geo1"
if wait_text geo1 "use your location?" 20; then
  shot 3-location-prompt
  pass "Website location request shows Block Browser's prompt"
  tap_btn geo1 "Allow"
  if wait_step geo1 30; then
    R=$(step_result geo1); [ "$R" != "err1" ] && pass "Allowed: the site got location access (result: $R)" || fail "Site was denied after Allow"
  else fail "Location page never reported"; fi
  open_url "$SITE/geo?step=geo2"
  wait_step geo2 30
  PROMPTS=$(adb logcat -d -s BlockBrowserTest:I | grep -c "location prompt shown")
  if [ "$PROMPTS" -eq 1 ]; then pass "The remembered decision was used without asking again"
  else
    fail "Asked again although the decision was remembered ($PROMPTS prompts)"
    echo "  - log: $(adb logcat -d -s BlockBrowserTest:I | grep location | tail -6 | sed 's/^.*BlockBrowserTest: //' | tr '\n' '|')" >> "$SUMMARY"
    dump geo2; tap_btn geo2 "Allow"
  fi
  wait_step geo2 30 && [ "$(step_result geo2)" != "err1" ] && pass "Second visit got location access" || fail "Second visit result: $(step_result geo2)"
  if [ "$API" -ge 28 ]; then
    open_private "$SITE/geo?step=geo3"
    if wait_text geo3 "use your location?" 25; then
      pass "Private window asks again (normal 'Allow' decisions aren't carried over)"
      tap_btn geo3 "Block"
      wait_step geo3 30 && [ "$(step_result geo3)" = "err1" ] && pass "Blocking in private denied the site" || fail "Private block result: $(step_result geo3)"
    else fail "Private window used the normal window's permission"; diag geo3; fi
    adb shell input keyevent KEYCODE_BACK; sleep 3
  fi
else fail "No location prompt appeared"; diag geo1; fi

# ---- 7. Full screen kept across navigation --------------------------------------------------------------
adb shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 2
dump menu0; tap menu0 text "⋮"; sleep 2; dump menu1
if tap menu1 text "Full screen"; then
  sleep 3
  dismiss_tip
  open_url "$SITE/page?n=6"; sleep 5; dump fs
  if has fs content-desc "Address bar"; then fail "Full screen was lost after navigating"; diag fs; else pass "Full screen stays on when a new page opens"; fi
  adb shell input keyevent KEYCODE_BACK; sleep 2
  # Back leaves full screen; the dismissal tip on Android 15 may need a second Back
  dump fsoff; has fsoff content-desc "Address bar" || { adb shell input keyevent KEYCODE_BACK; sleep 2; }
else fail "'Full screen' not found in the menu"; diag menu1; fi

# ---- 8. Clear browsing data --------------------------------------------------------------------------------
dismiss_tip
open_url "$SITE/login?who=clearme"; wait_for "'clearme' in d['stored']" 20
dismiss_tip
dump c0; tap c0 text "⋮"; sleep 2; dump c1
for _ in 1 2 3 4; do
  has c1 text "Clear browsing data" && break
  read -r SX SY EY < <(scroll_list c1) || true
  [ -z "${SX:-}" ] && break
  adb shell input swipe "$SX" "$SY" "$SX" "$EY" 500   # scroll the menu
  sleep 1; dump c1
done
if tap c1 text "Clear browsing data"; then
  sleep 2; dump c2; shot 4-clear-dialog
  if tap_btn c2 "Clear"; then
    sleep 3
    open_url "$SITE/check?step=after-clear"
    if wait_step after-clear 30; then
      C=$(json "d['steps']['after-clear'].get('cookie','')"); L=$(step_result after-clear)
      [ -z "$C" ] && [ -z "$L" ] && pass "Clearing browsing data removed cookies and site storage" || fail "After clearing: cookie='$C' localStorage='$L'"
    else fail "Check page after clearing never reported"; fi
    adb exec-out run-as "$PKG" cat databases/history.db > "$OUT/history.db" 2>/dev/null
    adb exec-out run-as "$PKG" cat databases/history.db-wal > "$OUT/history.db-wal" 2>/dev/null
    N=$(python3 -c "import sqlite3;print(sqlite3.connect('$OUT/history.db').execute('select count(*) from history where url not like \"%after-clear%\"').fetchone()[0])" 2>/dev/null)
    [ "${N:-x}" = "0" ] && pass "History cleared" || fail "History rows left after clearing: ${N:-unreadable}"
  else fail "Clear button not found"; diag c2; fi
else fail "'Clear browsing data' not found in the menu"; echo "  - menu showed: $(screen_text c1)" >> "$SUMMARY"; fi

# ---- 8b. Settings screens respond to taps
reset_ui
# Menu -> Settings -> Search engine -> DuckDuckGo, then a search from the address bar must go to DuckDuckGo.
open_menu_item(){ # $1 = dump prefix, $2 = menu label, $3 = "contains" to match part of the label
  local mode="${3:-exact}"
  dump "$1-a"; tap "$1-a" text "⋮"; sleep 2; dump "$1-b"
  for _ in 1 2 3 4; do
    has "$1-b" text "$2" "$mode" && break
    read -r SX SY EY < <(scroll_list "$1-b") || true
    [ -z "${SX:-}" ] && break
    adb shell input swipe "$SX" "$SY" "$SX" "$EY" 500; sleep 1; dump "$1-b"
  done
  tap "$1-b" text "$2" "$mode"
}
dismiss_tip
if open_menu_item set "Settings"; then
  if wait_text set-c "Search engine" 8; then
    tap set-c text "Search engine"
    if wait_text set-d "DuckDuckGo" 8; then
      shot 6-search-engines
      tap set-d text "DuckDuckGo"; sleep 2
      ENGINE=$(adb exec-out run-as "$PKG" cat shared_prefs/browser.xml | tr -d '\r' | grep -o 'name="search_engine" value="[0-9]*"' | grep -o '[0-9]*"$' | tr -d '"')
      [ "$ENGINE" = "2" ] && pass "Choosing DuckDuckGo in Settings saves it" || { fail "Search engine not saved after tapping DuckDuckGo (saved: '${ENGINE:-none}')"; diag engine; }
      dump set-e
      if has set-e text "DuckDuckGo" && has set-e text "Search engine"; then pass "Settings screen shows the chosen engine"; else fail "Settings didn't update after choosing an engine"; diag engine2; fi
      adb shell input keyevent KEYCODE_BACK; sleep 2
      dump srch; tap srch content-desc "Address bar"; sleep 1
      adb shell input text "blockbrowser%stest"; adb shell input keyevent KEYCODE_ENTER
      wait_text srch2 "duckduckgo.com" 20
      if has srch2 text "duckduckgo.com" contains; then pass "Address-bar search uses the chosen engine"; else fail "Search didn't go to DuckDuckGo"; diag search; fi
    else fail "Search engine list didn't open"; diag engines; fi
  else fail "Settings screen didn't open"; diag settings; fi
else fail "'Settings' not found in the menu"; diag menu-settings; fi
adb shell input keyevent KEYCODE_BACK; sleep 1

reset_ui
# Menu -> "Search engine: …" (directly in the menu) -> list of all 10 engines with Bing choosable.
if open_menu_item eng "Search engine: DuckDuckGo"; then
  if wait_text eng-c "Mojeek" 8; then
    N=0; for e in Google Bing DuckDuckGo "Brave Search" Startpage Ecosia Yahoo Qwant Mojeek SearXNG; do has eng-c text "$e" && N=$((N+1)); done
    shot 7-engine-list
    [ "$N" -eq 10 ] && pass "Search engine in the menu lists all 10 engines" || { fail "Search engine list shows $N of 10 engines"; diag engine-list; }
    tap eng-c text "Bing"; sleep 2
    open_menu_item eng-x "Search engine: Bing" >/dev/null; sleep 1; dump eng-e
    has eng-e text "Search engine: Bing" || has eng-e text "Bing" contains && pass "Choosing Bing from the menu's list works" || { fail "Menu didn't show Bing after choosing it"; diag engine-menu; }
    adb shell input keyevent KEYCODE_BACK; sleep 1
  else fail "Search engine list from the menu didn't open"; diag engine-open; fi
else fail "'Search engine' not in the menu"; diag menu-engine; fi

reset_ui
# Menu -> Privacy and security -> ad blocking switch toggles and is saved.
if open_menu_item priv "Privacy and security"; then
  if wait_text priv-c "Ad and tracker blocking" 8; then
    tap priv-c text "Ad and tracker blocking"; sleep 2
    AD=$(adb exec-out run-as "$PKG" cat shared_prefs/browser.xml | tr -d '\r' | grep -o 'name="adblock" value="[a-z]*"' | grep -o '[a-z]*"$' | tr -d '"')
    [ "$AD" = "false" ] && pass "Ad-blocking switch in Privacy and security works" || { fail "Ad-blocking switch didn't change the setting (saved: '${AD:-none}')"; diag adblock; }
    dump priv-d; tap priv-d text "Ad and tracker blocking"; sleep 1   # back on
  else fail "Privacy and security screen didn't open"; diag privacy; fi
else fail "'Privacy and security' not found in the menu"; diag menu-privacy; fi
adb shell input keyevent KEYCODE_BACK; sleep 1

reset_ui
# Menu -> History -> tapping an entry opens it (history was cleared above, so visit a page first).
open_url "$SITE/page?n=9"; sleep 5
open_url "$SITE/page?n=10"; sleep 5
if open_menu_item hist "History"; then
  if wait_text hist-c "Page 9" 8; then
    reset_hits
    tap hist-c text "Page 9"
    if wait_for "d['hits'].get('9',0)>=1" 15; then pass "Tapping a history entry opens the page"; else fail "Tapping a history entry did nothing"; diag history; fi
  else fail "History screen didn't list visited pages"; diag history-list; fi
else fail "'History' not found in the menu"; diag menu-history; fi

# ---- 10. Block Browser home page
reset_ui
HOME_URL="https://appassets.androidplatform.net/home"
echo "== $(date +%T) opening home page" | tee -a "$OUT/steps.txt"
open_url "$HOME_URL"
echo "== $(date +%T) adb state: $(adb get-state 2>&1)" | tee -a "$OUT/steps.txt"
if wait_text home "Ads and trackers blocked" 15; then
  pass "Home page opens (generated inside the app, no network)"
  has home text "This session" && has home text "Today" && pass "Home page shows blocking counts (today, this session, all time)" || { fail "Home page blocking counts missing"; diag home-blocked; }
  dump home2
  if tap home2 text "or type a URL" contains || tap home2 class "android.widget.EditText"; then
    sleep 1; adb shell input text "blockbrowser%shome%stest"; adb shell input keyevent KEYCODE_ENTER
    if wait_text home3 "bing.com" 20 || has home3 text "duckduckgo.com" contains; then pass "Searching from the home page goes to the chosen search engine"
    else fail "Home page search didn't reach the search engine"; diag home-search; fi
  else fail "Home page search box not found"; diag home-box; fi
else fail "Home page didn't load"; diag home; fi

# ---- Screen tour: a screenshot of every screen, for reviewing the design (no pass/fail) ------------------
reset_ui
mkdir -p "$OUT/tour"
tshot(){ sleep 2; adb exec-out screencap -p > "$OUT/tour/$1.png" 2>/dev/null || true; }
back(){ adb shell input keyevent KEYCODE_BACK; sleep 1; }
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null; sleep 2; dismiss_tip
open_url "$SITE/page?n=11"; sleep 5
tshot 01-browser
dump t0; tap t0 text "⋮"; tshot 02-menu-top
dump t1; read -r SX SY EY < <(scroll_list t1) || true; [ -n "${SX:-}" ] && adb shell input swipe "$SX" "$SY" "$SX" "$EY" 500; tshot 03-menu-bottom
back
for item in "Settings:04-settings" "Privacy and security:05-privacy" "History:06-history" "Bookmarks:07-bookmarks" "Downloads:08-downloads" "Recently closed tabs:09-recently-closed" "Blocking statistics:10-statistics"; do
  label="${item%%:*}"; name="${item##*:}"
  reset_ui; if open_menu_item "tour-$name" "$label"; then tshot "$name"; fi
done
open_menu_item tour-eng "Search engine:" contains
tshot 11-search-engines; back
dump ts; tap ts content-desc "Site information" contains && tshot 12-site-info && back
dump tt; tap tt content-desc "switch tabs" contains && tshot 13-tab-switcher && back
if open_menu_item tour-clear "Clear browsing data"; then tshot 14-clear-dialog; back; fi
if open_menu_item tour-hist "History"; then dump th; tap th text "Delete" && tshot 15-history-delete-dialog; back; back; fi
dump tf; tap tf text "⋮"; sleep 1; dump tf2; tap tf2 content-desc "Add bookmark" && tshot 16-bookmarked-snackbar
if open_menu_item tour-find "Find in page"; then adb shell input text "Paragraph"; tshot 17-find-bar; back; back; fi
open_url "http://localhost:1/unreachable"; sleep 4; tshot 18-error-page
open_url "https://appassets.androidplatform.net/home"; sleep 4; tshot 19-home
adb shell input swipe 500 1600 500 400 400; sleep 1; tshot 20-home-scrolled
if [ "$API" -ge 28 ]; then open_private "https://appassets.androidplatform.net/home"; sleep 6; tshot 21-private-home; back; fi
echo "Tour screenshots: $(ls "$OUT/tour" | wc -l)"

# ---- 9. A page's renderer dies --------------------------------------------------------------------------------
open_url "$SITE/page?n=7"; sleep 4
APP_PID=$(adb shell pidof "$PKG" | tr -d '\r')
if adb root >/dev/null 2>&1 && sleep 3 && adb wait-for-device && [ "$(adb shell id -u | tr -d '\r')" = "0" ]; then
  adb reverse "tcp:$PORT" "tcp:$PORT" >/dev/null
  # WebView renderers run as isolated "sandboxed_process" services. The emulator runs no other browser, so kill them all.
  RPIDS=$(adb shell ps -A -o PID,NAME 2>/dev/null | grep -E "sandboxed_process" | awk '{print $1}' | tr -d '\r' | tr '\n' ' ')
  if [ -n "${RPIDS// /}" ]; then
    adb logcat -c
    adb shell kill -9 $RPIDS; sleep 6
    NOW_PID=$(adb shell pidof "$PKG" | tr -d '\r')
    if [ -n "$NOW_PID" ] && [ "$NOW_PID" = "$APP_PID" ]; then pass "App kept running when the page renderer was killed"; else fail "App died with its renderer (pid $APP_PID -> ${NOW_PID:-none})"; diag renderer; fi
    adb logcat -d -s BlockBrowser:W | grep -q "renderer gone" && pass "Renderer loss was handled (tab rebuilt with an error page)" || fail "No renderer-gone handling logged"
    dump renderer; shot 5-renderer
  else skip "No separate WebView renderer process on this emulator (WebView runs in the app process)"; fi
  adb unroot >/dev/null 2>&1; sleep 3; adb wait-for-device
  adb reverse "tcp:$PORT" "tcp:$PORT" >/dev/null   # adbd restarted, so the port forwarding must be set again
else skip "adb root not available; renderer crash test skipped"; fi

{ echo; echo "Passed: $PASSED"; } >> "$SUMMARY"
[ -n "${GITHUB_STEP_SUMMARY:-}" ] && cat "$SUMMARY" >> "$GITHUB_STEP_SUMMARY"
if [ "$FAILED" -eq 0 ]; then echo "::notice::Browser check: all $PASSED checks passed on API $API"
else echo "::error::Browser check: failures on API $API (see errors above)"; fi
exit $FAILED

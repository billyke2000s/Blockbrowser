#!/usr/bin/env bash
# Video-stream download test, run on an Android emulator by .github/workflows/ui-check.yml (job stream-download-check).
# Uses the test-only "uitest" APK, test streams from scripts/make-test-streams.sh and the local site
# scripts/stream-server.py (reached through adb reverse).
#
# For each kind of stream a web page loads with a script, it opens ⋮ → Download video, picks a quality where asked,
# waits for the MP4 in Downloads, pulls it and checks it with ffprobe (video and audio present, size, duration).
# Also checks: encrypted and live streams are refused, a normal window's sign-in cookie is sent, a private
# window's download doesn't carry the normal window's cookie, and the Downloads screen lists the video.
set -uo pipefail

APK="app/build/outputs/apk/uitest/app-uitest.apk"
PKG="com.example.blockbrowser"
PORT=8766
SITE="http://localhost:$PORT"
OUT="stream-check-output"
STREAMS="${STREAMS_DIR:-$PWD/stream-test-files}"
RESULTS="$OUT/requests.json"
SUMMARY="$OUT/summary.md"
mkdir -p "$OUT"; : > "$SUMMARY"
FAILED=0; PASSED=0
fail(){ echo "::error::$*"; echo "- ❌ $*" >> "$SUMMARY"; FAILED=1; }
pass(){ echo "PASS: $*"; echo "- ✅ $*" >> "$SUMMARY"; PASSED=$((PASSED+1)); }
skip(){ echo "SKIP: $*"; echo "- ⏭️ $*" >> "$SUMMARY"; }

API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
echo "### Video stream downloads — Android API $API" >> "$SUMMARY"

[ -f "$STREAMS/ts/master.m3u8" ] || ./scripts/make-test-streams.sh "$STREAMS" || { fail "Couldn't make test streams"; exit 1; }
python3 scripts/stream-server.py "$PORT" "$RESULTS" "$STREAMS" 2> "$OUT/server.log" &
SERVER=$!
trap 'kill $SERVER 2>/dev/null' EXIT
sleep 1
adb reverse "tcp:$PORT" "tcp:$PORT" || { fail "adb reverse failed"; exit 1; }
sleep 15
adb uninstall "$PKG" >/dev/null 2>&1
adb install -r "$APK" || { fail "APK install failed"; exit 1; }
# Android 8-9: grant both storage permissions, as the app's own prompt does (granting only "write" leaves the
# shared folder read-only for the app).
if [ "$API" -le 28 ]; then
  adb shell pm grant "$PKG" android.permission.WRITE_EXTERNAL_STORAGE || fail "Couldn't grant the write-storage permission"
  adb shell pm grant "$PKG" android.permission.READ_EXTERNAL_STORAGE || fail "Couldn't grant the read-storage permission"
fi
adb shell rm -f "/sdcard/Download/Clip-*" 2>/dev/null

# Homepage on the local site, so the test doesn't depend on internet access.
cat > "$OUT/browser.xml" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><string name="homepage">$SITE/</string></map>
EOF
adb push "$OUT/browser.xml" /data/local/tmp/bb-browser.xml >/dev/null
adb shell run-as "$PKG" mkdir -p shared_prefs
adb shell run-as "$PKG" cp /data/local/tmp/bb-browser.xml shared_prefs/browser.xml
adb logcat -c
adb logcat -v time > "$OUT/logcat.txt" 2>&1 &
LOGCAT=$!
trap 'kill $SERVER $LOGCAT 2>/dev/null' EXIT

# ---- helpers (same approach as scripts/browser-check.sh) --------------------------------------------------
dump_raw(){ for _ in 1 2 3 4 5; do adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1 && return 0; sleep 2; done; return 1; }
dump(){
  for _ in 1 2 3 4; do
    dump_raw "$1" || return 1
    grep -q "isn't responding\|isn&apos;t responding" "$OUT/$1.xml" || return 0
    local xy; xy=$(find_node "$1" text "Wait"); [ -z "$xy" ] && xy=$(find_node "$1" text "WAIT")
    [ -n "$xy" ] && adb shell input tap $xy
    sleep 2
  done
  return 0
}
shot(){ adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; }
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
tap_btn(){ tap "$1" text "$2" || tap "$1" text "$(echo "$2" | tr '[:lower:]' '[:upper:]')"; }
has_btn(){ has "$1" text "$2" || has "$1" text "$(echo "$2" | tr '[:lower:]' '[:upper:]')"; }
open_url(){ adb shell am start -a android.intent.action.VIEW -d "'$1'" -n "$PKG/.MainActivity" >/dev/null; }
open_private(){ adb shell am start -a android.intent.action.VIEW -d "'$1'" -n "$PKG/.IncognitoActivity" >/dev/null; }
scroll_list(){ python3 - "$OUT/$1.xml" <<'PYEND'
import re, sys, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).iter("node"):
    if n.get("scrollable") == "true" and n.get("package") == "com.example.blockbrowser":
        l, t, r, b = map(int, re.findall(r"\d+", n.get("bounds")))
        print((l + r) // 2, b - 15, t + 15); break
PYEND
}
wait_text(){ for _ in $(seq 1 "${3:-15}"); do dump "$1" && has "$1" text "$2" contains && return 0; sleep 1; done; return 1; }
screen_text(){ python3 - "$OUT/$1.xml" <<'PYEND'
import sys, xml.etree.ElementTree as ET
items = []
try:
    for n in ET.parse(sys.argv[1]).iter("node"):
        label = (n.get("text") or "").strip() or (n.get("content-desc") or "").strip()
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
  echo "  - app log: $(adb logcat -d -s BlockBrowserStream:* BlockBrowserTest:I BlockBrowser:W | grep -vE '^\s*$|^-+ beginning|\sat ' | tail -8 | sed 's/^[^:]*: //' | tr '\n' '|' | head -c 1500)" >> "$SUMMARY"
  shot "diag-$1"
}
open_menu_item(){ # $1 = dump prefix, $2 = menu label
  dump "$1-a"; tap "$1-a" text "⋮"; sleep 2; dump "$1-b"
  # A window that has only just opened can miss the first tap; tap again if the menu isn't showing.
  if ! has "$1-b" text "Bookmarks" && ! has "$1-b" text "$2"; then sleep 2; dump "$1-a"; tap "$1-a" text "⋮"; sleep 2; dump "$1-b"; fi
  for _ in 1 2 3 4; do
    has "$1-b" text "$2" && break
    read -r SX SY EY < <(scroll_list "$1-b") || true
    [ -z "${SX:-}" ] && break
    adb shell input swipe "$SX" "$SY" "$SX" "$EY" 500; sleep 1; dump "$1-b"
  done
  tap "$1-b" text "$2"
}
requests(){ python3 -c "import json,sys; d=json.load(open('$RESULTS')); print(eval(sys.argv[1]))" "$1" 2>/dev/null; }
wait_request(){ for _ in $(seq 1 "${2:-30}"); do [ "$(requests "any(r['path']=='$1' for r in d['requests'])")" = "True" ] && return 0; sleep 1; done; return 1; }
# Android 13+ asks once for notification permission when the first stream download starts.
allow_notifications(){
  [ "$API" -ge 33 ] || return 0
  for _ in 1 2 3 4 5 6; do
    dump perm >/dev/null 2>&1
    if has perm resource-id "com.android.permissioncontroller:id/permission_allow_button"; then
      tap perm resource-id "com.android.permissioncontroller:id/permission_allow_button"; pass "Asked for notification permission before the first stream download"; return 0
    fi
    sleep 1
  done
}
# Waits for /sdcard/Download/NAME, pulls it and checks it with ffprobe. $1 = file name, $2 = expected width.
check_file(){
  local name="$1" width="$2" found=""
  for _ in $(seq 1 120); do found=$(adb shell ls "/sdcard/Download/$name" 2>/dev/null | tr -d '\r'); [ -n "$found" ] && break; sleep 1; done
  if [ -z "$found" ]; then fail "$name was not saved to Downloads within 2 minutes"; diag "missing-$name"; return 1; fi
  sleep 2
  adb pull "/sdcard/Download/$name" "$OUT/$name" >/dev/null 2>&1
  local info; info=$(ffprobe -v error -show_entries format=format_name,duration:stream=codec_type,codec_name,width,height -of json "$OUT/$name" 2>&1)
  echo "$info" > "$OUT/$name.ffprobe.json"
  python3 - "$OUT/$name.ffprobe.json" "$width" "$name" <<'PY' > "$OUT/check.txt"
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception as e:
    print("FAIL unreadable by ffprobe: %s" % open(sys.argv[1]).read()[:200]); sys.exit()
streams = d.get("streams", [])
v = [s for s in streams if s.get("codec_type") == "video"]
a = [s for s in streams if s.get("codec_type") == "audio"]
dur = float(d.get("format", {}).get("duration", 0))
fmt = d.get("format", {}).get("format_name", "")
problems = []
if "mp4" not in fmt: problems.append("not an MP4 (%s)" % fmt)
if not v: problems.append("no video track")
elif v[0].get("width") != int(sys.argv[2]): problems.append("width %s, expected %s" % (v[0].get("width"), sys.argv[2]))
if not a: problems.append("no audio track")
if not 11.0 <= dur <= 13.0: problems.append("duration %.2f s, expected 12 s" % dur)
summary = "%s %dx%d, %s + %s, %.1f s" % (sys.argv[3], v[0].get("width", 0) if v else 0, v[0].get("height", 0) if v else 0,
                                        v[0].get("codec_name") if v else "-", a[0].get("codec_name") if a else "-", dur)
print(("FAIL " + "; ".join(problems) + " — " + summary) if problems else ("OK " + summary))
PY
  local res; res=$(cat "$OUT/check.txt")
  case "$res" in OK*) pass "Saved a playable MP4: ${res#OK }"; return 0;; *) fail "${res#FAIL }"; return 1;; esac
}
# Opens the page that plays stream $1, titled $2, in a normal ($3=normal) or private window.
download_from_page(){
  local stream="$1" title="$2" window="${3:-normal}" playlist
  case "$stream" in *fmp4) playlist=fmp4/master.m3u8;; *single) playlist=single/index.m3u8;; *enc) playlist=enc/index.m3u8;; *live) playlist=live/index.m3u8;; *) playlist=ts/master.m3u8;; esac
  local prefix=""; [ "${stream#p/}" != "$stream" ] && prefix="/p"
  if [ "$window" = private ]; then open_private "$SITE/watch?s=$stream&t=$title"; else open_url "$SITE/watch?s=$stream&t=$title"; fi
  if ! wait_request "$prefix/streams/$playlist" 30; then fail "[$title] the page never loaded its stream"; diag "page-$title"; return 1; fi
  sleep 2
  if ! open_menu_item "dl-$title" "Download video"; then fail "[$title] 'Download video' not found in the menu"; diag "menu-$title"; return 1; fi
  return 0
}

dump anr >/dev/null 2>&1
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null; sleep 4

# ---- 0. Sign in to the test site in the normal window -------------------------------------------------------
open_url "$SITE/login"
wait_request "/login" 30 || fail "Login page didn't load"
sleep 2

# ---- 1. MPEG-TS stream with two qualities ------------------------------------------------------------------
if download_from_page ts Clip-TS; then
  if wait_text q1 "Choose quality" 20; then
    shot 1-quality
    if has q1 text "360p" contains && has q1 text "240p" contains; then pass "Offered both qualities (360p and 240p)"; else fail "Quality list unexpected: $(screen_text q1)"; fi
    tap q1 text "360p" contains
    allow_notifications
    check_file "Clip-TS.mp4" 640
    N=$(requests "len([r for r in d['requests'] if r['path'].startswith('/streams/ts/v0_')])")
    [ "${N:-0}" -ge 4 ] && pass "Downloaded every piece of the chosen quality ($N pieces)" || fail "Only ${N:-0} pieces of the 360p stream were requested"
    W=$(requests "len([r for r in d['requests'] if r['path'].startswith('/streams/ts/v1_')])")
    [ "${W:-0}" = 0 ] && pass "Didn't download the quality that wasn't chosen" || fail "Also fetched $W pieces of the 240p stream"
    C=$(requests "all('bbsession=normal' in r['cookie'] for r in d['requests'] if r['path'].startswith('/streams/ts/v0_'))")
    [ "$C" = "True" ] && pass "Stream requests carried the site's sign-in cookie" || fail "Stream requests were missing the sign-in cookie"
  else fail "[Clip-TS] quality chooser didn't appear"; diag quality; fi
fi

# ---- 2. Fragmented MP4 with separate audio (like X/Twitter) -------------------------------------------------
if download_from_page fmp4 Clip-FMP4; then
  allow_notifications
  check_file "Clip-FMP4.mp4" 640 && pass "Separate video and audio tracks were combined into one file"
fi

# ---- 3. One file addressed by byte ranges -----------------------------------------------------------------
if download_from_page single Clip-Single; then
  allow_notifications
  check_file "Clip-Single.mp4" 640
  R=$(requests "len([r for r in d['requests'] if r['path']=='/streams/single/index.ts' and r['range'].startswith('bytes=')])")
  [ "${R:-0}" -ge 3 ] && pass "Byte-range pieces requested with Range headers ($R)" || fail "Expected ranged requests, saw ${R:-0}"
fi

# ---- 4. Encrypted and live streams are refused ----------------------------------------------------------------
for kind in enc live; do
  adb logcat -c
  if download_from_page "$kind" "Clip-$kind"; then
    sleep 6
    if adb logcat -d -s BlockBrowserTest:I | grep -q "stream refused"; then
      MSG=$(adb logcat -d -s BlockBrowserTest:I | grep "stream refused" | tail -1 | sed 's/^.*stream refused: //')
      pass "$kind stream refused with: \"$MSG\""
    else fail "$kind stream was not refused"; diag "refuse-$kind"; fi
    [ -z "$(adb shell ls "/sdcard/Download/Clip-$kind.mp4" 2>/dev/null | tr -d '\r')" ] && pass "Nothing saved for the $kind stream" || fail "A file was saved for the $kind stream"
  fi
done

# ---- 5. Private window ------------------------------------------------------------------------------------------
if [ "$API" -ge 28 ]; then
  if download_from_page p/ts Clip-Private private; then
    if wait_text q5 "Choose quality" 20; then
      tap q5 text "240p" contains
      allow_notifications
      check_file "Clip-Private.mp4" 426
      LEAK=$(requests "[r['cookie'] for r in d['requests'] if r['path'].startswith('/p/streams/') and 'bbsession=normal' in r['cookie']]")
      [ "$LEAK" = "[]" ] && pass "Private download didn't send the normal window's sign-in cookie" || fail "Private download sent the normal cookie: $LEAK"
      S=$(adb exec-out run-as "$PKG" sh -c 'cat files/stream-jobs/*.json' 2>/dev/null)
      echo "$S" | grep -q '/p/streams' && fail "The private download's address was stored on disk" || pass "The private download's address isn't stored on disk"
    else fail "[Clip-Private] quality chooser didn't appear"; diag private-quality; fi
    adb shell input keyevent KEYCODE_BACK; sleep 1
    adb shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 2
  fi
fi

# ---- 6. Downloads screen lists the videos -------------------------------------------------------------------------
adb shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 2
if open_menu_item panel "Downloads"; then
  if wait_text panel-c "Clip-TS.mp4" 10; then
    shot 6-downloads
    pass "Downloads screen lists the stream download"
    has panel-c text "360p" contains && pass "Downloads screen shows the chosen quality" || fail "Quality not shown on the Downloads screen: $(screen_text panel-c)"
  else fail "Downloads screen doesn't list Clip-TS.mp4"; diag panel; fi
else fail "'Downloads' not found in the menu"; diag panel-menu; fi

# ---- 7. Notification -------------------------------------------------------------------------------------------
if adb shell dumpsys notification --noredact 2>/dev/null | grep -q "Download complete"; then pass "A 'Download complete' notification was shown"
elif [ "$API" -ge 33 ]; then fail "No 'Download complete' notification found"
else skip "Notification text not readable with dumpsys on this Android version"; fi

adb logcat -d -s BlockBrowserStream:* > "$OUT/stream-log.txt" 2>/dev/null
{ echo; echo "Passed: $PASSED"; } >> "$SUMMARY"
[ -n "${GITHUB_STEP_SUMMARY:-}" ] && cat "$SUMMARY" >> "$GITHUB_STEP_SUMMARY"
if [ "$FAILED" -eq 0 ]; then echo "::notice::Stream download check: all $PASSED checks passed on API $API"
else echo "::error::Stream download check: failures on API $API (see errors above)"; fi
exit $FAILED

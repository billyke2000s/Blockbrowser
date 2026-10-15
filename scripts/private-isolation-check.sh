#!/usr/bin/env bash
# Private-browsing isolation test, run on an Android emulator by .github/workflows/ui-check.yml.
# Uses the test-only "uitest" APK and a local website (scripts/isolation-server.py, reached via adb reverse).
#
# The site "logs in" by setting a persistent cookie, localStorage and IndexedDB, then each check page reports
# what the browser sent back. The test checks that:
#   1. Normal and private windows never see each other's cookie, localStorage or IndexedDB.
#   2. A private session keeps its own login while the window stays open.
#   3. Closing the last private window ends the private process and nothing comes back in the next one.
#   4. After the private process is killed (crash / force-stop), nothing comes back either.
#   5. Private data is deleted from disk by the main process, and normal data survives every wipe.
#   6. No private URL reaches normal history or saved session.
set -uo pipefail

APK="app/build/outputs/apk/uitest/app-uitest.apk"
PKG="com.example.blockbrowser"
PORT=8765
OUT="isolation-output"
RESULTS="$OUT/results.json"
SITE="http://localhost:$PORT"
mkdir -p "$OUT"
FAILED=0
SUMMARY="$OUT/summary.md"; : > "$SUMMARY"
PASSED=0
# GitHub shows only 10 notices per step, so passes go to the run summary page; failures stay as errors.
fail(){ echo "::error::$*"; echo "- ❌ $*" >> "$SUMMARY"; FAILED=1; }
pass(){ echo "PASS: $*"; echo "- ✅ $*" >> "$SUMMARY"; PASSED=$((PASSED+1)); }

API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
echo "Android API level: $API"
echo "### Private isolation check — Android API $API" >> "$SUMMARY"
if [ "$API" -lt 28 ]; then pass "Private browsing needs Android 9+; skipping on API $API"; exit 0; fi

python3 scripts/isolation-server.py "$PORT" "$RESULTS" 2> "$OUT/server.log" &
SERVER=$!
trap 'kill $SERVER 2>/dev/null' EXIT
sleep 1
adb reverse "tcp:$PORT" "tcp:$PORT" || { fail "adb reverse failed"; exit 1; }
sleep 15
adb install -r "$APK" || { fail "APK install failed"; exit 1; }
adb logcat -c

private_pid(){ adb shell pidof "$PKG:incognito" 2>/dev/null | tr -d '\r'; }
main_pid(){ adb shell pidof "$PKG" 2>/dev/null | tr -d '\r'; }
open_normal(){ adb shell am start -a android.intent.action.VIEW -d "'$1'" -n "$PKG/.MainActivity" >/dev/null; }
open_private(){ adb shell am start -a android.intent.action.VIEW -d "'$1'" -n "$PKG/.IncognitoActivity" >/dev/null; }

# Waits for a check step to report. Prints "cookie|localStorage|IndexedDB".
wait_step(){ # $1 = step
  for _ in $(seq 1 45); do
    if python3 - "$RESULTS" "$1" <<'PY'
import json, sys
s = json.load(open(sys.argv[1]))["steps"].get(sys.argv[2], {})
if s.get("done"):
    print("%s|%s|%s" % (s.get("cookie", ""), s.get("ls", ""), s.get("idb", ""))); sys.exit(0)
sys.exit(1)
PY
    then return 0; fi
    sleep 1
  done
  echo "TIMEOUT"; return 1
}
wait_stored(){ # $1 = who
  for _ in $(seq 1 45); do
    python3 -c 'import json,sys; sys.exit(0 if sys.argv[2] in json.load(open(sys.argv[1]))["stored"] else 1)' "$RESULTS" "$1" && return 0
    sleep 1
  done; return 1
}
login(){ # $1 = normal|private, $2 = who
  if [ "$1" = normal ]; then open_normal "$SITE/login?who=$2"; else open_private "$SITE/login?who=$2"; fi
  wait_stored "$2" && pass "Logged in as '$2' in a $1 window" || fail "Login as '$2' in a $1 window did not complete"
}
# check MODE STEP EXPECTED_WHO ("" = nothing stored)
check(){
  if [ "$1" = normal ]; then open_normal "$SITE/check?step=$2"; else open_private "$SITE/check?step=$2"; fi
  local got; got=$(wait_step "$2")
  local cookie ls idb; IFS='|' read -r cookie ls idb <<< "$got"
  local want_cookie=""; [ -n "$3" ] && want_cookie="bbsession=$3"
  if [ "$got" = "TIMEOUT" ]; then fail "[$2] page never reported (is the $1 window running?)"; diag "$2"; return; fi
  if [ "$cookie" = "$want_cookie" ] && [ "$ls" = "$3" ] && [ "$idb" = "$3" ]; then
    pass "[$2] $1 window sees cookie='${cookie}' localStorage='${ls}' IndexedDB='${idb}' as expected"
  else
    fail "[$2] $1 window expected '${3:-nothing}' but saw cookie='${cookie}' localStorage='${ls}' IndexedDB='${idb}'"
  fi
}
app_files(){ # lists the app's data and cache folders
  adb shell run-as "$PKG" ls -a /data/data/$PKG /data/data/$PKG/cache /data/data/$PKG/shared_prefs 2>&1 | tr -d '\r'
}
diag(){
  local crash; crash=$(adb logcat -d -b crash 2>/dev/null | grep -E "FATAL|Exception|at com\.example|Caused by" | head -15 | sed ':a;N;$!ba;s/\n/%0A/g')
  [ -n "$crash" ] && echo "::error::[$1] crash log:%0A$crash"
  echo "::notice::[$1] processes: main=$(main_pid) private=$(private_pid)"
}

# --- 1. Log in on normal and private; neither sees the other ---------------------
login normal normal
check normal normal-1 normal
check private private-fresh ""
[ -n "$(private_pid)" ] && pass "Private window runs in its own process ($PKG:incognito)" || fail "No $PKG:incognito process"
[ "$(private_pid)" != "$(main_pid)" ] || fail "Private and normal windows share a process"
login private private
check private private-1 private
check normal normal-2 normal
check private private-2 private   # private login survives while the window is open

# --- 2. Close the private window normally -----------------------------------------
adb shell input keyevent KEYCODE_BACK   # the check tab has no history, so Back closes the private window
sleep 4
if [ -z "$(private_pid)" ]; then pass "Closing the last private window ended the private process"
else fail "Private process still running after the private window closed (pid $(private_pid))"; fi
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null   # normal window resumes and cleans up
sleep 5
app_files > "$OUT/files-after-close.txt"
if grep -qi incognito "$OUT/files-after-close.txt"; then fail "Private files still on disk after closing the private window: $(grep -i incognito "$OUT/files-after-close.txt" | tr '\n' ' ')"
else pass "Private files deleted from disk after the private window closed"; fi
check private private-after-close ""

# --- 3. Kill the private process mid-session (crash / force-stop) -------------------
login private private2
PID=$(private_pid)
if [ -n "$PID" ]; then adb shell run-as "$PKG" kill -9 "$PID" || adb shell kill -9 "$PID"; fi
sleep 3
[ -z "$(private_pid)" ] && pass "Private process killed (simulated crash)" || fail "Could not kill the private process"
check private private-after-kill ""
check normal normal-3 normal

# --- 4. Main process deletes private files left behind -----------------------------
login private private3
adb shell am force-stop "$PKG"
sleep 2
app_files > "$OUT/files-after-force-stop.txt"
echo "Files after force-stop:"; cat "$OUT/files-after-force-stop.txt"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 6
app_files > "$OUT/files-after-main-start.txt"
echo "Files after the main process started:"; cat "$OUT/files-after-main-start.txt"
if grep -qi incognito "$OUT/files-after-force-stop.txt"; then
  pass "Private files existed on disk after force-stop (expected before cleanup)"
else
  echo "::warning::No private files found after force-stop; the main-process cleanup check proves less"
fi
if grep -qi incognito "$OUT/files-after-main-start.txt"; then fail "Private files still on disk after the main process started: $(grep -i incognito "$OUT/files-after-main-start.txt" | tr '\n' ' ')"
else pass "Main process deleted leftover private files"; fi
adb logcat -d -s BlockBrowserPrivate:I | tail -5
check normal normal-4 normal
check private private-after-force-stop ""

# --- 5. Nothing private in normal history or saved session ---------------------------
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null; sleep 2
adb shell input keyevent KEYCODE_HOME; sleep 2   # onStop saves the normal session
adb shell run-as "$PKG" cat /data/data/$PKG/shared_prefs/browser.xml > "$OUT/browser-prefs.xml" 2>/dev/null
if [ ! -s "$OUT/browser-prefs.xml" ]; then fail "Could not read normal browser preferences"
elif grep -q "step=private\|who=private" "$OUT/browser-prefs.xml"; then fail "Private URLs found in normal history/session: $(grep -o '[^;]*step=private[^&;]*' "$OUT/browser-prefs.xml" | head -3 | tr '\n' ' ')"
elif grep -q "step=normal" "$OUT/browser-prefs.xml"; then pass "Normal history contains normal pages and no private ones"
else fail "Normal history did not record normal pages (test cannot prove private ones are absent)"; fi

{ echo; echo "<details><summary>App folders after force-stop, then after the main process started</summary>"; echo; echo '```'
  cat "$OUT/files-after-force-stop.txt"; echo "--- after main process start ---"; cat "$OUT/files-after-main-start.txt"; echo '```'; echo "</details>"; } >> "$SUMMARY"
echo "Results:"; cat "$RESULTS"
[ -n "${GITHUB_STEP_SUMMARY:-}" ] && cat "$SUMMARY" >> "$GITHUB_STEP_SUMMARY"
if [ "$FAILED" -eq 0 ]; then echo "::notice::Private isolation: all $PASSED checks passed on API $API"
else echo "::error::Private isolation: failures on API $API (see errors above)"; fi
exit $FAILED

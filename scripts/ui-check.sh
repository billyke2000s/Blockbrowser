#!/usr/bin/env bash
# Automated UI check, run on an Android emulator by .github/workflows/ui-check.yml.
# Checks:
#   1. The toolbar (address bar) starts below the status bar, not underneath it.
#   2. Menu -> "Full screen" hides the toolbar and the system status bar.
#   3. Back leaves full screen and the toolbar comes back below the status bar.
# Screenshots of each state are saved to ui-check-output/ for the workflow to upload.
set -uo pipefail

APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="com.example.blockbrowser"
OUT="ui-check-output"
mkdir -p "$OUT"
FAILED=0

fail(){ echo "::error::$*"; FAILED=1; }
pass(){ echo "::notice::$*"; }

dump(){ # $1 = name; writes $OUT/$1.xml, retrying because WebView animations can block uiautomator
  for _ in 1 2 3 4 5; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1 && return 0
    sleep 2
  done
  return 1
}
shot(){ adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; }

# Prints "top bottom left right" of the first node matching an attribute=value, or nothing.
bounds(){ python3 - "$OUT/$1.xml" "$2" "$3" <<'PY'
import re, sys, xml.etree.ElementTree as ET
path, attr, value = sys.argv[1:4]
for n in ET.parse(path).iter("node"):
    if n.get(attr) == value:
        l, t, r, b = map(int, re.findall(r"\d+", n.get("bounds")))
        print(t, b, l, r); break
PY
}
# Bottom edge of the system status bar (0 if not visible).
statusbar_bottom(){ python3 - "$OUT/$1.xml" <<'PY'
import re, sys, xml.etree.ElementTree as ET
best = 0
for n in ET.parse(sys.argv[1]).iter("node"):
    if n.get("package") == "com.android.systemui" and "status_bar" in (n.get("resource-id") or ""):
        l, t, r, b = map(int, re.findall(r"\d+", n.get("bounds")))
        if t == 0 and b < 400: best = max(best, b)
print(best)
PY
}
# Status bar height as reported to the app (logged by MainActivity). Used when the screen dump doesn't include the status bar.
app_inset_top(){ adb logcat -d -s BlockBrowserInsets:I | grep -oE 'top=[0-9]+' | tail -1 | cut -d= -f2; }
statusbar(){ # $1 = dump name -> status bar bottom edge, from the dump or else the app's log
  local v; v=$(statusbar_bottom "$1"); if [ "$v" -gt 0 ]; then echo "$v"; else v=$(app_inset_top); echo "${v:-0}"; fi; }
tap_text(){ # $1 = dump name, $2 = text
  read -r t b l r < <(bounds "$1" text "$2") || return 1
  adb shell input tap $(( (l + r) / 2 )) $(( (t + b) / 2 ))
}

# Report a crash and what is on screen as annotations (logs/artifacts may not be viewable).
diag(){ # $1 = dump name
  local pid; pid=$(adb shell pidof "$PKG" | tr -d '\r')
  [ -z "$pid" ] && echo "::error::[$1] app process is not running"
  local crash; crash=$(adb logcat -d -b crash 2>/dev/null | grep -E "FATAL|Exception|Error|at com\.example|Caused by" | head -15 | sed ':a;N;$!ba;s/\n/%0A/g')
  [ -n "$crash" ] && echo "::error::[$1] crash log:%0A$crash"
  [ -f "$OUT/$1.xml" ] && echo "::notice::[$1] on screen: $(python3 - "$OUT/$1.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
items=[]
for n in ET.parse(sys.argv[1]).iter("node"):
    c=n.get("class","").split(".")[-1]; t=(n.get("text") or "")[:25]
    if c in ("EditText","TextView","Button","WebView") or n.get("package")=="com.android.systemui":
        items.append(f"{c}:{t}@{n.get('bounds')}" if t else f"{c}@{n.get('bounds')}({n.get('package').split('.')[-1]})")
print(" | ".join(items[:20]))
PY
)"
}
echo "Android API level: $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
adb install -r "$APK" || { fail "APK install failed"; exit 1; }
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 8

# --- 1. Normal layout -------------------------------------------------------
dump normal || fail "Could not read the screen (normal)"; shot 1-normal
SB=$(statusbar normal); echo "status bar from dump: $(statusbar_bottom normal), from app log: $(app_inset_top)"
read -r ET_TOP ET_BOT _ _ < <(bounds normal class android.widget.EditText) || true
echo "status bar bottom=$SB, address bar top=${ET_TOP:-none}"
if [ -z "${ET_TOP:-}" ]; then fail "Address bar not found on screen"
elif [ "$SB" -le 0 ]; then fail "Status bar height not found (neither in screen dump nor app log)"
elif [ "$ET_TOP" -ge "$SB" ]; then pass "Toolbar is below the status bar (address bar top $ET_TOP >= status bar bottom $SB)"
else fail "Toolbar overlaps the status bar (address bar top $ET_TOP < status bar bottom $SB)"; fi

# --- 2. Enter full screen from the menu ---------------------------------------
tap_text normal "⋮" || fail "Menu button not found"
sleep 2; dump menu; shot 2-menu; diag menu
tap_text menu "Full screen" || fail "'Full screen' menu item not found"
sleep 4; dump fullscreen || fail "Could not read the screen (full screen)"; shot 3-fullscreen; diag fullscreen
FS_SB=$(statusbar fullscreen); echo "full screen status bar from dump: $(statusbar_bottom fullscreen), from app log: $(app_inset_top)"
if bounds fullscreen class android.widget.EditText | grep -q .; then fail "Address bar still visible in full screen"
else pass "Toolbar hidden in full screen"; fi
if [ "$FS_SB" -gt 0 ]; then fail "System status bar still visible in full screen"
else pass "System status bar hidden in full screen"; fi
if bounds fullscreen text "Exit full screen" | grep -q .; then pass "Exit full screen button shown"
else fail "Exit full screen button not shown"; fi

# --- 3. Back leaves full screen ------------------------------------------------
adb shell input keyevent KEYCODE_BACK
sleep 4; dump after || fail "Could not read the screen (after Back)"; shot 4-after-back; diag after
SB2=$(statusbar after)
read -r ET2_TOP _ _ _ < <(bounds after class android.widget.EditText) || true
if [ -n "${ET2_TOP:-}" ] && [ "$SB2" -gt 0 ] && [ "$ET2_TOP" -ge "$SB2" ]; then pass "Back left full screen; toolbar is back below the status bar"
else fail "After Back: address bar top=${ET2_TOP:-none}, status bar bottom=$SB2"; fi

exit $FAILED

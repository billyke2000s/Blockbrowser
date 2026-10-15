#!/usr/bin/env bash
# Checks the search-suggestion endpoints in SearchSuggestions.java still answer in a format the app understands.
# They are informal APIs, so CI reports problems as warnings instead of failing the build.
set -uo pipefail
SRC="app/src/main/java/com/example/blockbrowser/SearchSuggestions.java"
mapfile -t URLS < <(grep -oE '"https://[^"]+"' "$SRC" | tr -d '"')
BAD=0
for url in "${URLS[@]}"; do
  body=$(curl -sS --max-time 10 -A "Mozilla/5.0 (Linux; Android 14) BlockBrowser-CI" "${url}weather" 2>&1)
  count=$(python3 - "$body" <<'PY'
import json, sys
text = sys.argv[1].strip()
out = []
try:
    root = json.loads(text)
    if isinstance(root, list) and len(root) >= 2 and isinstance(root[1], list):
        out = [s for s in root[1] if isinstance(s, str)]
    elif isinstance(root, list):
        out = [o.get("phrase") for o in root if isinstance(o, dict) and isinstance(o.get("phrase"), str)]
    elif isinstance(root, dict):
        out = [i.get("value") for i in root.get("data", {}).get("items", []) if isinstance(i, dict)]
except Exception:
    pass
print(len([s for s in out if s]))
PY
)
  if [ "${count:-0}" -gt 0 ]; then echo "::notice::Suggestions OK ($count) from ${url%%\?*}"
  else echo "::warning::No usable suggestions from ${url%%\?*} — response starts: $(echo "$body" | head -c 120 | tr '\n' ' ')"; BAD=1; fi
done
# Report only: warnings above, never a failed step.
[ "$BAD" -eq 0 ] || echo "Some suggestion endpoints didn't answer usefully from CI (see warnings)."
exit 0

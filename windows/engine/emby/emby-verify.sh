#!/usr/bin/env bash
set -uo pipefail
BASE=http://127.0.0.1:8097
O=/d/deepseek_test/MediaHub_Win/windows/engine/emby
T="$O/emby-api-transcript.txt"
say() { printf '%s\n' "$*" | tee -a "$T"; }
TOKEN=$(cat "$O/.emby-token")

say ""
say "########## VERIFY: are Emby item Ids GUIDs or short ids? ##########"
say "--- GET /Items (raw, first 1200 chars) ---"
curl -sS "$BASE/Items?Recursive=true&IncludeItemTypes=Movie&api_key=$TOKEN" 2>&1 | head -c 1200 | tee -a "$T"
say ""
say "--- every Id-looking pair in the Items response ---"
curl -sS "$BASE/Items?Recursive=true&IncludeItemTypes=Movie&api_key=$TOKEN" 2>&1 \
  | grep -oE '"(Id|ServerId|Name|ItemId|MediaType)":"[^"]*"' | head -20 | sed 's/^/    /' | tee -a "$T"

say ""
say "--- GET /Users/{userId}/Items (the user-scoped browse path) ---"
USERID=a5d8c0447545470fa9e227b942569158
curl -sS "$BASE/Users/$USERID/Items?Recursive=true&IncludeItemTypes=Movie&api_key=$TOKEN" 2>&1 | head -c 700 | tee -a "$T"

say ""
say "--- GET /Items/5 (fetch the item by that id) ---"
curl -sS "$BASE/Items/5?api_key=$TOKEN" -w '\n<<HTTP %{http_code}>>' 2>&1 | head -c 700 | tee -a "$T"

say ""
say "########## server + wizard state ##########"
say "--- GET /System/Info (authenticated) ---"
curl -sS "$BASE/System/Info?api_key=$TOKEN" 2>&1 | head -c 600 | tee -a "$T"

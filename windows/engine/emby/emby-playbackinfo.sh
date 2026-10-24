#!/usr/bin/env bash
# W5-F part 2: /Users/Me shape probes, library creation, and the PlaybackInfo / PlaySessionId
# comparison against Jellyfin 12.2 (one of the W4 Jellyfin fixes assumed Jellyfin's shape).
set -uo pipefail
BASE=http://127.0.0.1:8097
O=/d/deepseek_test/MediaHub_Win/windows/engine/emby
T="$O/emby-playbackinfo-transcript.txt"
: > "$T"
say() { printf '%s\n' "$*" | tee -a "$T"; }

TOKEN=$(cat "$O/.emby-token" 2>/dev/null)
AUTH='MediaBrowser Client="MediaHubProbe", Device="Windows", DeviceId="mediahub-w5f-probe", Version="1.0.0"'
say "=== Emby part 2 $(date -Is) ==="
say "token: $TOKEN"

# user id from the login response
LOGIN=$(curl -sS -X POST "$BASE/Users/AuthenticateByName" -H 'Content-Type: application/json' \
  -H "X-Emby-Authorization: $AUTH" -d '{"Username":"mh","Pw":"mh-test-pass"}' 2>&1)
USERID=$(printf '%s' "$LOGIN" | tr -d '\n' | sed -n 's/.*"User":{[^}]*"Id":"\([^"]*\)".*/\1/p')
say "userId: $USERID"

say ""
say "########## /Users/Me variants (Emby returns 500 'Unrecognized Guid format.') ##########"
for label in "X-Emby-Token" "Authorization-with-Token" "api_key-query"; do
  say ""
  say "--- $label ---"
  case "$label" in
    "X-Emby-Token")          curl -sS "$BASE/Users/Me" -H "X-Emby-Token: $TOKEN" -w '\n<<HTTP %{http_code}>>' 2>&1 | tee -a "$T" ;;
    "Authorization-with-Token") curl -sS "$BASE/Users/Me" -H "Authorization: $AUTH, Token=\"$TOKEN\"" -w '\n<<HTTP %{http_code}>>' 2>&1 | tee -a "$T" ;;
    "api_key-query")         curl -sS "$BASE/Users/Me?api_key=$TOKEN" -w '\n<<HTTP %{http_code}>>' 2>&1 | tee -a "$T" ;;
  esac
  say ""
done

say ""
say "########## /Users/{userId} (the shape that must be used instead?) ##########"
curl -sS "$BASE/Users/$USERID" -H "X-Emby-Token: $TOKEN" -w '\n<<HTTP %{http_code}>>' 2>&1 | tee -a "$T"

say ""
say "########## library on the existing test media ##########"
say "--- POST /Library/VirtualFolders ---"
curl -sS -X POST "$BASE/Library/VirtualFolders?name=MHTest&collectionType=movies&refreshLibrary=true&api_key=$TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"LibraryOptions":{"PathInfos":[{"Path":"D:\\\\deepseek_test\\\\MediaHub_Win\\\\.tools\\\\media"}]},"Name":"MHTest","CollectionType":"movies"}' \
  -w '\n<<HTTP %{http_code}>>' 2>&1 | tee -a "$T"

say ""
say "--- POST /Library/Refresh ---"
curl -sS -X POST "$BASE/Library/Refresh?api_key=$TOKEN" -w '\n<<HTTP %{http_code}>>' 2>&1 | tee -a "$T"

say ""
say "--- waiting for the scan (up to 120s) ---"
ITEM=""
for i in $(seq 1 24); do
  sleep 5
  ITEMS=$(curl -sS "$BASE/Items?Recursive=true&IncludeItemTypes=Movie&api_key=$TOKEN" 2>&1)
  N=$(printf '%s' "$ITEMS" | grep -o '"Id"' | wc -l)
  ITEM=$(printf '%s' "$ITEMS" | tr -d '\n' | sed -n 's/.*"Items":\[{"Name":"[^"]*","ServerId":"[^"]*","Id":"\([^"]*\)".*/\1/p')
  say "  t=$((i*5))s items=$N itemId=$ITEM"
  [ -n "$ITEM" ] && break
done

if [ -n "$ITEM" ]; then
  say ""
  say "########## PlaybackInfo (THE comparison point) ##########"
  say "--- POST /Items/$ITEM/PlaybackInfo ---"
  PB=$(curl -sS -X POST "$BASE/Items/$ITEM/PlaybackInfo?UserId=$USERID&api_key=$TOKEN" \
    -H 'Content-Type: application/json' \
    -d "{\"UserId\":\"$USERID\",\"MaxStreamingBitrate\":20000000,\"StartTimeTicks\":0}" \
    -w '\n<<HTTP %{http_code}>>' 2>&1)
  printf '%s\n' "$PB" | tee -a "$T"
  say ""
  say "=== PlaySessionId LOCATION analysis ==="
  say "  top-level \"PlaySessionId\" present : $(printf '%s' "$PB" | grep -c '^{"MediaSources' >/dev/null && printf '%s' "$PB" | sed 's/.*//' ; printf '%s' "$PB" | grep -o '"PlaySessionId"' | wc -l) occurrence(s)"
  say "  --- top-level keys ---"
  printf '%s' "$PB" | tr ',' '\n' | grep -oE '^\s*"[A-Za-z]+":' | head -20 | sed 's/^/    /'
  say "  --- every PlaySessionId with 40 chars of left context ---"
  printf '%s' "$PB" | grep -oE '.{60}"PlaySessionId":"[^"]*"' | sed 's/^/    /'
else
  say "no item appeared; skipping PlaybackInfo"
fi
say ""
say "=== done $(date -Is) ==="

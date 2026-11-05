#!/usr/bin/env bash
# W5-F: drive Emby 4.10.1.0 first-run setup + auth through its HTTP API and record every
# request/response verbatim.  curl is used instead of Invoke-WebRequest because the latter
# collapsed the server's error bodies into a .NET NullReferenceException.
set -uo pipefail
BASE=http://127.0.0.1:8097
O=/d/deepseek_test/MediaHub_Win/windows/engine/emby
T="$O/emby-api-transcript.txt"
: > "$T"

say() { printf '%s\n' "$*" | tee -a "$T"; }

req() {                     # req METHOD PATH [JSON] [extra-curl-args...]
  local m="$1" p="$2" body="${3:-}"
  shift 3 2>/dev/null || true
  say ""
  say "### $m $p"
  [ -n "$body" ] && say "    request: $body"
  if [ -n "$body" ]; then
    out=$(curl -sS -X "$m" "$BASE$p" -H 'Content-Type: application/json' -d "$body" \
          -w '\n<<HTTP %{http_code}>>' "$@" 2>&1)
  else
    out=$(curl -sS -X "$m" "$BASE$p" -w '\n<<HTTP %{http_code}>>' "$@" 2>&1)
  fi
  say "$out" | sed 's/^/    /'
}

say "=== Emby API transcript $(date -Is) ==="
say "server: $BASE (Emby 4.10.1.0)"

say ""
say "### server identity"
curl -sS "$BASE/System/Info/Public" -w '\n<<HTTP %{http_code}>>' 2>&1 | tee -a "$T" | sed 's/^/    /'

say ""
say "########## startup wizard ##########"
req GET  '/Startup/User' ''
req POST '/Startup/Configuration' '{"UICulture":"en-US","MetadataCountryCode":"US","PreferredMetadataLanguage":"en"}'
req GET  '/Startup/User' ''
req POST '/Startup/User' '{"Name":"mh","Password":"mh-test-pass"}'
req POST '/Startup/RemoteAccess' '{"EnableRemoteAccess":false,"EnableAutomaticPortMapping":false}'
req POST '/Startup/Complete' '{}'

say ""
say "########## auth ##########"
AUTH='MediaBrowser Client="MediaHubProbe", Device="Windows", DeviceId="mediahub-w5f-probe", Version="1.0.0"'
say "authorization header: X-Emby-Authorization: $AUTH"

say ""
say "### POST /Users/AuthenticateByName  (Emby's classic X-Emby-Authorization shape)"
LOGIN=$(curl -sS -X POST "$BASE/Users/AuthenticateByName" \
  -H 'Content-Type: application/json' \
  -H "X-Emby-Authorization: $AUTH" \
  -d '{"Username":"mh","Pw":"mh-test-pass"}' -w '\n<<HTTP %{http_code}>>' 2>&1)
say "$LOGIN" | sed 's/^/    /'
TOKEN=$(printf '%s' "$LOGIN" | tr -d '\n' | sed -n 's/.*"AccessToken":"\([^"]*\)".*/\1/p')
say "    -> token: $TOKEN"

if [ -z "$TOKEN" ]; then
  say ""
  say "### retry with the plain Authorization header"
  LOGIN=$(curl -sS -X POST "$BASE/Users/AuthenticateByName" \
    -H 'Content-Type: application/json' \
    -H "Authorization: $AUTH" \
    -d '{"Username":"mh","Pw":"mh-test-pass"}' -w '\n<<HTTP %{http_code}>>' 2>&1)
  say "$LOGIN" | sed 's/^/    /'
  TOKEN=$(printf '%s' "$LOGIN" | tr -d '\n' | sed -n 's/.*"AccessToken":"\([^"]*\)".*/\1/p')
  say "    -> token: $TOKEN"
fi

if [ -n "$TOKEN" ]; then
  say ""
  say "########## /Users/Me ##########"
  curl -sS "$BASE/Users/Me" -H "X-Emby-Token: $TOKEN" -w '\n<<HTTP %{http_code}>>' 2>&1 | tee -a "$T" | sed 's/^/    /'
  say ""
  say "########## /Users/Me (Authorization header form) ##########"
  curl -sS "$BASE/Users/Me" -H "Authorization: $AUTH, Token=\"$TOKEN\"" -w '\n<<HTTP %{http_code}>>' 2>&1 | tee -a "$T" | sed 's/^/    /'
  printf '%s' "$TOKEN" > "$O/.emby-token"
fi
say ""
say "=== done $(date -Is) ==="

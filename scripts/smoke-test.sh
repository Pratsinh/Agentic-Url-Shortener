#!/usr/bin/env bash
# End-to-end check of a RUNNING shortener over HTTP (curl only). Exit code 0 = every check passed.
# Usage: ./scripts/smoke-test.sh [base-url]        (default http://localhost:8080)
set -uo pipefail
BASE="${1:-${BASE_URL:-http://localhost:8080}}"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
pass=0; fail=0
check() { # description, condition-result (0 = ok), detail
  if [ "$2" -eq 0 ]; then pass=$((pass+1)); echo "PASS  $1"; else fail=$((fail+1)); echo "FAIL  $1  -- $3"; fi
}
# request METHOD PATH [JSON-BODY] [extra curl args...] -> status in $status, headers in $TMP/h, body in $TMP/b
request() {
  local method=$1 path=$2 body=${3:-}; shift 3 2>/dev/null || shift $#
  if [ -n "$body" ]; then
    status=$(curl -s -o "$TMP/b" -D "$TMP/h" -w '%{http_code}' -X "$method" -H 'Content-Type: application/json' --data "$body" "$@" "$BASE$path")
  else
    status=$(curl -s -o "$TMP/b" -D "$TMP/h" -w '%{http_code}' -X "$method" "$@" "$BASE$path")
  fi
}
json() { sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\)\"\{0,1\}.*/\1/p" "$TMP/b" | head -1; }
header() { grep -i "^$1:" "$TMP/h" | head -1 | cut -d' ' -f2- | tr -d '\r'; }

echo "Smoke test against $BASE"
request POST /api/v1/links '{"url":"https://example.com/docs?page=1","ttlSeconds":3600}'
code=$(json code); short=$(json shortUrl)
check "create returns 201" $([ "$status" = 201 ]; echo $?) "status $status: $(cat "$TMP/b")"
check "short code is 7 base62 characters" $([[ "$code" =~ ^[0-9A-Za-z]{7}$ ]]; echo $?) "code '$code'"
check "short URL ends with the code" $([[ "$short" == */"$code" ]]; echo $?) "shortUrl '$short'"
check "Location header points at the short URL" $([ -n "$short" ] && [ "$(header Location)" = "$short" ]; echo $?) "Location '$(header Location)'"

request GET "/$code" ''
check "redirect is 302 to the original URL" $([ "$status" = 302 ] && [ "$(header Location)" = "https://example.com/docs?page=1" ]; echo $?) "status $status Location '$(header Location)'"

request GET "/api/v1/links/$code/stats" ''
check "stats count the click" $([ "$status" = 200 ] && grep -q '"totalClicks":1' "$TMP/b"; echo $?) "$(cat "$TMP/b")"

request GET "/api/v1/links/$code/audit" ''
check "audit records create and redirect" $(grep -q LINK_CREATED "$TMP/b" && grep -q LINK_REDIRECTED "$TMP/b"; echo $?) "$(cat "$TMP/b")"
check "audit stores the host, not the query string" $([ "$status" = 200 ] && ! grep -q "page=1" "$TMP/b"; echo $?) "$(cat "$TMP/b")"

request POST /api/v1/links '{"url":"ftp://example.com"}'
check "non-http URL is 400 problem+json" $([ "$status" = 400 ] && header Content-Type | grep -q 'application/problem+json'; echo $?) "status $status"
request POST /api/v1/links '{"url":"http://169.254.169.254/latest/meta-data"}'
check "cloud metadata address is rejected (SSRF)" $([ "$status" = 400 ]; echo $?) "status $status"
request POST /api/v1/links '{"url":'
check "malformed JSON is 400" $([ "$status" = 400 ]; echo $?) "status $status"
request GET /Zz9Zz9Z ''
check "unknown code is 404" $([ "$status" = 404 ]; echo $?) "status $status"
request GET /api/v1/audit '' -H 'X-Request-Id: smoke-test-1'
check "request id is echoed" $([ "$(header X-Request-Id)" = smoke-test-1 ]; echo $?) "X-Request-Id '$(header X-Request-Id)'"

request GET /api/v1/openapi.yaml ''
check "OpenAPI contract is published" $([ "$status" = 200 ] && grep -q '^openapi: 3' "$TMP/b" && grep -q 'operationId: createLink' "$TMP/b"; echo $?) "status $status"

request POST /api/v1/links '{"url":"https://example.com/short-lived","ttlSeconds":1}'
ttlcode=$(json code); sleep 2
request GET "/$ttlcode" ''
check "expired link is 410" $([ "$status" = 410 ]; echo $?) "status $status"

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]

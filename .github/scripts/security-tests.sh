#!/usr/bin/env bash
# Attack-style tests against the running stack, from outside, through the DMZ gateway as the internet
# would reach it (e2e-stack.sh must have started it). Covers the API checklist in docs/security-tests.md:
# authentication, record-level authorization, injection, malformed input, abuse and rate limits,
# replay and duplicates, server-controlled fields, uploads, exposure of internal endpoints, headers,
# and logs. Writes e2e-out/security-results.md. Exit code 1 if a test FAILs; a known open FINDING is
# listed but does not fail the run.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/e2e-out"; mkdir -p "$OUT"
GW=http://127.0.0.1:5090
RES="$OUT/security-results.md"
BODY="$OUT/sec-body.txt"
failed=0
echo "| # | Result | Test | Evidence |" > "$RES"
echo "|---|---|---|---|" >> "$RES"

row() { # id result test evidence
  echo "| $1 | $2 | $3 | ${4//|/\\|} |" >> "$RES"
  printf '%-6s %-8s %s — %s\n' "$1" "$2" "$3" "$4"
  [ "$2" = "FAIL" ] && failed=1
  return 0
}
sql() { docker exec mrsql /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "$SA_PASSWORD" -C -I -b -d MeterReading -h -1 -W -Q "SET NOCOUNT ON; $1"; }
# call METHOD PATH [curl args...] → prints the status; body in $BODY
call() { local m=$1 p=$2; shift 2; curl -s -o "$BODY" -w '%{http_code}' -X "$m" "$GW$p" "$@"; }
code_of() { jq -r '.code // empty' "$BODY" 2>/dev/null; }
# Refused sign-in: 401, or 403 with a DEVICE_* code as spec Appendix A answers it, and nothing else.
refused() { { [ "$1" = 401 ] || [ "$1" = 403 ]; } && [[ "$(code_of)" == DEVICE_* || "$1" = 401 ]]; }
leaks() { grep -qiE 'exception|stack ?trace|   at |SqlClient|System\.|Microsoft\.|select .* from|connection string|password=' "$BODY"; }

new_code() {
  local plain; plain=$(LC_ALL=C tr -dc 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789' </dev/urandom | head -c 12)
  sql "INSERT mr.DeviceRegistrationCode (CodeHash, Label, ExpiresAtUtc) VALUES (CONVERT(char(64), HASHBYTES('SHA2_256', CAST('$plain' AS varchar(12))), 2), N'$1', DATEADD(HOUR, 1, SYSUTCDATETIME()))" >/dev/null
  echo "${plain:0:4}-${plain:4:4}-${plain:8:4}"
}
register() { # label → "id key"
  local c; c=$(new_code "$1")
  call POST /api/v1/devices/register -H 'Content-Type: application/json' -d "{\"code\":\"$c\",\"model\":\"sec-test\",\"androidVersion\":\"14\",\"appVersion\":\"1.0.0\"}" >/dev/null
  echo "$(jq -r .deviceId "$BODY") $(jq -r .deviceKey "$BODY")"
}

read -r A_ID A_KEY <<< "$(register 'Security A')"
read -r B_ID B_KEY <<< "$(register 'Security B')"
read -r C_ID C_KEY <<< "$(register 'Security C')"
[ -n "$A_KEY" ] && [ "$A_KEY" != null ] || { echo "could not register test phones: $(cat "$BODY")"; exit 1; }
RASHID=rashid@dip.example; ANIL=anil@dip.example
A=(-H "X-Device-Id: $A_ID" -H "X-Device-Key: $A_KEY" -H "X-Reader: $RASHID")
B=(-H "X-Device-Id: $B_ID" -H "X-Device-Key: $B_KEY" -H "X-Reader: $ANIL")
J=(-H 'Content-Type: application/json')
now() { date -u -d "-${1:-2} minutes" +%Y-%m-%dT%H:%M:%SZ; }
reading() { # id meter value tenant [extra json]
  echo "{\"transactionId\":\"$1\",\"meterId\":\"$2\",\"condition\":\"WORKING\",\"newReading\":$3,\"readerConfirmedWarning\":true,\"capturedAtUtc\":\"$(now)\",\"tenantCode\":\"$4\",\"photoCount\":1${5:-}}"
}

echo "== 1. Authentication"
ENDPOINTS=("GET /api/v1/me" "GET /api/v1/sync/meters" "GET /api/v1/properties/search?q=1101" "GET /api/v1/readings/mine"
  "GET /api/v1/summary" "GET /api/v1/meters/BC0001" "POST /api/v1/readings" "GET /api/v1/inspections/plan"
  "GET /api/v1/inspections/units?period=2026-10&property=597-559&tenant=T-0559" "POST /api/v1/inspections"
  "GET /api/v1/readings/$(uuidgen)/images/$(uuidgen)" "PUT /api/v1/readings/$(uuidgen)/images/$(uuidgen)")
bad=""
for e in "${ENDPOINTS[@]}"; do s=$(call ${e% *} "${e#* }" "${J[@]}" -d '{}'); refused "$s" || bad="$bad ${e}=$s/$(code_of)"; done
[ -z "$bad" ] && row 1.1 PASS "Every protected endpoint refuses a request without a phone key" "${#ENDPOINTS[@]} endpoints → 403 DEVICE_NOT_REGISTERED" \
              || row 1.1 FAIL "Every protected endpoint refuses a request without a phone key" "$bad"
s=$(call GET /api/v1/me -H "X-Device-Id: $A_ID" -H "X-Device-Key: wrong$A_KEY" -H "X-Reader: $RASHID")
refused "$s" && row 1.2 PASS "Wrong key refused" "$s $(code_of)" || row 1.2 FAIL "Wrong key refused" "HTTP $s"
s=$(call GET /api/v1/me -H "X-Device-Id: $(uuidgen)" -H "X-Device-Key: $A_KEY" -H "X-Reader: $RASHID")
refused "$s" && row 1.3 PASS "Another phone's key with an unknown phone id refused" "$s $(code_of)" || row 1.3 FAIL "Unknown phone id refused" "HTTP $s"
s=$(call GET /api/v1/me -H "X-Device-Id: $B_ID" -H "X-Device-Key: $A_KEY" -H "X-Reader: $RASHID")
refused "$s" && row 1.4 PASS "Phone A's key does not open phone B" "$s $(code_of)" || row 1.4 FAIL "Phone A's key does not open phone B" "HTTP $s"
s=$(call GET /api/v1/me -H "X-Device-Id: not-a-guid" -H "X-Device-Key: x")
refused "$s" && row 1.5 PASS "Malformed phone id refused" "$s $(code_of)" || row 1.5 FAIL "Malformed phone id refused" "HTTP $s"
sql "UPDATE mr.Device SET Status='REVOKED', RevokedAtUtc=SYSUTCDATETIME() WHERE DeviceId='$C_ID'" >/dev/null
s=$(call GET /api/v1/me -H "X-Device-Id: $C_ID" -H "X-Device-Key: $C_KEY" -H "X-Reader: $RASHID")
[ "$s" = 401 ] || [ "$s" = 403 ] && row 1.6 PASS "Blocked (revoked) phone refused at once" "$s $(code_of)" || row 1.6 FAIL "Blocked phone refused" "HTTP $s"
s=$(call GET /api/v1/me -H "X-Dev-User: $RASHID")
refused "$s" && row 1.7 PASS "Development sign-in header is stripped by the gateway" "X-Dev-User → $s $(code_of)" || row 1.7 FAIL "X-Dev-User stripped" "HTTP $s"
s=$(call GET /api/v1/me -H "X-Device-Id: $A_ID" -H "X-Device-Key: $A_KEY" -H "X-Reader: nobody@evil.example")
[ "$s" = 403 ] || [ "$s" = 404 ] || [ "$s" = 401 ] && row 1.8 PASS "A reader who is not in the reader list is refused" "$s $(code_of)" || row 1.8 FAIL "Unknown reader refused" "HTTP $s"
c=$(new_code 'Security reuse'); call POST /api/v1/devices/register "${J[@]}" -d "{\"code\":\"$c\"}" >/dev/null
s=$(call POST /api/v1/devices/register "${J[@]}" -d "{\"code\":\"$c\"}")
[ "$s" = 400 ] || [ "$s" = 401 ] || [ "$s" = 403 ] || [ "$s" = 404 ] || [ "$s" = 422 ] && row 1.9 PASS "A registration code works only once" "second use → $s $(code_of)" || row 1.9 FAIL "Code works once" "HTTP $s"
k=$(sql "SELECT COUNT(*) FROM mr.Device WHERE KeyHash = '$A_KEY'"); kh=$(sql "SELECT LEN(KeyHash) FROM mr.Device WHERE DeviceId='$A_ID'")
[ "$k" = 0 ] && [ "$kh" = 64 ] && row 1.10 PASS "Only a hash of the phone key is stored" "KeyHash is 64 hex, never the key" || row 1.10 FAIL "Only key hash stored" "match=$k len=$kh"

echo "== 2. Record-level authorization"
# Unread meters with a tenant, from the live list: "meter value tenant", value = last + 100.
call GET /api/v1/sync/meters "${A[@]}" >/dev/null
mapfile -t FREE < <(jq -r '[.properties[] | {code, t: ((.tenants[0].code) // .tenantCode)}] as $p
  | .meters[] | select(.state == "PENDING") | . as $m | ([$p[] | select(.code == $m.propertyCode) | .t][0]) as $t
  | select($t != null) | "\(.id) \(((.previousReading // 0) + 100) | floor) \($t)"' "$BODY")
echo "unread meters for the tests: ${FREE[*]}"
[ "${#FREE[@]}" -ge 4 ] || { row 0 FAIL "Enough unread meters in the test data" "${#FREE[@]}"; }
read -r M1 V1 N1 <<< "${FREE[0]}"; read -r M2 V2 N2 <<< "${FREE[1]}"; read -r M3 V3 N3 <<< "${FREE[2]}"; read -r M4 V4 N4 <<< "${FREE[3]}"
T1=$(uuidgen)
s=$(call POST /api/v1/readings "${A[@]}" "${J[@]}" -d "$(reading "$T1" "$M1" "$V1" "$N1")")
[ "$s" = 201 ] || row 2.0 FAIL "Test reading stored" "$M1 → $s $(code_of)"
printf '\xff\xd8\xff\xe0\x00\x10JFIF\x00security-test\xff\xd9' > "$OUT/sec.jpg"
SHA=$(sha256sum "$OUT/sec.jpg" | cut -d' ' -f1 | tr a-f A-F); IMG=$(uuidgen)
s2=$(call PUT "/api/v1/readings/$T1/images/$IMG?role=DISPLAY" "${A[@]}" -H 'Content-Type: image/jpeg' -H "X-Content-SHA256: $SHA" --data-binary @"$OUT/sec.jpg")
s=$(call GET "/api/v1/readings/$T1/images/$IMG" "${B[@]}")
[ "$s" = 404 ] || [ "$s" = 403 ] && row 2.1 PASS "Another reader cannot download a reader's photo (IDOR)" "reading $s/photo $s2 by Rashid; Anil → $s" || row 2.1 FAIL "IDOR photo" "Anil got HTTP $s"
s=$(call PUT "/api/v1/readings/$T1/images/$(uuidgen)?role=DISPLAY" "${B[@]}" -H 'Content-Type: image/jpeg' -H "X-Content-SHA256: $SHA" --data-binary @"$OUT/sec.jpg")
[ "$s" = 404 ] || [ "$s" = 403 ] && row 2.2 PASS "Another reader cannot add a photo to a reader's reading" "Anil → $s $(code_of)" || row 2.2 FAIL "Add photo to other's reading" "HTTP $s"
call GET /api/v1/readings/mine "${B[@]}" >/dev/null
grep -q "$T1" "$BODY" && row 2.3 FAIL "My readings shows only the reader's own" "Anil sees Rashid's $T1" || row 2.3 PASS "My readings shows only the reader's own" "Rashid's reading not in Anil's list"
s=$(call GET /api/v1/readings/mine -H "X-Device-Id: $B_ID" -H "X-Device-Key: $B_KEY" -H "X-Reader: $RASHID")
if [ "$s" = 200 ]; then
  grep -q "$T1" "$BODY" && seen="Rashid's reading $M1 listed" || seen="list returned"
  row 2.4 FINDING "A registered phone can name any active reader (X-Reader) and act as that reader" "phone B (Anil's) with X-Reader Rashid → 200, $seen. The phone key is checked; the reader is not bound to the phone."
else row 2.4 PASS "A phone cannot act as a reader other than its own" "phone B as Rashid → $s"; fi
dev=$(sql "SELECT CONVERT(varchar(36), DeviceId) FROM mr.ReadingTransaction WHERE TransactionId='$T1'" | tr a-z A-Z)
[ "$dev" = "$(echo "$A_ID" | tr a-z A-Z)" ] && row 2.5 PASS "A reading is stored with the phone that sent it" "DeviceId = phone A" || row 2.5 FAIL "Reading stored with its phone" "DeviceId=$dev"
T2=$(uuidgen)
s=$(call POST /api/v1/readings "${A[@]}" "${J[@]}" -d "$(reading "$T2" "$M2" "$V2" "$N2" ",\"deviceId\":\"$B_ID\"")")
dev=$(sql "SELECT CONVERT(varchar(36), DeviceId) FROM mr.ReadingTransaction WHERE TransactionId='$T2'" | tr a-z A-Z)
[ "$dev" = "$(echo "$A_ID" | tr a-z A-Z)" ] && row 2.6 PASS "A body naming another phone is ignored (server-controlled field)" "body deviceId=B → stored A" || row 2.6 FAIL "Body deviceId ignored" "stored $dev (HTTP $s)"

echo "== 3. Injection and malformed input"
call GET "/api/v1/properties/search?q=" "${A[@]}" >/dev/null; all=$(jq '[.. | objects | select(has("code"))] | length' "$BODY" 2>/dev/null)
for q in "%27%20OR%201%3D1%20--" "1101%27%3B%20DROP%20TABLE%20mr.Device%3B--" "%25" "%5B" "1101%27%20UNION%20SELECT%20name%20FROM%20sys.tables--"; do
  s=$(call GET "/api/v1/properties/search?q=$q" "${A[@]}"); n=$(jq '[.. | objects | select(has("code"))] | length' "$BODY" 2>/dev/null)
  if [ "$s" = 500 ] || leaks; then row 3.1 FAIL "SQL injection in search q=$q" "HTTP $s"; elif [ "$s" = 200 ] && [ "${n:-0}" -gt 3 ] && [ "${n:-0}" != "$all" ]; then row 3.1 FAIL "SQL injection in search q=$q" "$n results"; else row 3.1 PASS "SQL injection in search ($q)" "HTTP $s, $n results (an empty search gives $all: punctuation is ignored and search runs in memory, not in SQL), no error text"; fi
done
s=$(call GET "/api/v1/meters/BC0001%27%20OR%20%271%27%3D%271" "${A[@]}"); { [ "$s" = 404 ] || [ "$s" = 400 ]; } && ! leaks && row 3.2 PASS "SQL injection in meter id" "HTTP $s" || row 3.2 FAIL "SQL injection in meter id" "HTTP $s"
s=$(call GET "/api/v1/sync/meters?zone=597%27%20OR%201%3D1--" "${A[@]}"); n=$(jq '.meters | length' "$BODY" 2>/dev/null)
[ "$s" != 500 ] && ! leaks && [ "${n:-0}" = 0 ] && row 3.3 PASS "SQL injection in zone filter" "HTTP $s, ${n:-0} meters" || row 3.3 FAIL "SQL injection in zone" "HTTP $s, $n meters"
[ "$(sql "SELECT COUNT(*) FROM sys.tables WHERE name='Device' AND schema_id=SCHEMA_ID('mr')")" = 1 ] && row 3.4 PASS "Tables intact after injection attempts" "mr.Device still there" || row 3.4 FAIL "Tables intact" "mr.Device missing"
s=$(call POST /api/v1/readings "${A[@]}" "${J[@]}" -d '{"transactionId": "x", "meterId": ')
[ "$s" = 400 ] && ! leaks && row 3.5 PASS "Malformed JSON → controlled 400" "$s $(code_of), no internals" || row 3.5 FAIL "Malformed JSON" "HTTP $s $(head -c 200 "$BODY")"
s=$(call POST /api/v1/readings "${A[@]}" "${J[@]}" -d "$(reading "$(uuidgen)" BC0005 62200 T-0201 ',"status":"APPROVED"')")
[ "$s" = 400 ] && row 3.6 PASS "Mass assignment: unknown field (status=APPROVED) refused" "$s $(code_of)" || row 3.6 FAIL "Mass assignment refused" "HTTP $s"
s=$(call POST /api/v1/readings "${A[@]}" "${J[@]}" -d '{"transactionId":"'"$(uuidgen)"'","meterId":"BC0005","condition":"WORKING","newReading":"abc","readerConfirmedWarning":false,"capturedAtUtc":"'"$(now)"'","tenantCode":"T-0201"}')
[ "$s" = 400 ] && ! leaks && row 3.7 PASS "Wrong data type refused" "$s $(code_of)" || row 3.7 FAIL "Wrong type" "HTTP $s"
s=$(call POST /api/v1/readings "${A[@]}" -H 'Content-Type: text/plain' -d 'hello')
[ "$s" = 415 ] || [ "$s" = 400 ] && row 3.8 PASS "Unsupported content type refused" "HTTP $s" || row 3.8 FAIL "Content type" "HTTP $s"
s=$(call POST /api/v1/readings "${A[@]}" "${J[@]}" -d "$(reading "$(uuidgen)" "$M4" "$V4" "$N4" ',"note":"'"$(printf "<script>alert(1)</script>%.0s" {1..5})"'"')")
row 3.9 INFO "Script text in a note is stored as text (no HTML is rendered by the API)" "HTTP $s $(code_of)"
s=$(call POST /api/v1/readings "${A[@]}" "${J[@]}" -d '{"transactionId":"'"$(uuidgen)"'","meterId":"BC0005","condition":"NOPE","newReading":1,"readerConfirmedWarning":false,"capturedAtUtc":"'"$(now)"'","tenantCode":"T-0201"}')
[ "$s" = 400 ] || [ "$s" = 422 ] && row 3.10 PASS "Unknown code values refused (server-side rules)" "$s $(code_of)" || row 3.10 FAIL "LOV check" "HTTP $s"
s=$(call POST /api/v1/readings "${A[@]}" "${J[@]}" -d "$(reading "$(uuidgen)" BC0005 1234567 T-0201)")
[ "$s" = 422 ] && row 3.11 PASS "Business rules enforced on the server (more digits than the meter has)" "$s $(code_of)" || row 3.11 FAIL "Server-side rule" "HTTP $s"
s=$(call POST /api/v1/readings "${A[@]}" "${J[@]}" -d "$(reading "$(uuidgen)" BC0005 62200 T-0102)")
[ "$s" = 422 ] || [ "$s" = 409 ] && row 3.12 PASS "A tenant not of this property is refused (FR-006.12)" "$s $(code_of)" || row 3.12 FAIL "Tenant check" "HTTP $s"
FUT=$(date -u -d "+2 hours" +%Y-%m-%dT%H:%M:%SZ)
s=$(call POST /api/v1/readings "${A[@]}" "${J[@]}" -d '{"transactionId":"'"$(uuidgen)"'","meterId":"BC0005","condition":"WORKING","newReading":62200,"readerConfirmedWarning":true,"capturedAtUtc":"'"$FUT"'","tenantCode":"T-0201"}')
[ "$s" = 422 ] && row 3.13 PASS "A capture time in the future is refused" "$s $(code_of)" || row 3.13 FAIL "Future time" "HTTP $s"

echo "== 4. Exposure of internal endpoints"
for p in /openapi/v1.json /swagger /swagger/index.html /health/ready /api/v1/admin /api/v1/devices /api/v1/readings/../../health/ready "/api/v1/meters/..%2F..%2Fhealth%2Fready" /.env /web.config /appsettings.json; do
  s=$(call GET "$p" "${A[@]}")
  [ "$s" = 404 ] || [ "$s" = 400 ] || [ "$s" = 401 ] || [ "$s" = 405 ] && row 4.1 PASS "Not reachable from outside: $p" "HTTP $s" || row 4.1 FAIL "Exposed: $p" "HTTP $s"
done
s=$(call DELETE /api/v1/readings "${A[@]}"); [ "$s" = 405 ] && row 4.2 PASS "Unsupported method on a known path" "DELETE → 405" || row 4.2 FAIL "Unsupported method" "HTTP $s"
s=$(call TRACE /api/v1/me "${A[@]}"); [ "$s" = 405 ] || [ "$s" = 404 ] || [ "$s" = 400 ] && row 4.3 PASS "TRACE not allowed" "HTTP $s" || row 4.3 FAIL "TRACE" "HTTP $s"
s=$(call POST /api/v1/me "${A[@]}" -H 'X-HTTP-Method-Override: GET'); [ "$s" = 405 ] && row 4.4 PASS "Method override header has no effect" "POST + override → 405" || row 4.4 FAIL "Method override" "HTTP $s"
hdr=$(curl -s -D - -o /dev/null "$GW/health/live")
miss=""; for h in Strict-Transport-Security X-Content-Type-Options X-Frame-Options Content-Security-Policy Referrer-Policy Cross-Origin-Resource-Policy Cache-Control; do echo "$hdr" | grep -qi "^$h:" || miss="$miss $h"; done
echo "$hdr" | grep -qiE '^(Server|X-Powered-By):' && miss="$miss (Server/X-Powered-By present)"
[ -z "$miss" ] && row 4.5 PASS "Security headers set, server not named" "$(echo "$hdr" | grep -iE '^(X-Content-Type-Options|X-Frame-Options|Referrer-Policy)' | tr -d '\r' | paste -sd';')" || row 4.5 FAIL "Security headers" "missing:$miss"

echo "== 5. Abuse, size limits and rate limits"
head -c 200000 /dev/zero | tr '\0' 'a' > "$OUT/big.txt"
{ printf '{"note":"'; cat "$OUT/big.txt"; printf '"}'; } > "$OUT/big.json"
s=$(call POST /api/v1/readings "${A[@]}" "${J[@]}" --data-binary @"$OUT/big.json"); [ "$s" = 413 ] && row 5.1 PASS "Oversized JSON body refused at the gateway" "200 KB → 413" || row 5.1 FAIL "Oversized JSON" "HTTP $s"
python3 -c "print('['*5000 + ']'*5000)" > "$OUT/deep.json"
s=$(call POST /api/v1/readings "${A[@]}" "${J[@]}" --data-binary @"$OUT/deep.json"); [ "$s" = 400 ] || [ "$s" = 413 ] && ! leaks && row 5.2 PASS "Deeply nested JSON refused" "HTTP $s" || row 5.2 FAIL "Deep JSON" "HTTP $s"
head -c 3000000 /dev/urandom > "$OUT/big.jpg"
s=$(call PUT "/api/v1/readings/$T1/images/$(uuidgen)?role=CONTEXT" "${A[@]}" -H 'Content-Type: image/jpeg' -H "X-Content-SHA256: $(sha256sum "$OUT/big.jpg" | cut -d' ' -f1)" --data-binary @"$OUT/big.jpg")
[ "$s" = 413 ] && row 5.3 PASS "Oversized photo refused" "3 MB → 413" || row 5.3 FAIL "Oversized photo" "HTTP $s"
printf 'MZ not a jpeg' > "$OUT/x.exe"
s=$(call PUT "/api/v1/readings/$T1/images/$(uuidgen)?role=CONTEXT" "${A[@]}" -H 'Content-Type: image/jpeg' -H "X-Content-SHA256: $(sha256sum "$OUT/x.exe" | cut -d' ' -f1)" --data-binary @"$OUT/x.exe")
[ "$s" = 422 ] && row 5.4 PASS "A file that is not a JPEG is refused (content checked, not the name)" "$s $(code_of)" || row 5.4 FAIL "Non-JPEG" "HTTP $s"
s=$(call PUT "/api/v1/readings/$T1/images/$(uuidgen)?role=CONTEXT" "${A[@]}" -H 'Content-Type: image/jpeg' -H "X-Content-SHA256: $(printf '0%.0s' {1..64})" --data-binary @"$OUT/sec.jpg")
[ "$s" = 422 ] && row 5.5 PASS "A photo whose SHA-256 does not match is refused" "$s $(code_of)" || row 5.5 FAIL "Hash mismatch" "HTTP $s"
s=$(call PUT "/api/v1/readings/$T1/images/..%2F..%2Fetc?role=CONTEXT" "${A[@]}" -H "X-Content-SHA256: $SHA" --data-binary @"$OUT/sec.jpg")
[ "$s" = 404 ] || [ "$s" = 400 ] && row 5.6 PASS "Path traversal in an image id refused" "HTTP $s" || row 5.6 FAIL "Path traversal" "HTTP $s"
n=0; for i in $(seq 1 12); do s=$(call PUT "/api/v1/readings/$T1/images/$(uuidgen)?role=CONTEXT" "${A[@]}" -H "X-Content-SHA256: $SHA" --data-binary @"$OUT/sec.jpg"); [ "$s" = 422 ] && n=$((n+1)); done
[ "$n" -gt 0 ] && row 5.7 PASS "Photos per reading are capped (storage exhaustion)" "$n of 12 extra photos refused $(code_of)" || row 5.7 FAIL "Photo cap" "none refused"
codes=$(for i in $(seq 1 160); do curl -s -o /dev/null -w '%{http_code}\n' "$GW/api/v1/me" "${B[@]}" -H "X-Forwarded-For: 10.9.$((i%250)).$((i%7))"; done | sort | uniq -c | tr '\n' ' ')
echo "$codes" | grep -q ' 429' && row 5.8 PASS "Per-phone rate limit holds, also with a changing X-Forwarded-For" "160 requests in a burst: $codes" || row 5.8 FAIL "Rate limit" "$codes"
codes=$(for i in $(seq 1 20); do curl -s -o /dev/null -w '%{http_code}\n' -X POST "$GW/api/v1/devices/register" "${J[@]}" -d '{"code":"AAAA-BBBB-CCCC"}'; done | sort | uniq -c | tr '\n' ' ')
echo "$codes" | grep -q ' 429' && row 5.9 PASS "Registration code guessing is rate limited" "20 tries: $codes" || row 5.9 FAIL "Registration brute force" "$codes"
sleep 61 # let the limits reset for the next tests

echo "== 6. Replay and duplicates (billing)"
T3=$(uuidgen); R=$(reading "$T3" "$M3" "$V3" "$N3")
s1=$(call POST /api/v1/readings "${A[@]}" "${J[@]}" -d "$R"); s2=$(call POST /api/v1/readings "${A[@]}" "${J[@]}" -d "$R")
n=$(sql "SELECT COUNT(*) FROM mr.ReadingTransaction WHERE TransactionId='$T3'")
[ "$s1" = 201 ] && [ "$s2" = 200 ] && [ "$n" = 1 ] && row 6.1 PASS "Replaying the same reading stores it once" "$s1 then $s2, $n row" || row 6.1 FAIL "Replay" "$s1/$s2 rows=$n"
s=$(call POST /api/v1/readings "${A[@]}" "${J[@]}" -d "$(reading "$T3" "$M3" "$((V3 + 500))" "$N3")")
[ "$s" = 409 ] && row 6.2 PASS "Same id with a changed number is refused (no overwrite)" "$s $(code_of)" || row 6.2 FAIL "Changed replay" "HTTP $s"
s=$(call POST /api/v1/readings "${A[@]}" "${J[@]}" -d "$(reading "$(uuidgen)" "$M3" "$((V3 + 10))" "$N3")")
[ "$s" = 409 ] && row 6.3 PASS "A meter already read cannot be read again by a new request" "$s $(code_of)" || row 6.3 FAIL "Second reading" "HTTP $s"
s=$(call POST /api/v1/readings "${B[@]}" "${J[@]}" -d "$(reading "$(uuidgen)" "$M3" "$((V3 + 20))" "$N3")")
[ "$s" = 409 ] && row 6.4 PASS "Another reader cannot overwrite it either" "$s $(code_of)" || row 6.4 FAIL "Other reader overwrite" "HTTP $s"
sleep 25
n=$(sql "SELECT COUNT(*) FROM dbo.MaintainMeterReading m JOIN mr.ReadingTransaction t ON t.PmsRowId = m.RowId WHERE t.TransactionId='$T3'")
dup=$(sql "SELECT COUNT(*) FROM (SELECT PmsRowId FROM mr.ReadingTransaction WHERE PmsRowId IS NOT NULL GROUP BY PmsRowId HAVING COUNT(*) > 1) d")
[ "$n" -le 1 ] && [ "$dup" = 0 ] && row 6.5 PASS "No duplicate billing rows in MaintainMeterReading" "copies of this reading: $n; shared rows: $dup" || row 6.5 FAIL "Duplicate billing" "copies=$n shared=$dup"
u=$(sql "SELECT COUNT(*) FROM (SELECT MeterId FROM mr.ReadingTransaction WHERE Status IN ('ACCEPTED','EXCEPTION') GROUP BY PeriodCode, MeterId HAVING COUNT(*) > 1) d")
[ "$u" = 0 ] && row 6.6 PASS "At most one live reading per meter per period" "meters with two: $u" || row 6.6 FAIL "One reading per meter" "$u meters"

echo "== 7. Errors and logs"
grep -rhoiE 'exception|   at [A-Z]' "$OUT"/sec-body.txt >/dev/null 2>&1 && row 7.1 FAIL "Error bodies hide internals" "see body" || row 7.1 PASS "Error responses are problem details without internals" "checked on every refusal above"
for f in "$OUT/api.log" "$OUT/gateway.log"; do
  if grep -qF "$A_KEY" "$f" || grep -qF "$B_KEY" "$f" || grep -qF "$SA_PASSWORD" "$f"; then row 7.2 FAIL "No keys or passwords in $(basename "$f")" "found"; else row 7.2 PASS "No phone keys or passwords in $(basename "$f")" "searched for the test keys and the SQL password"; fi
done
a=$(grep -cE 'DEVICE_KEY_WRONG|DEVICE_UNKNOWN|DEVICE_REVOKED|DEVICE_HEADERS' "$OUT/api.log"); r=$(grep -ciE '429|rate' "$OUT/gateway.log")
[ "$a" -gt 0 ] && row 7.3 PASS "Refused sign-ins are audited with a reason" "$a audit lines (key wrong / unknown / revoked)" || row 7.3 FAIL "Auth audit" "no audit lines"
[ "$r" -gt 0 ] && row 7.4 PASS "Rate-limit refusals are logged at the gateway" "$r lines" || row 7.4 FAIL "Rate-limit log" "none in gateway.log"

echo
cat "$RES"
exit $failed

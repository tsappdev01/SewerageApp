#!/usr/bin/env bash
# OWASP ZAP API scan (passive and active) of the gateway, signed in as a test phone, from the API's
# own OpenAPI description. The stack is this run's throw-away copy, so active attacks are allowed.
# Report: e2e-out/zap-report.html / .json / .md. Does not fail the run: the alerts are reviewed.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/e2e-out"; mkdir -p "$OUT"
sql() { docker exec mrsql /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "$SA_PASSWORD" -C -I -b -d MeterReading -h -1 -W -Q "SET NOCOUNT ON; $1"; }

# The OpenAPI description: the published API serves it only in Development, so start one briefly.
CONN="Server=127.0.0.1,1433;Database=MeterReading;User Id=sa;Password=$SA_PASSWORD;Encrypt=True;TrustServerCertificate=True"
( cd "$OUT/api-bin" && ASPNETCORE_ENVIRONMENT=Development ASPNETCORE_URLS=http://127.0.0.1:5081 ConnectionStrings__MeterReading="$CONN" Auth__Mode=Device \
  nohup dotnet MeterReading.Api.dll > "$OUT/api-openapi.log" 2>&1 & echo $! > "$OUT/api-openapi.pid" )
for i in $(seq 1 30); do curl -fsS http://127.0.0.1:5081/openapi/v1.json -o "$OUT/openapi.json" 2>/dev/null && break; sleep 2; done
kill "$(cat "$OUT/api-openapi.pid")" 2>/dev/null || true
[ -s "$OUT/openapi.json" ] || { echo "no OpenAPI document"; exit 0; }

plain=$(LC_ALL=C tr -dc 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789' </dev/urandom | head -c 12)
sql "INSERT mr.DeviceRegistrationCode (CodeHash, Label, ExpiresAtUtc) VALUES (CONVERT(char(64), HASHBYTES('SHA2_256', CAST('$plain' AS varchar(12))), 2), N'ZAP scan', DATEADD(HOUR, 1, SYSUTCDATETIME()))" >/dev/null
curl -s -X POST http://127.0.0.1:5090/api/v1/devices/register -H 'Content-Type: application/json' -d "{\"code\":\"$plain\",\"model\":\"zap\"}" -o "$OUT/zap-device.json"
ID=$(jq -r .deviceId "$OUT/zap-device.json"); KEY=$(jq -r .deviceKey "$OUT/zap-device.json")

hdr() { # n name value
  echo "-config replacer.full_list($1).description=h$1 -config replacer.full_list($1).enabled=true -config replacer.full_list($1).matchtype=REQ_HEADER -config replacer.full_list($1).matchstr=$2 -config replacer.full_list($1).regex=false -config replacer.full_list($1).replacement=$3"
}
chmod 777 "$OUT"
docker run --rm --network host -v "$OUT":/zap/wrk:rw ghcr.io/zaproxy/zaproxy:stable zap-api-scan.py \
  -t /zap/wrk/openapi.json -f openapi -O http://127.0.0.1:5090 -I \
  -r zap-report.html -J zap-report.json -w zap-report.md \
  -z "$(hdr 0 X-Device-Id "$ID") $(hdr 1 X-Device-Key "$KEY") $(hdr 2 X-Reader rashid@dip.example)" > "$OUT/zap-console.txt" 2>&1
tail -n 40 "$OUT/zap-console.txt"
exit 0

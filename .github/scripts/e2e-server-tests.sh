#!/usr/bin/env bash
# The API's and the gateway's own test suites, on the same SQL Server as the end-to-end test but in a
# database of their own (MeterReadingTest), so they cannot disturb what the phone sent.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/e2e-out"; mkdir -p "$OUT"
sqlcmd() { docker exec mrsql /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "$SA_PASSWORD" -C -b "$@"; }

sqlcmd -Q "IF DB_ID('MeterReadingTest') IS NULL CREATE DATABASE MeterReadingTest" || exit 1
for f in dev/000_create_dev_source_views.sql 002_create_mr_schema.sql 003_meter_id_as_text.sql 004_add_expected_photos.sql \
         005_reading_tenant_and_export.sql 006_pms_transfer.sql 007_reading_image_data.sql 009_device_keys.sql \
         010_field_inspection.sql 012_inspection_location.sql dev/010_seed_dev_readings.sql dev/020_create_dev_maintain_meter_reading.sql; do
  sqlcmd -d MeterReadingTest -i "/db/$f" > /dev/null || { echo "db/$f failed"; exit 1; }
done

export MR_TEST_SQL="Server=127.0.0.1,1433;Database=MeterReadingTest;User Id=sa;Password=$SA_PASSWORD;Encrypt=True;TrustServerCertificate=True"
failed=0
dotnet test "$ROOT/api/MeterReading.slnx" --logger "console;verbosity=minimal" > "$OUT/api-tests.txt" 2>&1 || failed=1
tail -n 25 "$OUT/api-tests.txt"
dotnet test "$ROOT/gateway/MeterReading.Gateway.slnx" --logger "console;verbosity=minimal" > "$OUT/gateway-tests.txt" 2>&1 || failed=1
tail -n 15 "$OUT/gateway-tests.txt"
exit $failed

#!/usr/bin/env bash
# Runs on the booted emulator (from android/): installs the app and its end-to-end tests, gives them a
# fresh registration code from db/ops/new_device_code.sql, runs EndToEndTest, checks the database
# (db/dev/900_e2e_checks.sql), blocks the phone (db/ops/revoke_device.sql) and runs RevokedPhoneTest.
# Everything it sees goes to ../e2e-out/. Fails if any test or check failed.
set -uo pipefail
OUT="$(cd .. && pwd)/e2e-out"; mkdir -p "$OUT/screens"
PKG=ae.dipark.fieldservice
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
LABEL="E2E emulator"
failed=0

sql_file() { # $1 = path under db/, with the label set
  sed "s/N'Phone 01'/N'$LABEL'/" "../db/$1" > "$OUT/run.sql"
  docker cp "$OUT/run.sql" mrsql:/tmp/run.sql
  docker exec mrsql /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "$SA_PASSWORD" -C -b -d MeterReading -W -s " | " -i /tmp/run.sql
}

adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell cmd location set-location-enabled true || true
# Stand at the DIP office: the emulator's GPS keeps reporting it.
( while true; do adb emu geo fix 55.170344 24.999906 >/dev/null 2>&1; sleep 3; done ) &
GEO=$!
adb logcat -c

CODE=$(sql_file ops/new_device_code.sql | grep -oE '[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}' | head -n 1)
echo "Registration code made (label $LABEL)."
[ -n "$CODE" ] || { echo "No registration code"; exit 1; }

echo "== EndToEndTest"
adb shell am instrument -w -r -e class com.meterreading.reader.e2e.EndToEndTest -e regCode "$CODE" "$RUNNER" | tee "$OUT/instrument-e2e.txt"
grep -q "^OK (" "$OUT/instrument-e2e.txt" || failed=1

echo "== Waiting for the PMS copy (every 10 s)"
sleep 30
echo "== Database checks"
sql_file dev/900_e2e_checks.sql | tee "$OUT/db-checks.txt"
[ "${PIPESTATUS[0]}" -eq 0 ] || failed=1

echo "== Blocking the phone, then RevokedPhoneTest"
sql_file ops/revoke_device.sql | tee "$OUT/revoke.txt"
adb shell am instrument -w -r -e class com.meterreading.reader.e2e.RevokedPhoneTest "$RUNNER" | tee "$OUT/instrument-revoked.txt"
grep -q "^OK (" "$OUT/instrument-revoked.txt" || failed=1

kill $GEO 2>/dev/null || true
adb pull "/sdcard/Android/data/$PKG/files/e2e/." "$OUT/screens/" >/dev/null 2>&1 || true
adb exec-out screencap -p > "$OUT/screens/zz_last_screen.png" || true
adb logcat -d > "$OUT/logcat.txt" || true
ls "$OUT/screens" | head -n 80
exit $failed

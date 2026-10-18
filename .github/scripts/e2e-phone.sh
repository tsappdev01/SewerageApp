#!/usr/bin/env bash
# Runs on the booted emulator (from android/): installs the app and its end-to-end tests, approves the
# phone when it asks to be registered (db/ops/approve_device.sql), runs EndToEndTest, checks the database
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
  docker exec mrsql /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "$SA_PASSWORD" -C -I -b -d MeterReading -W -s " | " -i /tmp/run.sql
}

adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell cmd location set-location-enabled true || true
# A slow emulator may show "isn't responding" for its own apps; those dialogs are not the app's.
adb shell settings put global hide_error_dialogs 1 || true
adb shell input keyevent KEYCODE_HOME; sleep 5
# Stand at the DIP office: the emulator's GPS keeps reporting it.
( while true; do adb emu geo fix 55.170344 24.999906 >/dev/null 2>&1; sleep 3; done ) &
GEO=$!
adb logcat -c

# FR-002.8: the app asks to be registered on first start; approve it as IT would, with
# db/ops/approve_device.sql and the Phone ID and Check code the phone shows (read here from mr.Device).
approve_waiting_phone() {
  for _ in $(seq 1 80); do
    row=$(docker exec mrsql /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "$SA_PASSWORD" -C -d MeterReading -h -1 -W \
      -Q "SET NOCOUNT ON; SELECT TOP (1) AndroidId + ' ' + CheckCode FROM mr.Device WHERE Status = 'PENDING' ORDER BY RegisteredAtUtc DESC" | tr -d '\r' | grep -v '^$' | head -n 1)
    if [ -n "$row" ]; then
      set -- $row
      echo "Waiting phone $1, check code $2: approving for rashid@dip.example"
      sed -e "s/'PHONE_ID'/'$1'/" -e "s/'CHECK1'/'$2'/" -e "s/N'reader@dipark.com'/N'rashid@dip.example'/" -e "s/N'Phone 01'/N'$LABEL'/" \
        ../db/ops/approve_device.sql > "$OUT/approve.sql"
      docker cp "$OUT/approve.sql" mrsql:/tmp/approve.sql
      docker exec mrsql /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "$SA_PASSWORD" -C -I -b -d MeterReading -W -s " | " -i /tmp/approve.sql
      return 0
    fi
    sleep 3
  done
  echo "No phone asked to be registered"; return 1
}
approve_waiting_phone > "$OUT/approve.txt" 2>&1 &

echo "== EndToEndTest"
adb shell am instrument -w -r -e class com.meterreading.reader.e2e.EndToEndTest "$RUNNER" | tee "$OUT/instrument-e2e.txt"
cat "$OUT/approve.txt"
grep -q "^OK (" "$OUT/instrument-e2e.txt" || failed=1

echo "== Waiting for the PMS copy (every 10 s)"
sleep 30
echo "== Database checks"
sql_file dev/900_e2e_checks.sql | tee "$OUT/db-checks.txt"
[ "${PIPESTATUS[0]}" -eq 0 ] || failed=1

echo "== Blocking the phone, then RevokedPhoneTest"
sql_file ops/revoke_device.sql | tee "$OUT/revoke.txt"
adb logcat -c
adb shell am instrument -w -r -e class com.meterreading.reader.e2e.RevokedPhoneTest "$RUNNER" | tee "$OUT/instrument-revoked.txt"
grep -q "^OK (" "$OUT/instrument-revoked.txt" || failed=1

kill $GEO 2>/dev/null || true
adb pull "/sdcard/Android/data/$PKG/files/e2e/." "$OUT/screens/" >/dev/null 2>&1 || true
adb exec-out screencap -p > "$OUT/screens/zz_last_screen.png" || true
adb logcat -d > "$OUT/logcat.txt" || true
# The app's own lines and its network calls during RevokedPhoneTest.
grep -E "fieldservice|okhttp|OkHttp|meterreading|AndroidRuntime|System.err" "$OUT/logcat.txt" | tail -n 300 > "$OUT/logcat-revoked.txt" || true
ls "$OUT/screens" | head -n 80
exit $failed

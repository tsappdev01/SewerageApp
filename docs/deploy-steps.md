# Deploy, step by step

The order to follow, for the setup on 2026-10-04: database **MRDB** on **UATWEB01** (the views
there read `PropertyManagementSystem`), the API on IIS, phones registered with their own key.
Each step ends with a **Check**. Details and reasons are in `docs/deployment.md` (section numbers
in brackets).

Who does what: **DBA** (parts A, E), **IT / web server admin** (parts B, B+), **developer** (part C),
**IT and supervisors** (part D).

**Where does the API go?** If the phones' public address can be on a server that reaches
UATWEB01, do part B only. If the internet-facing server is in a **DMZ without database access**,
do part B on an *internal* server and then part **B+** (the gateway in the DMZ).

---

## Part A — Database (DBA, in SSMS, connected to UATWEB01)

**A1. Pull the latest code** from branch `claude/meter-reading-initial`. All scripts are in `db/`.

**A2. Check the views.** Open `db/001_check_source_views.sql`, choose database **MRDB**, run it.
- **Check:** result 1 shows only `vw_MR_ReadingHistory` rows ("Optional view not found"): fine.
- Result 2 and 3 list data to fix. Before readers start, at least:
  - every reader has their **own `LoginEmail`** (not the shared `nayyar@techsource`);
  - the two shared barcodes (`I-2208-0001`, `S-2208-0001`) each get their own barcode;
  - only the month being read is `OPEN`.

**A3. Create the API's tables.** In MRDB, run in this order:
`002_create_mr_schema.sql`, `003_meter_id_as_text.sql`, `004_add_expected_photos.sql`,
`005_reading_tenant_and_export.sql`, `006_pms_transfer.sql`, `007_reading_image_data.sql`,
`009_device_keys.sql`. All are safe to run again. (`008` comes in A4.)
- **Check:** `SELECT name FROM sys.tables WHERE schema_id = SCHEMA_ID('mr')` lists
  `Device`, `DeviceRegistrationCode`, `ReadingImage`, `ReadingImageData`, `ReadingTransaction`.

**A3b. Field Inspection** (when the inspection views are ready):
1. In PropertyManagementSystem, remove `TOP (1000)` from `vw_MR_InspectionUnit` (`TenantCode` on units is not needed).
2. In MRDB run `db/010_field_inspection.sql`, `db/012_inspection_location.sql`, then `db/011_inspection_wrapper_views.sql`.
   The DIP office (24.999906, 55.170344) is in `appsettings.json` (`Inspection:OfficeLatitude/OfficeLongitude`); override in `web.config` only if it moves.
3. Run `db/001_check_source_views.sql`: no rows about `vw_MR_Inspection*` in results 1 and 2.
- **Check:** after A4, `/health/ready` is ready and `/api/v1/me` (with a registered phone) says `"canInspect":true`.

**A4. The API's login `mr_api`.** It exists already. **Change its password**, because the old
one was pushed to GitHub:
```sql
ALTER LOGIN mr_api WITH PASSWORD = '<new strong password, 12+ characters>';
```
Then open `db/008_create_api_login.sql`, put the **same new password** in `@Password` (do not
save the file), and run it in MRDB. It keeps the password and (re)applies the permissions.
- **Check:** the message ends with `Permissions granted to mr_api.`

**A5. Size and backups** (1.5). Run once:
```sql
ALTER DATABASE MRDB MODIFY FILE (NAME = N'MRDB',     SIZE = 10GB, FILEGROWTH = 1GB);
ALTER DATABASE MRDB MODIFY FILE (NAME = N'MRDB_log', FILEGROWTH = 512MB);
```
Schedule a full backup daily and **log backups every 15 minutes** (recovery model is FULL).
Plan to move the files off C:.
- **Check:** the backup jobs show in SQL Server Agent.

---

## Part B — API on the web server (IT)

**B1. Prepare the server.**
- Install the **ASP.NET Core 10.0 Hosting Bundle**, then run `iisreset`.
- Make sure the web server reaches **UATWEB01 on port 1433**.
- Get an **HTTPS certificate** for the address the phones will use, e.g.
  `https://zApps.dipark.com` (the DMZ gateway, part B+). For field use the address must be reachable from
  mobile data (public DNS and firewall port 443).
- **Check:** `Test-NetConnection UATWEB01 -Port 1433` shows `TcpTestSucceeded : True`.

**B2. Build the API** (on a PC with the .NET 10 SDK, in the repository folder):
```powershell
dotnet publish api\src\MeterReading.Api -c Release -o publish
```
Delete `publish\appsettings.Development.json`.
- **Check:** `publish\MeterReading.Api.dll` and `publish\web.config` exist.

**B3. Copy** the `publish` folder to the server, e.g. `D:\Sites\MeterReadingApi`.

**B4. Application pool.** IIS Manager → Application Pools → Add: name `MeterReadingApi`,
**.NET CLR version: No Managed Code**. Advanced settings: **Start Mode = AlwaysRunning**.

**B5. Site.** IIS Manager → Sites → Add Website: physical path `D:\Sites\MeterReadingApi`, pool
`MeterReadingApi`, binding **https** on port 443 with the certificate and host name. Do not add
plain http.

**B6. Settings.** IIS Manager → the site → **Configuration Editor** →
`system.webServer/aspNetCore` → `environmentVariables` → add:

| Name | Value |
|---|---|
| `ASPNETCORE_ENVIRONMENT` | `Production` |
| `ConnectionStrings__MeterReading` | `Server=UATWEB01;Database=MRDB;User Id=mr_api;Password=<new password>;Encrypt=True;TrustServerCertificate=True;Application Name=MeterReadingApi` |
| `Auth__Mode` | `Device` |
| `PmsTransfer__Enabled` | `false` |

Click **Apply**. The values are written into the site's `web.config`, so only admins should be able
to read that folder.

**B7. Start and check.** Recycle the pool, then open in a browser:
- `https://<address>/health/live` → `{"status":"live"}`
- `https://<address>/health/ready` → `{"status":"ready"}`. If it says `not ready`, its `missing` list
  names what to fix (2.6). A placeholder password stops the site from starting; the reason is in
  the Windows Event Log (Application).
- `https://<address>/api/v1/me` → `403` with `"code":"DEVICE_NOT_REGISTERED"`. This is correct: only
  registered phones get in.

---

## Part B+ — DMZ gateway (IT; only when the DMZ cannot reach the database)

Full detail, with the PowerShell for the certificates: `gateway/README.md`.

**B+1. Certificates.** Public certificate for the phones' address on the gateway server; a
**gateway client certificate** in the gateway server's `LocalMachine\My` (note its thumbprint, give
`IIS AppPool\MeterReadingGateway` read access to its key); its public part trusted on the API server.

**B+2. Build:** `dotnet publish gateway\src\MeterReading.Gateway -c Release -o publish-gateway`.
Copy to the DMZ server, new pool `MeterReadingGateway` (*No Managed Code*), site with https 443.

**B+3. Gateway settings** (Configuration Editor → environmentVariables): `ASPNETCORE_ENVIRONMENT` =
`Production`, `Gateway__ApiBaseUrl` = the internal API's https address,
`Gateway__ClientCertificate__Thumbprint` = the client certificate's thumbprint, optionally
`Gateway__ApiCertificateThumbprint` = the API certificate's thumbprint.

**B+4. API settings** (add to B6): `Gateway__KnownProxies__0` = the gateway's IP,
`Gateway__RequireClientCertificate` = `true`, `Gateway__ClientCertificateThumbprints__0` = the
client certificate's thumbprint. API site → **SSL Settings** → Client certificates **Accept** (or
**Require**). Recycle both pools.

**B+5. Firewall:** Internet → gateway 443; gateway → API 443 only; API → UATWEB01 1433; nothing
from the DMZ or internet to SQL Server or the API.

- **Check** from outside: `https://<public>/gateway/health` → live; `https://<public>/health/live` →
  live; `https://<public>/health/ready` → 404; `https://<public>/api/v1/me` → `403
  DEVICE_NOT_REGISTERED`. From an office PC straight to the API's `/api/v1/me` → refused or `403
  GATEWAY_REQUIRED`.
- In part C and D the **server address is the gateway's public address**.

---

## Part C — Build the Android app (developer, once per release)

**C1. Build machine.** Install **Android Studio** (current stable) with **Android SDK 36**. Open the
`android` folder and let Gradle sync.
- **No Android Studio at hand?** The workflow `.github/workflows/android-apk.yml` builds on every push:
  tests, debug APK, and the release build, which it also starts on an emulator. With the upload-key
  secrets set it attaches the signed **release APK** and the **Play bundle (.aab)** to the Releases
  page, so C3–C5 are done there.
- **Check:** *Build → Make Project* succeeds.

**C2. Tests.** In the Android Studio terminal: `.\gradlew testDebugUnitTest`.
- **Check:** all pass.

**C3. Upload key (once, keep it forever).**
```powershell
keytool -genkeypair -v -keystore dip-upload.jks -alias dip-upload -keyalg RSA -keysize 2048 -validity 10000
```
Keep the `.jks` file and both passwords in the password vault. With Google Play App Signing, Google
holds the key the phones see and this is the *upload* key; if it is lost, Google can reset it. For
the workflow add the secrets `ANDROID_KEYSTORE_BASE64` (`base64 -w0 dip-upload.jks`),
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.

**C4. Version.** `versionName` is in `android\app\build.gradle.kts` (1.0.0). `versionCode` must go up
with every upload: the workflow uses its run number; by hand pass `-PversionCode=<number>`.

**C5. Build the signed release** (bundle for Google Play, APK for direct install):
```powershell
$env:MR_UPLOAD_KEYSTORE="C:\keys\dip-upload.jks"; $env:MR_UPLOAD_KEYSTORE_PASSWORD="..."
$env:MR_UPLOAD_KEY_ALIAS="dip-upload"; $env:MR_UPLOAD_KEY_PASSWORD="..."
.\gradlew bundleRelease assembleRelease -PversionCode=<number> -PapiBaseUrl=https://zApps.dipark.com/
```
Outputs: `app\build\outputs\bundle\release\app-release.aab` and `app\build\outputs\apk\release\app-release.apk`.
Without the `MR_UPLOAD_*` values the release is signed with the debug key: fine for trying, refused
by Google Play.
- **Check:** `apksigner verify --print-certs app-release.apk` shows the upload key's certificate.

**C6. Try it on one phone** before handing out: install with `adb install app-release.apk`
or copy the file to the phone. Then do D3–D6 on that phone.

---

## Part D — Phones (IT and supervisors, once per phone)

**D1. Put the app on the phones.** Intune → *Apps → Android → Add → Line-of-business app* →
upload `DIPFieldService-1.0.0.apk` → assign to the readers' group. For a few phones, copy the APK and
install it instead.

**D2. Phone ready.** Android 10 or newer, a **screen lock** set (PIN, pattern, fingerprint or face),
an **English** text-to-speech voice (*Settings → Accessibility → Text-to-speech*).

**D3. Registration code (IT, in SSMS).** Open `db/ops/new_device_code.sql`, set `@Label` to the
phone's name or asset tag (e.g. `N'Phone 07'`), run it in MRDB. Give the code (like
`K7QM-2XPA-9TRB`) to the supervisor. It works once, for 24 hours.

**D4. Set up the phone (supervisor).**
1. Open **DIP Field Service** and allow notifications when asked. Unlock with the phone's PIN, finger
   or face.
2. The start screen says "No reader is set". Tap the **gear** and type the **supervisor PIN**
   (from C5).
3. In **Settings**: check the **Server address** and tap **Test** → "Server found".
4. **This phone** → type the code → **Register this phone**. The app goes back to the start screen.
5. Gear → PIN → Settings → **Reader on this phone**: the reader's email as in `vw_MR_Reader`.
   Keep **Unlock with the phone's lock** on. Optionally **Change supervisor PIN**. **Save**.
- **Check:** Settings shows **Registered as Phone 07**, and the app opens Home with "Hello, <name>".
  `db/ops/list_devices.sql` shows the phone with `LastReaderId` filled in.

**D5. First reading.** The reader reads one meter: photo, number, taps the tenant, **Yes, send**.
- **Check** in SSMS:
  ```sql
  SELECT TOP 5 MeterId, NewReading, TenantCode, Status, ReceivedAtUtc FROM mr.ReadingTransaction ORDER BY ReceivedAtUtc DESC;
  SELECT TOP 5 i.ImageRole, DATALENGTH(d.ImageBytes) AS Bytes FROM mr.ReadingImage i JOIN mr.ReadingImageData d ON d.BlobPath = i.BlobPath ORDER BY i.ReceivedAtUtc DESC;
  ```

**D6. No-signal test.** Switch on flight mode, read two meters (result: "Saved"), close the app,
restart the phone, open the app (Home says "No signal: list from …"). Switch flight mode off.
- **Check:** within a minute the app (or, if closed, a notification) asks **"Signal is back — Send
  now / Later"**. Tap **Send now**; the readings appear in `mr.ReadingTransaction`. Try **Later** on
  another round: it asks again after 30 minutes.

---

## Part E — Later and ongoing

- **Lost or retired phone:** `db/ops/revoke_device.sql` with its `@Label`. Its next call is refused;
  readings on it stay on it. Set `Status = 'ACTIVE'` again if it is found.
- **Copy into MaintainMeterReading** (optional, 2.8): first confirm the formats with the PMS team.
  Then re-run `008` with `@AllowPmsCopy = 1`, set `PmsTransfer__Enabled = true`, recycle the pool and
  check `/health/ready`.
- **API update:** run any new `db/0xx` scripts first, keep the old `publish` folder, copy the new
  files over (keep the site's environment variables), recycle the pool, check `/health/ready`
  (2.9).
- **App update:** raise `versionCode`, build and sign with the **same** key (C4–C5), upload to Intune.
  Phones keep their registration, reader, supervisor PIN and waiting readings.
- **Developers running the API from Visual Studio:** keep the connection string in **user secrets**
  (right-click the project → *Manage User Secrets*), never in `appsettings*.json` (`api/README.md`).

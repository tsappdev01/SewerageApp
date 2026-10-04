# Deployment guide — Meter Reading API and Android app

Covers the database, the API and the Android app, in the order they are set up.
Last checked against the code on 2026-10-04 (API 104 tests passing; app version 0.2.0).

---

## 0. Read this first: what can be deployed today

| Part | Ready? |
|---|---|
| Database scripts (`db/`) | Yes |
| API on a server | Yes, but see point 1 for how it knows who the reader is |
| Android app (phone lock, supervisor PIN, Settings) | Yes, once it has been built in Android Studio (see 3.1) |
| Android app for **field use over the internet** | **Not yet.** Two pieces are missing, see below. |

1. **The server trusts the reader name the phone sends.** The phone opens with its own lock (PIN,
   pattern, finger or face) and sends the reader set in its Settings. The server cannot check the
   phone's lock, so it accepts that name only in **UAT mode** (section 2.7), where anyone who can
   reach the API could claim to be any reader. **Use it only on the office network or VPN.** For
   use over mobile data the server must recognise registered phones (device keys, not built yet);
   the API's Entra ID mode is still there but the phone no longer signs in with Entra.
2. **Readings waiting to upload are kept in memory.** If the app is closed or the phone restarts
   while there is no signal, those readings and photos are lost. Fine for a supervised UAT trial;
   not acceptable for field use.

Also: the app has **not yet been built with the Android SDK**. Expect small compile fixes the
first time it is opened in Android Studio.

---

## 1. Database

### 1.1 Where things live

| What | Where |
|---|---|
| Source views `vw_MR_Reader`, `vw_MR_Zone`, `vw_MR_Property`, `vw_MR_Meter`, `vw_MR_ReadingPeriod`, `vw_MR_Tenant` | `PropertyManagementSystem` (yours) |
| The API's own tables (schema `mr`): readings, photos, devices | `PropertyManagementSystem`, or another database on the same server |
| `MaintainMeterReading` (optional copy of accepted readings) | `PropertyManagementSystem` |

Putting `mr` in `PropertyManagementSystem` is simplest: one connection string, and the copy into
`MaintainMeterReading` needs no database name.

### 1.2 Check the views

Run `db/001_check_source_views.sql` in `PropertyManagementSystem`. It is read-only.

- **Result 1** lists missing views or columns. It must be **empty**.
- **Result 2** lists data problems, each with a count. A check that could not run shows an
  empty count and the SQL error in **Detail**. Fix at least these before readers start:
  - "Active readers sharing a LoginEmail": those readers cannot sign in. Today **every** reader
    has `nayyar@techsource`, so nobody can sign in.
  - "Property with no current tenant": those properties cannot be read (spec FR-006.12).

- **Result 3** lists the rows behind problems that must be fixed by hand: readers sharing a
  sign-in name, and barcodes (MeterId) used more than once.

The full list is in `docs/source-views.md` under "To fix in the views before go-live".

### 1.3 Create the API's tables

A DBA runs these in order, in the database chosen in 1.1. Each one is safe to run again.

Any compatibility level works: the API passes lists to SQL as XML, not `OPENJSON` (UAT's
`PropertyManagementSystem` is below level 130).

| Script | Does |
|---|---|
| `db/002_create_mr_schema.sql` | Schema `mr`: devices, readings, photo records |
| `db/003_meter_id_as_text.sql` | Upgrades an older `002`; does nothing on a new install |
| `db/004_add_expected_photos.sql` | Expected photo count |
| `db/005_reading_tenant_and_export.sql` | `MaintainMeterReading` fields and view `mr.vw_MeterReading` |
| `db/006_pms_transfer.sql` | Tracking for the copy into `MaintainMeterReading` |
| `db/007_reading_image_data.sql` | `mr.ReadingImageData`: the photos themselves |

**Never run anything in `db/dev/`** outside a developer's machine. Those scripts create test
views and a test `MaintainMeterReading`.

### 1.4 The API's SQL login

The API uses one SQL login, **`mr_api`**, for every database action, and nothing else uses it.
Run **`db/008_create_api_login.sql`** in **MRDB** after setting `@Password` at the top (12+
characters; never save it in the file). It creates the login if it is missing (an existing one
keeps its password), creates its users, and grants only what is listed below. Re-runnable. For a
Windows-only server, set `@WindowsLogin` to the app pool's account instead.

| Where | What |
|---|---|
| `mr` database (MRDB) | SELECT, INSERT, UPDATE on schema `mr`; SELECT on its `dbo.vw_MR_*` views |
| `PropertyManagementSystem` | SELECT on the six `dbo.vw_MR_*` views. Needed because MRDB's views read these and ownership chaining between databases is off. |
| `PropertyManagementSystem`, only with `@AllowPmsCopy = 1` | INSERT on `dbo.MaintainMeterReading` (section 2.8) |

The API never needs DELETE, never writes to the views, and never creates tables.

With the views in MRDB as wrappers (as on UAT), the API needs **one connection string, to
MRDB**: leave `ConnectionStrings__Source` empty.

### 1.5 Size, growth and backups

Photos are stored in the database (`mr.ReadingImageData`). Each is about 500 KB, so allow about
**1 GB per 2,000 photos**, in the data file and in backups. Example: 3,000 meters × 2 photos × 12
months ≈ 36 GB a year. SQL Server Express (10 GB limit) is not enough.

The UAT MRDB (scripted 2026-10-04) was created with SQL Server's small defaults. Before go-live:

```sql
-- Fixed growth steps instead of 1 MB and 10 %: photos would otherwise grow the file thousands of times.
ALTER DATABASE MRDB MODIFY FILE (NAME = N'MRDB',     SIZE = 10GB, FILEGROWTH = 1GB);
ALTER DATABASE MRDB MODIFY FILE (NAME = N'MRDB_log', FILEGROWTH = 512MB);
```

- **Recovery model FULL** needs scheduled **transaction log backups** (e.g. every 15 minutes),
  or the log grows without end; every photo passes through it. Use SIMPLE only if losing up to a
  day's readings since the last full backup is acceptable.
- The files are on **C:** (`...\MSSQL11.MSSQLSERVER\MSSQL\DATA\`). Move them to a data drive before
  the photos arrive, so a full C: cannot stop the server.
- Optional: put `mr.ReadingImageData` on its own filegroup or drive, so photos can be sized and
  backed up apart from the readings.

---

## 2. API

### 2.1 Server requirements

- Windows Server with IIS, **or** any Linux server or container host.
- **.NET 10 runtime**. On IIS: the *ASP.NET Core 10.0 Hosting Bundle*.
- Network access to SQL Server (port 1433).
- A **public HTTPS address** the phones can reach over mobile data, e.g.
  `https://meterreading-api.dubaiinvestments.example`. This needs a proper certificate; phones
  will not trust a self-signed one. *UAT mode: office network or VPN only, see section 0.*

### 2.2 Build

On a build machine with the .NET 10 SDK, from the repository root:

```bash
dotnet test api/MeterReading.slnx          # optional: needs the dev SQL Server, see api/README.md
dotnet publish api/src/MeterReading.Api -c Release -o publish
```

Copy the `publish` folder to the server. **Delete `appsettings.Development.json` from the server
copy**: it holds the developer's local connection string and is not needed.

### 2.3 Settings

Settings come from `appsettings.json`, and **environment variables override it** (a double
underscore `__` stands for `:`). Keep passwords in environment variables (or the IIS app pool's
settings), not in the file.

| Environment variable | Production value |
|---|---|
| `ASPNETCORE_ENVIRONMENT` | `Production` |
| `ConnectionStrings__MeterReading` | `Server=UATWEB01;Database=MRDB;User Id=mr_api;Password=<mr_api's password>;Encrypt=True;TrustServerCertificate=True;Application Name=MeterReadingApi`. `appsettings.json` has the same string with `Password=SET_ON_SERVER`; the API refuses to start until this variable gives the real password. Use `TrustServerCertificate=False` once SQL Server has a certificate the web server trusts. |
| `ConnectionStrings__Source` | Database with the views. **Leave empty** if it is the same database. |
| `SourceViews__Schema` | `dbo` |
| `Auth__Mode` | `Entra` |
| `Auth__ReaderRole` | `MeterReader` (the app role in 2.4) |
| `AzureAd__TenantId` | Your Entra tenant (directory) ID |
| `AzureAd__ClientId` | The API app registration's client ID |
| `AzureAd__Audience` | The API's Application ID URI, e.g. `api://meterreading-api` |
| `ImageStore__Kind` | `Database` (default) |
| `PmsTransfer__Enabled` | `false` until section 2.6 is done |

Other settings (the high-consumption factor, photo limits) have sensible defaults; see
`api/README.md`.

### 2.4 Microsoft Entra ID (done once by the Entra admin)

1. **App registrations → New registration**: name "Meter Reading API", single tenant.
2. **Expose an API**: set the Application ID URI (e.g. `api://meterreading-api`) and add the
   scope `access_as_user`.
3. **App roles → Create app role**: display name "Meter Reader", value **`MeterReader`**, allowed
   member types *Users/Groups*.
4. **Enterprise applications → Meter Reading API → Users and groups**: assign the readers, or a
   "Meter Readers" group, to the role. Someone without the role is refused even if their sign-in
   name is in `vw_MR_Reader`.
5. Put the tenant ID, client ID and Application ID URI in the settings (2.3).

Only needed if the API runs in Entra mode for other clients (e.g. the supervisor portal). The
phone app no longer signs in with Entra (FR-001.1).

Each reader's Entra sign-in name (UPN) must equal their `LoginEmail` in `vw_MR_Reader`.

### 2.5 Host on IIS (Windows)

1. Install the ASP.NET Core 10.0 Hosting Bundle, then run `iisreset`.
2. Copy the `publish` folder, e.g. to `D:\Sites\MeterReadingApi`.
3. **Application pool**: new pool `MeterReadingApi`, *.NET CLR version* = **No Managed Code**,
   pipeline Integrated, start mode *AlwaysRunning* (keeps the transfer job running).
4. **Site**: point it at the folder, add an **HTTPS binding** with the certificate. Remove any
   plain HTTP binding, or redirect it.
5. **Environment variables**: IIS Manager → *Configuration Editor* →
   `system.webServer/aspNetCore` → `environmentVariables`, add the values from 2.3. They are
   stored in the site's `web.config`; restrict who can read that folder.
6. The `publish` folder already has a `web.config` for the ASP.NET Core module. Leave it in place.

**On Linux or containers** (alternative): run `dotnet MeterReading.Api.dll` as a systemd service
behind nginx with HTTPS, or build a container from `mcr.microsoft.com/dotnet/aspnet:10.0`. Set
`ASPNETCORE_URLS` and the same environment variables.

### 2.6 Check it is working

| Check | Expected |
|---|---|
| `GET https://…/health/live` | `{"status":"live"}`: the API is running |
| `GET https://…/health/ready` | `{"status":"ready"}`: database reachable, views and tables present |
| `GET https://…/api/v1/me` with no token | `401` |

If `/health/ready` says `not ready`, its `missing` list names what is wrong:

| Message | Fix |
|---|---|
| `vw_MR_…` | View missing, or the login has no SELECT on it (1.4) |
| `mr.ReadingTransaction (run db/002…)` and similar | Run the named script (1.3) |
| `mr.ReadingImageData (run db/007…)` | Run `db/007_reading_image_data.sql` |
| `database unreachable` | Connection string, firewall, or SQL login |
| `dbo.MaintainMeterReading … not found or no permission` | Only when the transfer is on: table name or INSERT permission |

Once a reader can sign in, problems show up as codes in the app's messages, e.g.
`READER_NOT_FOUND` (not in `vw_MR_Reader` or not active), `LOGIN_NOT_UNIQUE` (shared sign-in
name), `NO_OPEN_PERIOD` (no OPEN month in `vw_MR_ReadingPeriod`).

### 2.7 UAT mode (test sign-in, office network only)

The phone sends the reader set in its Settings; a UAT API trusts that name:

| Environment variable | UAT value |
|---|---|
| `ASPNETCORE_ENVIRONMENT` | `Development` (the API refuses UAT sign-in in any other environment) |
| `Auth__Mode` | `Development` |
| `ConnectionStrings__MeterReading` | The UAT database (overrides the developer value) |

**Anyone who can reach this API can act as any reader.** Bind it to the office network or VPN
only. Field use over the internet needs the server to recognise registered phones (section 0).

### 2.8 Copy readings into MaintainMeterReading (optional)

Off by default. Before switching it on, confirm with the PMS team the items listed in
`docs/readings-table.md` → "To confirm with the PMS team before switching on" (date format,
MeterStatus values, MeterReader, SubTenant length, `Posted`). Then:

1. Grant INSERT on `MaintainMeterReading` (1.4).
2. Set `PmsTransfer__Enabled=true`. If `mr` is in a different database, set
   `PmsTransfer__TargetTable=PropertyManagementSystem.dbo.MaintainMeterReading`.
3. Restart the site, then check `/health/ready`.
4. Watch for failures:
   `SELECT TransactionId, PmsCopyAttempts, PmsCopyError FROM mr.ReadingTransaction WHERE PmsCopiedAtUtc IS NULL AND PmsCopyAttempts > 0;`

Use **either** this copy **or** an existing process reading `mr.vw_MeterReading`, never both,
or readings are posted twice.

### 2.9 Updates and rollback

1. Run any new `db/0xx_*.sql` scripts first. They only add, so the old API keeps working.
2. Keep the previous `publish` folder. Stop the app pool (or drop an `app_offline.htm` into the
   site folder), copy the new files over (keep the server's `web.config` environment
   variables), start it, and check `/health/ready`.
3. Roll back by copying the previous folder back. The database needs no rollback.

Phones keep a reading and retry it with the same ID, so a short outage loses nothing that is
still on the phone (but see section 0, point 2).

### 2.10 Logs and backups

- Logs go to the console. On IIS, set `stdoutLogEnabled="true"` in `web.config` only while
  investigating, or send logs to your logging tool.
- Back up the `mr` schema with `PropertyManagementSystem`; it now includes the photos.

---

## 3. Android app

### 3.1 Build machine (once)

- Android Studio (current stable), with Android SDK Platform **35** and JDK **17** (bundled).
- Open the `android/` folder. Let Gradle sync. **The first sync may show small compile errors**:
  the screens were written without the Android SDK, so the build has never run. Fix them before
  anything else.
- Run the unit tests: `./gradlew test` (53 tests; the live-API tests are skipped unless
  `MR_API_URL` is set).

### 3.2 Phone lock, supervisor PIN and Settings (gear icon)

- **Opening the app:** the phone's own lock — PIN, pattern, password, fingerprint or face. Then
  Home opens directly as the reader set in Settings. After 15 minutes in the background the lock
  is asked again. A phone without a screen lock cannot open the app: set one first.
- **Gear icon** (start screen and Home) → **supervisor PIN** → **Settings**:
  - **Server address**, with a **Test** button (asks the API's `/health/live`).
  - **Reader on this phone**: their `LoginEmail` from `vw_MR_Reader`.
  - **Unlock with the phone's lock**: On/Off.
  - **Change supervisor PIN**.
- Changing the server or reader is refused while readings still wait on the phone.
- **Forgotten supervisor PIN:** clear the app's data (Intune: *wipe app data*, or phone *Settings →
  Apps → Meter Reading → Storage → Clear data*); the build's first values come back.

The build only sets the **first values**. To hand out phones ready to use, set them at build time:

| Gradle property | Meaning | Default |
|---|---|---|
| `apiBaseUrl` | The API's HTTPS address | `http://10.0.2.2:5080/` (emulator → this PC) |
| `useFakeData` | `true` = demo with sample data, no server | `false` |
| `readerLogin` | The reader on this phone (usually set per phone in Settings instead) | empty (debug builds: `rashid@dip.example`) |
| `deviceLock` | Ask for the phone's lock | `true` |
| `settingsPin` | First supervisor PIN, 4–8 digits | empty: the first person to open Settings sets it |

### 3.3 UAT build (test sign-in)

```bash
cd android
./gradlew assembleDebug -PapiBaseUrl=https://meterreading-uat.dubaiinvestments.example/ -PsettingsPin=<pin>
# output: app/build/outputs/apk/debug/app-debug.apk
```

- Use this with an API in UAT mode (2.7).
- On each phone, a supervisor opens Settings (gear → PIN) and sets the **reader on this phone**.
- A debug build also allows plain `http://`. Use HTTPS for anything beyond one developer's PC.
- For a demo with no server: `./gradlew assembleDebug -PuseFakeData=true`.

### 3.4 Release build

1. **Create the signing key once** and keep it safe. It cannot be replaced for updates to the
   same app:
   ```bash
   keytool -genkeypair -v -keystore meterreader-release.jks -alias meterreader \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
   Store the `.jks` file and its passwords in your password vault, never in the repository.
2. **Raise the version** in `android/app/build.gradle.kts` for every release: `versionCode` (a
   whole number that must always go up) and `versionName`.
3. **Build and sign**: Android Studio → *Build → Generate Signed App Bundle / APK* → APK →
   choose the keystore → *release*, or from the command line:
   ```bash
   ./gradlew assembleRelease -PapiBaseUrl=https://meterreading-api.dubaiinvestments.example/ -PsettingsPin=<pin>
   apksigner sign --ks meterreader-release.jks --out app-release.apk \
     app/build/outputs/apk/release/app-release-unsigned.apk
   ```
4. Release builds refuse plain `http://`.
5. First start on each phone: a supervisor sets the reader in Settings. From then on the reader
   unlocks with the phone's PIN, finger or face and Home opens.

### 3.5 Put it on the phones

- **Managed phones (recommended):** upload the signed APK to your MDM (e.g. Microsoft Intune →
  *Apps → Android → Line-of-business app*, or a private app in *Managed Google Play*). Assign
  it to the readers' group; updates follow the same route.
- **Small UAT group:** copy the APK to the phone and install it, allowing installs from that
  source.

### 3.6 Phone requirements and first-run checks

- **Android 10 or newer** (minSdk 29), with a rear camera.
- Allow the **camera** permission when asked.
- **Read-aloud** uses the phone's text-to-speech. Check that an **English** voice is installed
  (*Settings → Accessibility → Text-to-speech*).
- **Voice notes** use Google speech input; it needs the Google app and a connection.
- Mobile data or Wi-Fi that can reach the API address.
- First run: sign in → choose a zone → read one meter at a property with a tenant → see "Sent"
  → check the reading in `mr.ReadingTransaction` and its photo in `mr.ReadingImageData`.

---

## 4. Go-live checklist

**Views and data**
- [ ] `db/001` result 1 empty; shared sign-in names fixed; properties without a tenant reviewed.
- [ ] Only the month being read is `OPEN` in `vw_MR_ReadingPeriod`.
- [ ] Each meter's real `RegisterDigits` and `DecimalDigits` provided.

**Database**
- [ ] Scripts `002`–`008` run in MRDB; `mr_api`'s password set in `ConnectionStrings__MeterReading`; backups include `mr`.
- [ ] Database has room for photos (1.5).

**API**
- [ ] Deployed with HTTPS and a trusted certificate; `ASPNETCORE_ENVIRONMENT=Production`,
      `Auth__Mode=Entra`.
- [ ] Entra app registration, `MeterReader` role assigned to the readers.
- [ ] `/health/ready` = ready.
- [ ] Transfer into `MaintainMeterReading` either confirmed and on, or knowingly left off.

**App** (blocked until these are built)
- [ ] Server recognises registered phones (device keys), so the API need not run in UAT mode.
- [ ] Each phone has a screen lock, its reader set in Settings, and a supervisor PIN.
- [ ] Encrypted on-phone queue that survives the app closing.
- [ ] Built, signed with the release key, tested on the readers' phone models, and distributed
      through MDM.

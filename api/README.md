# Meter Reading API

.NET 10 Web API for the Sewerage & Irrigation Meter Reading System. It **reads** master data
from SQL Server views your team provides, and keeps what the phones send in its own `mr` tables.
This first part covers the Meter Reader app's read endpoints; submitting readings comes next.

## Source views

The API reads five `vw_MR_*` views in `PropertyManagementSystem` (plus an optional history view). How each column is read, and what to fix in the views, is in
**[docs/source-views.md](../docs/source-views.md)**. After creating them, run
`db/001_check_source_views.sql` (repository root): no rows in either result means the API can use them.

## Database scripts (`/db` at the repository root)

| Script | What it does | Where |
|---|---|---|
| `001_check_source_views.sql` | Checks the views: missing columns, wrong types, duplicate keys, unknown codes, more than one open period. Read-only. | Any environment |
| `002_create_mr_schema.sql` | Creates the API's own tables `mr.Device`, `mr.ReadingTransaction`, `mr.ReadingImage`. Re-runnable. | Any environment |
| `004_add_expected_photos.sql` | Adds `mr.ReadingTransaction.ExpectedPhotos` (photos the phone will upload). Re-runnable. | Any environment |
| `005_reading_tenant_and_export.sql` | Adds the source readings-table fields to readings (RowId, PropertyId, PropertyCode, MeterNumber, TenantCode, Type, SubTenant, Posted, Transferred, TransferredToBaan) and the view `mr.vw_MeterReading` in `MaintainMeterReading`'s column names. See `docs/readings-table.md`. Re-runnable. | Any environment |
| `006_pms_transfer.sql` | Adds the columns that track the copy into `MaintainMeterReading` (`PmsRowId`, `PmsCopiedAtUtc`, `PmsCopyAttempts`, `PmsCopyError`). Re-runnable. | Any environment |
| `007_reading_image_data.sql` | Creates `mr.ReadingImageData`, which holds the photos when `ImageStore:Kind` is `Database` (the default). Re-runnable. | Any environment |
| `009_device_keys.sql` | Registered phones: `mr.Device` key hash, label, revoke time; `mr.DeviceRegistrationCode` for one-time codes. Re-runnable. | Any environment |
| `ops/new_device_code.sql`, `ops/list_devices.sql`, `ops/revoke_device.sql` | IT's tasks: make a one-time registration code, list phones, block a lost phone (`docs/deployment.md` 2.11). | Any environment, as needed |
| `008_create_api_login.sql` | Creates the API's login `mr_api` (set `@Password` first) and grants what it needs in the `mr` database and in the views' database (SELECT on the views, SELECT/INSERT/UPDATE on `mr`, optional INSERT on `MaintainMeterReading`). Re-runnable. | Any environment |
| `003_meter_id_as_text.sql` | Brings an `mr` schema from an earlier `002` in line: `MeterId` as text (barcode), readings to 4 decimals. Does nothing on a fresh install. | Any environment |
| `dev/000_create_dev_source_views.sql` | Test stand-ins shaped like the real views (barcode ids, Status 1, every month OPEN, ISNULL zeros), with sample data. | Development only |
| `dev/010_seed_dev_readings.sql` | Sample readings already received this period. | Development only |
| `dev/020_create_dev_maintain_meter_reading.sql` | Stand-in `dbo.MaintainMeterReading` for the transfer tests. **Never run where the real table exists.** | Development only |

## Endpoints

All under `/api/v1`, for signed-in active readers (Entra app role `MeterReader`). Work is not
assigned: every reader sees every active meter, and `?zone=597,598` narrows lists. A meter read by
anyone shows as done for everyone. "My readings" and "read by you" are the reader's own.
Errors are RFC 9457 problem details with a `code` (e.g. `READER_NOT_FOUND`, `NO_OPEN_PERIOD`).

| Endpoint | Returns |
|---|---|
| `POST /devices/register` | No sign-in. A phone exchanges a one-time code (`REGISTRATION_CODE_INVALID` if wrong, used or expired) for its id and secret key. Rate limited. |
| `GET /me` | Reader name and the open reading period |
| `GET /sync/meters?zone=` | Zones, properties and meters to read, with last reading, expected range and state (`PENDING`, `SENT`, `CHECKING`, `READ_AGAIN`, `REVISIT`) |
| `GET /properties/search?q=&zone=&done=&type=` | Find a Property: matches code, name or meter number, ignoring case, spaces and dashes |
| `GET /readings/mine?period=` | The reader's submissions |
| `GET /summary?period=&zone=` | Reconciliation: meters, read, accepted, being checked, read again, visit again, not read, read by you, per zone |
| `GET /meters/{id}` | One active meter (id = barcode) with history when the history view exists |
| `POST /readings` | Send one reading (below). 201 when stored, 200 when the same `transactionId` was already stored |
| `PUT /readings/{id}/images/{imageId}` | Upload one photo of a stored reading (below) |
| `GET /readings/{id}/images/{imageId}` | One of the reader's own photos |
| `GET /health/live`, `GET /health/ready` | Liveness; readiness checks the views and `mr` tables exist |

OpenAPI document (Development): `/openapi/v1.json`.

### Sending a reading

```json
POST /api/v1/readings
{ "transactionId": "7d3c0f0e-…", "meterId": "BC0006", "condition": "WORKING", "newReading": 52840,
  "readerConfirmedWarning": false, "capturedAtUtc": "2026-10-04T07:15:22Z" }
```

Checks run in the order of spec §7.2. A refusal stores nothing and returns a problem `code`:
`VALIDATION_FAILED`, `INVALID_LOV_CODE`, `TRANSACTION_ID_REUSED`, `NO_OPEN_PERIOD`,
`CAPTURE_TIME_INVALID`, `METER_NOT_FOUND`, `NO_TENANT`, `TENANT_NOT_CONFIRMED`, `TENANT_CHANGED`,
`MANDATORY_FIELD_MISSING`, `READING_EXCEEDS_REGISTER`, `ALREADY_READ`. A stored reading is `ACCEPTED`, or `EXCEPTION` for the supervisor with reasons
(`LOWER_THAN_PREVIOUS`, `HIGH_CONSUMPTION`, `ROLLOVER_OUT_OF_RANGE`, `DAMAGED_METER`,
`METER_REPLACEMENT`, `METER_REMOVAL`). A meter can be read again only after the supervisor
rejects its reading or when it was not accessible; the check runs under a lock, so two phones
cannot both read it. `photoCount` says how many photos will follow; `subTenant` (optional, up to
100 characters) is the sub-tenant name the reader typed.

**`tenantCode` is required** (spec FR-006.12): the tenant the reader tapped on the check screen. It
must be a current tenant of the meter's property in `vw_MR_Tenant`, checked when the reading arrives:
none on record → `NO_TENANT`; missing → `TENANT_NOT_CONFIRMED`; not the property's (e.g. changed
since the phone synced) → `TENANT_CHANGED`. Sync lists each property's `tenants` for the phone.
The server adds the property, meter number and meter type from the views, so the reading keeps
them if they change later.

### Uploading photos

After the reading is stored, each photo goes up on its own, so a slow photo never holds up the
reading:

```
PUT /api/v1/readings/{transactionId}/images/{imageId}?role=DISPLAY&capturedAtUtc=2026-10-04T07:15:10Z
Content-Type: image/jpeg
X-Content-SHA256: <SHA-256 of the body, hex>
<JPEG bytes>
```

The server checks the reading is the caller's (`READING_NOT_FOUND`), the role
(`INVALID_LOV_CODE`), that the body is a JPEG (`IMAGE_INVALID`) no larger than
`ImageStore:MaxImageBytes` (`IMAGE_TOO_LARGE`), that it matches its hash
(`IMAGE_HASH_MISMATCH`), and the limit of `ImageStore:MaxImagesPerReading` (`TOO_MANY_IMAGES`).
The same photo sent again returns 200; a different photo under the same id is `IMAGE_ID_REUSED`.
Photos are stored once, never overwritten, in `mr.ReadingImageData` under the name
`readings/{yyyy}/{MM}/{meterId}/{transactionId}/{imageId}.jpg`, and recorded in `mr.ReadingImage`.
"My readings" and the summary show photos still to come (`photosExpected`, `photosReceived`,
`photosWaiting`).

## Configuration (`appsettings.json`)

| Setting | Meaning |
|---|---|
| `ConnectionStrings:MeterReading` | Database with the `mr` tables (write access). |
| `ConnectionStrings:Source` | Database with the views. Empty = same as `MeterReading`. |
| `SourceViews:Schema` | Schema of the views (default `dbo`). |
| `SourceViews:HasReadingHistory` | `true` only when `vw_MR_ReadingHistory` is provided (default `false`). |
| `ReadingRules:*` | Average periods, high-consumption factor and floors, default averages (spec BR-007, BR-008). |
| `ImageStore:Kind` | `Database` (default: photos in `mr.ReadingImageData`, backed up with the readings), `FileSystem` (photos under `ImageStore:Root`, which the API must be able to write) or `AzureBlob` (`ImageStore:BlobServiceUri` and `Container`, reached with the API's managed identity; the container stays private). |
| `ImageStore:MaxImageBytes`, `MaxImagesPerReading` | Upload limits: 2 MB and 4 photos by default. |
| `PmsTransfer:Enabled` | Copies accepted readings into `MaintainMeterReading` (default `false`). See `docs/readings-table.md`. |
| `PmsTransfer:TargetTable` | `dbo.MaintainMeterReading`, or `PropertyManagementSystem.dbo.MaintainMeterReading` when `mr` is in another database on the same server. |
| `PmsTransfer:IntervalSeconds`, `BatchSize`, `MaxAttempts` | Every 60 s, up to 100 readings; a reading that failed 10 times is left for someone to look at. |
| `PmsTransfer:ReadingDateFormat`, `TimeZone`, `MeterStatusMap` | How `ReadingDate` and `MeterStatus` are written (default `yyyy-MM-dd HH:mm:ss` in UAE time; condition codes as they are unless mapped, e.g. `{"WORKING": "Working"}`). |
| `Auth:Mode` | `Device` (production for the phone app: headers `X-Device-Id`, `X-Device-Key` and `X-Reader`; refusals `DEVICE_NOT_REGISTERED`, `DEVICE_REVOKED`), `Entra`, or `Development` (trusts `X-Dev-User` or `X-Reader`; refused outside Development). |
| `Gateway:KnownProxies`, `Gateway:RequireClientCertificate`, `Gateway:ClientCertificateThumbprints` | Behind the DMZ gateway (`gateway/README.md`): `X-Forwarded-For` is used only from these IPs (so rate limits count per phone), and with the certificate required every request except `/health/*` needs the gateway's client certificate (`403 GATEWAY_REQUIRED`). Empty/off by default. |
| `Devices:RegisterPerMinute` | Registration tries per address per minute (default 10). |
| `AzureAd:*` | Entra tenant and API app registration. |

Use a SQL login or managed identity with **SELECT only** on the views and read/write on schema `mr`.

## Run locally against UATWEB01 (Visual Studio)

Never put a password in `appsettings*.json`: they are in git. Keep yours in .NET user secrets, which
live in your Windows profile, not in the repository:

```powershell
cd api\src\MeterReading.Api
dotnet user-secrets set "ConnectionStrings:MeterReading" "Server=UATWEB01;Database=MRDB;User Id=mr_api;Password=<password>;Encrypt=True;TrustServerCertificate=True;Application Name=MeterReadingApi"
```

Visual Studio: right-click the project → *Manage User Secrets* does the same. Without it the API
stops at startup and says so, because the committed settings carry `Password=SET_ON_SERVER`.

## Run locally

```bash
# SQL Server 2022 in Docker
docker run -d --name mrsql -p 1433:1433 -e ACCEPT_EULA=Y -e MSSQL_SA_PASSWORD='Dev_Passw0rd!' mcr.microsoft.com/mssql/server:2022-latest
# create database MeterReading, then run in order:
#   ../db/dev/000_create_dev_source_views.sql, ../db/002_create_mr_schema.sql, ../db/003_meter_id_as_text.sql,
#   ../db/004_add_expected_photos.sql, ../db/005_reading_tenant_and_export.sql, ../db/006_pms_transfer.sql, ../db/007_reading_image_data.sql,
#   ../db/dev/010_seed_dev_readings.sql, ../db/dev/020_create_dev_maintain_meter_reading.sql
dotnet run --project src/MeterReading.Api            # Development: http://localhost:5080
curl -H "X-Dev-User: rashid@dip.example" "http://localhost:5080/api/v1/sync/meters?zone=598"
dotnet test                                          # all tests; integration tests need the database above (MR_TEST_SQL to override)
```

## Design notes

- **Views are read-only** and may be in another database, so view data and `mr` data are queried
  separately and joined in memory. Lists of meter ids go to SQL as one XML parameter (`Data/SqlList.cs`),
  which avoids SQL Server's 2,100-parameter limit and, unlike `OPENJSON`, works at any database
  compatibility level (`PropertyManagementSystem` on UATWEB01 (the development server) is below 130).
- **Every view column is CAST** (TRY_CAST for optional numbers) to a fixed type, so the views'
  own types and codes pass through: `MeterType` by first letter, `Status` as 1/True/ACTIVE, the
  period code built from `StartDate`, and 0 read as "never read" / "no average".
- **One sign-in name, one reader.** If `vw_MR_Reader` has the same `LoginEmail` on two active
  rows, sign-in is refused with `LOGIN_NOT_UNIQUE` rather than guessing.
- **No assignment.** Meter lists are shared; only a reader's own submissions are scoped to them.
  Phones should sync by zone (`?zone=`) on large estates rather than downloading every meter.
- **The transfer is the only write outside `mr`.** Each reading is copied in one transaction
  that inserts into `MaintainMeterReading` and stores the new `RowId` in `PmsRowId`, under a lock,
  so a reading is copied once even with two API instances running. A failure rolls back and
  is counted in `PmsCopyAttempts` / `PmsCopyError`.
- Dapper rather than EF Core: the API does not own the source schema.

## Next

Device registration, supervisor endpoints (exception queue, photo review), and delta sync (`since` token).

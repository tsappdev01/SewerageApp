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
| `003_meter_id_as_text.sql` | Brings an `mr` schema from an earlier `002` in line: `MeterId` as text (barcode), readings to 4 decimals. Does nothing on a fresh install. | Any environment |
| `dev/000_create_dev_source_views.sql` | Test stand-ins shaped like the real views (barcode ids, Status 1, every month OPEN, ISNULL zeros), with sample data. | Development only |
| `dev/010_seed_dev_readings.sql` | Sample readings already received this period. | Development only |

## Endpoints

All under `/api/v1`, for signed-in active readers (Entra app role `MeterReader`). Work is not
assigned: every reader sees every active meter, and `?zone=597,598` narrows lists. A meter read by
anyone shows as done for everyone. "My readings" and "read by you" are the reader's own.
Errors are RFC 9457 problem details with a `code` (e.g. `READER_NOT_FOUND`, `NO_OPEN_PERIOD`).

| Endpoint | Returns |
|---|---|
| `GET /me` | Reader name and the open reading period |
| `GET /sync/meters?zone=` | Zones, properties and meters to read, with last reading, expected range and state (`PENDING`, `SENT`, `CHECKING`, `READ_AGAIN`, `REVISIT`) |
| `GET /properties/search?q=&zone=&done=&type=` | Find a Property: matches code, name or meter number, ignoring case, spaces and dashes |
| `GET /readings/mine?period=` | The reader's submissions |
| `GET /summary?period=&zone=` | Reconciliation: meters, read, accepted, being checked, read again, visit again, not read, read by you, per zone |
| `GET /meters/{id}` | One active meter (id = barcode) with history when the history view exists |
| `POST /readings` | Send one reading (below). 201 when stored, 200 when the same `transactionId` was already stored |
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
`CAPTURE_TIME_INVALID`, `METER_NOT_FOUND`, `MANDATORY_FIELD_MISSING`, `READING_EXCEEDS_REGISTER`,
`ALREADY_READ`. A stored reading is `ACCEPTED`, or `EXCEPTION` for the supervisor with reasons
(`LOWER_THAN_PREVIOUS`, `HIGH_CONSUMPTION`, `ROLLOVER_OUT_OF_RANGE`, `DAMAGED_METER`,
`METER_REPLACEMENT`, `METER_REMOVAL`). A meter can be read again only after the supervisor
rejects its reading or when it was not accessible; the check runs under a lock, so two phones
cannot both read it. Photos are not uploaded yet.

## Configuration (`appsettings.json`)

| Setting | Meaning |
|---|---|
| `ConnectionStrings:MeterReading` | Database with the `mr` tables (write access). |
| `ConnectionStrings:Source` | Database with the views. Empty = same as `MeterReading`. |
| `SourceViews:Schema` | Schema of the views (default `dbo`). |
| `SourceViews:HasReadingHistory` | `true` only when `vw_MR_ReadingHistory` is provided (default `false`). |
| `ReadingRules:*` | Average periods, high-consumption factor and floors, default averages (spec BR-007, BR-008). |
| `Auth:Mode` | `Entra` (production) or `Development` (trusts `X-Dev-User` header; refused outside Development). |
| `AzureAd:*` | Entra tenant and API app registration. |

Use a SQL login or managed identity with **SELECT only** on the views and read/write on schema `mr`.

## Run locally

```bash
# SQL Server 2022 in Docker
docker run -d --name mrsql -p 1433:1433 -e ACCEPT_EULA=Y -e MSSQL_SA_PASSWORD='Dev_Passw0rd!' mcr.microsoft.com/mssql/server:2022-latest
# create database MeterReading, then run in order:
#   ../db/dev/000_create_dev_source_views.sql, ../db/002_create_mr_schema.sql, ../db/003_meter_id_as_text.sql, ../db/dev/010_seed_dev_readings.sql
dotnet run --project src/MeterReading.Api            # Development: http://localhost:5080
curl -H "X-Dev-User: rashid@dip.example" "http://localhost:5080/api/v1/sync/meters?zone=598"
dotnet test                                          # all tests; integration tests need the database above (MR_TEST_SQL to override)
```

## Design notes

- **Views are read-only** and may be in another database, so view data and `mr` data are queried
  separately and joined in memory. Lists of meter ids go to SQL as one JSON parameter (`OPENJSON`),
  which avoids SQL Server's 2,100-parameter limit on big routes.
- **Every view column is CAST** (TRY_CAST for optional numbers) to a fixed type, so the views'
  own types and codes pass through: `MeterType` by first letter, `Status` as 1/True/ACTIVE, the
  period code built from `StartDate`, and 0 read as "never read" / "no average".
- **One sign-in name, one reader.** If `vw_MR_Reader` has the same `LoginEmail` on two active
  rows, sign-in is refused with `LOGIN_NOT_UNIQUE` rather than guessing.
- **No assignment.** Meter lists are shared; only a reader's own submissions are scoped to them.
  Phones should sync by zone (`?zone=`) on large estates rather than downloading every meter.
- Dapper rather than EF Core: the API does not own the source schema.

## Next

`POST /api/v1/readings` (idempotent, multipart with photos to Blob storage), device registration,
supervisor endpoints, and delta sync (`since` token).

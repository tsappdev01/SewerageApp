# SewerageApp — working notes for Claude

Sewerage & Irrigation Meter Reading System. Requirements: `docs/spec.md`. Cite requirement
IDs (FR-, BR-, SEC-, NFR-, INT-) in test names and on non-obvious code.

## SQL lives in `db/`

Every SQL script goes in `db/`, numbered and named for what it does. Scripts are re-runnable:
guard DDL with `OBJECT_ID(...)` and `sys.indexes`, inserts with `NOT EXISTS`, and put
`CREATE SCHEMA` and each `CREATE INDEX` in its own `GO` batch. `db/dev/` is test data and must
never run outside development.

## API (`api/`)

- .NET 10 minimal API, Dapper on Microsoft.Data.SqlClient. The phone app authenticates as a registered
  phone (`Auth:Mode` `Device`: device key + reader, FR-002); Entra ID is still supported for other clients.
  Only hashes of registration codes and device keys are stored.
- Master data comes from the customer's `vw_MR_*` views (`docs/source-views.md`). **Never write
  to them** and never hard-code their schema: use `SqlConnectionFactory.Views`. CAST every view
  column to the row type in SQL.
- The API's own tables are schema `mr`; a new column needs a new numbered script in `db/`.
- The only write outside `mr` is `PmsTransferService` inserting into `MaintainMeterReading`
  (off unless `PmsTransfer:Enabled`). Keep it insert-only, once per reading, in one transaction
  with the `PmsRowId` update.
- Work is not assigned: meter lists are shared by all active readers and narrowed by zone. A
  reader's own submissions ("my readings", "read by you") are scoped to them; test that.
- A reading is saved only with the tenant the reader checked on site, which must be a current
  tenant of the property in `vw_MR_Tenant` (FR-006.12). The phone checks before saving or queueing;
  the server checks again on arrival.
- Errors are problem details with a `code` from spec Appendix A.
- Photos go through `IImageStore` (database by default, table `mr.ReadingImageData`; folder or
  Azure Blob by setting) and are never overwritten; a reading is
  stored first and its photos follow one by one, checked against their SHA-256.
- `dotnet test api/MeterReading.slnx` needs SQL Server prepared as in `api/README.md`.

## Gateway (`gateway/`)

- The DMZ's only piece: YARP, no database, stores nothing. The routes it passes on are a fixed
  list in `Routes.cs`; a new app endpoint must be added there (and tested) or phones get 404.
- It strips `X-Dev-User` and forwarding headers and sets its own `X-Forwarded-For`; the API trusts
  that only from `Gateway:KnownProxies` and can require the gateway's client certificate.
- `dotnet test gateway/MeterReading.Gateway.slnx` needs no database.

## Android (`android/`)

- Plain, short English; every screen has a read-aloud line (`speak_*` strings).
- One navy primary button per screen; state is never shown by colour alone.
- Business rules mirror the server's in `data/`; the server decides.

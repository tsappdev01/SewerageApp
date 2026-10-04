# SewerageApp — working notes for Claude

Sewerage & Irrigation Meter Reading System. Requirements: `docs/spec.md`. Cite requirement
IDs (FR-, BR-, SEC-, NFR-, INT-) in test names and on non-obvious code.

## SQL lives in `db/`

Every SQL script goes in `db/`, numbered and named for what it does. Scripts are re-runnable:
guard DDL with `OBJECT_ID(...)` and `sys.indexes`, inserts with `NOT EXISTS`, and put
`CREATE SCHEMA` and each `CREATE INDEX` in its own `GO` batch. `db/dev/` is test data and must
never run outside development.

## API (`api/`)

- .NET 10 minimal API, Dapper on Microsoft.Data.SqlClient, Entra ID auth.
- Master data comes from the customer's `vw_MR_*` views (`docs/source-views.md`). **Never write
  to them** and never hard-code their schema: use `SqlConnectionFactory.Views`. CAST every view
  column to the row type in SQL.
- The API's own tables are schema `mr`; a new column needs a new numbered script in `db/`.
- Every endpoint scopes data to the signed-in reader; add an integration test proving it.
- Errors are problem details with a `code` from spec Appendix A.
- `dotnet test api/MeterReading.slnx` needs SQL Server prepared as in `api/README.md`.

## Android (`android/`)

- Plain, short English; every screen has a read-aloud line (`speak_*` strings).
- One navy primary button per screen; state is never shown by colour alone.
- Business rules mirror the server's in `data/`; the server decides.

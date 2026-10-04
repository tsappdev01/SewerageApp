# SewerageApp

Sewerage & Irrigation Meter Reading System for Dubai Investments Park: field readers capture
meter readings with photo evidence on Android; a .NET API validates them and passes accepted
readings to billing.

| Folder | What it is |
|---|---|
| [`docs/`](docs) | [Specification](docs/spec.md), [source view contract](docs/source-views.md), [screen mock](docs/meter-reader-mock.html) |
| [`api/`](api) | .NET 10 Web API. Reads master data from SQL Server views, stores readings in its own `mr` tables. See [api/README.md](api/README.md). |
| [`db/`](db) | SQL scripts, numbered in run order. `db/dev/` is test data, development only. |
| [`android/`](android) | Meter Reader app, Kotlin and Jetpack Compose. See [android/README.md](android/README.md). |

## Status

- **API:** reader read endpoints done (`/me`, sync, Find a Property, my readings, summary, meter detail),
  tested against SQL Server 2022. Work is not assigned; lists narrow by zone. Submitting readings is next.
- **Android:** all reader screens on sample data, English only, Dubai Investments Park colours. Not
  yet compiled with the Android SDK; the plain-Kotlin parts compile and their 15 tests pass.
- **Source views:** the five views in `PropertyManagementSystem` are read as they are. Items to fix
  before go-live are listed in [docs/source-views.md](docs/source-views.md#to-fix-in-the-views-before-go-live).

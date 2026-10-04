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

- **API:** reader endpoints done, including sending readings (`POST /readings`, safe to retry);
  66 tests against SQL Server 2022. Work is not assigned; lists narrow by zone. Photo upload is next.
- **Android:** all reader screens, connected to the API (development sign-in until Entra ID),
  with an upload queue for readings taken without signal. Not yet compiled with the Android SDK;
  the plain-Kotlin parts compile and their 28 tests pass, including against the running API.
- **Source views:** the five views in `PropertyManagementSystem` are read as they are. Items to fix
  before go-live are listed in [docs/source-views.md](docs/source-views.md#to-fix-in-the-views-before-go-live).

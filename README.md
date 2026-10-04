# SewerageApp

Sewerage & Irrigation Meter Reading System for Dubai Investments Park: field readers capture
meter readings with photo evidence on Android; a .NET API validates them and passes accepted
readings to billing.

| Folder | What it is |
|---|---|
| [`docs/`](docs) | [Specification](docs/spec.md), [source view contract](docs/source-views.md), [readings table](docs/readings-table.md), [screen mock](docs/meter-reader-mock.html), [deployment guide](docs/deployment.md), [step-by-step deploy](docs/deploy-steps.md), the Dubai Investments Park logo |
| [`api/`](api) | .NET 10 Web API. Reads master data from SQL Server views, stores readings in its own `mr` tables. See [api/README.md](api/README.md). |
| [`db/`](db) | SQL scripts, numbered in run order. `db/dev/` is test data, development only. |
| [`gateway/`](gateway) | DMZ gateway on 443: passes the app's requests to the internal API; no database. See [gateway/README.md](gateway/README.md). |
| [`android/`](android) | Meter Reader app, Kotlin and Jetpack Compose. See [android/README.md](android/README.md). |

## Status

- **API:** reader endpoints done, including sending readings and their photos (safe to retry;
  photos stored in the database); copy of accepted readings into `MaintainMeterReading`
  (off until switched on, see [docs/readings-table.md](docs/readings-table.md#copying-readings-into-maintainmeterreading));
  the reader checks the property's tenant before every reading is saved (spec FR-006.12);
  118 tests against SQL Server 2022. Work is not assigned.
- **DMZ gateway:** YARP reverse proxy for a DMZ without database access: fixed list of the app's
  requests, size and rate limits, mutual TLS to the API (which then accepts only the gateway); 30 tests.
- **Android:** all reader screens, connected to the API; opens with the phone's own lock (PIN,
  pattern, finger or face) as the reader set in Settings; each phone registered with its own key;
  Settings (gear) behind a supervisor PIN;
  the meter list and an encrypted queue kept for working without signal; when signal returns it asks "Send now or Later". Built by GitHub Actions
  (`.github/workflows/android-apk.yml`): every push to `android/` runs the unit tests and puts the APK
  on the [Releases page](https://github.com/tsappdev01/SewerageApp/releases). Not yet tried on a real phone.
- **Source views:** the six views in `PropertyManagementSystem` (now with `vw_MR_Tenant`) are read as they are. Items to fix
  before go-live are listed in [docs/source-views.md](docs/source-views.md#to-fix-in-the-views-before-go-live).

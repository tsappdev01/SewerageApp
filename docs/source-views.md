# Source views read by the Meter Reading API

The API **reads** master data from views in the `PropertyManagementSystem` database. It never
writes to them. Readings captured on the phone are stored in the API's own tables (schema `mr`),
see the end.

This page describes the views as they exist (scripted 2026-10-04) and how the API reads each
column. Default schema: `dbo` (`SourceViews:Schema` in `appsettings.json`).

Run `db/001_check_source_views.sql` in that database after any change to the views. Result 1
lists missing columns and wrong types; result 2 lists data problems and notes, with a count.

## Conventions

- Keep the column names below; aliases in the view are fine.
- **Key columns must be unique** and must never be reused for a different thing.
- The API CASTs every column itself, so exact SQL types do not matter as long as the values convert.

---

## 1. `vw_MR_Reader` — who can sign in

| Column | Read as | Notes |
|---|---|---|
| **UserId** | text, key | `MaintainUser.UserId`. Stored on every reading the user submits. |
| LoginEmail | text | **Must be each reader's own Microsoft 365 sign-in name.** The API finds the signed-in user by it. If two active readers share one, both are refused with `LOGIN_NOT_UNIQUE`. |
| DisplayName | text | Shown on the phone: "Hello, …". |
| TeamCode | text | `RoleCode`. |
| SupervisorEmail | text | |
| IsActive | 1/0 | Only `1` can sign in. |

## 2. `vw_MR_Zone`

| Column | Read as | Notes |
|---|---|---|
| **ZoneCode** | text, key | `597`, `598`. |
| ZoneName | text | Trailing spaces are trimmed. |
| IsActive | 1/0 | |

## 3. `vw_MR_Property`

| Column | Read as | Notes |
|---|---|---|
| PropertyId | — | Not read by the API (the meter view joins on it). |
| **PropertyCode** | text, key | |
| PropertyName | text | |
| ZoneCode | text | Must exist in `vw_MR_Zone`. |
| RouteSequence | int | Walking order; the API sorts by it. |
| Latitude, Longitude | number | A value that is not a number is read as empty. |
| IsActive | 1/0 | `Billable`. Only `1` is listed. |
| TenantCode | text | From `MaintainTransactionKeys`. |
| CompanyName | text | The tenant's company (`MaintainTenant.CompanyName`). **Shown on the phone under the property code**, and searchable in Find a Property. If empty, the phone shows `PropertyName`. |

## 4. `vw_MR_Meter`

One row per meter.

| Column | Read as | Notes |
|---|---|---|
| **MeterId** | text, key (max 50) | `Barcode`. Must be unique and never change: readings are stored against it. |
| MeterNumber | text | |
| PropertyCode | text | Must exist in `vw_MR_Property`. |
| MeterType | text | First letter `I` = irrigation, `S` = sewerage; anything else is reported by the checker. |
| RegisterDigits | int | Number of whole-number wheels. The phone shows one box per wheel. |
| DecimalDigits | int | Readings are kept to 4 decimal places. |
| OpeningReading | number | The meter's **last billed reading** (last transferred `Current_Reading`). This is "Last time" on the phone and the base for consumption. **0 or NULL means never read**: the next reading is the meter's first (spec BR-004). |
| LastConsumption | number | Consumption of the latest reading; shown as "used last time". |
| AvgConsumption | number | Average of the last 6 consumptions. Sets the "much bigger than usual" warning at 3 × average (spec BR-008). **0 or NULL means no average**: no warning. |
| InstallDate | date | |
| SerialNumber | text | |
| RouteSequence | int | Order of meters inside the property. |
| Status | 1/0 | `1`, `True` or `ACTIVE` is active; anything else is left out. |

## 5. `vw_MR_ReadingPeriod`

| Column | Read as | Notes |
|---|---|---|
| PeriodCode | — | Not read: `CONCAT(YEAR, '-', MONTH)` gives `2026-9`. The API builds `2026-09` from `StartDate`. |
| StartDate | date | First day of the billing month. |
| EndDate | date | Last day of the billing month. |
| Status | text | `OPEN`, `CLOSED` or `PLANNED`. When several months are `OPEN`, the API reads into the **latest**. |

## 6. `vw_MR_ReadingHistory` (optional, not provided)

Past readings for the supervisor's history view and for averages when `AvgConsumption` is 0.
Not needed while `AvgConsumption` is in the meter view. If added later, set
`SourceViews:HasReadingHistory` to `true`.

| Column | Notes |
|---|---|
| MeterId | Barcode, as in `vw_MR_Meter`. |
| PeriodCode | `yyyy-MM` |
| ReadingDate, ReadingValue, Consumption | |
| ConsumptionBasis | `ACTUAL` or `AVERAGE` |

## Work is not assigned

There is no assignment view. Every active reader may read every active meter in the open period;
a meter read by anyone shows as done for everyone. Readers choose zones on the phone.

---

## To fix in the views before go-live

1. **`vw_MR_Reader.LoginEmail`** is `'nayyar@techsource'` for every user, so no reader can sign in
   (`LOGIN_NOT_UNIQUE`). It needs each reader's own sign-in name, e.g. from `MaintainUser`.
2. **`RegisterDigits` 10 and `DecimalDigits` 4** are the same for every meter. The phone shows one
   box per wheel, so it would ask readers for 14 digits on every meter. Please provide each meter's
   real number of wheels; if decimals are not read from the meter, set `DecimalDigits` to 0.
3. **`vw_MR_ReadingPeriod.Status`** is `OPEN` for every month. The API copes (latest month wins),
   but only the month being read should be `OPEN`, so a late reading cannot land in an old month.
4. **`ISNULL(..., 0)`** on `OpeningReading` and `AvgConsumption` is handled (0 = never read / no
   average), but NULL would be clearer if a real meter can read exactly 0.
5. **`vw_MR_Property`** lists only zones 597 and 598, and only properties that appear in
   `MaintainMeterReadingLog`. Confirm that new properties with no log entry should be left out.
6. **`MeterType`**: confirm `MaintainPropertyMeter.Type` values start with `I` and `S`.
7. **Tenant join in `vw_MR_Property`** is an `INNER JOIN`: a property with no tenant row (e.g.
   vacant) disappears, and so do its meters, because `vw_MR_Meter` joins the property view. Use a
   `LEFT JOIN` if vacant properties must still be read. A property with **two** tenant rows appears
   twice; the API keeps one row per meter and per property, and the checker reports the duplicate.

## What the API stores itself (schema `mr`)

The API needs **its own schema with write access**, in `PropertyManagementSystem` or another
database (`ConnectionStrings:MeterReading` in `api/src/MeterReading.Api/appsettings.json`). Run, in order:

- `db/002_create_mr_schema.sql` — creates `mr.Device`, `mr.ReadingTransaction`, `mr.ReadingImage`.
- `db/003_meter_id_as_text.sql` — only needed if an earlier `002` was run; converts `MeterId` to
  text (barcode) and readings to 4 decimals. Safe to run either way.
- `db/004_add_expected_photos.sql` — adds the expected photo count to readings.
- `db/005_reading_tenant_and_export.sql` — adds the `MaintainMeterReading` fields (TenantCode,
  SubTenant, Posted, Transferred, …) and the view `mr.vw_MeterReading`. See `docs/readings-table.md`.

Photos are not kept in the database: they go to a folder or an Azure Blob container
(`ImageStore` in `appsettings.json`); `mr.ReadingImage` records where.

The API's login needs **SELECT** on the views and **read/write** on schema `mr`.

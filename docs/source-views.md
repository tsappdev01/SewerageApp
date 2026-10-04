# Source views required by the Meter Reading API

The API **reads** master data (readers, zones, properties, meters, periods, assignments and
previous readings) from SQL Server views that your team provides. It never writes to them.
Readings captured on the phone are stored in the API's own tables (schema `mr`), see the end.

Default schema for the views: `dbo`. It can be changed in `appsettings.json`
(`SourceViews:Schema`) without a code change.

Run `db/001_check_source_views.sql` after creating the views: it lists any view or column
that is missing or has an incompatible type.

## Conventions

- Names are case-insensitive; keep the column names exactly as below (aliases in the view are fine).
- **Key columns must be unique** within the view and must never be reused for a different thing.
- Codes in `MeterType` and `Status` columns must use the exact values listed (map them in the view).
- Readings are `decimal(18,3)`. Dates are `date`; timestamps are `datetime2` in UTC.
- "Required" means the API needs the column; nullable columns may return NULL.

---

## 1. `vw_MR_Reader` — who can read meters (required)

One row per meter reader. Used to identify the signed-in reader and their supervisor.

| Column | Type | Null | Notes |
|---|---|---|---|
| **ReaderId** | varchar(50) | no | Key. Employee or staff number. |
| LoginEmail | nvarchar(256) | no | Unique. The reader's Microsoft 365 / Entra sign-in name (UPN), e.g. `rashid@dip.ae`. The API matches the signed-in user on this. |
| DisplayName | nvarchar(100) | no | Shown on the phone: "Hello, Rashid". |
| TeamCode | varchar(20) | yes | Team the reader belongs to. |
| SupervisorEmail | nvarchar(256) | yes | Sign-in name of the reader's supervisor. |
| IsActive | bit | no | 0 = cannot sign in. |

## 2. `vw_MR_Zone` (required)

| Column | Type | Null | Notes |
|---|---|---|---|
| **ZoneCode** | varchar(20) | no | Key, e.g. `597`. |
| ZoneName | nvarchar(100) | yes | |
| IsActive | bit | no | |

## 3. `vw_MR_Property` (required)

One row per property (villa, building, plot).

| Column | Type | Null | Notes |
|---|---|---|---|
| **PropertyCode** | varchar(30) | no | Key, e.g. `1100`, `1499-W1`. |
| PropertyName | nvarchar(150) | yes | e.g. `Building 1499-W1`. |
| ZoneCode | varchar(20) | no | Must exist in `vw_MR_Zone`. |
| RouteSequence | int | yes | Walking order inside the zone. NULL sorts last. |
| Latitude | decimal(9,6) | yes | For map links later. |
| Longitude | decimal(9,6) | yes | |
| IsActive | bit | no | |

## 4. `vw_MR_Meter` (required)

One row per physical meter.

| Column | Type | Null | Notes |
|---|---|---|---|
| **MeterId** | bigint | no | Key. Stable internal id, never reused. |
| MeterNumber | varchar(30) | no | Number printed on the meter, e.g. `2001-I`. Unique among active meters. |
| PropertyCode | varchar(30) | no | Must exist in `vw_MR_Property`. |
| MeterType | varchar(20) | no | Exactly `IRRIGATION` or `SEWERAGE`. |
| RegisterDigits | tinyint | no | Number of whole-number wheels, e.g. `5` (max 99,999). |
| DecimalDigits | tinyint | no | Fractional wheels; `0` if none. |
| OpeningReading | decimal(18,3) | yes | Reading when installed. NULL = 0. |
| InstallDate | date | yes | |
| RouteSequence | int | yes | Order of meters inside the property. |
| SerialNumber | varchar(50) | yes | |
| Status | varchar(10) | no | Exactly `ACTIVE` or `INACTIVE`. |

## 5. `vw_MR_ReadingPeriod` (required)

| Column | Type | Null | Notes |
|---|---|---|---|
| **PeriodCode** | char(7) | no | Key, `yyyy-MM`, e.g. `2026-10`. |
| StartDate | date | no | First day readings may be taken. |
| EndDate | date | no | Last day readings may be taken. |
| Status | varchar(10) | no | Exactly `PLANNED`, `OPEN` or `CLOSED`. **At most one `OPEN`.** |

## 6. `vw_MR_Assignment` (required)

Which reader reads which meter in a period. One row per meter per period.

| Column | Type | Null | Notes |
|---|---|---|---|
| **PeriodCode** | char(7) | no | Key part 1. |
| **MeterId** | bigint | no | Key part 2. |
| ReaderId | varchar(50) | no | Must exist in `vw_MR_Reader`. |
| AssignedOn | datetime2 | yes | When it was assigned (UTC). |

If your system assigns **whole zones** rather than meters, provide `vw_MR_ZoneReader`
(`ZoneCode`, `ReaderId`) instead and set `SourceViews:AssignmentMode` to `Zone`; the API then
assigns every active meter in the zone to that reader for the open period.

## 7. `vw_MR_LastReading` (required)

The last **approved actual** reading of each meter, before the open period. This is the
"Last time" the reader sees and the base for consumption.

| Column | Type | Null | Notes |
|---|---|---|---|
| **MeterId** | bigint | no | Key. Meters with no reading yet are simply absent. |
| ReadingValue | decimal(18,3) | no | |
| ReadingDate | date | no | |
| PeriodCode | char(7) | yes | Period the reading belongs to. |
| AverageConsumption | decimal(18,3) | yes | Average consumption per period, if billing already calculates it (spec BR-007). If NULL the API calculates it from `vw_MR_ReadingHistory`, or uses the configured default. |

## 8. `vw_MR_ReadingHistory` (optional, recommended)

Past readings, for averages and for the supervisor's history view. Last 12 periods is enough.

| Column | Type | Null | Notes |
|---|---|---|---|
| **MeterId** | bigint | no | Key part 1. |
| **PeriodCode** | char(7) | no | Key part 2. |
| ReadingDate | date | yes | |
| ReadingValue | decimal(18,3) | yes | NULL when the period was estimated. |
| Consumption | decimal(18,3) | yes | |
| ConsumptionBasis | varchar(10) | no | `ACTUAL` or `AVERAGE`. |

---

## What the API stores itself (schema `mr`)

The API needs **its own schema with write access**, in the same database or another one
(`ConnectionStrings:MeterReading` in `api/src/MeterReading.Api/appsettings.json`). It holds what the phone sends and nothing that is in
the views:

- `mr.ReadingTransaction` — each submission (transaction ID, meter, reading, condition, reason, note, GPS, times, status).
- `mr.ReadingImage` — photo metadata and blob path (photos go to Azure Blob storage).
- `mr.Device` — registered phones.

## Questions for your team

1. Can the API have a separate schema `mr` with create/write rights in the same database as the views? If not, which database?
2. Does your system assign work **per meter** (`vw_MR_Assignment`) or **per zone** (`vw_MR_ZoneReader`)?
3. Is `LoginEmail` the same as the readers' Microsoft 365 sign-in name?
4. Does billing already calculate average consumption (`AverageConsumption`), or should the API?

# Readings table

Every reading the phone sends is one row in **`mr.ReadingTransaction`** (created by
`db/002`, extended by `db/003`–`db/005`). Photos are rows in **`mr.ReadingImage`**, one per photo.

**`mr.vw_MeterReading`** shows the live readings (not superseded by a correction) with the same
column names as `MaintainMeterReading`, so the existing posting and Baan transfer can read it
like that table. Times in the view are UAE time; the table keeps UTC.

## `mr.ReadingTransaction`

### Fields from `MaintainMeterReading`

| Column (view name if different) | Type | Filled by | Meaning |
|---|---|---|---|
| RowId | bigint, identity | database | Running number, unique. |
| PropertyId | bigint | API, from `vw_MR_Property` | Property id at the time of reading. |
| PropertyCode | varchar(30) | API, from `vw_MR_Property` | e.g. `1499-W1`. |
| MeterNumber | varchar(30) | API, from `vw_MR_Meter` | Number printed on the meter. |
| **TenantCode** | varchar(30) | API, from `vw_MR_Property` | **The tenant when the reading was taken.** Kept even if the tenant changes later. |
| CapturedAtUtc (**ReadingDate**) | datetime2 | phone | When the reader took the reading. |
| MeterCondition (**MeterStatus**) | varchar(20) | phone | `WORKING`, `DAMAGED`, `SUBMERSED`, `NOT_ACCESSIBLE`, `METER_REPLACED`, `REMOVED`. |
| Latitude, Longitude | decimal(9,6) | phone | GPS when the reader allowed location. |
| PreviousReading (**Previous_Reading**) | decimal(18,4) | API, from `vw_MR_Meter.OpeningReading` | Last billed reading. |
| NewReading (**Current_Reading**) | decimal(18,4) | phone | The reading. For a replaced meter the view shows the new meter's reading. |
| Posted | bit, default 0 | posting process | Set by the existing posting. |
| ReaderId (**MeterReader**) | varchar(50) | API | `vw_MR_Reader.UserId` of the signed-in reader. |
| MeterType (**Type**) | varchar(20) | API, from `vw_MR_Meter` | Exactly as the source writes it (`MaintainPropertyMeter.Type`). |
| ReceivedAtUtc (**UploadTime**) | datetime2, default now | database | When the server received the reading. |
| Transferred | bit, default 0 | transfer process | Set when transferred. `TransferredAtUtc` can hold when. |
| Consumption | decimal(18,4) | API | New − previous, with rollover handled (BR-006); for a replaced meter, old + new meter consumption. |
| TransferredToBaan | nvarchar(50), null | Baan transfer | Baan reference or flag. |
| **SubTenant** | nvarchar(100), null | **reader, optional** | Sub-tenant name typed on the check screen when the premises has one. |

### The API's own fields

| Column | Type | Meaning |
|---|---|---|
| TransactionId | uniqueidentifier, key | Made on the phone; the same id on a retry is never stored twice. |
| PeriodCode | char(7) | Billing month, `2026-10`. |
| MeterId | varchar(50) | Barcode (`vw_MR_Meter.MeterId`). |
| DeviceId | uniqueidentifier | Phone that sent it. |
| ReasonCode, Remarks | text | Why a meter was broken, not reachable, replaced or removed, and the reader's note. |
| OldFinalReading, NewMeterNumber, NewOpeningReading, NewCurrentReading | | Meter replacement details. |
| OcrValue, OcrConfidence | | For OCR assist (later). |
| GpsAccuracyM | decimal | GPS accuracy in metres. |
| Status | varchar(25) | `ACCEPTED`, `EXCEPTION` (for the supervisor), `APPROVED`, `REJECTED_BY_SUPERVISOR`, `POSTED`, `ACKNOWLEDGED`, `BILLING_FAILED`, `SUPERSEDED`. |
| StatusNote | text | Exception reasons (e.g. `HIGH_CONSUMPTION`) or the supervisor's reason. |
| StatusChangedAtUtc | datetime2 | Last status change. |
| ExpectedPhotos | tinyint | Photos the phone took; compare with `mr.ReadingImage` to see missing ones. |
| PayloadHash | char(64) | Fingerprint of what the phone sent (safe retries). |
| CorrectsTransactionId | uniqueidentifier | Set on a correction; the corrected reading becomes `SUPERSEDED`. |

## `mr.ReadingImage` — the pictures

| Column | Type | Meaning |
|---|---|---|
| ImageId | uniqueidentifier, key | Made on the phone. |
| TransactionId | uniqueidentifier | The reading it belongs to. |
| ImageRole | varchar(20) | `DISPLAY` (the numbers), `CONTEXT`, `OBSTRUCTION`, `DAMAGE`, `OLD_METER_FINAL`, `NEW_METER`. |
| BlobPath | nvarchar(400) | Where the photo is: `readings/{yyyy}/{MM}/{meter}/{reading}/{image}.jpg` in the photo folder or Azure Blob container. |
| Sha256 | char(64) | Checked on upload. |
| SizeBytes, Width, Height | int | |
| CapturedAtUtc | datetime2 | When the photo was taken. |
| ReceivedAtUtc | datetime2, default now | When the server received it. |

`mr.vw_MeterReading` adds **PhotoCount** and **PhotoPath** (the display photo) to each reading.

## Reading it from the existing process

```sql
-- Readings not yet transferred, in MaintainMeterReading's shape
SELECT RowId, PropertyId, PropertyCode, MeterNumber, TenantCode, ReadingDate, MeterStatus,
       Latitude, Longitude, Previous_Reading, Current_Reading, Posted, MeterReader, [Type],
       UploadTime, Transferred, Consumption, TransferredToBaan, SubTenant, PhotoPath
FROM mr.vw_MeterReading
WHERE Transferred = 0 AND Status IN ('ACCEPTED', 'APPROVED');   -- exceptions wait for the supervisor
```

The process marks rows done on the table itself (`UPDATE mr.ReadingTransaction SET Transferred = 1,
TransferredAtUtc = SYSUTCDATETIME(), TransferredToBaan = @ref WHERE RowId = @rowId`); its login
needs SELECT on the view and UPDATE on those columns only.

## Copying readings into `MaintainMeterReading`

The API can copy readings into PMS's own `MaintainMeterReading`, so posting and the Baan transfer
work exactly as today. It is **off** until `PmsTransfer:Enabled` is `true` (`appsettings.json`).

**What is copied:** readings with status `ACCEPTED`, or `APPROVED` by a supervisor, a condition of
`WORKING`, `DAMAGED`, `SUBMERSED` or `REMOVED`, and a reading value. Exceptions wait until a
supervisor approves them; `NOT_ACCESSIBLE` and replaced meters are not copied (no single reading).

**How:** every 60 seconds, up to 100 waiting readings, oldest first. Each one is a single
transaction: insert the row, then store the `RowId` it got in `mr.ReadingTransaction.PmsRowId`.
A reading with `PmsRowId` set is never copied again. If the insert fails, nothing is kept, the
error goes in `PmsCopyError` and `PmsCopyAttempts` goes up; after 10 attempts the reading waits
for someone to look at it. `GET /health/ready` reports a missing table or column.

| `MaintainMeterReading` | Written as |
|---|---|
| RowId | Given by the table; stored back in `PmsRowId`. |
| PropertyId, PropertyCode, MeterNumber, TenantCode, SubTenant | As stored with the reading. |
| ReadingDate | `CapturedAtUtc` in UAE time, as text `yyyy-MM-dd HH:mm:ss` (`ReadingDateFormat`, `TimeZone`). |
| MeterStatus | The condition code (`WORKING`, …), or the text from `MeterStatusMap`. |
| Latitude, Longitude | Up to 6 decimals; empty when no GPS. |
| Previous_Reading, Current_Reading | `PreviousReading`, `NewReading`, without trailing zeros (`1234.5`). |
| MeterReader | `vw_MR_Reader.UserId`. |
| Type | The meter type as the source writes it. |
| Posted, UploadTime, Transferred, Consumption, TransferredToBaan | Left to the table's defaults and calculation. |

Readings to see which are waiting or failing:

```sql
SELECT TransactionId, MeterNumber, Status, PmsCopyAttempts, PmsCopyError
FROM mr.ReadingTransaction
WHERE PmsCopiedAtUtc IS NULL AND PmsCopyAttempts > 0;
```

To retry one after fixing the cause: `UPDATE mr.ReadingTransaction SET PmsCopyAttempts = 0 WHERE TransactionId = @id`.

### To confirm with the PMS team before switching on

1. **ReadingDate** text format the posting expects (default `2026-10-04 09:15:30`).
2. **MeterStatus** values the table uses (e.g. `Working`); set them in `MeterStatusMap`.
3. **MeterReader**: `UserId` or the reader's name.
4. **SubTenant** length in the table: the app allows 100 characters; a longer value fails the copy and shows in `PmsCopyError`.
5. Whether `Posted` should start as `0` for copied rows (the table's default is used).
6. **Login:** INSERT on `MaintainMeterReading` for the API's login.
7. Use **either** this copy **or** reading `mr.vw_MeterReading` (above) in the existing process, not both, or readings would be posted twice.

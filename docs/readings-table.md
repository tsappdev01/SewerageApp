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

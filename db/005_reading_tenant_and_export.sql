/*
  005_reading_tenant_and_export.sql
  Brings the readings table in line with PropertyManagementSystem's own readings table
  (MaintainMeterReading): who the tenant was, the property and meter as they were when read,
  a sub-tenant typed by the reader, and the Posted / Transferred / TransferredToBaan flags used
  by the existing posting and Baan transfer.

  Then mr.vw_MeterReading shows every live reading under that table's column names, plus the
  API's own fields (photos, status, ids). docs/readings-table.md describes every column.
  Re-runnable: each column is added only if missing; the view is (re)created.
*/
IF COL_LENGTH(N'mr.ReadingTransaction', N'RowId') IS NULL
    ALTER TABLE mr.ReadingTransaction ADD RowId bigint IDENTITY(1, 1) NOT NULL;
GO
IF COL_LENGTH(N'mr.ReadingTransaction', N'PropertyId') IS NULL
    ALTER TABLE mr.ReadingTransaction ADD PropertyId bigint NULL;
GO
IF COL_LENGTH(N'mr.ReadingTransaction', N'PropertyCode') IS NULL
    ALTER TABLE mr.ReadingTransaction ADD PropertyCode varchar(30) NULL;
GO
IF COL_LENGTH(N'mr.ReadingTransaction', N'MeterNumber') IS NULL
    ALTER TABLE mr.ReadingTransaction ADD MeterNumber varchar(30) NULL;
GO
IF COL_LENGTH(N'mr.ReadingTransaction', N'MeterType') IS NULL
    ALTER TABLE mr.ReadingTransaction ADD MeterType varchar(20) NULL;  -- as the source writes it (MaintainPropertyMeter.Type)
GO
IF COL_LENGTH(N'mr.ReadingTransaction', N'TenantCode') IS NULL
    ALTER TABLE mr.ReadingTransaction ADD TenantCode varchar(30) NULL;
GO
IF COL_LENGTH(N'mr.ReadingTransaction', N'SubTenant') IS NULL
    ALTER TABLE mr.ReadingTransaction ADD SubTenant nvarchar(100) NULL;
GO
IF COL_LENGTH(N'mr.ReadingTransaction', N'Posted') IS NULL
    ALTER TABLE mr.ReadingTransaction ADD Posted bit NOT NULL CONSTRAINT DF_ReadingTransaction_Posted DEFAULT (0);
GO
IF COL_LENGTH(N'mr.ReadingTransaction', N'Transferred') IS NULL
    ALTER TABLE mr.ReadingTransaction ADD Transferred bit NOT NULL CONSTRAINT DF_ReadingTransaction_Transferred DEFAULT (0);
GO
IF COL_LENGTH(N'mr.ReadingTransaction', N'TransferredAtUtc') IS NULL
    ALTER TABLE mr.ReadingTransaction ADD TransferredAtUtc datetime2(0) NULL;
GO
IF COL_LENGTH(N'mr.ReadingTransaction', N'TransferredToBaan') IS NULL
    ALTER TABLE mr.ReadingTransaction ADD TransferredToBaan nvarchar(50) NULL;
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'UX_ReadingTransaction_RowId' AND object_id = OBJECT_ID(N'mr.ReadingTransaction'))
CREATE UNIQUE INDEX UX_ReadingTransaction_RowId ON mr.ReadingTransaction (RowId);
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_ReadingTransaction_Transfer' AND object_id = OBJECT_ID(N'mr.ReadingTransaction'))
CREATE INDEX IX_ReadingTransaction_Transfer ON mr.ReadingTransaction (Transferred, Posted) INCLUDE (Status, RowId);
GO

/*
  Readings under MaintainMeterReading's column names (first block), then the API's own fields.
  Superseded readings (replaced by a correction) are left out. Times are UAE time
  (Arabian Standard Time); the table keeps UTC.
*/
CREATE OR ALTER VIEW mr.vw_MeterReading AS
SELECT
    t.RowId,
    t.PropertyId,
    t.PropertyCode,
    t.MeterNumber,
    t.TenantCode,
    CAST(t.CapturedAtUtc AT TIME ZONE 'UTC' AT TIME ZONE 'Arabian Standard Time' AS datetime2(0)) AS ReadingDate,
    t.MeterCondition AS MeterStatus,
    t.Latitude,
    t.Longitude,
    t.PreviousReading AS Previous_Reading,
    COALESCE(t.NewReading, t.NewCurrentReading) AS Current_Reading,
    t.Posted,
    t.ReaderId AS MeterReader,
    t.MeterType AS [Type],
    CAST(t.ReceivedAtUtc AT TIME ZONE 'UTC' AT TIME ZONE 'Arabian Standard Time' AS datetime2(0)) AS UploadTime,
    t.Transferred,
    t.Consumption,
    t.TransferredToBaan,
    t.SubTenant,
    -- The API's own fields
    t.TransactionId,
    t.PeriodCode,
    t.MeterId,
    t.Status,
    t.StatusNote,
    t.ReasonCode,
    t.Remarks,
    t.OldFinalReading,
    t.NewMeterNumber,
    t.NewOpeningReading,
    t.CapturedAtUtc,
    t.ReceivedAtUtc,
    t.DeviceId,
    t.ExpectedPhotos,
    (SELECT COUNT(*) FROM mr.ReadingImage i WHERE i.TransactionId = t.TransactionId) AS PhotoCount,
    (SELECT TOP (1) i.BlobPath FROM mr.ReadingImage i WHERE i.TransactionId = t.TransactionId
     ORDER BY CASE i.ImageRole WHEN 'DISPLAY' THEN 0 ELSE 1 END, i.CapturedAtUtc) AS PhotoPath
FROM mr.ReadingTransaction t
WHERE t.Status <> 'SUPERSEDED';
GO

/*
  006_pms_transfer.sql
  Tracks the copy of each accepted reading into PropertyManagementSystem's MaintainMeterReading
  (done by the API's transfer job, see docs/readings-table.md). PmsRowId is the RowId the reading
  got there; it is set in the same transaction as the insert, so a reading is copied exactly once.
  Re-runnable.
*/
IF COL_LENGTH(N'mr.ReadingTransaction', N'PmsRowId') IS NULL
    ALTER TABLE mr.ReadingTransaction ADD PmsRowId bigint NULL;
GO
IF COL_LENGTH(N'mr.ReadingTransaction', N'PmsCopiedAtUtc') IS NULL
    ALTER TABLE mr.ReadingTransaction ADD PmsCopiedAtUtc datetime2(0) NULL;
GO
IF COL_LENGTH(N'mr.ReadingTransaction', N'PmsCopyAttempts') IS NULL
    ALTER TABLE mr.ReadingTransaction ADD PmsCopyAttempts int NOT NULL CONSTRAINT DF_ReadingTransaction_PmsCopyAttempts DEFAULT (0);
GO
IF COL_LENGTH(N'mr.ReadingTransaction', N'PmsCopyError') IS NULL
    ALTER TABLE mr.ReadingTransaction ADD PmsCopyError nvarchar(400) NULL;
GO

/* The transfer job's queue: readings not copied yet. (Not a filtered index: those need
   QUOTED_IDENTIFIER ON in every session that writes to the table.) */
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_ReadingTransaction_PmsPending' AND object_id = OBJECT_ID(N'mr.ReadingTransaction'))
CREATE INDEX IX_ReadingTransaction_PmsPending ON mr.ReadingTransaction (PmsCopiedAtUtc, ReceivedAtUtc)
    INCLUDE (Status, MeterCondition, PmsCopyAttempts);
GO

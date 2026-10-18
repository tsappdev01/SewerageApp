/*
  022_trancode_and_period_dates.sql
  - mr.ReadingTransaction.TranCode: the TranCode (vw_MR_Property, db/021) of the property's row for the
    tenant the reader checked, kept with each reading.
  - mr.MaintainFinalizedMeter: TranCode (from the row's readings), and FromDate / EndDate, the first and
    last day of the billing month (PeriodCode 2026-09: 2026-09-01 to 2026-09-30). Spec FR-037.10.
  Run in MRDB after 020. Re-runnable.
*/
IF COL_LENGTH(N'mr.ReadingTransaction', N'TranCode') IS NULL
    ALTER TABLE mr.ReadingTransaction ADD TranCode varchar(30) NULL;
GO
IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'TranCode') IS NULL
    ALTER TABLE mr.MaintainFinalizedMeter ADD TranCode varchar(30) NULL;
GO
IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'FromDate') IS NULL
    ALTER TABLE mr.MaintainFinalizedMeter ADD FromDate date NULL, EndDate date NULL;
GO
-- Rows written before this script: the dates follow from their PeriodCode.
UPDATE mr.MaintainFinalizedMeter
SET FromDate = CAST(PeriodCode + '-01' AS date), EndDate = EOMONTH(CAST(PeriodCode + '-01' AS date))
WHERE FromDate IS NULL;
GO

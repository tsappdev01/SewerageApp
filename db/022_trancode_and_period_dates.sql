/*
  022_trancode_and_period_dates.sql
  - mr.ReadingTransaction.TranCode: the TranCode (vw_MR_Property, db/021) of the property's row for the
    tenant the reader checked, kept with each reading.
  - mr.ReadingTransaction.BaanCode: the BaanCode (vw_MR_Tenant, db/023) of the tenant the reader checked.
  - mr.MaintainFinalizedMeter: TenantCode, TranCode and BaanCode, and FromDate / EndDate: the period's
    StartDate and EndDate in vw_MR_ReadingPeriod (2026-9: 2026-09-01 to 2026-09-30). Spec FR-037.10.
    When finalizing, BaanCode is looked up in vw_MR_Tenant and TranCode in vw_MR_Property for the row's
    PropertyCode and TenantCode (the reading's tenant), so they are the views' current values (a BaanCode
    can change during the month; billing uses the latest). The codes kept with readings are a record only.
  Run in MRDB after 020. Re-runnable.
*/
IF COL_LENGTH(N'mr.ReadingTransaction', N'TranCode') IS NULL
    ALTER TABLE mr.ReadingTransaction ADD TranCode varchar(30) NULL;
GO
IF COL_LENGTH(N'mr.ReadingTransaction', N'BaanCode') IS NULL
    ALTER TABLE mr.ReadingTransaction ADD BaanCode varchar(30) NULL;
GO
IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'BaanCode') IS NULL
    ALTER TABLE mr.MaintainFinalizedMeter ADD BaanCode varchar(30) NULL;
GO
IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'TenantCode') IS NULL
    ALTER TABLE mr.MaintainFinalizedMeter ADD TenantCode varchar(30) NULL;
GO
IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'TranCode') IS NULL
    ALTER TABLE mr.MaintainFinalizedMeter ADD TranCode varchar(30) NULL;
GO
IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'FromDate') IS NULL
    ALTER TABLE mr.MaintainFinalizedMeter ADD FromDate date NULL, EndDate date NULL;
GO
-- Rows written before this script: the first and last day of their PeriodCode month (as the view has them).
UPDATE mr.MaintainFinalizedMeter
SET FromDate = CAST(PeriodCode + '-01' AS date), EndDate = EOMONTH(CAST(PeriodCode + '-01' AS date))
WHERE FromDate IS NULL;
GO

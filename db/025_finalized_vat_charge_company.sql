/*
  025_finalized_vat_charge_company.sql
  Spec FR-037.12 (DIP 2026-10-18), on mr.MaintainFinalizedMeter:
  - VATCode 'SSD', ChargeType1 'sew' (sewerage) and ChargeType2 'Irri' (irrigation): fixed values, the
    same on every row.
  - CompanyName: the tenant's company from vw_MR_Tenant, looked up when finalizing (like BaanCode).
  Run in MRDB after 024. Re-runnable.
*/
IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'VATCode') IS NULL
    ALTER TABLE mr.MaintainFinalizedMeter ADD VATCode varchar(10) NOT NULL
        CONSTRAINT DF_MaintainFinalizedMeter_VATCode DEFAULT 'SSD';
GO
IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'ChargeType1') IS NULL
    ALTER TABLE mr.MaintainFinalizedMeter ADD ChargeType1 varchar(10) NOT NULL
        CONSTRAINT DF_MaintainFinalizedMeter_ChargeType1 DEFAULT 'sew';
GO
IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'ChargeType2') IS NULL
    ALTER TABLE mr.MaintainFinalizedMeter ADD ChargeType2 varchar(10) NOT NULL
        CONSTRAINT DF_MaintainFinalizedMeter_ChargeType2 DEFAULT 'Irri';
GO
IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'CompanyName') IS NULL
    ALTER TABLE mr.MaintainFinalizedMeter ADD CompanyName nvarchar(200) NULL;
GO

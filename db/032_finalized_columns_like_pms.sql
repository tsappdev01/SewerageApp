/*
  032_finalized_columns_like_pms.sql
  DIP 2026-10-31: mr.MaintainFinalizedMeter takes the column names and fields of PMS's dbo.MaintainFinalizedMeter.

  Renamed (same data):   FinalizedMeterId -> RowId, CompanyName -> TenantsName, EndDate -> ToDate,
                         FinalizedAtUtc -> CreatedDate (still UTC), SewerageAmount -> SewAmount,
                         IrrigationAmount -> IrrAmount
  Added, filled when finalizing from vw_MR_Property:  Area, Rate, DimensionCode (its BaanDimensionCode)
  Added with defaults:   TTBaan 'No', Status 'Ready', FusionInvoiceNo 0
  Added, empty for now:  InvNo (bigint)

  Rows finalized before this get the defaults and no Area / Rate / DimensionCode. Run in PropertyManagementSystem
  after 030, before deploying the API that writes these names. Re-runnable.
*/
DECLARE @t nvarchar(200) = N'mr.MaintainFinalizedMeter';
IF COL_LENGTH(@t, N'FinalizedMeterId') IS NOT NULL AND COL_LENGTH(@t, N'RowId') IS NULL       EXEC sp_rename N'mr.MaintainFinalizedMeter.FinalizedMeterId', N'RowId', N'COLUMN';
IF COL_LENGTH(@t, N'CompanyName') IS NOT NULL AND COL_LENGTH(@t, N'TenantsName') IS NULL      EXEC sp_rename N'mr.MaintainFinalizedMeter.CompanyName', N'TenantsName', N'COLUMN';
IF COL_LENGTH(@t, N'EndDate') IS NOT NULL AND COL_LENGTH(@t, N'ToDate') IS NULL               EXEC sp_rename N'mr.MaintainFinalizedMeter.EndDate', N'ToDate', N'COLUMN';
IF COL_LENGTH(@t, N'FinalizedAtUtc') IS NOT NULL AND COL_LENGTH(@t, N'CreatedDate') IS NULL   EXEC sp_rename N'mr.MaintainFinalizedMeter.FinalizedAtUtc', N'CreatedDate', N'COLUMN';
IF COL_LENGTH(@t, N'SewerageAmount') IS NOT NULL AND COL_LENGTH(@t, N'SewAmount') IS NULL     EXEC sp_rename N'mr.MaintainFinalizedMeter.SewerageAmount', N'SewAmount', N'COLUMN';
IF COL_LENGTH(@t, N'IrrigationAmount') IS NOT NULL AND COL_LENGTH(@t, N'IrrAmount') IS NULL   EXEC sp_rename N'mr.MaintainFinalizedMeter.IrrigationAmount', N'IrrAmount', N'COLUMN';
GO

IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'Area') IS NULL
    ALTER TABLE mr.MaintainFinalizedMeter ADD Area decimal(18,2) NULL;
IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'Rate') IS NULL
    ALTER TABLE mr.MaintainFinalizedMeter ADD Rate decimal(18,4) NULL;
IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'DimensionCode') IS NULL
    ALTER TABLE mr.MaintainFinalizedMeter ADD DimensionCode varchar(30) NULL;
IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'TTBaan') IS NULL
    ALTER TABLE mr.MaintainFinalizedMeter ADD TTBaan varchar(5) NOT NULL CONSTRAINT DF_MaintainFinalizedMeter_TTBaan DEFAULT 'No';
IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'Status') IS NULL
    ALTER TABLE mr.MaintainFinalizedMeter ADD Status varchar(20) NOT NULL CONSTRAINT DF_MaintainFinalizedMeter_Status DEFAULT 'Ready';
IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'FusionInvoiceNo') IS NULL
    ALTER TABLE mr.MaintainFinalizedMeter ADD FusionInvoiceNo bigint NOT NULL CONSTRAINT DF_MaintainFinalizedMeter_FusionInvoiceNo DEFAULT 0;
IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'InvNo') IS NULL
    ALTER TABLE mr.MaintainFinalizedMeter ADD InvNo bigint NULL;
GO

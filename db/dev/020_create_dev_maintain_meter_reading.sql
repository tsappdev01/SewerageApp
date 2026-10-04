/*
  dev/020_create_dev_maintain_meter_reading.sql
  DEVELOPMENT AND TEST ONLY. A stand-in for PropertyManagementSystem's dbo.MaintainMeterReading
  with the same columns: text for most fields, RowId as identity, defaults for Posted, UploadTime
  and Transferred, and Consumption calculated. The transfer job copies readings into it.
  Re-runnable.
*/
IF OBJECT_ID(N'dbo.MaintainMeterReading', N'U') IS NULL
CREATE TABLE dbo.MaintainMeterReading (
    RowId              int IDENTITY(1, 1) NOT NULL CONSTRAINT PK_MaintainMeterReading PRIMARY KEY,
    PropertyId         int            NULL,
    PropertyCode       nvarchar(50)   NULL,
    MeterNumber        nvarchar(50)   NULL,
    TenantCode         nvarchar(50)   NULL,
    ReadingDate        nvarchar(50)   NULL,
    MeterStatus        nvarchar(50)   NULL,
    Latitude           nvarchar(50)   NULL,
    Longitude          nvarchar(50)   NULL,
    Previous_Reading   nvarchar(50)   NULL,
    Current_Reading    nvarchar(50)   NULL,
    Posted             nvarchar(10)   NULL CONSTRAINT DF_MaintainMeterReading_Posted DEFAULT ('0'),
    MeterReader        nvarchar(50)   NULL,
    [Type]             nvarchar(50)   NULL,
    UploadTime         datetime       NULL CONSTRAINT DF_MaintainMeterReading_UploadTime DEFAULT (GETDATE()),
    Transferred        bit            NULL CONSTRAINT DF_MaintainMeterReading_Transferred DEFAULT (0),
    Consumption        AS (CAST(TRY_CAST(Current_Reading AS decimal(18, 4)) - TRY_CAST(Previous_Reading AS decimal(18, 4)) AS bigint)),
    TransferredToBaan  nvarchar(50)   NULL,
    SubTenant          nvarchar(50)   NULL
);
GO

/*
  033_finalized_invoice_header.sql
  DIP 2026-10-31: finalized billing as header and detail.
    mr.MaintainFinalizedInvoice  header: one invoice per finalized property and month; InvNo is an IDENTITY
                                 primary key; the totals (consumption, SewAmount, IrrAmount, VAT, TotalAmount)
                                 and the invoice fields (tenant, Baan and Tran codes, Area, Rate, DimensionCode,
                                 TTBaan 'No', Status 'Ready', FusionInvoiceNo 0); TaxableAmount (excl. VAT),
                                 VATAmount, InvAmount (incl. VAT) and SIMNo (= InvNo) worked out from it;
                                 RecAmount 0 and Details 'sew' by default.
    mr.MaintainFinalizedMeter    detail: one row per meter number; InvNo is a foreign key to the header.
  Finalizing inserts the header, takes its new InvNo and inserts the detail rows with it, in one transaction.
  Finalizing again marks the earlier invoice and its detail rows IsCurrent = 0 and writes a new invoice.

  Detail rows written before this (no InvNo) get a header each: one per property, month and finalizing time.
  Run in PropertyManagementSystem after 032, before deploying the API that writes the header. Re-runnable.
*/
IF OBJECT_ID(N'mr.MaintainFinalizedInvoice', N'U') IS NULL
CREATE TABLE mr.MaintainFinalizedInvoice (
    InvNo                  bigint IDENTITY(1, 1) NOT NULL CONSTRAINT PK_MaintainFinalizedInvoice PRIMARY KEY,
    PeriodCode             char(7)          NOT NULL,
    PropertyId             bigint           NULL,
    PropertyCode           varchar(50)      NOT NULL,
    TranCode               varchar(30)      NULL,
    TenantCode             varchar(30)      NULL,
    TenantsName            nvarchar(200)    NULL,
    BaanCode               varchar(30)      NULL,
    SubTenant              nvarchar(100)    NULL,
    Area                   decimal(18,2)    NULL,
    Rate                   decimal(18,4)    NULL,
    DimensionCode          varchar(30)      NULL,
    FromDate               date             NULL,
    ToDate                 date             NULL,
    VATCode                varchar(10)      NOT NULL CONSTRAINT DF_MaintainFinalizedInvoice_VATCode DEFAULT 'SSD',
    ChargeType1            varchar(10)      NOT NULL CONSTRAINT DF_MaintainFinalizedInvoice_ChargeType1 DEFAULT 'sew',
    ChargeType2            varchar(10)      NOT NULL CONSTRAINT DF_MaintainFinalizedInvoice_ChargeType2 DEFAULT 'Irri',
    ConsumptionSewerage    decimal(18,4)    NOT NULL CONSTRAINT DF_MaintainFinalizedInvoice_ConsS DEFAULT 0,
    ConsumptionIrrigation  decimal(18,4)    NOT NULL CONSTRAINT DF_MaintainFinalizedInvoice_ConsI DEFAULT 0,
    SewAmount              decimal(18,2)    NOT NULL CONSTRAINT DF_MaintainFinalizedInvoice_SewAmount DEFAULT 0,
    IrrAmount              decimal(18,2)    NOT NULL CONSTRAINT DF_MaintainFinalizedInvoice_IrrAmount DEFAULT 0,
    SewerageVat            decimal(18,2)    NOT NULL CONSTRAINT DF_MaintainFinalizedInvoice_SewerageVat DEFAULT 0,
    IrrigationVat          decimal(18,2)    NOT NULL CONSTRAINT DF_MaintainFinalizedInvoice_IrrigationVat DEFAULT 0,
    TotalAmount            decimal(18,2)    NOT NULL CONSTRAINT DF_MaintainFinalizedInvoice_TotalAmount DEFAULT 0,
    TTBaan                 varchar(5)       NOT NULL CONSTRAINT DF_MaintainFinalizedInvoice_TTBaan DEFAULT 'No',
    Status                 varchar(20)      NOT NULL CONSTRAINT DF_MaintainFinalizedInvoice_Status DEFAULT 'Ready',
    FusionInvoiceNo        bigint           NOT NULL CONSTRAINT DF_MaintainFinalizedInvoice_FusionInvoiceNo DEFAULT 0,
    FinalizedByReaderId    varchar(50)      NOT NULL,
    DeviceId               uniqueidentifier NULL,
    CreatedDate            datetime2(0)     NOT NULL CONSTRAINT DF_MaintainFinalizedInvoice_CreatedDate DEFAULT SYSUTCDATETIME(),
    IsCurrent              bit              NOT NULL CONSTRAINT DF_MaintainFinalizedInvoice_IsCurrent DEFAULT 1
);
GO

-- DIP 2026-10-31: worked out from the header's own columns, so they always agree with it.
IF COL_LENGTH(N'mr.MaintainFinalizedInvoice', N'TaxableAmount') IS NULL
    ALTER TABLE mr.MaintainFinalizedInvoice ADD TaxableAmount AS CAST(SewAmount + IrrAmount AS decimal(18,2)) PERSISTED;     -- excl. VAT
IF COL_LENGTH(N'mr.MaintainFinalizedInvoice', N'VATAmount') IS NULL
    ALTER TABLE mr.MaintainFinalizedInvoice ADD VATAmount AS CAST(SewerageVat + IrrigationVat AS decimal(18,2)) PERSISTED;
IF COL_LENGTH(N'mr.MaintainFinalizedInvoice', N'InvAmount') IS NULL
    ALTER TABLE mr.MaintainFinalizedInvoice ADD InvAmount AS CAST(SewAmount + IrrAmount + SewerageVat + IrrigationVat AS decimal(18,2)) PERSISTED; -- incl. VAT
IF COL_LENGTH(N'mr.MaintainFinalizedInvoice', N'SIMNo') IS NULL
    ALTER TABLE mr.MaintainFinalizedInvoice ADD SIMNo AS InvNo PERSISTED;
-- Defaults.
IF COL_LENGTH(N'mr.MaintainFinalizedInvoice', N'RecAmount') IS NULL
    ALTER TABLE mr.MaintainFinalizedInvoice ADD RecAmount decimal(18,2) NOT NULL CONSTRAINT DF_MaintainFinalizedInvoice_RecAmount DEFAULT 0;
IF COL_LENGTH(N'mr.MaintainFinalizedInvoice', N'Details') IS NULL
    ALTER TABLE mr.MaintainFinalizedInvoice ADD Details varchar(50) NOT NULL CONSTRAINT DF_MaintainFinalizedInvoice_Details DEFAULT 'sew';
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_MaintainFinalizedInvoice_Period_Property' AND object_id = OBJECT_ID(N'mr.MaintainFinalizedInvoice'))
CREATE INDEX IX_MaintainFinalizedInvoice_Period_Property ON mr.MaintainFinalizedInvoice (PeriodCode, PropertyCode, IsCurrent);
GO

/* Detail rows from before this script: one header per property, month and finalizing time. */
IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'InvNo') IS NULL
    ALTER TABLE mr.MaintainFinalizedMeter ADD InvNo bigint NULL;
GO
DECLARE @made TABLE (InvNo bigint, PeriodCode char(7), PropertyCode varchar(50), CreatedDate datetime2(0));
INSERT mr.MaintainFinalizedInvoice (PeriodCode, PropertyId, PropertyCode, TranCode, TenantCode, TenantsName, BaanCode, SubTenant,
    Area, Rate, DimensionCode, FromDate, ToDate, ConsumptionSewerage, ConsumptionIrrigation, SewAmount, IrrAmount,
    SewerageVat, IrrigationVat, TotalAmount, FinalizedByReaderId, DeviceId, CreatedDate, IsCurrent)
OUTPUT inserted.InvNo, inserted.PeriodCode, inserted.PropertyCode, inserted.CreatedDate INTO @made
SELECT f.PeriodCode, MAX(f.PropertyId), f.PropertyCode, MAX(f.TranCode), MAX(f.TenantCode), MAX(f.TenantsName), MAX(f.BaanCode),
       MAX(f.SubTenant), MAX(f.Area), MAX(f.Rate), MAX(f.DimensionCode), MAX(f.FromDate), MAX(f.ToDate),
       SUM(f.ConsumptionSewerage), SUM(f.ConsumptionIrrigation), SUM(f.SewAmount), SUM(f.IrrAmount),
       SUM(f.SewerageVat), SUM(f.IrrigationVat), SUM(f.TotalAmount), MAX(f.FinalizedByReaderId), MAX(f.DeviceId),
       f.CreatedDate, CAST(MAX(CAST(f.IsCurrent AS int)) AS bit)
FROM mr.MaintainFinalizedMeter f
WHERE f.InvNo IS NULL
GROUP BY f.PeriodCode, f.PropertyCode, f.CreatedDate;
UPDATE f SET InvNo = m.InvNo
FROM mr.MaintainFinalizedMeter f
JOIN @made m ON m.PeriodCode = f.PeriodCode AND m.PropertyCode = f.PropertyCode AND m.CreatedDate = f.CreatedDate
WHERE f.InvNo IS NULL;
SELECT COUNT(*) AS EarlierInvoicesMade FROM @made;
GO

IF NOT EXISTS (SELECT 1 FROM sys.foreign_keys WHERE name = N'FK_MaintainFinalizedMeter_Invoice')
    ALTER TABLE mr.MaintainFinalizedMeter WITH CHECK
        ADD CONSTRAINT FK_MaintainFinalizedMeter_Invoice FOREIGN KEY (InvNo) REFERENCES mr.MaintainFinalizedInvoice (InvNo);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_MaintainFinalizedMeter_InvNo' AND object_id = OBJECT_ID(N'mr.MaintainFinalizedMeter'))
CREATE INDEX IX_MaintainFinalizedMeter_InvNo ON mr.MaintainFinalizedMeter (InvNo);
GO

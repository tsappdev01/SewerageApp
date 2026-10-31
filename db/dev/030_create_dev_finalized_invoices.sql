/*
  dev/030_create_dev_finalized_invoices.sql
  DEVELOPMENT AND TEST ONLY. A stand-in for PropertyManagementSystem's dbo.MaintainFinalizedInvoices with the
  same columns, IDENTITY InvNo and its unique indexes on InvNo and SourceRowId (033 drops the SourceRowId one,
  as in PMS). Starts numbering at 190705, after PMS's highest InvNo on 2026-10-31. Run before db/033.
  Never run outside development. Re-runnable.
*/
IF OBJECT_ID(N'dbo.MaintainFinalizedInvoices', N'U') IS NULL
CREATE TABLE dbo.MaintainFinalizedInvoices (
    InvNo                  bigint IDENTITY(190705, 1) NOT NULL,
    TranCode               varchar(200)   NULL,
    TenantsName            varchar(200)   NULL,
    PropertyCode           varchar(200)   NULL,
    Area                   varchar(200)   NULL,
    Rate                   varchar(200)   NULL,
    FromDate               datetime       NULL,
    ToDate                 datetime       NULL,
    InvAmount              decimal(18,2)  NULL,
    RecAmount              decimal(18,2)  NULL,
    BaanCode               varchar(200)   NULL,
    DimensionCode          varchar(200)   NULL,
    TTBaan                 varchar(200)   NULL,
    Status                 varchar(200)   NULL,
    SIMNo                  varchar(200)   NULL,
    Details                varchar(200)   NULL,
    CreatedDate            datetime       NULL CONSTRAINT DF_MaintainFinalizedInvoices_CreatedDate DEFAULT (GETDATE()),
    VATCode                varchar(10)    NULL,
    VATAmount              numeric(18,2)  NULL,
    TaxableAmount          numeric(18,2)  NULL,
    SubleaseCode           bigint         NULL,
    Is_SCN                 bit            NOT NULL CONSTRAINT DF_MaintainFinalizedInvoices_Is_SCN DEFAULT (0),
    InvoiceNo_SCN          bigint         NULL,
    UserName               varchar(200)   NULL,
    ORA_Error              text           NULL,
    FusionSync             varchar(5)     NULL,
    FusionStatus           varchar(10)    NULL,
    FusionReferenceNumber  varchar(100)   NULL,
    ReceiptNumber          varchar(100)   NULL,
    FusionPosted           datetime       NULL,
    SourceRowId            bigint         NULL
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'UX_MaintainFinalizedInvoices_InvNo' AND object_id = OBJECT_ID(N'dbo.MaintainFinalizedInvoices'))
CREATE UNIQUE INDEX UX_MaintainFinalizedInvoices_InvNo ON dbo.MaintainFinalizedInvoices (InvNo);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'UX_MaintainFinalizedInvoices_SourceRowId' AND object_id = OBJECT_ID(N'dbo.MaintainFinalizedInvoices'))
   AND NOT EXISTS (SELECT 1 FROM sys.foreign_keys WHERE name = N'FK_MaintainFinalizedMeter_Invoice')
CREATE UNIQUE INDEX UX_MaintainFinalizedInvoices_SourceRowId ON dbo.MaintainFinalizedInvoices (SourceRowId);
GO

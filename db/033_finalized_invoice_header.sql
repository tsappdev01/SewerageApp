/*
  033_finalized_invoice_header.sql
  DIP 2026-10-31: finalized billing as header and detail.
    dbo.MaintainFinalizedInvoices  header: PMS's own invoice table, shared with PMS's other modules. One invoice
                                   per finalized property and month. InvNo is PMS's IDENTITY, so SQL Server hands
                                   out the number and it cannot be a duplicate (unique index
                                   UX_MaintainFinalizedInvoices_InvNo as well). The app never chooses an InvNo.
    mr.MaintainFinalizedMeter      detail: one row per meter number; InvNo is a foreign key to the header.

  Finalizing, in one transaction:
    1. insert the header (no InvNo: IDENTITY) and read the new InvNo back with OUTPUT inserted.InvNo;
    2. insert the detail rows with that InvNo;
    3. set the header's SIMNo = InvNo and SourceRowId = 900000000000 + the first detail row's RowId
       (an offset that cannot meet PMS's own row numbers, so the app's invoices are easy to tell apart).
  The header's fields: TranCode, TenantsName, PropertyCode, Area, Rate, FromDate, ToDate, BaanCode, DimensionCode,
  VATCode 'SSD', UserName (the reader); InvAmount = total incl. VAT, TaxableAmount = total excl. VAT,
  VATAmount = total VAT; RecAmount 0, TTBaan 'No', Status 'Ready', Details 'sew'; CreatedDate by its default.
  Finalizing the property again in the same month updates the same invoice, but only while it is untouched
  (TTBaan 'No', Status 'Ready', nothing from Fusion, nothing received); otherwise it is refused (ALREADY_INVOICED).

  This script:
    - drops UX_MaintainFinalizedInvoices_SourceRowId (unique SourceRowId; DIP decision 2026-10-31), if there;
    - adds the foreign key from mr.MaintainFinalizedMeter.InvNo to dbo.MaintainFinalizedInvoices(InvNo);
    - gives mr_api what it needs on dbo.MaintainFinalizedInvoices and nothing more: INSERT; SELECT on the
      columns it checks; UPDATE on the columns it sets. No DELETE.
  Detail rows finalized before this keep InvNo empty: no invoices are made for them here.
  Run in PropertyManagementSystem after 032, before deploying the API that writes the header. Re-runnable.
*/
SET NOCOUNT ON;
IF OBJECT_ID(N'dbo.MaintainFinalizedInvoices', N'U') IS NULL
BEGIN
    RAISERROR(N'dbo.MaintainFinalizedInvoices is not in this database: run this in PropertyManagementSystem.', 16, 1) WITH NOWAIT;
    SET NOEXEC ON;
END
GO

IF EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'UX_MaintainFinalizedInvoices_SourceRowId' AND object_id = OBJECT_ID(N'dbo.MaintainFinalizedInvoices'))
    DROP INDEX UX_MaintainFinalizedInvoices_SourceRowId ON dbo.MaintainFinalizedInvoices;
GO

-- InvNo must be unique for the foreign key (PMS has UX_MaintainFinalizedInvoices_InvNo).
IF NOT EXISTS (SELECT 1 FROM sys.indexes i JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
               WHERE i.object_id = OBJECT_ID(N'dbo.MaintainFinalizedInvoices') AND i.is_unique = 1 AND i.has_filter = 0
                 AND ic.column_id = COLUMNPROPERTY(i.object_id, N'InvNo', 'ColumnId')
                 AND (SELECT COUNT(*) FROM sys.index_columns x WHERE x.object_id = i.object_id AND x.index_id = i.index_id AND x.is_included_column = 0) = 1)
    CREATE UNIQUE INDEX UX_MaintainFinalizedInvoices_InvNo ON dbo.MaintainFinalizedInvoices (InvNo);
GO

IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'InvNo') IS NULL
    ALTER TABLE mr.MaintainFinalizedMeter ADD InvNo bigint NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.foreign_keys WHERE name = N'FK_MaintainFinalizedMeter_Invoice')
    ALTER TABLE mr.MaintainFinalizedMeter WITH CHECK
        ADD CONSTRAINT FK_MaintainFinalizedMeter_Invoice FOREIGN KEY (InvNo) REFERENCES dbo.MaintainFinalizedInvoices (InvNo);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_MaintainFinalizedMeter_InvNo' AND object_id = OBJECT_ID(N'mr.MaintainFinalizedMeter'))
CREATE INDEX IX_MaintainFinalizedMeter_InvNo ON mr.MaintainFinalizedMeter (InvNo);
GO

IF DATABASE_PRINCIPAL_ID(N'mr_api') IS NULL
    RAISERROR(N'No user mr_api in this database: run db/008_create_api_login.sql here, then this script again.', 10, 1) WITH NOWAIT;
ELSE
BEGIN
    GRANT INSERT ON dbo.MaintainFinalizedInvoices TO [mr_api];
    GRANT SELECT (InvNo, PropertyCode, FromDate, ToDate, InvAmount, RecAmount, TTBaan, Status, SIMNo, Details,
                  FusionSync, FusionStatus, FusionReferenceNumber, ReceiptNumber, FusionPosted, SourceRowId)
        ON dbo.MaintainFinalizedInvoices TO [mr_api];
    GRANT UPDATE (TranCode, TenantsName, Area, Rate, FromDate, ToDate, InvAmount, BaanCode, DimensionCode,
                  SIMNo, VATCode, VATAmount, TaxableAmount, UserName, SourceRowId)
        ON dbo.MaintainFinalizedInvoices TO [mr_api];
END
GO

SELECT COUNT(*) AS DetailRowsWithoutInvoice FROM mr.MaintainFinalizedMeter WHERE InvNo IS NULL;
GO
SET NOEXEC OFF;
GO

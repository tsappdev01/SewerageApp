/*
  035_invoice_details_irrigation.sql
  DIP 2026-11-01 (FR-037.16): an invoice with no sewerage consumption and only irrigation consumption has
  Details = 'Irr' (otherwise 'sew'). Finalizing a property again may turn one into the other, so mr_api may now
  update Details on dbo.MaintainFinalizedInvoices (still only while the invoice is untouched).
  Run in PropertyManagementSystem after 033, before deploying the API that sets it. Re-runnable.
*/
IF OBJECT_ID(N'dbo.MaintainFinalizedInvoices', N'U') IS NULL
    RAISERROR(N'dbo.MaintainFinalizedInvoices is not in this database: run this in PropertyManagementSystem.', 16, 1) WITH NOWAIT;
ELSE IF DATABASE_PRINCIPAL_ID(N'mr_api') IS NULL
    RAISERROR(N'No user mr_api in this database: run db/008_create_api_login.sql here, then this script again.', 10, 1) WITH NOWAIT;
ELSE
BEGIN
    GRANT UPDATE (Details) ON dbo.MaintainFinalizedInvoices TO [mr_api];
    PRINT N'mr_api may update Details on dbo.MaintainFinalizedInvoices.';
END
GO

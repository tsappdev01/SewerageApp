/*
  023_tenant_view_baancode.sql
  MRDB's dbo.vw_MR_Tenant over PropertyManagementSystem.dbo.vw_MR_Tenant, now with BaanCode
  (DIP 2026-10-18). Each reading keeps the BaanCode of the tenant the reader checked
  (mr.ReadingTransaction.BaanCode, db/022), and finalized rows carry it too (FR-037.10).
  The API reads the column from now on, so run this before deploying it. Run in MRDB as the view's
  owner. Re-runnable.

  Not for development databases: db/dev/000 makes its own stand-in.
*/
IF DB_ID(N'PropertyManagementSystem') IS NULL
BEGIN
    RAISERROR(N'Database PropertyManagementSystem not found on this server.', 16, 1);
    RETURN;
END
IF COL_LENGTH(N'PropertyManagementSystem.dbo.vw_MR_Tenant', N'BaanCode') IS NULL
BEGIN
    RAISERROR(N'PropertyManagementSystem.dbo.vw_MR_Tenant has no BaanCode column: add it there first.', 16, 1);
    RETURN;
END
EXEC (N'
CREATE OR ALTER VIEW dbo.vw_MR_Tenant AS
SELECT PropertyCode, TenantCode, CompanyName, BaanCode
FROM PropertyManagementSystem.dbo.vw_MR_Tenant;');
PRINT N'dbo.vw_MR_Tenant updated with BaanCode.';
GO

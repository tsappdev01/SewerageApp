/*
  021_property_view_trancode.sql
  MRDB's dbo.vw_MR_Property over PropertyManagementSystem.dbo.vw_MR_Property, now with TranCode
  (DIP 2026-10-18). Each reading keeps the TranCode of the property's row for the tenant the reader
  checked (mr.ReadingTransaction.TranCode, db/022), and finalized rows carry it too (FR-037.10).
  The API reads the column from now on, so run this before deploying it. Run in MRDB as the view's
  owner. Re-runnable.

  Not for development databases: db/dev/000 makes its own stand-in.
*/
IF DB_ID(N'PropertyManagementSystem') IS NULL
BEGIN
    RAISERROR(N'Database PropertyManagementSystem not found on this server.', 16, 1);
    RETURN;
END
IF COL_LENGTH(N'PropertyManagementSystem.dbo.vw_MR_Property', N'TranCode') IS NULL
BEGIN
    RAISERROR(N'PropertyManagementSystem.dbo.vw_MR_Property has no TranCode column: add it there first.', 16, 1);
    RETURN;
END
EXEC (N'
CREATE OR ALTER VIEW dbo.vw_MR_Property AS
SELECT PropertyId, PropertyCode, PropertyName, ZoneCode, RouteSequence, Latitude, Longitude, IsActive,
       TenantCode, CompanyName, TranCode
FROM PropertyManagementSystem.dbo.vw_MR_Property;');
PRINT N'dbo.vw_MR_Property updated with TranCode.';
GO

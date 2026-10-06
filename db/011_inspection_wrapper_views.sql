/*
  011_inspection_wrapper_views.sql
  The two Field Inspection wrapper views in MRDB (docs/source-views.md §7): no TOP (1000) on the units.
  TenantCode on the units is optional (the tenant comes from the plan view); it is passed through only
  if PropertyManagementSystem's view has it. Run in MRDB as the views' owner, then run 008 again so
  mr_api can read them. Re-runnable.

  Not for development databases: db/dev/000 makes its own stand-ins.
*/
IF DB_ID(N'PropertyManagementSystem') IS NULL
BEGIN
    RAISERROR(N'Database PropertyManagementSystem not found on this server.', 16, 1);
    RETURN;
END
EXEC (N'
CREATE OR ALTER VIEW dbo.vw_MR_InspectionPlan AS
SELECT PeriodCode, InspectionPlanDate, PropertyCode, TenantCode, CompanyName, ActiveUnits, InactiveUnits, TotalUnits
FROM PropertyManagementSystem.dbo.vw_MR_InspectionPlan;');

-- TenantCode on the units is optional: passed through only when PMS's view has it.
DECLARE @tenant nvarchar(20) = CASE WHEN COL_LENGTH(N'PropertyManagementSystem.dbo.vw_MR_InspectionUnit', N'TenantCode') IS NULL THEN N'' ELSE N'TenantCode, ' END;
EXEC (N'
CREATE OR ALTER VIEW dbo.vw_MR_InspectionUnit AS
SELECT UnitId, PropertyCode, ' + @tenant + N'BuildingName, UnitCode, Category, SubTenantName, Active
FROM PropertyManagementSystem.dbo.vw_MR_InspectionUnit;');

PRINT N'vw_MR_InspectionPlan and vw_MR_InspectionUnit updated. Now run db/008_create_api_login.sql again.';
GO

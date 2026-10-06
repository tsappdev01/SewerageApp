/*
  011_inspection_wrapper_views.sql
  The two Field Inspection wrapper views in MRDB (docs/source-views.md §7), after the fixes agreed on
  2026-10-06: no TOP (1000) on the units, and TenantCode on the units so a property with two tenants
  splits its units correctly. Run in MRDB as the views' owner, after PropertyManagementSystem's own
  vw_MR_InspectionUnit has a TenantCode column. Then run 008 again so mr_api can read them. Re-runnable.

  Not for development databases: db/dev/000 makes its own stand-ins.
*/
IF DB_ID(N'PropertyManagementSystem') IS NULL
BEGIN
    RAISERROR(N'Database PropertyManagementSystem not found on this server.', 16, 1);
    RETURN;
END
IF NOT EXISTS (SELECT 1 FROM PropertyManagementSystem.sys.columns
               WHERE object_id = OBJECT_ID(N'PropertyManagementSystem.dbo.vw_MR_InspectionUnit') AND name = N'TenantCode')
BEGIN
    RAISERROR(N'PropertyManagementSystem.dbo.vw_MR_InspectionUnit has no TenantCode column yet. Add it there first.', 16, 1);
    RETURN;
END

EXEC (N'
CREATE OR ALTER VIEW dbo.vw_MR_InspectionPlan AS
SELECT PeriodCode, InspectionPlanDate, PropertyCode, TenantCode, CompanyName, ActiveUnits, InactiveUnits, TotalUnits
FROM PropertyManagementSystem.dbo.vw_MR_InspectionPlan;');

EXEC (N'
CREATE OR ALTER VIEW dbo.vw_MR_InspectionUnit AS
SELECT UnitId, PropertyCode, TenantCode, BuildingName, UnitCode, Category, SubTenantName, Active
FROM PropertyManagementSystem.dbo.vw_MR_InspectionUnit;');

PRINT N'vw_MR_InspectionPlan and vw_MR_InspectionUnit updated. Now run db/008_create_api_login.sql again.';
GO

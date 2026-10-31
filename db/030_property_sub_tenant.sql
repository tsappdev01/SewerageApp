/*
  030_property_sub_tenant.sql
  DIP 2026-10-31: the sub-tenant of a property, kept by the office. The phone pre-fills the reading's
  sub-tenant with it (the reader can change it), and finalizing copies the reading's sub-tenant (else
  this name) to mr.MaintainFinalizedMeter.SubTenant.

  One name per property; IsActive = 0 stops it being offered without deleting it. Fill and change the
  names with db/ops/set_sub_tenant.sql. Run in the app's database (PropertyManagementSystem) after 029.
  The API's user already has SELECT, INSERT, UPDATE on schema mr. Re-runnable.
*/
IF OBJECT_ID(N'mr.PropertySubTenant', N'U') IS NULL
CREATE TABLE mr.PropertySubTenant (
    PropertyCode   varchar(30)   NOT NULL CONSTRAINT PK_PropertySubTenant PRIMARY KEY,
    SubTenantName  nvarchar(100) NOT NULL,
    IsActive       bit           NOT NULL CONSTRAINT DF_PropertySubTenant_IsActive DEFAULT 1,
    Note           nvarchar(200) NULL,
    UpdatedAtUtc   datetime2(0)  NOT NULL CONSTRAINT DF_PropertySubTenant_UpdatedAtUtc DEFAULT SYSUTCDATETIME(),
    UpdatedBy      nvarchar(128) NOT NULL CONSTRAINT DF_PropertySubTenant_UpdatedBy DEFAULT SUSER_SNAME()
);
GO

IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'SubTenant') IS NULL
    ALTER TABLE mr.MaintainFinalizedMeter ADD SubTenant nvarchar(100) NULL;
GO

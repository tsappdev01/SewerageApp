/*
  dev/000_create_dev_source_views.sql
  DEVELOPMENT AND TEST ONLY. Never run against UAT or production.

  Stands in for the views in PropertyManagementSystem (docs/source-views.md): tables in schema
  devsrc with the same sample data as the Android app, and dbo.vw_MR_* views over them shaped
  like the real ones: text MeterId (barcode), Status 1/0, PeriodCode like 2026-9 with every month
  OPEN, and ISNULL(..., 0) for readings and averages. Re-runnable.
*/
IF SCHEMA_ID(N'devsrc') IS NULL EXEC (N'CREATE SCHEMA devsrc');
GO

IF OBJECT_ID(N'devsrc.Reader', N'U') IS NULL
CREATE TABLE devsrc.Reader (ReaderId varchar(50) PRIMARY KEY, LoginEmail nvarchar(256) NOT NULL,
    DisplayName nvarchar(100) NOT NULL, TeamCode varchar(20) NULL, SupervisorEmail nvarchar(256) NULL, IsActive bit NOT NULL);
IF OBJECT_ID(N'devsrc.Zone', N'U') IS NULL
CREATE TABLE devsrc.Zone (ZoneCode varchar(20) PRIMARY KEY, ZoneName nvarchar(100) NULL, IsActive bit NOT NULL);
IF OBJECT_ID(N'devsrc.Property', N'U') IS NULL
CREATE TABLE devsrc.Property (PropertyCode varchar(30) PRIMARY KEY, PropertyName nvarchar(150) NULL, ZoneCode varchar(20) NOT NULL,
    RouteSequence int NULL, Latitude decimal(9,6) NULL, Longitude decimal(9,6) NULL, IsActive bit NOT NULL);
IF OBJECT_ID(N'devsrc.Tenant', N'U') IS NULL
CREATE TABLE devsrc.Tenant (PropertyCode varchar(30) NOT NULL, TenantCode varchar(30) NOT NULL, CompanyName nvarchar(200) NOT NULL,
    PRIMARY KEY (PropertyCode, TenantCode));
IF OBJECT_ID(N'devsrc.Meter', N'U') IS NULL
CREATE TABLE devsrc.Meter (MeterId bigint PRIMARY KEY, MeterNumber varchar(30) NOT NULL, PropertyCode varchar(30) NOT NULL,
    MeterType varchar(20) NOT NULL, RegisterDigits tinyint NOT NULL, DecimalDigits tinyint NOT NULL, OpeningReading decimal(18,3) NULL,
    InstallDate date NULL, RouteSequence int NULL, SerialNumber varchar(50) NULL, Status varchar(10) NOT NULL);
IF OBJECT_ID(N'devsrc.ReadingPeriod', N'U') IS NULL
CREATE TABLE devsrc.ReadingPeriod (PeriodCode char(7) PRIMARY KEY, StartDate date NOT NULL, EndDate date NOT NULL, Status varchar(10) NOT NULL);
IF OBJECT_ID(N'devsrc.ReadingHistory', N'U') IS NULL
CREATE TABLE devsrc.ReadingHistory (MeterId bigint NOT NULL, PeriodCode char(7) NOT NULL, ReadingDate date NULL,
    ReadingValue decimal(18,3) NULL, Consumption decimal(18,3) NULL, ConsumptionBasis varchar(10) NOT NULL,
    AverageConsumption decimal(18,3) NULL, PRIMARY KEY (MeterId, PeriodCode));
GO

/* Like MaintainTransactionKeys.TranCode: vw_MR_Tenant leaves out codes with a dash (ended leases). */
IF COL_LENGTH(N'devsrc.Tenant', N'TranCode') IS NULL
    ALTER TABLE devsrc.Tenant ADD TranCode varchar(20) NOT NULL CONSTRAINT DF_devsrc_Tenant_TranCode DEFAULT ('TR');
GO

/* The real reader view can repeat a sign-in name; earlier dev tables forbade it. */
DECLARE @unique sysname = (SELECT kc.name FROM sys.key_constraints kc
                           WHERE kc.parent_object_id = OBJECT_ID(N'devsrc.Reader') AND kc.type = 'UQ');
IF @unique IS NOT NULL EXEC (N'ALTER TABLE devsrc.Reader DROP CONSTRAINT ' + @unique);
GO

INSERT devsrc.Reader (ReaderId, LoginEmail, DisplayName, TeamCode, SupervisorEmail, IsActive)
SELECT v.* FROM (VALUES
    ('E1001', N'rashid@dip.example', N'Rashid', 'T1', N'supervisor@dip.example', 1),
    ('E1002', N'anil@dip.example', N'Anil', 'T1', N'supervisor@dip.example', 1),
    ('E1003', N'left.company@dip.example', N'Former reader', 'T1', NULL, 0),
    -- Two active readers on one sign-in name, as when the view uses a placeholder email.
    ('E1004', N'shared@dip.example', N'Shared one', 'T1', NULL, 1),
    ('E1005', N'shared@dip.example', N'Shared two', 'T1', NULL, 1)
) v (ReaderId, LoginEmail, DisplayName, TeamCode, SupervisorEmail, IsActive)
WHERE NOT EXISTS (SELECT 1 FROM devsrc.Reader r WHERE r.ReaderId = v.ReaderId);

INSERT devsrc.Zone (ZoneCode, ZoneName, IsActive)
SELECT v.* FROM (VALUES ('597', N'Zone 597', 1), ('598', N'Zone 598', 1), ('602', N'Zone 602', 1)) v (ZoneCode, ZoneName, IsActive)
WHERE NOT EXISTS (SELECT 1 FROM devsrc.Zone z WHERE z.ZoneCode = v.ZoneCode);

INSERT devsrc.Property (PropertyCode, PropertyName, ZoneCode, RouteSequence, Latitude, Longitude, IsActive)
SELECT v.* FROM (VALUES
    ('1100', N'Villa 1100', '597', 1, 24.987100, 55.155300, 1),
    ('1101', N'Villa 1101', '597', 2, 24.987300, 55.155600, 1),
    ('1499-W1', N'Building 1499-W1', '598', 1, 24.991200, 55.162100, 1),
    ('1502', N'Villa 1502', '598', 2, NULL, NULL, 1),
    ('1497', N'Villa 1497', '598', 3, NULL, NULL, 1),
    ('3010', N'Villa 3010', '602', 1, NULL, NULL, 1),
    ('4001', N'Plot 4001', '602', 2, NULL, NULL, 1)
) v (PropertyCode, PropertyName, ZoneCode, RouteSequence, Latitude, Longitude, IsActive)
WHERE NOT EXISTS (SELECT 1 FROM devsrc.Property p WHERE p.PropertyCode = v.PropertyCode);

/* Fictional tenants. Property 1101 has two current tenants, as can happen in the real data, and
   plot 4001 has none, so the inner join drops it like the real view would. Villa 3010's lease has
   ended (TranCode with a dash, set below): the property view still shows the company, but
   vw_MR_Tenant has no tenant for it, so its readings are refused with NO_TENANT. */
INSERT devsrc.Tenant (PropertyCode, TenantCode, CompanyName)
SELECT v.* FROM (VALUES
    ('1100', 'T-0101', N'Palmgate Foods Trading'),
    ('1101', 'T-0102', N'Crescent Fabrication LLC'),
    ('1101', 'T-0199', N'Crescent Fabrication (Old Lease)'),
    ('1499-W1', 'T-0201', N'Sandline Logistics LLC'),
    ('1502', 'T-0202', N'Bluewave Packaging'),
    ('1497', 'T-0203', N'Oasis Cold Store'),
    ('3010', 'T-0301', N'Northgate Marble Works')
) v (PropertyCode, TenantCode, CompanyName)
WHERE NOT EXISTS (SELECT 1 FROM devsrc.Tenant t WHERE t.PropertyCode = v.PropertyCode AND t.TenantCode = v.TenantCode);

UPDATE devsrc.Tenant SET TranCode = 'TR-END' WHERE PropertyCode = '3010' AND TranCode NOT LIKE '%-%';

INSERT devsrc.Meter (MeterId, MeterNumber, PropertyCode, MeterType, RegisterDigits, DecimalDigits, OpeningReading, InstallDate, RouteSequence, SerialNumber, Status)
SELECT v.* FROM (VALUES
    (1,  '1001-I', '1100',    'IRRIGATION', 5, 0, 0, '2019-03-01', 1, 'SN-0001', 'ACTIVE'),
    (2,  '1001-S', '1100',    'SEWERAGE',   5, 0, 0, '2019-03-01', 2, 'SN-0002', 'ACTIVE'),
    (3,  '1101-I', '1101',    'IRRIGATION', 5, 0, 0, '2019-03-01', 1, NULL, 'ACTIVE'),
    (4,  '1101-S', '1101',    'SEWERAGE',   5, 0, 0, '2019-03-01', 2, NULL, 'ACTIVE'),
    (5,  '2001-I', '1499-W1', 'IRRIGATION', 5, 0, 0, '2020-06-01', 1, NULL, 'ACTIVE'),
    (6,  '2001-2', '1499-W1', 'IRRIGATION', 5, 0, 0, '2020-06-01', 2, NULL, 'ACTIVE'),
    (7,  '2002-2', '1499-W1', 'SEWERAGE',   5, 0, 0, '2020-06-01', 3, NULL, 'ACTIVE'),
    (8,  '2002-3', '1499-W1', 'SEWERAGE',   5, 0, 0, '2020-06-01', 4, NULL, 'ACTIVE'),
    (9,  '2002-4', '1499-W1', 'SEWERAGE',   5, 0, 0, '2020-06-01', 5, NULL, 'ACTIVE'),
    (10, '2002-5', '1499-W1', 'SEWERAGE',   5, 0, 12, '2026-09-20', 6, NULL, 'ACTIVE'),
    (11, '2002-6', '1499-W1', 'SEWERAGE',   5, 0, 0, '2020-06-01', 7, NULL, 'ACTIVE'),
    (12, '1502-I', '1502',    'IRRIGATION', 5, 0, 0, '2018-01-15', 1, NULL, 'ACTIVE'),
    (13, '1502-S', '1502',    'SEWERAGE',   5, 0, 0, '2018-01-15', 2, NULL, 'ACTIVE'),
    (14, '1497-I', '1497',    'IRRIGATION', 5, 0, 0, '2018-01-15', 1, NULL, 'ACTIVE'),
    (15, '1497-S', '1497',    'SEWERAGE',   5, 0, 0, '2018-01-15', 2, NULL, 'ACTIVE'),
    (16, '3010-I', '3010',    'IRRIGATION', 5, 0, 0, '2017-05-10', 1, NULL, 'ACTIVE'),
    (17, '3010-S', '3010',    'SEWERAGE',   5, 0, 0, '2017-05-10', 2, NULL, 'ACTIVE'),
    (18, '4001-I', '4001',    'IRRIGATION', 6, 0, 0, '2016-02-01', 1, NULL, 'ACTIVE'),
    (19, '9999-X', '4001',    'IRRIGATION', 5, 0, 0, '2010-01-01', 2, NULL, 'INACTIVE')
) v (MeterId, MeterNumber, PropertyCode, MeterType, RegisterDigits, DecimalDigits, OpeningReading, InstallDate, RouteSequence, SerialNumber, Status)
WHERE NOT EXISTS (SELECT 1 FROM devsrc.Meter m WHERE m.MeterId = v.MeterId);

INSERT devsrc.ReadingPeriod (PeriodCode, StartDate, EndDate, Status)
SELECT v.* FROM (VALUES ('2026-08', '2026-08-01', '2026-08-10', 'CLOSED'), ('2026-09', '2026-09-01', '2026-09-10', 'CLOSED'),
                        ('2026-10', '2026-10-01', '2026-10-10', 'OPEN'), ('2026-11', '2026-11-01', '2026-11-10', 'PLANNED')) v (PeriodCode, StartDate, EndDate, Status)
WHERE NOT EXISTS (SELECT 1 FROM devsrc.ReadingPeriod p WHERE p.PeriodCode = v.PeriodCode);

/* Readings for August and September. Meter 10 is new (no history); meter 4 sits near 99,999 (rollover case).
   AverageConsumption is given for most meters; meters 6 and 7 leave it NULL so the API calculates it. */
INSERT devsrc.ReadingHistory (MeterId, PeriodCode, ReadingDate, ReadingValue, Consumption, ConsumptionBasis, AverageConsumption)
SELECT v.* FROM (VALUES
    (1, '2026-09', '2026-09-03', 49820, 1010, 'ACTUAL', 1000), (1, '2026-08', '2026-08-03', 48810, 990, 'ACTUAL', NULL),
    (2, '2026-09', '2026-09-03',  8150,  300, 'ACTUAL',  300),
    (3, '2026-09', '2026-09-03', 30110,  950, 'ACTUAL', 1000),
    (4, '2026-09', '2026-09-03', 99950,  280, 'ACTUAL',  300),
    (5, '2026-09', '2026-09-03', 61200, 1020, 'ACTUAL', 1000),
    (6, '2026-09', '2026-09-03', 52500, 1200, 'ACTUAL', NULL), (6, '2026-08', '2026-08-03', 51300, 900, 'ACTUAL', NULL),
    (6, '2026-07', NULL, NULL, 900, 'AVERAGE', NULL),
    (7, '2026-09', '2026-09-03', 17040,  310, 'ACTUAL', NULL), (7, '2026-08', '2026-08-03', 16730, 290, 'ACTUAL', NULL),
    (8, '2026-09', '2026-09-03', 22310,  300, 'ACTUAL',  300),
    (9, '2026-09', '2026-09-03',  5120,  300, 'ACTUAL',  300),
    (11, '2026-09', '2026-09-03', 40400, 300, 'ACTUAL',  300),
    (12, '2026-09', '2026-09-03', 12000, 1000, 'ACTUAL', 1000),
    (13, '2026-09', '2026-09-03',  3300,  300, 'ACTUAL',  300),
    (14, '2026-09', '2026-09-03',  7450, 1000, 'ACTUAL', 1000),
    (15, '2026-09', '2026-09-03',  2010,  300, 'ACTUAL',  300),
    (16, '2026-09', '2026-09-03', 15600, 1000, 'ACTUAL', 1000),
    (17, '2026-09', '2026-09-03',  4480,  300, 'ACTUAL',  300),
    (18, '2026-09', '2026-09-03', 345600, 1000, 'ACTUAL', 1000)
) v (MeterId, PeriodCode, ReadingDate, ReadingValue, Consumption, ConsumptionBasis, AverageConsumption)
WHERE NOT EXISTS (SELECT 1 FROM devsrc.ReadingHistory h WHERE h.MeterId = v.MeterId AND h.PeriodCode = v.PeriodCode);
GO

CREATE OR ALTER VIEW dbo.vw_MR_Reader AS
SELECT ReaderId AS UserId, LoginEmail, DisplayName, TeamCode, SupervisorEmail, IsActive FROM devsrc.Reader;
GO
CREATE OR ALTER VIEW dbo.vw_MR_Zone AS
SELECT ZoneCode, ZoneName + ' ' AS ZoneName, CAST(IsActive AS varchar(1)) AS IsActive FROM devsrc.Zone;
GO
/* Like the real view: inner join to the tenant, so a property without one is left out and a
   property with two tenant rows appears twice. */
CREATE OR ALTER VIEW dbo.vw_MR_Property AS
SELECT ROW_NUMBER() OVER (ORDER BY p.PropertyCode) AS PropertyId,
       p.PropertyCode, p.PropertyCode AS PropertyName, p.ZoneCode, p.RouteSequence, p.Latitude, p.Longitude, p.IsActive,
       t.TenantCode, t.CompanyName
FROM devsrc.Property p
JOIN devsrc.Tenant t ON t.PropertyCode = p.PropertyCode;
GO
/* Same as the real view: current tenants only (TranCode without a dash). */
CREATE OR ALTER VIEW dbo.vw_MR_Tenant AS
SELECT PropertyCode, TenantCode, CompanyName FROM devsrc.Tenant WHERE TranCode NOT LIKE '%-%';
GO
/* Shaped like the real view: barcode id, last billed reading as OpeningReading, ISNULL zeros, Status 1/0. */
CREATE OR ALTER VIEW dbo.vw_MR_Meter AS
SELECT 'BC' + RIGHT('0000' + CAST(m.MeterId AS varchar(10)), 4) AS MeterId,
       m.MeterNumber, m.PropertyCode,
       CASE m.MeterType WHEN 'IRRIGATION' THEN 'Irrigation' ELSE 'Sewerage' END AS MeterType,
       m.RegisterDigits, m.DecimalDigits,
       ISNULL(lastActual.ReadingValue, 0) AS OpeningReading,
       ISNULL(lastAny.Consumption, 0) AS LastConsumption,
       ISNULL(avgRead.AvgConsumption, 0) AS AvgConsumption,
       m.InstallDate, 'BC' + RIGHT('0000' + CAST(m.MeterId AS varchar(10)), 4) AS SerialNumber,
       m.RouteSequence,
       CASE m.Status WHEN 'ACTIVE' THEN 1 ELSE 0 END AS Status
FROM devsrc.Meter m
OUTER APPLY (SELECT TOP (1) r.ReadingValue FROM devsrc.ReadingHistory r
             WHERE r.MeterId = m.MeterId AND r.ConsumptionBasis = 'ACTUAL' ORDER BY r.PeriodCode DESC) lastActual
OUTER APPLY (SELECT TOP (1) r.Consumption FROM devsrc.ReadingHistory r
             WHERE r.MeterId = m.MeterId ORDER BY r.PeriodCode DESC) lastAny
OUTER APPLY (SELECT AVG(CAST(x.Consumption AS decimal(18,4))) AS AvgConsumption
             FROM (SELECT TOP (6) r.Consumption FROM devsrc.ReadingHistory r
                   WHERE r.MeterId = m.MeterId ORDER BY r.PeriodCode DESC) x) avgRead;
GO
/* Like the real view: PeriodCode without a leading zero and every billing month OPEN. */
CREATE OR ALTER VIEW dbo.vw_MR_ReadingPeriod AS
SELECT CONCAT(YEAR(StartDate), '-', MONTH(StartDate)) AS PeriodCode, StartDate, EOMONTH(StartDate) AS EndDate, 'OPEN' AS Status
FROM devsrc.ReadingPeriod
WHERE Status <> 'PLANNED';
GO
/* Work is not assigned (docs/source-views.md); earlier versions had these views. */
DROP VIEW IF EXISTS dbo.vw_MR_Assignment;
GO
DROP TABLE IF EXISTS devsrc.Assignment;
GO
DROP VIEW IF EXISTS dbo.vw_MR_LastReading;
GO
CREATE OR ALTER VIEW dbo.vw_MR_ReadingHistory AS
SELECT 'BC' + RIGHT('0000' + CAST(MeterId AS varchar(10)), 4) AS MeterId, PeriodCode, ReadingDate, ReadingValue, Consumption, ConsumptionBasis
FROM devsrc.ReadingHistory;
GO

/*
  dev/000_create_dev_source_views.sql
  DEVELOPMENT AND TEST ONLY. Never run against UAT or production.

  Stands in for the views your team will provide (docs/source-views.md): tables in schema
  devsrc with the same sample data as the Android app, and dbo.vw_MR_* views over them.
  Re-runnable.
*/
IF SCHEMA_ID(N'devsrc') IS NULL EXEC (N'CREATE SCHEMA devsrc');
GO

IF OBJECT_ID(N'devsrc.Reader', N'U') IS NULL
CREATE TABLE devsrc.Reader (ReaderId varchar(50) PRIMARY KEY, LoginEmail nvarchar(256) NOT NULL UNIQUE,
    DisplayName nvarchar(100) NOT NULL, TeamCode varchar(20) NULL, SupervisorEmail nvarchar(256) NULL, IsActive bit NOT NULL);
IF OBJECT_ID(N'devsrc.Zone', N'U') IS NULL
CREATE TABLE devsrc.Zone (ZoneCode varchar(20) PRIMARY KEY, ZoneName nvarchar(100) NULL, IsActive bit NOT NULL);
IF OBJECT_ID(N'devsrc.Property', N'U') IS NULL
CREATE TABLE devsrc.Property (PropertyCode varchar(30) PRIMARY KEY, PropertyName nvarchar(150) NULL, ZoneCode varchar(20) NOT NULL,
    RouteSequence int NULL, Latitude decimal(9,6) NULL, Longitude decimal(9,6) NULL, IsActive bit NOT NULL);
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

INSERT devsrc.Reader (ReaderId, LoginEmail, DisplayName, TeamCode, SupervisorEmail, IsActive)
SELECT v.* FROM (VALUES
    ('E1001', N'rashid@dip.example', N'Rashid', 'T1', N'supervisor@dip.example', 1),
    ('E1002', N'anil@dip.example', N'Anil', 'T1', N'supervisor@dip.example', 1),
    ('E1003', N'left.company@dip.example', N'Former reader', 'T1', NULL, 0)
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
SELECT ReaderId, LoginEmail, DisplayName, TeamCode, SupervisorEmail, IsActive FROM devsrc.Reader;
GO
CREATE OR ALTER VIEW dbo.vw_MR_Zone AS
SELECT ZoneCode, ZoneName, IsActive FROM devsrc.Zone;
GO
CREATE OR ALTER VIEW dbo.vw_MR_Property AS
SELECT PropertyCode, PropertyName, ZoneCode, RouteSequence, Latitude, Longitude, IsActive FROM devsrc.Property;
GO
/* The meter with its last ACTUAL reading. A meter never read shows its OpeningReading and no date. */
CREATE OR ALTER VIEW dbo.vw_MR_Meter AS
SELECT m.MeterId, m.MeterNumber, m.PropertyCode, m.MeterType, m.RegisterDigits, m.DecimalDigits,
       COALESCE(h.ReadingValue, m.OpeningReading) AS LastReading,
       h.ReadingDate AS LastReadingDate,
       h.AverageConsumption,
       m.InstallDate, m.RouteSequence, m.SerialNumber, m.Status
FROM devsrc.Meter m
OUTER APPLY (
    SELECT TOP (1) r.ReadingValue, r.ReadingDate, r.AverageConsumption
    FROM devsrc.ReadingHistory r
    WHERE r.MeterId = m.MeterId AND r.ConsumptionBasis = 'ACTUAL'
    ORDER BY r.PeriodCode DESC
) h;
GO
CREATE OR ALTER VIEW dbo.vw_MR_ReadingPeriod AS
SELECT PeriodCode, StartDate, EndDate, Status FROM devsrc.ReadingPeriod;
GO
/* Work is not assigned (docs/source-views.md); earlier versions had an assignment view. */
DROP VIEW IF EXISTS dbo.vw_MR_Assignment;
GO
DROP TABLE IF EXISTS devsrc.Assignment;
GO
/* Earlier versions had a separate last-reading view; it is now part of vw_MR_Meter. */
DROP VIEW IF EXISTS dbo.vw_MR_LastReading;
GO
CREATE OR ALTER VIEW dbo.vw_MR_ReadingHistory AS
SELECT MeterId, PeriodCode, ReadingDate, ReadingValue, Consumption, ConsumptionBasis FROM devsrc.ReadingHistory;
GO

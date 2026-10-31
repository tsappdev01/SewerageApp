/*
  028_pms_app_views.sql
  DIP 2026-10-29: the app's database moves from MRDB to PropertyManagementSystem (PMS). The API's
  tables keep schema mr; the views it reads are made in schema mr too (mr.vw_MR_*), so nothing of
  PMS's own dbo.vw_MR_* views is ever changed. The API is pointed at them with SourceViews:Schema = mr.

  Run in PropertyManagementSystem, as dbo or a db_owner, after the table scripts have been run there
  (docs/move-to-pms.md):
    002 003 004 005 006 007 009 010 012 013 014 015 017 019 020 022 024 025 026
  and 008 for the login and user. Not 011, 018, 021, 023, 027: those make MRDB's wrapper views and
  refuse to run in PMS.

  The views:
    mr.vw_MR_Meter          PMS MaintainPropertyMeter with the opening reading, last consumption and
                            average from the app's readings of earlier months, else mr.MeterOpeningReading,
                            else 0 (as db/027 in MRDB).
    mr.vw_MR_Meter_Base     the meters themselves.
    mr.vw_MR_Reader, _Zone, _Property, _Tenant, _ReadingPeriod, _ReadingHistory, _MeterStatus,
    _InspectionPlan, _InspectionUnit
                            the same rows as PMS's dbo.vw_MR_* (made only when PMS has that view).
  Schema mr belongs to dbo, so the views read PMS's dbo objects through ownership chaining: mr_api needs
  rights on schema mr only (granted at the end). Re-runnable; running it again picks up column changes
  in PMS's views.
*/
SET NOCOUNT ON;
DECLARE @db sysname = DB_NAME();
IF @db <> N'PropertyManagementSystem'
BEGIN
    RAISERROR(N'Run this in PropertyManagementSystem (now in %s).', 16, 1, @db) WITH NOWAIT;
    SET NOEXEC ON;
END
GO
IF SCHEMA_ID(N'mr') IS NULL EXEC (N'CREATE SCHEMA mr AUTHORIZATION dbo');
GO
-- 002 makes the schema owned by whoever runs it; the views need dbo as owner to read PMS's dbo objects.
IF (SELECT principal_id FROM sys.schemas WHERE name = N'mr') <> USER_ID(N'dbo')
    ALTER AUTHORIZATION ON SCHEMA::mr TO dbo;
GO
IF OBJECT_ID(N'mr.ReadingTransaction', N'U') IS NULL OR OBJECT_ID(N'mr.MeterOpeningReading', N'U') IS NULL
BEGIN
    RAISERROR(N'Schema mr has no ReadingTransaction or MeterOpeningReading yet: run the table scripts in this database first (see the header).', 16, 1) WITH NOWAIT;
    SET NOEXEC ON;
END
GO

-- Pass-through views over PMS's own views, one per view PMS has.
DECLARE @v sysname, @sql nvarchar(max);
DECLARE views CURSOR LOCAL FAST_FORWARD FOR
    SELECT name FROM (VALUES (N'vw_MR_Reader'), (N'vw_MR_Zone'), (N'vw_MR_Property'), (N'vw_MR_Tenant'),
                             (N'vw_MR_ReadingPeriod'), (N'vw_MR_ReadingHistory'), (N'vw_MR_MeterStatus'),
                             (N'vw_MR_InspectionPlan'), (N'vw_MR_InspectionUnit')) v (name);
OPEN views;
FETCH NEXT FROM views INTO @v;
WHILE @@FETCH_STATUS = 0
BEGIN
    IF OBJECT_ID(N'dbo.' + QUOTENAME(@v), N'V') IS NOT NULL
    BEGIN
        SET @sql = N'CREATE OR ALTER VIEW mr.' + QUOTENAME(@v) + N' AS SELECT * FROM dbo.' + QUOTENAME(@v) + N';';
        EXEC (@sql);
        PRINT N'mr.' + @v + N' made over dbo.' + @v + N'.';
    END
    ELSE
    BEGIN
        SET @sql = N'DROP VIEW IF EXISTS mr.' + QUOTENAME(@v) + N';';
        EXEC (@sql);
        PRINT N'dbo.' + @v + N' not in PMS: mr.' + @v + N' not made.';
    END
    FETCH NEXT FROM views INTO @v;
END
CLOSE views;
DEALLOCATE views;
GO

IF COL_LENGTH(N'dbo.vw_MR_Property', N'TranCode') IS NULL OR COL_LENGTH(N'dbo.vw_MR_Tenant', N'BaanCode') IS NULL
    RAISERROR(N'PMS''s vw_MR_Property needs TranCode and vw_MR_Tenant needs BaanCode (finalize uses them).', 10, 1) WITH NOWAIT;
GO

CREATE OR ALTER VIEW mr.vw_MR_Meter_Base
AS
SELECT b.Barcode                          AS MeterId,
       b.MeterNumber,
       p.PropertyCode                     AS PropertyCode,   -- MaintainPropertyMeter.PropertyId is PMS's number (e.g. 1035), not the code
       b.Type                             AS MeterType,
       10                                 AS RegisterDigits,
       4                                  AS DecimalDigits,
       DATEADD(day, -9999, GETDATE())     AS InstallDate,
       b.Barcode                          AS SerialNumber,
       0                                  AS RouteSequence,
       b.Active                           AS Status,
       b.FloatValue,
       b.RateFactor
FROM dbo.MaintainPropertyMeter b
-- One row per property: a property with two tenant rows in the view must not list its meters twice (DIP fix, 2026-10-31).
JOIN (SELECT DISTINCT PropertyId, PropertyCode FROM dbo.vw_MR_Property) p ON p.PropertyId = b.PropertyId;
GO

CREATE OR ALTER VIEW mr.vw_MR_Meter
AS
WITH openPeriod AS (       -- month as a number (2026-9 -> 202609): PMS's codes have no leading zero, so text order is wrong
    SELECT TOP (1) YEAR(StartDate) * 100 + MONTH(StartDate) AS MonthKey
    FROM dbo.vw_MR_ReadingPeriod WHERE Status = 'OPEN' ORDER BY StartDate DESC
),
billed AS (                -- each meter of each finalized (billed) month before the open one, sewerage and irrigation side
    SELECT f.PropertyCode, 'S' AS Type, f.MeterNumberSewerage AS MeterNumber, f.CurrentReadingSewerage AS Reading,
           f.ConsumptionSewerage AS Consumption,
           CAST(LEFT(f.PeriodCode, 4) AS int) * 100 + CAST(SUBSTRING(f.PeriodCode, 6, 2) AS int) AS MonthKey
    FROM mr.MaintainFinalizedMeter f
    WHERE f.IsCurrent = 1 AND f.MeterNumberSewerage <> '0' AND f.SewerageTransactionId IS NOT NULL
      AND NOT (f.CurrentReadingSewerage = 0 AND f.PrevReadingSewerage > 0)   -- not accessible: no reading that month
    UNION ALL
    SELECT f.PropertyCode, 'I', f.MeterNumberIrrigation, f.CurrentReadingIrrigation, f.ConsumptionIrrigation,
           CAST(LEFT(f.PeriodCode, 4) AS int) * 100 + CAST(SUBSTRING(f.PeriodCode, 6, 2) AS int)
    FROM mr.MaintainFinalizedMeter f
    WHERE f.IsCurrent = 1 AND f.MeterNumberIrrigation <> '0' AND f.IrrigationTransactionId IS NOT NULL
      AND NOT (f.CurrentReadingIrrigation = 0 AND f.PrevReadingIrrigation > 0)
),
billedKey AS (
    SELECT LTRIM(RTRIM(PropertyCode)) AS PropertyCode, Type,
           CASE WHEN LTRIM(RTRIM(MeterNumber)) NOT LIKE '%[^0-9]%' AND LTRIM(RTRIM(MeterNumber)) <> ''
                THEN CAST(CAST(LTRIM(RTRIM(MeterNumber)) AS bigint) AS varchar(30)) ELSE LTRIM(RTRIM(MeterNumber)) END AS MeterKey,
           Reading, Consumption, MonthKey,
           ROW_NUMBER() OVER (PARTITION BY LTRIM(RTRIM(PropertyCode)), Type, LTRIM(RTRIM(MeterNumber)) ORDER BY MonthKey DESC) AS rn
    FROM billed
    WHERE MonthKey < (SELECT MonthKey FROM openPeriod)
),
billedAgg AS (             -- the last billed month; average = |mean of the last 3 billed months|, a whole number (as PMS's)
    SELECT PropertyCode, Type, MeterKey,
           MAX(CASE WHEN rn = 1 THEN Reading END)     AS OpeningReading,
           MAX(CASE WHEN rn = 1 THEN Consumption END) AS LastConsumption,
           CAST(ROUND(ABS(AVG(CONVERT(numeric(18,2), Consumption))), 0) AS int) AS AvgConsumption
    FROM billedKey WHERE rn <= 3
    GROUP BY PropertyCode, Type, MeterKey
),
base AS (
    SELECT b.*,
           LTRIM(RTRIM(CAST(b.PropertyCode AS varchar(50)))) COLLATE DATABASE_DEFAULT AS PropertyKey,
           LEFT(LTRIM(CAST(b.MeterType AS varchar(20))), 1) COLLATE DATABASE_DEFAULT AS TypeKey,
           (CASE WHEN LTRIM(RTRIM(CAST(b.MeterNumber AS varchar(30)))) NOT LIKE '%[^0-9]%' AND LTRIM(RTRIM(CAST(b.MeterNumber AS varchar(30)))) <> ''
                 THEN CAST(CAST(LTRIM(RTRIM(CAST(b.MeterNumber AS varchar(30)))) AS bigint) AS varchar(30))
                 ELSE LTRIM(RTRIM(CAST(b.MeterNumber AS varchar(30)))) END) COLLATE DATABASE_DEFAULT AS MeterKey
    FROM mr.vw_MR_Meter_Base b
)
-- 1st the last billed month (mr.MaintainFinalizedMeter), 2nd PMS's history (mr.MeterOpeningReading), else 0.
SELECT b.MeterId, b.MeterNumber, b.PropertyCode, b.MeterType, b.RegisterDigits, b.DecimalDigits,
       CAST(COALESCE(a.OpeningReading,  o.LastReading,     0) AS decimal(18,4)) AS OpeningReading,
       CAST(COALESCE(a.LastConsumption, o.LastConsumption, 0) AS decimal(18,4)) AS LastConsumption,
       CAST(COALESCE(a.AvgConsumption,  o.AvgConsumption,  0) AS decimal(18,4)) AS AvgConsumption,
       b.InstallDate, b.SerialNumber, b.RouteSequence, b.Status, b.FloatValue, b.RateFactor
FROM base b
LEFT JOIN billedAgg a
       ON a.PropertyCode COLLATE DATABASE_DEFAULT = b.PropertyKey AND a.Type = b.TypeKey AND a.MeterKey COLLATE DATABASE_DEFAULT = b.MeterKey
OUTER APPLY (SELECT TOP (1) o.LastReading, o.LastConsumption, o.AvgConsumption
             FROM mr.MeterOpeningReading o
             WHERE o.PropertyCode = b.PropertyKey AND o.Type = b.TypeKey
               AND (CASE WHEN o.MeterNumber NOT LIKE '%[^0-9]%' AND o.MeterNumber <> '' THEN CAST(CAST(o.MeterNumber AS bigint) AS varchar(30)) ELSE o.MeterNumber END) = b.MeterKey
             ORDER BY o.ReadingDate DESC) o;
GO

-- The API's user: read and write its own schema, which also lets it read the views above.
IF DATABASE_PRINCIPAL_ID(N'mr_api') IS NULL
    RAISERROR(N'No user mr_api in this database yet: run db/008_create_api_login.sql here, then this script again.', 10, 1) WITH NOWAIT;
ELSE
    GRANT SELECT, INSERT, UPDATE ON SCHEMA::mr TO [mr_api];
GO

SELECT s.name + N'.' + o.name AS AppView
FROM sys.objects o JOIN sys.schemas s ON s.schema_id = o.schema_id
WHERE s.name = N'mr' AND o.type = 'V' AND o.name LIKE N'vw[_]MR[_]%'
ORDER BY o.name;
GO
SET NOEXEC OFF;
GO

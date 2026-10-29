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
       b.PropertyId                       AS PropertyCode,
       b.Type                             AS MeterType,
       10                                 AS RegisterDigits,
       4                                  AS DecimalDigits,
       DATEADD(day, -9999, GETDATE())     AS InstallDate,
       b.Barcode                          AS SerialNumber,
       0                                  AS RouteSequence,
       b.Active                           AS Status,
       b.FloatValue,
       b.RateFactor
FROM dbo.MaintainPropertyMeter b;
GO

CREATE OR ALTER VIEW mr.vw_MR_Meter
AS
WITH openPeriod AS (
    SELECT TOP (1) CONVERT(char(7), CAST(StartDate AS date), 126) AS PeriodCode
    FROM dbo.vw_MR_ReadingPeriod WHERE Status = 'OPEN' ORDER BY StartDate DESC
),
appReadings AS (           -- uploaded readings of earlier months: the latest per meter per month
    SELECT t.MeterId, t.PeriodCode,
           COALESCE(t.NewCurrentReading, t.NewReading) AS Reading, t.Consumption,
           ROW_NUMBER() OVER (PARTITION BY t.MeterId, t.PeriodCode ORDER BY t.CapturedAtUtc DESC, t.ReceivedAtUtc DESC) AS inMonth
    FROM mr.ReadingTransaction t
    WHERE COALESCE(t.NewCurrentReading, t.NewReading) IS NOT NULL
      AND t.Status <> 'SUPERSEDED'
      AND t.PeriodCode < (SELECT PeriodCode FROM openPeriod)
),
appMonths AS (             -- months numbered newest first (1 = latest)
    SELECT *, ROW_NUMBER() OVER (PARTITION BY MeterId ORDER BY PeriodCode DESC) AS rn
    FROM appReadings WHERE inMonth = 1
),
appAgg AS (
    SELECT MeterId,
           MAX(CASE WHEN rn = 1 THEN Reading END)     AS OpeningReading,
           MAX(CASE WHEN rn = 1 THEN Consumption END) AS LastConsumption,
           CAST(AVG(CASE WHEN rn BETWEEN 2 AND 4 THEN CAST(Consumption AS decimal(18,2)) END) AS decimal(18,2)) AS AvgConsumption
    FROM appMonths WHERE rn <= 4
    GROUP BY MeterId
)
SELECT b.MeterId, b.MeterNumber, b.PropertyCode, b.MeterType, b.RegisterDigits, b.DecimalDigits,
       CAST(COALESCE(a.OpeningReading,  o.LastReading,     0) AS decimal(18,4)) AS OpeningReading,
       CAST(COALESCE(a.LastConsumption, o.LastConsumption, 0) AS decimal(18,4)) AS LastConsumption,
       CAST(COALESCE(a.AvgConsumption,  o.AvgConsumption,  0) AS decimal(18,4)) AS AvgConsumption,
       b.InstallDate, b.SerialNumber, b.RouteSequence, b.Status, b.FloatValue, b.RateFactor
FROM mr.vw_MR_Meter_Base b
LEFT JOIN appAgg a
       ON a.MeterId = CAST(b.MeterId AS varchar(50)) COLLATE DATABASE_DEFAULT
LEFT JOIN mr.MeterOpeningReading o
       ON o.PropertyCode = LTRIM(RTRIM(CAST(b.PropertyCode AS varchar(50)))) COLLATE DATABASE_DEFAULT
      AND o.Type = LEFT(LTRIM(CAST(b.MeterType AS varchar(20))), 1) COLLATE DATABASE_DEFAULT
      AND (CASE WHEN o.MeterNumber NOT LIKE '%[^0-9]%' THEN CAST(CAST(o.MeterNumber AS bigint) AS varchar(30)) ELSE o.MeterNumber END)
        = (CASE WHEN LTRIM(RTRIM(CAST(b.MeterNumber AS varchar(30)))) NOT LIKE '%[^0-9]%' AND LTRIM(RTRIM(CAST(b.MeterNumber AS varchar(30)))) <> ''
                THEN CAST(CAST(LTRIM(RTRIM(CAST(b.MeterNumber AS varchar(30)))) AS bigint) AS varchar(30))
                ELSE LTRIM(RTRIM(CAST(b.MeterNumber AS varchar(30)))) END) COLLATE DATABASE_DEFAULT;
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

/*
  031_opening_from_finalized.sql
  DIP 2026-10-31: where a meter's opening reading comes from (vw_MR_Meter: OpeningReading, LastConsumption,
  AvgConsumption, what the phone shows as "Last time"):
    1st  the last finalized (billed) month before the open one in mr.MaintainFinalizedMeter: its current
         reading and consumption; AvgConsumption = |mean of the last 3 billed months|, a whole number;
    2nd  PMS's reading history (mr.MeterOpeningReading, filled by mr.usp_FillMeterOpeningReading with DIP's
         query: the latest reading per property and meter, AvgConsumption = |mean of the latest 3|, whole);
    else 0.
  Readings that were not finalized no longer carry over: only billed readings open the next month.
  A meter not accessible in its last billed month (no reading) is skipped there, so the month before counts.

  Run in PropertyManagementSystem after 028, then db/ops/fill_meter_opening_reading.sql. Re-runnable.
  (026 and 028 hold the same procedure and view, so running them again does not undo this.)
*/
DECLARE @db sysname = DB_NAME();
IF @db <> N'PropertyManagementSystem'
BEGIN
    RAISERROR(N'Run this in PropertyManagementSystem (now in %s).', 16, 1, @db) WITH NOWAIT;
    SET NOEXEC ON;
END
GO
CREATE OR ALTER PROCEDURE mr.usp_FillMeterOpeningReading
AS
BEGIN
    -- DIP 2026-10-31: per property and meter (S = sewerage, I = irrigation) the latest PMS reading, its
    -- consumption, and AvgConsumption = |mean of the latest 3 readings|, a whole number (DIP's query).
    SET NOCOUNT ON;

    ;WITH s AS (
        SELECT t_date AS ReadingDate, LTRIM(RTRIM(t_plot)) AS PropertyCode, LTRIM(RTRIM(t_metr_1)) AS MeterNumber,
               t_curr_1 AS Reading, t_cons_1 AS Consumption, 'S' AS Type,
               ROW_NUMBER() OVER (PARTITION BY LTRIM(RTRIM(t_plot)), LTRIM(RTRIM(t_metr_1)) ORDER BY t_date DESC) AS rn,
               CAST(ROUND(ABS(AVG(CONVERT(numeric(18,2), t_cons_1))
                   OVER (PARTITION BY LTRIM(RTRIM(t_plot)), LTRIM(RTRIM(t_metr_1)) ORDER BY t_date DESC
                         ROWS BETWEEN CURRENT ROW AND 2 FOLLOWING)), 0) AS int) AS AvgConsumption
        FROM dbo.tgcpms315110
        WHERE LTRIM(RTRIM(ISNULL(t_metr_1, ''))) <> '' AND LTRIM(RTRIM(ISNULL(t_plot, ''))) <> ''
    ),
    i AS (
        SELECT t_date AS ReadingDate, LTRIM(RTRIM(t_plot)) AS PropertyCode, LTRIM(RTRIM(t_metr_2)) AS MeterNumber,
               t_curr_2 AS Reading, t_cons_2 AS Consumption, 'I' AS Type,
               ROW_NUMBER() OVER (PARTITION BY LTRIM(RTRIM(t_plot)), LTRIM(RTRIM(t_metr_2)) ORDER BY t_date DESC) AS rn,
               CAST(ROUND(ABS(AVG(CONVERT(numeric(18,2), t_cons_2))
                   OVER (PARTITION BY LTRIM(RTRIM(t_plot)), LTRIM(RTRIM(t_metr_2)) ORDER BY t_date DESC
                         ROWS BETWEEN CURRENT ROW AND 2 FOLLOWING)), 0) AS int) AS AvgConsumption
        FROM dbo.tgcpms315110
        WHERE LTRIM(RTRIM(ISNULL(t_metr_2, ''))) <> '' AND LTRIM(RTRIM(ISNULL(t_plot, ''))) <> ''
    )
    SELECT PropertyCode COLLATE DATABASE_DEFAULT AS PropertyCode, MeterNumber COLLATE DATABASE_DEFAULT AS MeterNumber, Type,
           ReadingDate, Reading AS LastReading, Consumption AS LastConsumption, AvgConsumption
    INTO #fill
    FROM (SELECT * FROM s WHERE rn = 1 UNION ALL SELECT * FROM i WHERE rn = 1) x;

    BEGIN TRAN;
    DELETE FROM mr.MeterOpeningReading;
    INSERT mr.MeterOpeningReading (PropertyCode, MeterNumber, Type, ReadingDate, LastReading, LastConsumption, AvgConsumption)
    SELECT PropertyCode, MeterNumber, Type, ReadingDate, LastReading, LastConsumption, AvgConsumption
    FROM #fill;
    COMMIT;

    SELECT COUNT(*) AS MetersLoaded FROM mr.MeterOpeningReading;
END
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
IF DATABASE_PRINCIPAL_ID(N'mr_api') IS NOT NULL GRANT SELECT, INSERT, UPDATE ON SCHEMA::mr TO [mr_api];
GO
SET NOEXEC OFF;
GO

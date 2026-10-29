/*
  027_meter_view_opening_reading.sql
  DIP 2026-10-28: vw_MR_Meter's OpeningReading, LastConsumption and AvgConsumption come
    1st from the meter's latest uploaded reading in mr.ReadingTransaction of a month before the open one
        (AvgConsumption: average of the 3 app months before that, once there are any),
    2nd from mr.MeterOpeningReading (PMS history, 026), matched on property, type (S/I) and meter number
        ('001' = '1'),
    else 0 (= never read / no average, spec BR-004 and BR-008).
  Readings of the open month are left out on purpose: a meter read this month keeps last month's reading
  as its opening, so a second reading of it this month is still worked out from the right base.

  vw_MR_Meter_Base holds the meters themselves (PMS MaintainPropertyMeter, as on UAT); vw_MR_Meter adds the
  three values. The API reads vw_MR_Meter as before (docs/source-views.md section 4).

  Needs the PropertyManagementSystem database on the same server and 026. The API's user needs SELECT on
  vw_MR_Meter (granted at the end; change [mr_api] if the user has another name). Run in MRDB after 026.
  Re-runnable.
*/
IF DB_NAME() = N'PropertyManagementSystem'
BEGIN
    -- In PMS this would replace PMS's own view with one that reads itself.
    RAISERROR(N'MRDB only: this makes MRDB''s wrapper view over PMS''s view of the same name. In PropertyManagementSystem run db/028_pms_app_views.sql instead.', 16, 1) WITH NOWAIT;
    SET NOEXEC ON;
END
GO
CREATE OR ALTER VIEW dbo.vw_MR_Meter_Base
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
FROM PropertyManagementSystem.dbo.MaintainPropertyMeter b;
GO

CREATE OR ALTER VIEW dbo.vw_MR_Meter
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
FROM dbo.vw_MR_Meter_Base b
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

IF DATABASE_PRINCIPAL_ID(N'mr_api') IS NOT NULL
    GRANT SELECT ON dbo.vw_MR_Meter TO [mr_api];
GO
SET NOEXEC OFF;
GO

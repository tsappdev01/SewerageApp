/*
  026_meter_opening_reading.sql
  DIP 2026-10-28: each meter's last reading, last consumption and average from PMS's reading history
  (PropertyManagementSystem.dbo.tgcpms315110, last 2 years), kept in mr.MeterOpeningReading. vw_MR_Meter
  (027) uses it for meters that have no reading in the app yet.

  - One row per property, type (S = sewerage from the _1 columns, I = irrigation from the _2 columns)
    and meter. The meter number is kept as written in PMS on the latest reading ('0001'); '001', '0001'
    and '1' count as the same meter.
  - One reading per meter per month (the latest in that month). LastReading / LastConsumption are the
    latest month's; AvgConsumption is the average of the 3 months before it.
  - mr.usp_FillMeterOpeningReading reloads the whole table in one transaction; run it again (or from a
    SQL Agent job, e.g. monthly) when PMS has new readings: ops/fill_meter_opening_reading.sql.

  Needs the PropertyManagementSystem database on the same server (UAT / Production, not the dev or test
  database). The login that runs the procedure needs SELECT on tgcpms315110. Run in MRDB. Re-runnable.
*/
IF OBJECT_ID(N'mr.MeterOpeningReading', N'U') IS NULL
CREATE TABLE mr.MeterOpeningReading (
    PropertyCode        varchar(50)    NOT NULL,
    MeterNumber         varchar(30)    NOT NULL,
    Type                char(1)        NOT NULL,   -- S = sewerage, I = irrigation
    ReadingDate         datetime       NULL,
    LastReading         decimal(18,2)  NULL,
    LastConsumption     decimal(18,2)  NULL,
    AvgConsumption      decimal(18,2)  NULL,
    CONSTRAINT PK_MeterOpeningReading PRIMARY KEY (PropertyCode, Type, MeterNumber)
);
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
        FROM PropertyManagementSystem.dbo.tgcpms315110
        WHERE LTRIM(RTRIM(ISNULL(t_metr_1, ''))) <> '' AND LTRIM(RTRIM(ISNULL(t_plot, ''))) <> ''
    ),
    i AS (
        SELECT t_date AS ReadingDate, LTRIM(RTRIM(t_plot)) AS PropertyCode, LTRIM(RTRIM(t_metr_2)) AS MeterNumber,
               t_curr_2 AS Reading, t_cons_2 AS Consumption, 'I' AS Type,
               ROW_NUMBER() OVER (PARTITION BY LTRIM(RTRIM(t_plot)), LTRIM(RTRIM(t_metr_2)) ORDER BY t_date DESC) AS rn,
               CAST(ROUND(ABS(AVG(CONVERT(numeric(18,2), t_cons_2))
                   OVER (PARTITION BY LTRIM(RTRIM(t_plot)), LTRIM(RTRIM(t_metr_2)) ORDER BY t_date DESC
                         ROWS BETWEEN CURRENT ROW AND 2 FOLLOWING)), 0) AS int) AS AvgConsumption
        FROM PropertyManagementSystem.dbo.tgcpms315110
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

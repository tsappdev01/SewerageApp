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
    SET NOCOUNT ON;
    DECLARE @From date = DATEADD(year, -2, CAST(GETDATE() AS date));   -- last 2 years

    ;WITH raw AS (
        SELECT t_plot, t_metr_1 AS Meter, t_date, t_curr_1 AS Reading, t_cons_1 AS Consumption, 'S' AS Type
        FROM PropertyManagementSystem.dbo.tgcpms315110
        WHERE LTRIM(RTRIM(ISNULL(t_metr_1, ''))) <> '' AND t_date >= @From
        UNION ALL
        SELECT t_plot, t_metr_2, t_date, t_curr_2, t_cons_2, 'I'
        FROM PropertyManagementSystem.dbo.tgcpms315110
        WHERE LTRIM(RTRIM(ISNULL(t_metr_2, ''))) <> '' AND t_date >= @From
    ),
    src AS (
        SELECT LTRIM(RTRIM(t_plot)) COLLATE DATABASE_DEFAULT AS PropertyCode,
               LTRIM(RTRIM(Meter))  COLLATE DATABASE_DEFAULT AS MeterNumber,   -- as written, e.g. '0001'
               -- grouping key only: '001', '0001' and '1' are the same meter
               (CASE WHEN LTRIM(RTRIM(Meter)) NOT LIKE '%[^0-9]%'
                     THEN CAST(CAST(LTRIM(RTRIM(Meter)) AS bigint) AS varchar(30))
                     ELSE LTRIM(RTRIM(Meter)) END) COLLATE DATABASE_DEFAULT AS MeterKey,
               Type, t_date AS ReadingDate, Reading, Consumption
        FROM raw
    ),
    monthly AS (   -- one reading per meter per month: the latest in that month
        SELECT *, ROW_NUMBER() OVER (PARTITION BY PropertyCode, Type, MeterKey, YEAR(ReadingDate), MONTH(ReadingDate)
                                     ORDER BY ReadingDate DESC, Reading DESC) AS inMonth
        FROM src
    ),
    r AS (         -- months numbered newest first (1 = latest)
        SELECT *, ROW_NUMBER() OVER (PARTITION BY PropertyCode, Type, MeterKey ORDER BY ReadingDate DESC) AS rn
        FROM monthly WHERE inMonth = 1
    )
    SELECT PropertyCode,
           MAX(CASE WHEN rn = 1 THEN MeterNumber END)  AS MeterNumber,   -- as on the latest reading
           Type,
           MAX(CASE WHEN rn = 1 THEN ReadingDate END)  AS ReadingDate,
           MAX(CASE WHEN rn = 1 THEN Reading END)      AS LastReading,
           MAX(CASE WHEN rn = 1 THEN Consumption END)  AS LastConsumption,
           CAST(AVG(CASE WHEN rn BETWEEN 2 AND 4 THEN CAST(Consumption AS decimal(18,2)) END) AS decimal(18,2)) AS AvgConsumption
    INTO #fill
    FROM r
    WHERE rn <= 4
    GROUP BY PropertyCode, Type, MeterKey;

    BEGIN TRAN;
    DELETE FROM mr.MeterOpeningReading;
    INSERT mr.MeterOpeningReading (PropertyCode, MeterNumber, Type, ReadingDate, LastReading, LastConsumption, AvgConsumption)
    SELECT PropertyCode, MeterNumber, Type, ReadingDate, LastReading, LastConsumption, AvgConsumption
    FROM #fill;
    COMMIT;

    SELECT COUNT(*) AS MetersLoaded FROM mr.MeterOpeningReading;
END
GO

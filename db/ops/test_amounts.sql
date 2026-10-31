/* ops/test_amounts.sql — works out sewerage and irrigation amounts (excluding VAT) the way the app does
   (FR-037.5 to .9, api/src/MeterReading.Api/Domain/Finalize.cs), for readings you type in. Writes nothing.

   Amount     = consumption x FloatValue x 1.2 x (RateFactor / 100), rounded to 2 decimals
   Irrigation = the calculated amount if above 10% of the sewerage amount or above 500,
                else 10% of the sewerage amount, at most 500 (needs the sewerage meter of the same row)

   FloatValue and RateFactor: from the meter in vw_MR_Meter when you give its MeterId, else the values typed
   (UAT's meters are 220 and 1.50). Run in PropertyManagementSystem. */
SET NOCOUNT ON;

DECLARE @in TABLE (
    Line        int IDENTITY(1, 1),
    MeterType   char(1)        NOT NULL,   -- 'S' sewerage, 'I' irrigation
    OldReading  decimal(18,4)  NOT NULL,
    NewReading  decimal(18,4)  NOT NULL,
    PairNo      int            NULL,       -- same number on an S and an I line = one billing row (for the irrigation minimum)
    MeterId     varchar(50)    NULL,       -- optional: take FloatValue / RateFactor from this meter
    FloatValue  decimal(18,6)  NULL,       -- used when no MeterId (default 220)
    RateFactor  decimal(18,6)  NULL        -- used when no MeterId (default 1.50)
);

/* ----- type your readings here ----- */
INSERT @in (MeterType, OldReading, NewReading, PairNo, MeterId, FloatValue, RateFactor) VALUES
    ('S', 3654, 3855, 1, NULL, 220, 1.50),
    ('I',    0,  100, 1, NULL, 220, 1.50),
    ('S', 1000, 1100, 2, NULL, 220, 1.50),     -- 100 used: 396.00; 10% = 39.60
    ('I',  500,  505, 2, NULL, 220, 1.50);     -- 5 used: 19.80 calculated, billed 39.60 (the 10% minimum)
/* ------------------------------------ */

;WITH m AS (
    SELECT i.*, i.NewReading - i.OldReading AS Consumption,
           COALESCE(TRY_CAST(v.FloatValue AS decimal(18,6)), i.FloatValue, 220)  AS UsedFloatValue,
           COALESCE(TRY_CAST(v.RateFactor AS decimal(18,6)), i.RateFactor, 1.50) AS UsedRateFactor
    FROM @in i
    LEFT JOIN mr.vw_MR_Meter v ON i.MeterId IS NOT NULL AND CAST(v.MeterId AS varchar(50)) = i.MeterId
),
a AS (
    SELECT *, CAST(ROUND(CAST(Consumption AS decimal(38,10)) * UsedFloatValue * 1.2 * (UsedRateFactor / 100), 2) AS decimal(18,2)) AS AmountCalculated
    FROM m
),
pair AS (   -- the sewerage amount of the same row, for the irrigation minimum
    SELECT PairNo, CAST(ROUND(SUM(AmountCalculated) * 0.10, 2) AS decimal(18,2)) AS TenPercentOfSewerage
    FROM a WHERE MeterType = 'S' AND PairNo IS NOT NULL GROUP BY PairNo
)
SELECT a.Line, CASE a.MeterType WHEN 'S' THEN 'Sewerage' ELSE 'Irrigation' END AS Meter,
       a.OldReading, a.NewReading, a.Consumption,
       a.UsedFloatValue AS FloatValue, a.UsedRateFactor AS RateFactor,
       a.AmountCalculated AS [Amount (calculated, excl. VAT)],
       p.TenPercentOfSewerage AS [10% of sewerage],
       CAST(CASE WHEN a.MeterType = 'S' THEN a.AmountCalculated
                 WHEN p.TenPercentOfSewerage IS NULL THEN a.AmountCalculated     -- no sewerage meter on the row: no minimum
                 WHEN a.AmountCalculated > p.TenPercentOfSewerage OR a.AmountCalculated > 500 THEN a.AmountCalculated
                 WHEN p.TenPercentOfSewerage <= 500 THEN p.TenPercentOfSewerage
                 ELSE 500 END AS decimal(18,2)) AS [Amount billed (excl. VAT)],
       CASE WHEN a.Consumption < 0 THEN 'New reading is below the old one: check (meter rolled over or replaced?)' ELSE '' END AS Note
FROM a LEFT JOIN pair p ON p.PairNo = a.PairNo
ORDER BY a.PairNo, a.MeterType DESC, a.Line;

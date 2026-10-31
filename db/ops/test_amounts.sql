/* ops/test_amounts.sql — works out sewerage and irrigation amounts (excluding VAT) the way the app does
   (FR-037.5 to .9, api/src/MeterReading.Api/Domain/Finalize.cs), for readings you type in. Writes nothing.

   You give: property, meter type (S / I), old and new reading; the meter number only when the property has
   more than one meter of that type; the reading date only to test a no-charge plot (default today).
   It takes FloatValue and RateFactor from the property's meter in vw_MR_Meter, and puts the sewerage and
   irrigation meters with the same number on one billing row, as finalizing does.

   Amount     = consumption x FloatValue x 1.2 x (RateFactor / 100), rounded to 2 decimals
   Irrigation = the calculated amount if above 10% of the row's sewerage amount or above 500,
                else 10% of the sewerage amount, at most 500; 0 on a no-charge plot (mr.IrrigationNoCharge)
   Run in PropertyManagementSystem. */
SET NOCOUNT ON;

DECLARE @in TABLE (
    Line          int IDENTITY(1, 1),
    PropertyCode  varchar(30)    NOT NULL,
    MeterType     char(1)        NOT NULL,   -- 'S' sewerage, 'I' irrigation
    OldReading    decimal(18,4)  NOT NULL,
    NewReading    decimal(18,4)  NOT NULL,
    MeterNumber   varchar(30)    NULL,       -- only when the property has several meters of this type
    ReadingDate   date           NULL        -- only to test a no-charge plot; default today
);

/* ----- type your readings here ----- */
INSERT @in (PropertyCode, MeterType, OldReading, NewReading, MeterNumber, ReadingDate) VALUES
    ('597-646', 'S', 3654, 3855, NULL, NULL),
    ('597-646', 'I',    0,  100, NULL, NULL);
/* ------------------------------------ */

;WITH meters AS (   -- the property's meters, with a number key ('0001' = '1')
    SELECT LTRIM(RTRIM(CAST(v.PropertyCode AS varchar(30)))) AS PropertyCode,
           LEFT(LTRIM(CAST(v.MeterType AS varchar(20))), 1)  AS MeterType,
           CAST(v.MeterId AS varchar(50)) AS MeterId, LTRIM(RTRIM(CAST(v.MeterNumber AS varchar(30)))) AS MeterNumber,
           CASE WHEN LTRIM(RTRIM(CAST(v.MeterNumber AS varchar(30)))) NOT LIKE '%[^0-9]%' AND LTRIM(RTRIM(CAST(v.MeterNumber AS varchar(30)))) <> ''
                THEN CAST(CAST(LTRIM(RTRIM(CAST(v.MeterNumber AS varchar(30)))) AS bigint) AS varchar(30))
                ELSE LTRIM(RTRIM(CAST(v.MeterNumber AS varchar(30)))) END AS NumKey,
           TRY_CAST(v.FloatValue AS decimal(18,6)) AS FloatValue, TRY_CAST(v.RateFactor AS decimal(18,6)) AS RateFactor
    FROM mr.vw_MR_Meter v
),
m AS (
    SELECT i.*, x.MeterId, x.MeterNumber AS FoundNumber, x.NumKey, x.FloatValue, x.RateFactor, x.Found,
           i.NewReading - i.OldReading AS Consumption
    FROM @in i
    OUTER APPLY (SELECT TOP (1) mt.*, COUNT(*) OVER () AS Found
                 FROM meters mt
                 WHERE mt.PropertyCode = LTRIM(RTRIM(i.PropertyCode)) AND mt.MeterType = i.MeterType
                   AND (i.MeterNumber IS NULL OR mt.NumKey =
                        CASE WHEN LTRIM(RTRIM(i.MeterNumber)) NOT LIKE '%[^0-9]%' AND LTRIM(RTRIM(i.MeterNumber)) <> ''
                             THEN CAST(CAST(LTRIM(RTRIM(i.MeterNumber)) AS bigint) AS varchar(30)) ELSE LTRIM(RTRIM(i.MeterNumber)) END)
                 ORDER BY mt.MeterId) x
),
a AS (
    SELECT m.*,
           CAST(CASE WHEN m.FloatValue IS NULL OR m.RateFactor IS NULL THEN 0
                     ELSE ROUND(CAST(m.Consumption AS decimal(38,10)) * m.FloatValue * 1.2 * (m.RateFactor / 100), 2) END AS decimal(18,2)) AS AmountCalculated,
           CASE WHEN m.MeterType = 'I' AND nc.PropertyCode IS NOT NULL AND ISNULL(m.ReadingDate, CAST(GETDATE() AS date)) > nc.NoChargeAfter
                     AND (nc.ToDate IS NULL OR ISNULL(m.ReadingDate, CAST(GETDATE() AS date)) <= nc.ToDate) THEN 1 ELSE 0 END AS NoCharge
    FROM m
    LEFT JOIN mr.IrrigationNoCharge nc ON nc.PropertyCode = LTRIM(RTRIM(m.PropertyCode))
),
sew AS (            -- 10% of the sewerage amount on the same billing row (same property and meter number)
    SELECT LTRIM(RTRIM(PropertyCode)) AS PropertyCode, NumKey, CAST(ROUND(SUM(AmountCalculated) * 0.10, 2) AS decimal(18,2)) AS TenPercent
    FROM a WHERE MeterType = 'S' AND NumKey IS NOT NULL GROUP BY LTRIM(RTRIM(PropertyCode)), NumKey
)
SELECT a.Line, a.PropertyCode, CASE a.MeterType WHEN 'S' THEN 'Sewerage' ELSE 'Irrigation' END AS Meter,
       a.MeterId, a.FoundNumber AS MeterNumber, a.OldReading, a.NewReading, a.Consumption,
       a.FloatValue, a.RateFactor,
       CASE WHEN a.NoCharge = 1 THEN CAST(0 AS decimal(18,2)) ELSE a.AmountCalculated END AS [Amount (calculated, excl. VAT)],
       CASE WHEN a.MeterType = 'I' THEN s.TenPercent END AS [10% of sewerage],
       CAST(CASE WHEN a.MeterType = 'S' THEN a.AmountCalculated
                 WHEN a.NoCharge = 1 THEN 0
                 WHEN ISNULL(s.TenPercent, 0) = 0 THEN a.AmountCalculated          -- no sewerage meter on the row
                 WHEN a.AmountCalculated > s.TenPercent OR a.AmountCalculated > 500 THEN a.AmountCalculated
                 WHEN s.TenPercent <= 500 THEN s.TenPercent
                 ELSE 500 END AS decimal(18,2)) AS [Amount billed (excl. VAT)],
       CONCAT(
           CASE WHEN a.MeterId IS NULL THEN 'No such meter at this property (check code, type, number). ' END,
           CASE WHEN a.Found > 1 AND a.MeterNumber IS NULL THEN CONCAT('Property has ', a.Found, ' meters of this type: give MeterNumber. ') END,
           CASE WHEN a.MeterId IS NOT NULL AND (a.FloatValue IS NULL OR a.RateFactor IS NULL) THEN 'Meter has no FloatValue or RateFactor: amount 0. ' END,
           CASE WHEN a.NoCharge = 1 THEN 'No-charge plot (mr.IrrigationNoCharge): irrigation not charged. ' END,
           CASE WHEN a.MeterType = 'I' AND s.TenPercent IS NULL AND a.MeterId IS NOT NULL THEN 'No sewerage line for this meter number: irrigation minimum not checked. ' END,
           CASE WHEN a.Consumption < 0 THEN 'New reading below the old one (rolled over or replaced?). ' END) AS Note
FROM a
LEFT JOIN sew s ON s.PropertyCode = LTRIM(RTRIM(a.PropertyCode)) AND s.NumKey = a.NumKey
ORDER BY a.PropertyCode, a.NumKey, a.MeterType DESC, a.Line;

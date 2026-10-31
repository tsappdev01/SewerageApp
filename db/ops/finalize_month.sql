/* ops/finalize_month.sql — "Finalize property" for every fully read property of the open month, from SSMS.

   Writes mr.MaintainFinalizedMeter exactly as the phone's Finalize property button does (FR-037,
   api/src/MeterReading.Api/Domain/Finalize.cs): one row per meter number (sewerage and irrigation side by
   side), amounts = consumption x FloatValue x 1.2 x RateFactor/100, VAT 5%, the irrigation minimum
   (10% of sewerage, at most 500), no-charge plots (mr.IrrigationNoCharge), TenantCode / BaanCode / CompanyName
   from vw_MR_Tenant and TranCode from vw_MR_Property as they are now, VATCode SSD, ChargeType1 sew,
   ChargeType2 Irri, FromDate / EndDate from vw_MR_ReadingPeriod, and the sub-tenant (the reading's,
   else the office's name in mr.PropertySubTenant, db/030). A property finalized before keeps its rows
   with IsCurrent = 0 when finalized again.

   A property is finalized only when every one of its meters has a reading this month (sent, or "can't
   reach"); the others are listed with how many meters are left. When every property is finalized the month
   closes (FR-037.11), as on the phone; set @Close = 1 to close it anyway (as My summary -> Finalize month).

   Run in PropertyManagementSystem (the app's database). First with @Preview = 1 (the default): it shows what
   it would write and writes nothing. Then with @Preview = 0 to write.
*/
SET NOCOUNT ON;
SET XACT_ABORT ON;

DECLARE @Preview      bit         = 1;            -- 1: show only. 0: write.
DECLARE @Close        bit         = 0;            -- 1: close the month afterwards even if some properties are not finalized.
DECLARE @Refinalize   bit         = 0;            -- 1: finalize again properties already finalized this month.
DECLARE @OnlyProperty varchar(30) = NULL;         -- e.g. '597-646' for one property; NULL for all.
DECLARE @FinalizedBy  varchar(50) = LEFT(N'office:' + SUSER_SNAME(), 50);

/* 1. The open month, as the API takes it: the newest OPEN row; its code is yyyy-MM from StartDate. */
DECLARE @Period char(7), @From date, @End date;
SELECT TOP (1) @Period = CONVERT(char(7), CAST(StartDate AS date), 126), @From = CAST(StartDate AS date), @End = CAST(EndDate AS date)
FROM mr.vw_MR_ReadingPeriod WHERE Status = 'OPEN' ORDER BY StartDate DESC;
IF @Period IS NULL
BEGIN
    RAISERROR(N'There is no OPEN period in vw_MR_ReadingPeriod.', 16, 1);
    RETURN;
END
IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'SubTenant') IS NULL
BEGIN
    RAISERROR(N'Run db/030_property_sub_tenant.sql first (mr.MaintainFinalizedMeter has no SubTenant column).', 16, 1);
    RETURN;
END
IF EXISTS (SELECT 1 FROM mr.ReadingPeriodStatus WHERE PeriodCode = @Period AND Status = 'CLOSED')
BEGIN
    RAISERROR(N'Period %s is closed. Reopen it first (db/ops/reopen_reading_period.sql).', 16, 1, @Period);
    RETURN;
END
PRINT N'Period ' + @Period + N' (' + CONVERT(varchar(10), @From, 23) + N' to ' + CONVERT(varchar(10), @End, 23) + N')'
    + CASE WHEN @Preview = 1 THEN N' - PREVIEW, nothing is written.' ELSE N' - WRITING.' END;

/* 2. The active meters of active properties (the API's meter list), one per barcode. */
DROP TABLE IF EXISTS #m;
SELECT CAST(m.MeterId AS varchar(50)) AS MeterId, CAST(m.MeterNumber AS varchar(30)) AS MeterNumber,
       CASE UPPER(LEFT(LTRIM(CAST(m.MeterType AS varchar(20))), 1)) WHEN 'I' THEN 'IRRIGATION' WHEN 'S' THEN 'SEWERAGE'
            ELSE UPPER(CAST(m.MeterType AS varchar(20))) END AS MeterType,
       CAST(p.PropertyCode AS varchar(30)) AS PropertyCode,
       TRY_CAST(m.OpeningReading AS decimal(18,4)) AS LastReading,
       TRY_CAST(m.FloatValue AS decimal(18,6)) AS FloatValue, TRY_CAST(m.RateFactor AS decimal(18,6)) AS RateFactor
INTO #m
FROM mr.vw_MR_Meter m
JOIN mr.vw_MR_Property p ON p.PropertyCode = m.PropertyCode
LEFT JOIN mr.vw_MR_Zone z ON z.ZoneCode = p.ZoneCode
WHERE UPPER(LTRIM(RTRIM(CAST(m.Status AS varchar(10))))) IN ('1','TRUE','T','Y','YES','ACTIVE')
  AND UPPER(LTRIM(RTRIM(CAST(p.IsActive AS varchar(10))))) IN ('1','TRUE','T','Y','YES','ACTIVE')
  AND (z.IsActive IS NULL OR UPPER(LTRIM(RTRIM(CAST(z.IsActive AS varchar(10))))) IN ('1','TRUE','T','Y','YES','ACTIVE'));
-- A property with two tenant rows lists its meters twice: keep one. A barcode on different meters is left out.
WITH d AS (SELECT *, ROW_NUMBER() OVER (PARTITION BY MeterId ORDER BY (SELECT NULL)) AS rn,
                  COUNT(*) OVER (PARTITION BY MeterId) AS n,
                  COUNT(*) OVER (PARTITION BY MeterId, MeterNumber, PropertyCode) AS same FROM #m)
DELETE FROM d WHERE rn > 1 OR same <> n;

/* 3. Each meter's latest reading this month (not superseded); read = sent, approved, ... or "can't reach". */
DROP TABLE IF EXISTS #t;
SELECT x.*
INTO #t
FROM (
    SELECT t.MeterId, t.TransactionId, t.Status, t.PropertyId, t.PreviousReading, t.TenantCode, t.SubTenant,
           COALESCE(t.NewReading, t.NewCurrentReading) AS CurrentReading, t.Consumption,
           CAST(t.CapturedAtUtc AT TIME ZONE 'UTC' AT TIME ZONE 'Arabian Standard Time' AS date) AS ReadingDate,
           ROW_NUMBER() OVER (PARTITION BY t.MeterId ORDER BY t.ReceivedAtUtc DESC, t.CapturedAtUtc DESC) AS rn
    FROM mr.ReadingTransaction t
    WHERE t.PeriodCode = @Period AND t.Status <> 'SUPERSEDED'
) x
WHERE x.rn = 1 AND x.MeterId IN (SELECT MeterId FROM #m);

/* 4. Properties: meters, read, and whether to finalize now. */
DROP TABLE IF EXISTS #p;
SELECT m.PropertyCode, COUNT(*) AS Meters,
       SUM(CASE WHEN t.Status IN ('ACCEPTED','APPROVED','POSTED','ACKNOWLEDGED','BILLING_FAILED') THEN 1 ELSE 0 END) AS ReadMeters,
       MAX(CASE WHEN f.PropertyCode IS NOT NULL THEN 1 ELSE 0 END) AS AlreadyFinalized
INTO #p
FROM #m m
LEFT JOIN #t t ON t.MeterId = m.MeterId
LEFT JOIN (SELECT DISTINCT PropertyCode FROM mr.MaintainFinalizedMeter WHERE PeriodCode = @Period AND IsCurrent = 1) f
       ON f.PropertyCode = m.PropertyCode
GROUP BY m.PropertyCode;

DROP TABLE IF EXISTS #todo;
SELECT PropertyCode INTO #todo FROM #p
WHERE ReadMeters = Meters
  AND (@Refinalize = 1 OR AlreadyFinalized = 0)
  AND (@OnlyProperty IS NULL OR PropertyCode = @OnlyProperty);

/* 5. The tenants of those properties, as vw_MR_Tenant has them now. */
DROP TABLE IF EXISTS #ten;
SELECT DISTINCT CAST(t.PropertyCode AS varchar(30)) AS PropertyCode, LTRIM(RTRIM(CAST(t.TenantCode AS varchar(30)))) AS TenantCode,
       LTRIM(RTRIM(CAST(t.CompanyName AS nvarchar(200)))) AS CompanyName, NULLIF(LTRIM(RTRIM(CAST(t.BaanCode AS varchar(30)))), '') AS BaanCode
INTO #ten
FROM mr.vw_MR_Tenant t
WHERE CAST(t.PropertyCode AS varchar(30)) IN (SELECT PropertyCode FROM #todo)
  AND LTRIM(RTRIM(CAST(t.TenantCode AS varchar(30)))) <> '';

/* 6. One line per meter with its amount. */
DROP TABLE IF EXISTS #mv;
SELECT v.*, CAST(CASE WHEN v.NoCharge = 1 OR v.FloatValue IS NULL OR v.RateFactor IS NULL THEN 0
                      ELSE ROUND(CAST(v.Consumption AS decimal(38,10)) * v.FloatValue * 1.2 * (v.RateFactor / 100), 2) END AS decimal(18,2)) AS Amount
INTO #mv
FROM (
SELECT m.PropertyCode, m.MeterId, m.MeterNumber, m.MeterType,
       ISNULL(t.PreviousReading, ISNULL(m.LastReading, 0)) AS Previous,
       ISNULL(t.CurrentReading, 0) AS [Current], ISNULL(t.Consumption, 0) AS Consumption,
       t.TransactionId, t.PropertyId, m.FloatValue, m.RateFactor,
       tc.TenantCode, tn.BaanCode, tn.CompanyName,
       tr.TranCode,
       -- The sub-tenant sent with the reading, else the office's name for the property (db/030).
       COALESCE(NULLIF(LTRIM(RTRIM(t.SubTenant)), N''), st.SubTenantName) AS SubTenant,
       CAST(CASE WHEN m.MeterType = 'IRRIGATION' AND nc.PropertyCode IS NOT NULL AND t.ReadingDate > nc.NoChargeAfter
                      AND (nc.ToDate IS NULL OR t.ReadingDate <= nc.ToDate) THEN 1 ELSE 0 END AS bit) AS NoCharge
FROM #m m
JOIN #todo d ON d.PropertyCode = m.PropertyCode
LEFT JOIN #t t ON t.MeterId = m.MeterId
-- The reading's tenant; a reading without one takes the property's only tenant (FR-037.10).
OUTER APPLY (SELECT CASE WHEN NULLIF(LTRIM(RTRIM(t.TenantCode)), '') IS NOT NULL THEN LTRIM(RTRIM(t.TenantCode))
                         WHEN (SELECT COUNT(DISTINCT x.TenantCode) FROM #ten x WHERE x.PropertyCode = m.PropertyCode) = 1
                         THEN (SELECT TOP (1) x.TenantCode FROM #ten x WHERE x.PropertyCode = m.PropertyCode) END AS TenantCode) tc
OUTER APPLY (SELECT TOP (1) x.BaanCode, x.CompanyName FROM #ten x WHERE x.PropertyCode = m.PropertyCode AND x.TenantCode = tc.TenantCode) tn
OUTER APPLY (SELECT TOP (1) NULLIF(LTRIM(RTRIM(CAST(p.TranCode AS varchar(30)))), '') AS TranCode
             FROM mr.vw_MR_Property p
             WHERE tc.TenantCode IS NOT NULL AND CAST(p.PropertyCode AS varchar(30)) = m.PropertyCode
             ORDER BY CASE WHEN LTRIM(RTRIM(CAST(p.TenantCode AS varchar(30)))) = tc.TenantCode THEN 0 ELSE 1 END) tr
LEFT JOIN mr.IrrigationNoCharge nc ON nc.PropertyCode = m.PropertyCode
LEFT JOIN mr.PropertySubTenant st ON st.PropertyCode = m.PropertyCode AND st.IsActive = 1
) v;

/* 7. Sewerage and irrigation with the same meter number share a row (FR-037.3). */
DROP TABLE IF EXISTS #rows;
WITH s AS (SELECT *, LTRIM(RTRIM(MeterNumber)) AS NumKey, ROW_NUMBER() OVER (PARTITION BY PropertyCode, LTRIM(RTRIM(MeterNumber)) ORDER BY MeterId) AS n
           FROM #mv WHERE MeterType = 'SEWERAGE'),
     i AS (SELECT *, LTRIM(RTRIM(MeterNumber)) AS NumKey, ROW_NUMBER() OVER (PARTITION BY PropertyCode, LTRIM(RTRIM(MeterNumber)) ORDER BY MeterId) AS n
           FROM #mv WHERE MeterType = 'IRRIGATION'),
     pr AS (SELECT
                COALESCE(s.PropertyCode, i.PropertyCode) AS PropertyCode,
                s.MeterNumber AS SNumber, s.Previous AS SPrev, s.[Current] AS SCur, s.Consumption AS SCons, s.TransactionId AS STx,
                s.FloatValue AS SFloat, s.RateFactor AS SRate, ISNULL(s.Amount, 0) AS SewerageAmount,
                i.MeterNumber AS INumber, i.Previous AS IPrev, i.[Current] AS ICur, i.Consumption AS ICons, i.TransactionId AS ITx,
                i.FloatValue AS IFloat, i.RateFactor AS IRate, ISNULL(i.Amount, 0) AS IrrigationAmountCalculated,
                CASE WHEN i.MeterId IS NULL THEN NULL ELSE i.NoCharge END AS INoCharge,
                COALESCE(s.TranCode, i.TranCode) AS TranCode, COALESCE(s.BaanCode, i.BaanCode) AS BaanCode,
                COALESCE(s.TenantCode, i.TenantCode) AS TenantCode, COALESCE(s.CompanyName, i.CompanyName) AS CompanyName,
                COALESCE(s.SubTenant, i.SubTenant) AS SubTenant
            FROM s FULL JOIN i ON i.PropertyCode = s.PropertyCode AND i.NumKey = s.NumKey AND i.n = s.n)
,
     ten AS (SELECT pr.*, CAST(ROUND(SewerageAmount * 0.10, 2) AS decimal(18,2)) AS TenPercentAmount FROM pr),
     -- FR-037.9: the calculated irrigation amount if above 10% of sewerage or above 500, else 10% of sewerage, at most 500.
     irr AS (SELECT ten.*, CAST(CASE WHEN INumber IS NULL OR INoCharge = 1 THEN 0
                                     WHEN IrrigationAmountCalculated > TenPercentAmount OR IrrigationAmountCalculated > 500 THEN IrrigationAmountCalculated
                                     WHEN TenPercentAmount <= 500 THEN TenPercentAmount
                                     WHEN TenPercentAmount > 0 THEN 500
                                     ELSE IrrigationAmountCalculated END AS decimal(18,2)) AS IrrigationAmount FROM ten),
     vat AS (SELECT irr.*, CAST(ROUND(SewerageAmount * 0.05, 2) AS decimal(18,2)) AS SewerageVat,
                    CAST(ROUND(IrrigationAmount * 0.05, 2) AS decimal(18,2)) AS IrrigationVat FROM irr)
SELECT vat.*, CAST(SewerageAmount + IrrigationAmount + SewerageVat + IrrigationVat AS decimal(18,2)) AS TotalAmount
INTO #rows FROM vat;

/* 8. What it does: the rows, then a line per property. */
SELECT PropertyCode, ISNULL(SNumber, '0') AS MeterNumberSewerage, SCons AS ConsumptionSewerage, SewerageAmount,
       ISNULL(INumber, '0') AS MeterNumberIrrigation, ICons AS ConsumptionIrrigation, IrrigationAmountCalculated, IrrigationAmount,
       SewerageVat, IrrigationVat, TotalAmount, TenPercentAmount, TenantCode, BaanCode, TranCode, CompanyName, SubTenant,
       ISNULL(INoCharge, 0) AS IrrigationNoCharge
FROM #rows ORDER BY PropertyCode, SNumber, INumber;

SELECT p.PropertyCode, p.Meters, p.ReadMeters, p.Meters - p.ReadMeters AS MetersLeft,
       CASE WHEN d.PropertyCode IS NOT NULL THEN CASE WHEN @Preview = 1 THEN 'would be finalized' ELSE 'finalized now' END
            WHEN p.ReadMeters < p.Meters THEN 'not finalized: meters left to read'
            WHEN p.AlreadyFinalized = 1 THEN 'already finalized (set @Refinalize = 1 to redo)'
            ELSE 'not selected' END AS Result
FROM #p p LEFT JOIN #todo d ON d.PropertyCode = p.PropertyCode
WHERE p.ReadMeters > 0 OR d.PropertyCode IS NOT NULL
ORDER BY CASE WHEN d.PropertyCode IS NOT NULL THEN 0 ELSE 1 END, p.PropertyCode;

SELECT COUNT(DISTINCT PropertyCode) AS PropertiesToFinalize, COUNT(*) AS BillingRows, SUM(TotalAmount) AS TotalAmount FROM #rows;

IF @Preview = 1
BEGIN
    PRINT N'Preview only. Check the results, then set @Preview = 0 and run again to write.';
    RETURN;
END

/* 9. Write: earlier rows of these properties stay with IsCurrent = 0; the new set is current. */
BEGIN TRANSACTION;
DECLARE @At datetime2(0) = SYSUTCDATETIME();

UPDATE f SET IsCurrent = 0
FROM mr.MaintainFinalizedMeter f WITH (UPDLOCK, HOLDLOCK)
WHERE f.PeriodCode = @Period AND f.IsCurrent = 1 AND f.PropertyCode IN (SELECT PropertyCode FROM #todo);

INSERT mr.MaintainFinalizedMeter (PeriodCode, PropertyId, PropertyCode,
    MeterNumberSewerage, PrevReadingSewerage, CurrentReadingSewerage, ConsumptionSewerage,
    MeterNumberIrrigation, PrevReadingIrrigation, CurrentReadingIrrigation, ConsumptionIrrigation,
    SewerageTransactionId, IrrigationTransactionId, FinalizedByReaderId, DeviceId, FinalizedAtUtc, IsCurrent,
    SewerageAmount, IrrigationAmount, FloatValueSewerage, RateFactorSewerage, FloatValueIrrigation, RateFactorIrrigation,
    SewerageVat, IrrigationVat, TotalAmount, IrrigationNoCharge, TenPercentAmount, IrrigationAmountCalculated,
    TranCode, BaanCode, TenantCode, FromDate, EndDate, CompanyName, VATCode, ChargeType1, ChargeType2, SubTenant)
SELECT @Period, (SELECT TOP (1) v.PropertyId FROM #mv v WHERE v.PropertyCode = r.PropertyCode AND v.PropertyId IS NOT NULL), r.PropertyCode,
       ISNULL(r.SNumber, '0'), ISNULL(r.SPrev, 0), ISNULL(r.SCur, 0), ISNULL(r.SCons, 0),
       ISNULL(r.INumber, '0'), ISNULL(r.IPrev, 0), ISNULL(r.ICur, 0), ISNULL(r.ICons, 0),
       r.STx, r.ITx, @FinalizedBy, NULL, @At, 1,
       r.SewerageAmount, r.IrrigationAmount, r.SFloat, r.SRate, r.IFloat, r.IRate,
       r.SewerageVat, r.IrrigationVat, r.TotalAmount, ISNULL(r.INoCharge, 0), r.TenPercentAmount, r.IrrigationAmountCalculated,
       r.TranCode, r.BaanCode, r.TenantCode, @From, @End, r.CompanyName, 'SSD', 'sew', 'Irri', r.SubTenant
FROM #rows r;
PRINT CONCAT(@@ROWCOUNT, N' billing rows written.');

/* 10. Close the month: when every property is finalized (FR-037.11), or when asked (@Close = 1). */
DECLARE @All int = (SELECT COUNT(*) FROM #p);
DECLARE @Done int = (SELECT COUNT(DISTINCT f.PropertyCode) FROM mr.MaintainFinalizedMeter f
                     WHERE f.PeriodCode = @Period AND f.IsCurrent = 1 AND f.PropertyCode IN (SELECT PropertyCode FROM #p));
IF @Close = 1 OR (@All > 0 AND @Done >= @All)
BEGIN
    MERGE mr.ReadingPeriodStatus WITH (HOLDLOCK) AS t
    USING (SELECT @Period AS PeriodCode) AS s ON t.PeriodCode = s.PeriodCode
    WHEN MATCHED THEN UPDATE SET Status = 'CLOSED', ChangedAtUtc = SYSUTCDATETIME(), ChangedBy = @FinalizedBy,
         Note = CASE WHEN @Done >= @All THEN N'Posting complete: every property finalized (office script)' ELSE N'Finalize month (office script)' END
    WHEN NOT MATCHED THEN INSERT (PeriodCode, Status, ChangedBy, Note)
         VALUES (@Period, 'CLOSED', @FinalizedBy,
                 CASE WHEN @Done >= @All THEN N'Posting complete: every property finalized (office script)' ELSE N'Finalize month (office script)' END);
    PRINT N'Period ' + @Period + N' is now CLOSED: phones take no new readings until it is reopened (db/ops/reopen_reading_period.sql).';
END
ELSE
    PRINT CONCAT(N'Period ', @Period, N' stays open: ', @Done, N' of ', @All, N' properties finalized. Set @Close = 1 to close it anyway.');
COMMIT TRANSACTION;

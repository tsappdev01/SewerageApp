/* ops/finalize_month.sql — "Finalize property" for every fully read property of the open month, from SSMS.

   Writes mr.MaintainFinalizedMeter exactly as the phone's Finalize property button does (FR-037,
   api/src/MeterReading.Api/Domain/Finalize.cs): one row per meter number (sewerage and irrigation side by
   side), amounts = consumption x FloatValue x 1.2 x RateFactor/100, VAT 5%, the irrigation minimum
   (10% of sewerage, at most 500), no-charge plots (mr.IrrigationNoCharge), TenantCode / BaanCode / CompanyName
   from vw_MR_Tenant and TranCode from vw_MR_Property as they are now, VATCode SSD, ChargeType1 sew,
   ChargeType2 Irri, FromDate / ToDate from vw_MR_ReadingPeriod, one invoice per property in PMS's
   dbo.MaintainFinalizedInvoices (db/033; InvNo from PMS's IDENTITY, never chosen here) whose InvNo the meter
   rows carry, and the sub-tenant (the reading's, else the office's name in mr.PropertySubTenant, db/030).
   A property finalized before keeps its rows with IsCurrent = 0 when finalized again, and its invoice is
   updated, not doubled, while still untouched (TTBaan 'No', Status 'Ready', nothing from Fusion, nothing
   received); once in use it is not finalized again (listed as "invoice in use in PMS").

   A property is finalized only when every one of its meters has a reading this month (sent, or "can't
   reach"); the others are listed with how many meters are left. When every property is finalized the month
   closes (FR-037.11), as on the phone; set @Close = 1 to close it anyway (as My summary -> Finalize month).

   An earlier month (after the next one opened): set @ForPeriod, e.g. '2026-09'; it is finalized the same way and
   is never closed or reopened by this script.

   Run in PropertyManagementSystem (the app's database). First with @Preview = 1 (the default): it shows what
   it would write and writes nothing. Then with @Preview = 0 to write.
*/
SET NOCOUNT ON;
SET XACT_ABORT ON;

DECLARE @Preview      bit         = 1;            -- 1: show only. 0: write.
DECLARE @Close        bit         = 0;            -- 1: close the month afterwards even if some properties are not finalized.
DECLARE @Refinalize   bit         = 0;            -- 1: finalize again properties already finalized this month.
DECLARE @OnlyProperty varchar(30) = NULL;         -- e.g. '597-646' for one property; NULL for all.
DECLARE @ForPeriod    char(7)     = NULL;         -- e.g. '2026-09' to finalize an earlier month after the next one opened;
                                                  -- NULL: the open month. Never closes an earlier month.
DECLARE @FinalizedBy  varchar(50) = LEFT(N'office:' + SUSER_SNAME(), 50);

/* 1. The month: the open one as the API takes it (the newest OPEN row; code yyyy-MM from StartDate), or @ForPeriod.
      An earlier month takes its dates from vw_MR_ReadingPeriod when it is still there, else the 1st to the last day. */
DECLARE @Period char(7), @From date, @End date;
IF @ForPeriod IS NULL
    SELECT TOP (1) @Period = CONVERT(char(7), CAST(StartDate AS date), 126), @From = CAST(StartDate AS date), @End = CAST(EndDate AS date)
    FROM mr.vw_MR_ReadingPeriod WHERE Status = 'OPEN' ORDER BY StartDate DESC;
ELSE
BEGIN
    IF @ForPeriod NOT LIKE '[12][0-9][0-9][0-9]-[01][0-9]'
    BEGIN
        RAISERROR(N'@ForPeriod must look like 2026-09.', 16, 1);
        RETURN;
    END
    SET @Period = @ForPeriod;
    SELECT TOP (1) @From = CAST(StartDate AS date), @End = CAST(EndDate AS date)
    FROM mr.vw_MR_ReadingPeriod WHERE CONVERT(char(7), CAST(StartDate AS date), 126) = @Period ORDER BY StartDate DESC;
    IF @From IS NULL
        SELECT @From = DATEFROMPARTS(LEFT(@Period, 4), RIGHT(@Period, 2), 1), @End = EOMONTH(DATEFROMPARTS(LEFT(@Period, 4), RIGHT(@Period, 2), 1));
    SET @Close = 0;
END
IF @Period IS NULL
BEGIN
    RAISERROR(N'There is no OPEN period in vw_MR_ReadingPeriod.', 16, 1);
    RETURN;
END
IF OBJECT_ID(N'dbo.MaintainFinalizedInvoices', N'U') IS NULL
   OR NOT EXISTS (SELECT 1 FROM sys.foreign_keys WHERE name = N'FK_MaintainFinalizedMeter_Invoice')
BEGIN
    RAISERROR(N'Run db/033_finalized_invoice_header.sql first (invoice header in dbo.MaintainFinalizedInvoices).', 16, 1);
    RETURN;
END
IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'SewAmount') IS NULL OR COL_LENGTH(N'mr.MaintainFinalizedMeter', N'DimensionCode') IS NULL
BEGIN
    RAISERROR(N'Run db/032_finalized_columns_like_pms.sql first (PMS column names, Area, Rate, DimensionCode).', 16, 1);
    RETURN;
END
IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'SubTenant') IS NULL
BEGIN
    RAISERROR(N'Run db/030_property_sub_tenant.sql first (mr.MaintainFinalizedMeter has no SubTenant column).', 16, 1);
    RETURN;
END
IF @ForPeriod IS NULL AND EXISTS (SELECT 1 FROM mr.ReadingPeriodStatus WHERE PeriodCode = @Period AND Status = 'CLOSED')
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

-- A property already finalized this month keeps its invoice; one already in use in PMS is not finalized again.
DROP TABLE IF EXISTS #old;
SELECT d.PropertyCode, f.InvNo,
       CASE WHEN i.InvNo IS NULL THEN 0                                   -- invoice gone: a new one is made
            WHEN i.TTBaan = 'No' AND i.Status = 'Ready' AND ISNULL(i.RecAmount, 0) = 0 AND i.FusionPosted IS NULL
                 AND ISNULL(i.FusionReferenceNumber, '') = '' AND ISNULL(i.ReceiptNumber, '') = '' THEN 1
            ELSE 2 END AS State                                           -- 1: update it; 2: in use
INTO #old
FROM #todo d
CROSS APPLY (SELECT TOP (1) x.InvNo FROM mr.MaintainFinalizedMeter x
             WHERE x.PeriodCode = @Period AND x.PropertyCode = d.PropertyCode AND x.IsCurrent = 1 AND x.InvNo IS NOT NULL
             ORDER BY x.RowId DESC) f
LEFT JOIN dbo.MaintainFinalizedInvoices i ON i.InvNo = f.InvNo;
DELETE FROM #todo WHERE PropertyCode IN (SELECT PropertyCode FROM #old WHERE State = 2);

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
            WHEN o.State = 2 THEN CONCAT('not finalized: invoice ', o.InvNo, ' in use in PMS')
            WHEN p.AlreadyFinalized = 1 THEN 'already finalized (set @Refinalize = 1 to redo)'
            ELSE 'not selected' END AS Result
FROM #p p LEFT JOIN #todo d ON d.PropertyCode = p.PropertyCode LEFT JOIN #old o ON o.PropertyCode = p.PropertyCode
WHERE p.ReadMeters > 0 OR d.PropertyCode IS NOT NULL
ORDER BY CASE WHEN d.PropertyCode IS NOT NULL THEN 0 ELSE 1 END, p.PropertyCode;

SELECT COUNT(DISTINCT PropertyCode) AS PropertiesToFinalize, COUNT(*) AS BillingRows, SUM(TotalAmount) AS TotalAmount FROM #rows;

IF @Preview = 1
BEGIN
    PRINT N'Preview only. Check the results, then set @Preview = 0 and run again to write.';
    RETURN;
END

/* 9. Write: earlier rows of these properties stay with IsCurrent = 0; the new set is current. */
-- Area, Rate and DimensionCode (BaanDimensionCode) from vw_MR_Property, the tenant's row first (db/032).
DROP TABLE IF EXISTS #pb;
SELECT d.PropertyCode, pb.Area, pb.Rate, pb.DimensionCode
INTO #pb
FROM #todo d
OUTER APPLY (SELECT TOP (1) TRY_CAST(p.Area AS decimal(18,2)) AS Area, TRY_CAST(p.Rate AS decimal(18,4)) AS Rate,
                    NULLIF(LTRIM(RTRIM(CAST(p.BaanDimensionCode AS varchar(30)))), '') AS DimensionCode
             FROM mr.vw_MR_Property p
             WHERE CAST(p.PropertyCode AS varchar(30)) = d.PropertyCode
             ORDER BY CASE WHEN LTRIM(RTRIM(CAST(p.TenantCode AS varchar(30)))) =
                           (SELECT TOP (1) r.TenantCode FROM #rows r WHERE r.PropertyCode = d.PropertyCode AND r.TenantCode IS NOT NULL)
                           THEN 0 ELSE 1 END) pb;

-- The invoice of each property: its totals and codes (as the API: Area 0.##, Rate 0.####).
DROP TABLE IF EXISTS #h;
SELECT r.PropertyCode, MAX(r.TranCode) AS TranCode, MAX(r.CompanyName) AS TenantsName, MAX(r.BaanCode) AS BaanCode,
       FORMAT(MAX(pb.Area), '0.##', 'en-US') AS Area, FORMAT(MAX(pb.Rate), '0.####', 'en-US') AS Rate, MAX(pb.DimensionCode) AS DimensionCode,
       SUM(r.SewerageAmount + r.IrrigationAmount) AS TaxableAmount, SUM(r.SewerageVat + r.IrrigationVat) AS VATAmount,
       SUM(r.SewerageAmount + r.IrrigationAmount + r.SewerageVat + r.IrrigationVat) AS InvAmount
INTO #h
FROM #rows r
LEFT JOIN #pb pb ON pb.PropertyCode = r.PropertyCode
GROUP BY r.PropertyCode;

BEGIN TRANSACTION;
DECLARE @At datetime2(0) = SYSUTCDATETIME();
DECLARE @inv TABLE (InvNo bigint, PropertyCode varchar(200));

-- Invoices to update: still untouched now (checked again under lock), else stop and change nothing.
IF EXISTS (SELECT 1 FROM #old o JOIN dbo.MaintainFinalizedInvoices i WITH (UPDLOCK, HOLDLOCK) ON i.InvNo = o.InvNo
           WHERE o.State = 1 AND NOT (i.TTBaan = 'No' AND i.Status = 'Ready' AND ISNULL(i.RecAmount, 0) = 0 AND i.FusionPosted IS NULL
                 AND ISNULL(i.FusionReferenceNumber, '') = '' AND ISNULL(i.ReceiptNumber, '') = ''))
BEGIN
    ROLLBACK TRANSACTION;
    RAISERROR(N'An invoice was taken up in PMS while this ran. Nothing written: run again.', 16, 1);
    RETURN;
END
UPDATE i SET TranCode = h.TranCode, TenantsName = h.TenantsName, Area = h.Area, Rate = h.Rate, FromDate = @From, ToDate = @End,
             InvAmount = h.InvAmount, BaanCode = h.BaanCode, DimensionCode = h.DimensionCode, VATCode = 'SSD',
             VATAmount = h.VATAmount, TaxableAmount = h.TaxableAmount, UserName = @FinalizedBy
OUTPUT inserted.InvNo, h.PropertyCode INTO @inv
FROM dbo.MaintainFinalizedInvoices i
JOIN #old o ON o.InvNo = i.InvNo AND o.State = 1
JOIN #h h ON h.PropertyCode = o.PropertyCode;
PRINT CONCAT(@@ROWCOUNT, N' invoices updated.');

-- New invoices: InvNo from PMS's IDENTITY.
INSERT dbo.MaintainFinalizedInvoices (TranCode, TenantsName, PropertyCode, Area, Rate, FromDate, ToDate, InvAmount, RecAmount,
    BaanCode, DimensionCode, TTBaan, Status, Details, VATCode, VATAmount, TaxableAmount, UserName)
OUTPUT inserted.InvNo, inserted.PropertyCode INTO @inv
SELECT h.TranCode, h.TenantsName, h.PropertyCode, h.Area, h.Rate, @From, @End, h.InvAmount, 0,
       h.BaanCode, h.DimensionCode, 'No', 'Ready', 'sew', 'SSD', h.VATAmount, h.TaxableAmount, @FinalizedBy
FROM #h h
WHERE h.PropertyCode NOT IN (SELECT PropertyCode FROM @inv);
PRINT CONCAT(@@ROWCOUNT, N' invoices written.');

UPDATE f SET IsCurrent = 0
FROM mr.MaintainFinalizedMeter f WITH (UPDLOCK, HOLDLOCK)
WHERE f.PeriodCode = @Period AND f.IsCurrent = 1 AND f.PropertyCode IN (SELECT PropertyCode FROM #todo);

INSERT mr.MaintainFinalizedMeter (PeriodCode, PropertyId, PropertyCode,
    MeterNumberSewerage, PrevReadingSewerage, CurrentReadingSewerage, ConsumptionSewerage,
    MeterNumberIrrigation, PrevReadingIrrigation, CurrentReadingIrrigation, ConsumptionIrrigation,
    SewerageTransactionId, IrrigationTransactionId, FinalizedByReaderId, DeviceId, CreatedDate, IsCurrent,
    SewAmount, IrrAmount, FloatValueSewerage, RateFactorSewerage, FloatValueIrrigation, RateFactorIrrigation,
    SewerageVat, IrrigationVat, TotalAmount, IrrigationNoCharge, TenPercentAmount, IrrigationAmountCalculated,
    TranCode, BaanCode, TenantCode, FromDate, ToDate, TenantsName, VATCode, ChargeType1, ChargeType2, SubTenant,
    Area, Rate, DimensionCode, InvNo)
SELECT @Period, (SELECT TOP (1) v.PropertyId FROM #mv v WHERE v.PropertyCode = r.PropertyCode AND v.PropertyId IS NOT NULL), r.PropertyCode,
       ISNULL(r.SNumber, '0'), ISNULL(r.SPrev, 0), ISNULL(r.SCur, 0), ISNULL(r.SCons, 0),
       ISNULL(r.INumber, '0'), ISNULL(r.IPrev, 0), ISNULL(r.ICur, 0), ISNULL(r.ICons, 0),
       r.STx, r.ITx, @FinalizedBy, NULL, @At, 1,
       r.SewerageAmount, r.IrrigationAmount, r.SFloat, r.SRate, r.IFloat, r.IRate,
       r.SewerageVat, r.IrrigationVat, r.TotalAmount, ISNULL(r.INoCharge, 0), r.TenPercentAmount, r.IrrigationAmountCalculated,
       r.TranCode, r.BaanCode, r.TenantCode, @From, @End, r.CompanyName, 'SSD', 'sew', 'Irri', r.SubTenant,
       pb.Area, pb.Rate, pb.DimensionCode, inv.InvNo
FROM #rows r
LEFT JOIN #pb pb ON pb.PropertyCode = r.PropertyCode
JOIN @inv inv ON inv.PropertyCode = r.PropertyCode;
PRINT CONCAT(@@ROWCOUNT, N' billing rows written.');

-- SIMNo = InvNo; SourceRowId = 900000000000 + the invoice's first meter row (kept once set), as the API.
UPDATE i SET SIMNo = CAST(i.InvNo AS varchar(20)),
             SourceRowId = ISNULL(i.SourceRowId, 900000000000 + (SELECT MIN(f.RowId) FROM mr.MaintainFinalizedMeter f WHERE f.InvNo = i.InvNo))
FROM dbo.MaintainFinalizedInvoices i
WHERE i.InvNo IN (SELECT InvNo FROM @inv);

/* 10. Close the month: when every property is finalized (FR-037.11), or when asked (@Close = 1). */
DECLARE @All int = (SELECT COUNT(*) FROM #p);
DECLARE @Done int = (SELECT COUNT(DISTINCT f.PropertyCode) FROM mr.MaintainFinalizedMeter f
                     WHERE f.PeriodCode = @Period AND f.IsCurrent = 1 AND f.PropertyCode IN (SELECT PropertyCode FROM #p));
IF @ForPeriod IS NULL AND (@Close = 1 OR (@All > 0 AND @Done >= @All))
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
ELSE IF @ForPeriod IS NULL
    PRINT CONCAT(N'Period ', @Period, N' stays open: ', @Done, N' of ', @All, N' properties finalized. Set @Close = 1 to close it anyway.');
ELSE
    PRINT CONCAT(N'Earlier month ', @Period, N': ', @Done, N' of ', @All, N' properties finalized.');
COMMIT TRANSACTION;

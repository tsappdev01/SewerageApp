/*
  dev/900_e2e_checks.sql — DEVELOPMENT AND TEST ONLY.
  Checks in the database what the end-to-end test (android/app/src/androidInstrumentedTest/.../e2e,
  run by .github/workflows/e2e.yml) sent from the phone: the registration, two readings with their
  photos, the copy into the PMS stand-in, and one field inspection visit with its photo.
  Run in the test database after the test. Read-only. Lists every check with PASS or FAIL and then
  raises an error if any failed, so `sqlcmd -b` stops the workflow.
*/
SET NOCOUNT ON;

DECLARE @label nvarchar(100) = N'E2E emulator';
DECLARE @device uniqueidentifier = (SELECT TOP (1) DeviceId FROM mr.Device WHERE Label = @label ORDER BY RegisteredAtUtc DESC);
DECLARE @checks TABLE (Seq int IDENTITY, [Check] nvarchar(200), Result varchar(4), Detail nvarchar(400));

-- FR-002: the phone registered with the one-time code.
INSERT @checks SELECT N'Phone registered with the code (FR-002)',
    IIF(@device IS NOT NULL AND EXISTS (SELECT 1 FROM mr.DeviceRegistrationCode WHERE DeviceId = @device AND UsedAtUtc IS NOT NULL), 'PASS', 'FAIL'),
    (SELECT CONCAT(N'model ', Model, N', ', AndroidVersion, N', app ', AppVersion, N', status ', Status) FROM mr.Device WHERE DeviceId = @device);
INSERT @checks SELECT N'Only the key''s hash is stored (SEC)',
    IIF(EXISTS (SELECT 1 FROM mr.Device WHERE DeviceId = @device AND LEN(KeyHash) = 64), 'PASS', 'FAIL'), NULL;

-- FR-006: meter BC0003 read with a photo and the tenant checked on site.
DECLARE @r1 uniqueidentifier = (SELECT TOP (1) TransactionId FROM mr.ReadingTransaction WHERE MeterId = 'BC0003' AND DeviceId = @device ORDER BY ReceivedAtUtc DESC);
INSERT @checks SELECT N'BC0003 reading stored from this phone, by Rashid (E1001)',
    IIF(EXISTS (SELECT 1 FROM mr.ReadingTransaction WHERE TransactionId = @r1 AND ReaderId = 'E1001' AND MeterCondition = 'WORKING' AND NewReading = 31050), 'PASS', 'FAIL'),
    (SELECT CONCAT(N'reading ', NewReading, N', previous ', PreviousReading, N', used ', Consumption, N', status ', Status) FROM mr.ReadingTransaction WHERE TransactionId = @r1);
INSERT @checks SELECT N'BC0003 tenant is the one tapped on site, T-0102 (FR-006.12)',
    IIF(EXISTS (SELECT 1 FROM mr.ReadingTransaction WHERE TransactionId = @r1 AND TenantCode = 'T-0102'), 'PASS', 'FAIL'), NULL;
INSERT @checks SELECT N'BC0003 photos all arrived, bytes match their SHA-256',
    IIF((SELECT COUNT(*) FROM mr.ReadingImage i JOIN mr.ReadingImageData d ON d.BlobPath = i.BlobPath
         WHERE i.TransactionId = @r1 AND CONVERT(char(64), HASHBYTES('SHA2_256', d.ImageBytes), 2) = i.Sha256) >= 1
        AND (SELECT COUNT(*) FROM mr.ReadingImage WHERE TransactionId = @r1) = (SELECT ISNULL(ExpectedPhotos, 0) FROM mr.ReadingTransaction WHERE TransactionId = @r1), 'PASS', 'FAIL'),
    (SELECT CONCAT(COUNT(*), N' photo(s), ', SUM(SizeBytes) / 1024, N' KB') FROM mr.ReadingImage WHERE TransactionId = @r1);

-- FR-020: meter BC0004 read without signal, sent later.
DECLARE @r2 uniqueidentifier = (SELECT TOP (1) TransactionId FROM mr.ReadingTransaction WHERE MeterId = 'BC0004' AND DeviceId = @device ORDER BY ReceivedAtUtc DESC);
INSERT @checks SELECT N'BC0004 reading saved without signal arrived later (FR-020)',
    IIF(EXISTS (SELECT 1 FROM mr.ReadingTransaction WHERE TransactionId = @r2 AND NewReading = 99990 AND DATEDIFF(SECOND, CapturedAtUtc, ReceivedAtUtc) >= 5), 'PASS', 'FAIL'),
    (SELECT CONCAT(N'captured ', FORMAT(CapturedAtUtc, 'HH:mm:ss'), N', received ', FORMAT(ReceivedAtUtc, 'HH:mm:ss'), N' UTC, status ', Status) FROM mr.ReadingTransaction WHERE TransactionId = @r2);
INSERT @checks SELECT N'BC0004 photos all arrived',
    IIF((SELECT COUNT(*) FROM mr.ReadingImage WHERE TransactionId = @r2) >= 1
        AND (SELECT COUNT(*) FROM mr.ReadingImage WHERE TransactionId = @r2) = (SELECT ISNULL(ExpectedPhotos, 0) FROM mr.ReadingTransaction WHERE TransactionId = @r2), 'PASS', 'FAIL'),
    (SELECT CONCAT(COUNT(*), N' photo(s)') FROM mr.ReadingImage WHERE TransactionId = @r2);
INSERT @checks SELECT N'Each reading stored once (no duplicates from retries)',
    IIF((SELECT COUNT(*) FROM mr.ReadingTransaction WHERE DeviceId = @device AND MeterId IN ('BC0003', 'BC0004')) = 2, 'PASS', 'FAIL'),
    (SELECT CONCAT(COUNT(*), N' rows') FROM mr.ReadingTransaction WHERE DeviceId = @device);

-- The copy into PMS (PmsTransferService, PmsTransfer:Enabled in the test).
INSERT @checks SELECT N'Accepted readings copied into MaintainMeterReading once',
    IIF(NOT EXISTS (SELECT 1 FROM mr.ReadingTransaction t WHERE t.TransactionId IN (@r1, @r2) AND t.Status = 'ACCEPTED'
                    AND NOT EXISTS (SELECT 1 FROM dbo.MaintainMeterReading m WHERE m.RowId = t.PmsRowId))
        AND (SELECT COUNT(*) FROM dbo.MaintainMeterReading m JOIN mr.ReadingTransaction t ON t.PmsRowId = m.RowId WHERE t.TransactionId IN (@r1, @r2))
          = (SELECT COUNT(*) FROM mr.ReadingTransaction WHERE TransactionId IN (@r1, @r2) AND Status = 'ACCEPTED'), 'PASS', 'FAIL'),
    (SELECT STRING_AGG(CONCAT(m.MeterNumber, N' ', m.Previous_Reading, N'→', m.Current_Reading, N' tenant ', m.TenantCode, N' by ', m.MeterReader), N'; ')
     FROM dbo.MaintainMeterReading m JOIN mr.ReadingTransaction t ON t.PmsRowId = m.RowId WHERE t.TransactionId IN (@r1, @r2));

-- FR-031..036: one inspection visit at 597-559 with two units.
DECLARE @v uniqueidentifier = (SELECT TOP (1) VisitId FROM mr.InspectionVisit WHERE PropertyCode = '597-559' AND DeviceId = @device ORDER BY ReceivedAtUtc DESC);
INSERT @checks SELECT N'Inspection visit at 597-559 stored, by E1001 (FR-031)',
    IIF(EXISTS (SELECT 1 FROM mr.InspectionVisit WHERE VisitId = @v AND InspectorId = 'E1001' AND TenantCode = 'T-0559'), 'PASS', 'FAIL'),
    (SELECT CONCAT(N'at property ', AtProperty, N', location ', Latitude, N',', Longitude, N', ', DistanceFromOfficeKm, N' km from office') FROM mr.InspectionVisit WHERE VisitId = @v);
INSERT @checks SELECT N'Unit 1 as recorded, unit 2 vacant (FR-032/033)',
    IIF(EXISTS (SELECT 1 FROM mr.InspectionUnitResult WHERE VisitId = @v AND UnitCode = '1' AND Result = 'AS_RECORDED')
        AND EXISTS (SELECT 1 FROM mr.InspectionUnitResult WHERE VisitId = @v AND UnitCode = '2' AND Result = 'VACANT')
        AND (SELECT COUNT(*) FROM mr.InspectionUnitResult WHERE VisitId = @v) = 2, 'PASS', 'FAIL'),
    (SELECT STRING_AGG(CONCAT(UnitCode, N'=', Result, IIF(Reasons IS NULL, N'', CONCAT(N' (', Reasons, N')'))), N', ') FROM mr.InspectionUnitResult WHERE VisitId = @v);
INSERT @checks SELECT N'Vacant unit''s photo arrived, bytes match (FR-033)',
    IIF((SELECT COUNT(*) FROM mr.InspectionImage i JOIN mr.InspectionUnitResult r ON r.ResultId = i.ResultId
         JOIN mr.ReadingImageData d ON d.BlobPath = i.BlobPath
         WHERE i.VisitId = @v AND r.Result = 'VACANT' AND CONVERT(char(64), HASHBYTES('SHA2_256', d.ImageBytes), 2) = i.Sha256) >= 1
        AND (SELECT COUNT(*) FROM mr.InspectionImage WHERE VisitId = @v) = (SELECT ExpectedPhotos FROM mr.InspectionVisit WHERE VisitId = @v), 'PASS', 'FAIL'),
    (SELECT CONCAT(COUNT(*), N' photo(s)') FROM mr.InspectionImage WHERE VisitId = @v);
INSERT @checks SELECT N'PMS is never written by inspections',
    IIF(NOT EXISTS (SELECT 1 FROM dbo.MaintainMeterReading m WHERE m.PropertyCode = '597-559'), 'PASS', 'FAIL'), NULL;

SELECT Seq, Result, [Check], Detail FROM @checks ORDER BY Seq;

IF EXISTS (SELECT 1 FROM @checks WHERE Result = 'FAIL')
    RAISERROR(N'End-to-end database checks failed (see the list above).', 16, 1);

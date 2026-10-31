/* ops/delete_all_readings.sql — deletes the meter readings, their photos and the finalized billing rows,
   e.g. to start clean after testing. Run in PropertyManagementSystem (the app's database).

   Deleted:  mr.ReadingTransaction, mr.ReadingImage, mr.ReadingImageData (the reading photos),
             mr.MaintainFinalizedMeter, the invoices it made in PMS's dbo.MaintainFinalizedInvoices (only the ones
             its meter rows point to, db/033), and the app's month closings in mr.ReadingPeriodStatus.
             If any of those invoices is already in use in PMS (TTBaan not 'No', Status not 'Ready', sent to
             Fusion or money received), nothing is deleted: the office deals with those invoices first.
   Kept:     phones (mr.Device), sub-tenant names, opening readings from PMS, no-charge plots, field
             inspections, and everything in PMS's own tables.

   Safe to run as it is: with @Confirm = 0 it only shows what would be deleted. With @Confirm = 1 it first
   copies every row into backup tables (mr.<table>_bak_yyyymmdd_hhmm) and then deletes, all in one
   transaction. To undo: copy the rows back from those tables. Drop the backup tables when no longer needed.

   Afterwards phones still show their old readings until they download again (Home -> download), and a
   reading still waiting on a phone is sent when it gets signal: let phones send everything first.
*/
SET NOCOUNT ON;
SET XACT_ABORT ON;

DECLARE @Confirm  bit = 0;            -- 0: show counts only. 1: back up and delete.
DECLARE @Period   char(7) = NULL;     -- e.g. '2026-09' to delete one month only; NULL for all months.

IF DB_NAME() <> N'PropertyManagementSystem'
BEGIN
    RAISERROR(N'Run this in PropertyManagementSystem.', 16, 1);
    RETURN;
END

SELECT 'mr.ReadingTransaction' AS [Table], COUNT(*) AS RowsToDelete FROM mr.ReadingTransaction WHERE @Period IS NULL OR PeriodCode = @Period
UNION ALL SELECT 'mr.ReadingImage', COUNT(*) FROM mr.ReadingImage i
    WHERE EXISTS (SELECT 1 FROM mr.ReadingTransaction t WHERE t.TransactionId = i.TransactionId AND (@Period IS NULL OR t.PeriodCode = @Period))
UNION ALL SELECT 'mr.ReadingImageData', COUNT(*) FROM mr.ReadingImageData d
    WHERE EXISTS (SELECT 1 FROM mr.ReadingImage i JOIN mr.ReadingTransaction t ON t.TransactionId = i.TransactionId
                  WHERE i.BlobPath = d.BlobPath AND (@Period IS NULL OR t.PeriodCode = @Period))
UNION ALL SELECT 'mr.MaintainFinalizedMeter', COUNT(*) FROM mr.MaintainFinalizedMeter WHERE @Period IS NULL OR PeriodCode = @Period
UNION ALL SELECT 'dbo.MaintainFinalizedInvoices (app''s)', COUNT(*) FROM dbo.MaintainFinalizedInvoices i
    WHERE i.InvNo IN (SELECT f.InvNo FROM mr.MaintainFinalizedMeter f WHERE @Period IS NULL OR f.PeriodCode = @Period)
UNION ALL SELECT 'mr.ReadingPeriodStatus', COUNT(*) FROM mr.ReadingPeriodStatus WHERE @Period IS NULL OR PeriodCode = @Period;

-- Invoices already in use in PMS: listed, and nothing is deleted while there are any.
SELECT i.InvNo, i.PropertyCode, i.TTBaan, i.Status, i.RecAmount, i.FusionPosted, i.FusionReferenceNumber, i.ReceiptNumber
INTO #inUse
FROM dbo.MaintainFinalizedInvoices i
WHERE i.InvNo IN (SELECT f.InvNo FROM mr.MaintainFinalizedMeter f WHERE @Period IS NULL OR f.PeriodCode = @Period)
  AND NOT (i.TTBaan = 'No' AND i.Status = 'Ready' AND ISNULL(i.RecAmount, 0) = 0 AND i.FusionPosted IS NULL
           AND ISNULL(i.FusionReferenceNumber, '') = '' AND ISNULL(i.ReceiptNumber, '') = '');
IF EXISTS (SELECT 1 FROM #inUse)
BEGIN
    SELECT * FROM #inUse ORDER BY InvNo;
    RAISERROR(N'These invoices are already in use in PMS: nothing deleted.', 16, 1);
    RETURN;
END

IF EXISTS (SELECT 1 FROM mr.ReadingTransaction WHERE PmsCopiedAtUtc IS NOT NULL AND (@Period IS NULL OR PeriodCode = @Period))
    RAISERROR(N'Note: some of these readings were already copied into PMS (MaintainMeterReading). Deleting them here does not remove them from PMS.', 10, 1) WITH NOWAIT;

IF @Confirm = 0
BEGIN
    PRINT N'Nothing deleted. Check the counts, then set @Confirm = 1 and run again.';
    RETURN;
END

DECLARE @suffix nvarchar(20) = N'_bak_' + FORMAT(SYSDATETIME(), 'yyyyMMdd_HHmm');
DECLARE @sql nvarchar(max) = N'
SELECT d.* INTO mr.ReadingImageData' + @suffix + N' FROM mr.ReadingImageData d
 WHERE EXISTS (SELECT 1 FROM mr.ReadingImage i JOIN mr.ReadingTransaction t ON t.TransactionId = i.TransactionId
               WHERE i.BlobPath = d.BlobPath AND (@Period IS NULL OR t.PeriodCode = @Period));
SELECT i.* INTO mr.ReadingImage' + @suffix + N' FROM mr.ReadingImage i
 WHERE EXISTS (SELECT 1 FROM mr.ReadingTransaction t WHERE t.TransactionId = i.TransactionId AND (@Period IS NULL OR t.PeriodCode = @Period));
SELECT * INTO mr.ReadingTransaction' + @suffix + N' FROM mr.ReadingTransaction WHERE @Period IS NULL OR PeriodCode = @Period;
SELECT * INTO mr.MaintainFinalizedMeter' + @suffix + N' FROM mr.MaintainFinalizedMeter WHERE @Period IS NULL OR PeriodCode = @Period;
SELECT * INTO mr.ReadingPeriodStatus' + @suffix + N' FROM mr.ReadingPeriodStatus WHERE @Period IS NULL OR PeriodCode = @Period;
SELECT * INTO mr.MaintainFinalizedInvoices' + @suffix + N' FROM dbo.MaintainFinalizedInvoices
 WHERE InvNo IN (SELECT f.InvNo FROM mr.MaintainFinalizedMeter f WHERE @Period IS NULL OR f.PeriodCode = @Period);';

BEGIN TRANSACTION;
EXEC sp_executesql @sql, N'@Period char(7)', @Period = @Period;

DELETE d FROM mr.ReadingImageData d
WHERE EXISTS (SELECT 1 FROM mr.ReadingImage i JOIN mr.ReadingTransaction t ON t.TransactionId = i.TransactionId
              WHERE i.BlobPath = d.BlobPath AND (@Period IS NULL OR t.PeriodCode = @Period));
DELETE i FROM mr.ReadingImage i
WHERE EXISTS (SELECT 1 FROM mr.ReadingTransaction t WHERE t.TransactionId = i.TransactionId AND (@Period IS NULL OR t.PeriodCode = @Period));
DELETE FROM mr.ReadingTransaction WHERE @Period IS NULL OR PeriodCode = @Period;
DECLARE @invoices TABLE (InvNo bigint PRIMARY KEY);
INSERT @invoices SELECT DISTINCT f.InvNo FROM mr.MaintainFinalizedMeter f WHERE f.InvNo IS NOT NULL AND (@Period IS NULL OR f.PeriodCode = @Period);
DELETE FROM mr.MaintainFinalizedMeter WHERE @Period IS NULL OR PeriodCode = @Period;
-- Only the app's invoices, and only once no meter row of another month still points to them.
DELETE i FROM dbo.MaintainFinalizedInvoices i
WHERE i.InvNo IN (SELECT InvNo FROM @invoices)
  AND NOT EXISTS (SELECT 1 FROM mr.MaintainFinalizedMeter f WHERE f.InvNo = i.InvNo);
DELETE FROM mr.ReadingPeriodStatus WHERE @Period IS NULL OR PeriodCode = @Period;
COMMIT TRANSACTION;

PRINT N'Deleted. Backups: mr.ReadingTransaction' + @suffix + N', mr.ReadingImage' + @suffix + N', mr.ReadingImageData' + @suffix
    + N', mr.MaintainFinalizedMeter' + @suffix + N', mr.MaintainFinalizedInvoices' + @suffix + N', mr.ReadingPeriodStatus' + @suffix + N'.';

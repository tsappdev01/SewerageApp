/* ops/send_invoice_report.sql — sends the Invoice Generation Report (db/034, FR-037.18) for a month now, e.g. to
   test the recipients or to send it again. It goes out by itself when the month is finalized. Run in
   PropertyManagementSystem. Recipients: db/ops/set_invoice_report_recipients.sql. Writes a row in
   mr.InvoiceReportLog; db/ops/invoice_report_status.sql shows whether it was delivered. */
DECLARE @Period char(7) = NULL;   -- e.g. '2026-10'; NULL: the newest month with finalized rows

IF @Period IS NULL
    SELECT TOP (1) @Period = PeriodCode FROM mr.MaintainFinalizedMeter WHERE IsCurrent = 1 ORDER BY PeriodCode DESC;
IF @Period IS NULL
BEGIN
    RAISERROR(N'No finalized month yet.', 16, 1);
    RETURN;
END
DECLARE @by nvarchar(128) = LEFT(N'office:' + SUSER_SNAME(), 128);
EXEC mr.usp_SendInvoiceReport @PeriodCode = @Period, @RequestedBy = @by;

/* ops/invoice_report_status.sql — whether the Invoice Generation Reports went out (db/034, FR-037.18).
   Database Mail sends in the background: this copies msdb's result (sent / failed / retrying / unsent, and the
   time sent) into mr.InvoiceReportLog for audit, then lists the latest reports with any Database Mail error.
   Run in PropertyManagementSystem; re-run as often as you like. */
SET NOCOUNT ON;
UPDATE l SET DispatchStatus = UPPER(a.sent_status), SentAt = a.sent_date, StatusCheckedAtUtc = SYSUTCDATETIME()
FROM mr.InvoiceReportLog l
JOIN msdb.dbo.sysmail_allitems a ON a.mailitem_id = l.MailItemId
WHERE l.MailItemId IS NOT NULL;

SELECT TOP (20) l.LogId, l.PeriodCode, l.RequestedAtUtc, l.RequestedBy, l.DispatchStatus, l.SentAt, l.Recipients,
       l.TotalInvoices, l.Successful, l.Failed, l.Pending, l.InvoiceAmount, l.ValidationOk, l.ValidationNote,
       COALESCE(l.ErrorMessage, e.description) AS Error
FROM mr.InvoiceReportLog l
OUTER APPLY (SELECT TOP (1) ev.description FROM msdb.dbo.sysmail_event_log ev
             WHERE ev.mailitem_id = l.MailItemId AND ev.event_type = 'error' ORDER BY ev.log_date DESC) e
ORDER BY l.LogId DESC;

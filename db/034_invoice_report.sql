/*
  034_invoice_report.sql
  DIP 2026-10-31 (FR-037.18): the Invoice Generation Report, an HTML email sent with SQL Server Database Mail
  (msdb.dbo.sp_send_dbmail) once the month's Sewerage and Irrigation invoices are generated.

  Made here:
    mr.InvoiceReportSetting   one row: who gets it (Recipients, CopyRecipients; addresses separated by ';'),
                              the Database Mail profile, and IsEnabled. Starts DISABLED with no recipients:
                              set them with db/ops/set_invoice_report_recipients.sql.
    mr.InvoiceReportLog       one row per report: the counts and amounts, the validation, recipients, the
                              Database Mail item, the dispatch status and times (audit).
    mr.usp_SendInvoiceReport  builds and sends the report for a month:
        1. invoices of the month: the current meter rows (mr.MaintainFinalizedMeter) and their invoice in
           PMS's dbo.MaintainFinalizedInvoices;
        2. validates each invoice: it exists, its InvAmount = the sum of its meter rows' TotalAmount,
           TaxableAmount + VATAmount = InvAmount, SIMNo = InvNo, SourceRowId set; and the month: one invoice
           per finalized property, invoice total = meter-row total;
        3. failed: an invoice that fails a check, or meter rows with no invoice;
           pending: a property read this month (readings not superseded) and not finalized;
        4. the HTML: summary, invoice status, then every invoice (No., tenant / property, amount, status);
        5. sends it (sp_send_dbmail) when enabled, else only logs it (DISABLED);
        6. logs the dispatch: QUEUED with the mail item id, FAILED with the error, or DISABLED.
       Database Mail sends in the background; db/ops/invoice_report_status.sql reads msdb's result
       (sent / failed / retrying) into the log with its time.
  It is called automatically when the month is finalized: "Finalize Meter Reading for the Month" on the phone,
  the last "Finalize property" of the month, and db/ops/finalize_month.sql when it closes the month. A
  failure to send never undoes the finalizing; it is logged. Send again: db/ops/send_invoice_report.sql.

  Needs Database Mail set up on the server with a profile. The API's user mr_api is added to msdb's
  DatabaseMailUserRole below (when this server has Database Mail).
  Run in PropertyManagementSystem after 033, as a sysadmin (for the msdb step). Re-runnable.
*/
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;   -- the procedure uses XML methods; it keeps the setting it is created with
GO
IF OBJECT_ID(N'mr.InvoiceReportSetting', N'U') IS NULL
CREATE TABLE mr.InvoiceReportSetting (
    SettingId        int            NOT NULL CONSTRAINT PK_InvoiceReportSetting PRIMARY KEY CONSTRAINT CK_InvoiceReportSetting_One CHECK (SettingId = 1),
    IsEnabled        bit            NOT NULL CONSTRAINT DF_InvoiceReportSetting_IsEnabled DEFAULT 0,
    ProfileName      sysname        NULL,          -- Database Mail profile; NULL: the default profile
    Recipients       nvarchar(2000) NULL,          -- 'a@dipark.ae;b@dipark.ae'
    CopyRecipients   nvarchar(2000) NULL,
    UpdatedAtUtc     datetime2(0)   NOT NULL CONSTRAINT DF_InvoiceReportSetting_UpdatedAtUtc DEFAULT SYSUTCDATETIME(),
    UpdatedBy        nvarchar(128)  NOT NULL CONSTRAINT DF_InvoiceReportSetting_UpdatedBy DEFAULT SUSER_SNAME()
);
GO
IF NOT EXISTS (SELECT 1 FROM mr.InvoiceReportSetting)
    INSERT mr.InvoiceReportSetting (SettingId, IsEnabled) VALUES (1, 0);
GO
IF OBJECT_ID(N'mr.InvoiceReportLog', N'U') IS NULL
CREATE TABLE mr.InvoiceReportLog (
    LogId            bigint IDENTITY(1, 1) NOT NULL CONSTRAINT PK_InvoiceReportLog PRIMARY KEY,
    PeriodCode       char(7)        NOT NULL,
    FromDate         date           NULL,
    ToDate           date           NULL,
    TotalInvoices    int            NOT NULL,
    Successful       int            NOT NULL,
    Failed           int            NOT NULL,
    Pending          int            NOT NULL,
    TaxableAmount    decimal(18,2)  NOT NULL,
    VatAmount        decimal(18,2)  NOT NULL,
    InvoiceAmount    decimal(18,2)  NOT NULL,
    MeterRowsAmount  decimal(18,2)  NOT NULL,      -- sum of TotalAmount of the meter rows: must equal InvoiceAmount
    ValidationOk     bit            NOT NULL,
    ValidationNote   nvarchar(1000) NULL,
    Subject          nvarchar(255)  NOT NULL,
    Recipients       nvarchar(2000) NULL,
    CopyRecipients   nvarchar(2000) NULL,
    ProfileName      sysname        NULL,
    MailItemId       int            NULL,          -- msdb.dbo.sysmail_allitems.mailitem_id
    DispatchStatus   varchar(20)    NOT NULL,      -- QUEUED, FAILED, DISABLED; then SENT / RETRYING / UNSENT from msdb
    ErrorMessage     nvarchar(2000) NULL,
    RequestedBy      nvarchar(128)  NOT NULL,
    RequestedAtUtc   datetime2(0)   NOT NULL CONSTRAINT DF_InvoiceReportLog_RequestedAtUtc DEFAULT SYSUTCDATETIME(),
    SentAt           datetime       NULL,          -- msdb's sent_date (server time), from invoice_report_status.sql
    StatusCheckedAtUtc datetime2(0) NULL
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_InvoiceReportLog_Period' AND object_id = OBJECT_ID(N'mr.InvoiceReportLog'))
CREATE INDEX IX_InvoiceReportLog_Period ON mr.InvoiceReportLog (PeriodCode, RequestedAtUtc);
GO

CREATE OR ALTER PROCEDURE mr.usp_SendInvoiceReport
    @PeriodCode  char(7),
    @RequestedBy nvarchar(128) = NULL,
    @LogId       bigint = NULL OUTPUT
AS
BEGIN
    SET NOCOUNT ON;
    SET XACT_ABORT OFF;
    SET @RequestedBy = LEFT(ISNULL(@RequestedBy, SUSER_SNAME()), 128);

    /* 1-3. Invoices of the month, each checked; meter rows with no invoice; properties read and not finalized. */
    DECLARE @inv TABLE (
        InvNo bigint NULL, PropertyCode varchar(200) NOT NULL, TenantsName nvarchar(200) NULL,
        InvAmount decimal(18,2) NULL, TaxableAmount decimal(18,2) NULL, VatAmount decimal(18,2) NULL,
        RowsAmount decimal(18,2) NULL, Status varchar(10) NOT NULL, Reason nvarchar(200) NULL);

    INSERT @inv (InvNo, PropertyCode, TenantsName, InvAmount, TaxableAmount, VatAmount, RowsAmount, Status, Reason)
    SELECT d.InvNo, d.PropertyCode, COALESCE(i.TenantsName, d.TenantsName), i.InvAmount, i.TaxableAmount, i.VATAmount, d.RowsAmount,
           CASE WHEN r.Reason IS NULL THEN 'Success' ELSE 'Failed' END, r.Reason
    FROM (SELECT f.InvNo, MAX(f.PropertyCode) AS PropertyCode, MAX(f.TenantsName) AS TenantsName, SUM(f.TotalAmount) AS RowsAmount
          FROM mr.MaintainFinalizedMeter f
          WHERE f.PeriodCode = @PeriodCode AND f.IsCurrent = 1 AND f.InvNo IS NOT NULL
          GROUP BY f.InvNo) d
    LEFT JOIN dbo.MaintainFinalizedInvoices i ON i.InvNo = d.InvNo
    CROSS APPLY (SELECT CASE
        WHEN i.InvNo IS NULL THEN N'Invoice not in PMS'
        WHEN ABS(ISNULL(i.InvAmount, 0) - d.RowsAmount) >= 0.01 THEN N'Invoice amount differs from its meter rows'
        WHEN ABS(ISNULL(i.TaxableAmount, 0) + ISNULL(i.VATAmount, 0) - ISNULL(i.InvAmount, 0)) >= 0.01 THEN N'Taxable + VAT differs from the invoice amount'
        WHEN ISNULL(i.SIMNo, '') <> CAST(i.InvNo AS varchar(20)) THEN N'SIMNo not set'
        WHEN i.SourceRowId IS NULL THEN N'SourceRowId not set'
        END AS Reason) r;

    INSERT @inv (InvNo, PropertyCode, TenantsName, RowsAmount, Status, Reason)
    SELECT NULL, f.PropertyCode, MAX(f.TenantsName), SUM(f.TotalAmount), 'Failed', N'Finalized without an invoice'
    FROM mr.MaintainFinalizedMeter f
    WHERE f.PeriodCode = @PeriodCode AND f.IsCurrent = 1 AND f.InvNo IS NULL
    GROUP BY f.PropertyCode;

    INSERT @inv (InvNo, PropertyCode, Status, Reason)
    SELECT NULL, x.PropertyCode, 'Pending', N'Read, not finalized'
    FROM (SELECT DISTINCT LTRIM(RTRIM(t.PropertyCode)) AS PropertyCode
          FROM mr.ReadingTransaction t
          WHERE t.PeriodCode = @PeriodCode AND t.Status <> 'SUPERSEDED' AND NULLIF(LTRIM(RTRIM(t.PropertyCode)), '') IS NOT NULL) x
    WHERE NOT EXISTS (SELECT 1 FROM mr.MaintainFinalizedMeter f
                      WHERE f.PeriodCode = @PeriodCode AND f.IsCurrent = 1 AND f.PropertyCode = x.PropertyCode);

    DECLARE @From date, @To date;
    SELECT @From = MIN(FromDate), @To = MAX(ToDate) FROM mr.MaintainFinalizedMeter WHERE PeriodCode = @PeriodCode AND IsCurrent = 1;
    IF @From IS NULL
        SELECT @From = DATEFROMPARTS(LEFT(@PeriodCode, 4), RIGHT(@PeriodCode, 2), 1), @To = EOMONTH(DATEFROMPARTS(LEFT(@PeriodCode, 4), RIGHT(@PeriodCode, 2), 1));

    DECLARE @total int, @ok int, @failed int, @pending int, @taxable decimal(18,2), @vat decimal(18,2), @amount decimal(18,2),
            @rows decimal(18,2), @properties int;
    SELECT @total   = COUNT(CASE WHEN InvNo IS NOT NULL THEN 1 END),
           @ok      = COUNT(CASE WHEN Status = 'Success' THEN 1 END),
           @failed  = COUNT(CASE WHEN Status = 'Failed' THEN 1 END),
           @pending = COUNT(CASE WHEN Status = 'Pending' THEN 1 END),
           @taxable = ISNULL(SUM(TaxableAmount), 0), @vat = ISNULL(SUM(VatAmount), 0), @amount = ISNULL(SUM(InvAmount), 0),
           @rows    = ISNULL(SUM(CASE WHEN InvNo IS NOT NULL THEN RowsAmount END), 0)
    FROM @inv;
    SELECT @properties = COUNT(DISTINCT PropertyCode) FROM mr.MaintainFinalizedMeter WHERE PeriodCode = @PeriodCode AND IsCurrent = 1;

    -- The month as a whole: one invoice per finalized property; invoices add up to their meter rows.
    DECLARE @note nvarchar(1000) = CONCAT(
        CASE WHEN @total <> @properties THEN CONCAT(N'Invoices (', @total, N') differ from finalized properties (', @properties, N'). ') END,
        CASE WHEN ABS(@amount - @rows) >= 0.01 THEN CONCAT(N'Invoice total ', FORMAT(@amount, 'N2'), N' differs from meter rows ', FORMAT(@rows, 'N2'), N'. ') END,
        CASE WHEN ABS(@taxable + @vat - @amount) >= 0.01 THEN N'Taxable + VAT differs from the invoice total. ' END,
        CASE WHEN @failed > 0 THEN CONCAT(@failed, N' failed. ') END,
        CASE WHEN @pending > 0 THEN CONCAT(@pending, N' pending. ') END);
    DECLARE @valid bit = CASE WHEN @note = N'' THEN 1 ELSE 0 END;
    IF @note = N'' SET @note = NULL;

    /* 4. The HTML (inline styles: mail programs drop style sheets). Text goes through FOR XML, so it is escaped. */
    DECLARE @td nvarchar(200) = N'padding:8px 6px;border-bottom:1px solid #eeeeee;font-size:13px;',
            @tdR nvarchar(200) = N'padding:8px 6px;border-bottom:1px solid #eeeeee;font-size:13px;text-align:right;',
            @th nvarchar(200) = N'padding:8px 6px;border-bottom:1px solid #dddddd;font-size:13px;text-align:left;';
    DECLARE @detail nvarchar(max) = CAST((
        SELECT [td/@style] = @td, td = ISNULL(CAST(InvNo AS varchar(20)), '-'), '',
               [td/@style] = @td, td = CONCAT(ISNULL(NULLIF(TenantsName, N''), N'-'), N' / ', PropertyCode), '',
               [td/@style] = @tdR, td = ISNULL(FORMAT(COALESCE(InvAmount, RowsAmount), 'N2'), '-'), '',
               [td/@style] = @td + CASE Status WHEN 'Success' THEN N'color:#1a7f37;' ELSE N'color:#c62828;' END,
               td = CASE WHEN Reason IS NULL THEN Status ELSE CONCAT(Status, N': ', Reason) END
        FROM @inv
        ORDER BY CASE Status WHEN 'Failed' THEN 0 WHEN 'Pending' THEN 1 ELSE 2 END, InvNo, PropertyCode
        FOR XML PATH('tr'), TYPE) AS nvarchar(max));

    DECLARE @esc nvarchar(2000) = NULLIF((SELECT @note AS [*] FOR XML PATH('')), N'');
    DECLARE @fromText varchar(20) = FORMAT(@From, 'dd-MMM-yyyy', 'en-US'), @toText varchar(20) = FORMAT(@To, 'dd-MMM-yyyy', 'en-US');
    DECLARE @allOk bit = CASE WHEN @failed = 0 AND @pending = 0 AND @valid = 1 THEN 1 ELSE 0 END;
    DECLARE @subject nvarchar(255) = CONCAT(N'Sewerage & Irrigation Invoice Generation Report - ', @fromText, N' to ', @toText,
        CASE WHEN @allOk = 1 THEN N'' ELSE CONCAT(N' (', @failed + @pending, N' failed / pending)') END);

    DECLARE @body nvarchar(max) = CONCAT(
N'<!DOCTYPE html><html><body style="margin:0;padding:16px;background:#f6f6f6;font-family:Segoe UI,Arial,sans-serif;color:#1f1f1f;">',
N'<p style="font-size:14px;">Dear Team,</p>',
N'<p style="font-size:14px;">', CASE WHEN @allOk = 1
    THEN N'The invoice generation process for Sewerage and Irrigation has been completed successfully.'
    ELSE N'The invoice generation process for Sewerage and Irrigation has been completed. Some invoices failed or are pending: see below.' END, N'</p>',
N'<p style="font-size:14px;">Please find below the summary of invoices generated for the billing period ', @fromText, N' to ', @toText, N'.</p>',
N'<p style="font-size:14px;font-weight:bold;margin-bottom:4px;">Invoice Generation Summary</p>',
N'<ul style="font-size:14px;margin-top:0;">',
N'<li>Total Invoices Generated: ', FORMAT(@total, 'N0'), N'</li>',
N'<li>Total Taxable Amount: AED ', FORMAT(@taxable, 'N2'), N'</li>',
N'<li>Total VAT Amount: AED ', FORMAT(@vat, 'N2'), N'</li>',
N'<li>Total Invoice Amount (Including VAT): AED ', FORMAT(@amount, 'N2'), N'</li>',
N'<li>Successful Invoices: ', FORMAT(@ok, 'N0'), N'</li>',
N'<li>Failed / Pending Invoices: ', FORMAT(@failed + @pending, 'N0'), N'</li>',
N'</ul>',
N'<p style="font-size:14px;">The detailed invoice generation report is provided below for your reference.</p>',
N'<table role="presentation" cellpadding="0" cellspacing="0" style="max-width:720px;width:100%;background:#ffffff;border:1px solid #dddddd;border-radius:10px;"><tr><td style="padding:16px 18px;">',
N'<div style="font-size:20px;font-weight:600;">Sewerage &amp; Irrigation</div>',
N'<div style="font-size:16px;font-weight:600;margin-top:6px;">Invoice Generation Report</div>',
N'<div style="font-size:12px;color:#555555;margin-top:6px;">Billing Period: ', @fromText, N' to ', @toText, N'</div>',
N'<div style="font-size:12px;color:#555555;">Generation Date: ', FORMAT(GETDATE(), 'dd-MMM-yyyy', 'en-US'), N'</div>',
N'<hr style="border:0;border-top:1px solid #dddddd;margin:14px 0;">',
N'<table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr>',
N'<td style="width:50%;padding:6px 10px;"><div style="font-size:12px;color:#555555;">Total Invoices</div><div style="font-size:22px;font-weight:600;">', FORMAT(@total, 'N0'), N'</div></td>',
N'<td style="width:50%;padding:6px 10px;"><div style="font-size:12px;color:#555555;">Total Amount (AED)</div><div style="font-size:22px;font-weight:600;">', FORMAT(@amount, 'N2'), N'</div></td>',
N'</tr><tr>',
N'<td style="padding:6px 10px;"><div style="font-size:12px;color:#555555;">Taxable Amount</div><div style="font-size:14px;font-weight:600;">AED ', FORMAT(@taxable, 'N2'), N'</div></td>',
N'<td style="padding:6px 10px;"><div style="font-size:12px;color:#555555;">VAT Amount (5%)</div><div style="font-size:14px;font-weight:600;">AED ', FORMAT(@vat, 'N2'), N'</div></td>',
N'</tr></table>',
N'<div style="font-size:14px;font-weight:600;margin:14px 0 6px;">Invoice Status</div>',
N'<table role="presentation" width="100%" cellpadding="0" cellspacing="0">',
N'<tr><td style="padding:6px 0;font-size:14px;font-weight:600;color:#1a7f37;">Successful</td><td style="padding:6px 0;font-size:14px;font-weight:600;text-align:right;">', FORMAT(@ok, 'N0'), N'</td></tr>',
N'<tr><td style="padding:6px 0;font-size:14px;font-weight:600;color:#c62828;">Failed / Pending</td><td style="padding:6px 0;font-size:14px;font-weight:600;text-align:right;">', FORMAT(@failed + @pending, 'N0'), N'</td></tr>',
N'</table>',
CASE WHEN @esc IS NOT NULL THEN CONCAT(N'<div style="font-size:12px;color:#c62828;margin-top:6px;">Validation: ', @esc, N'</div>') END,
N'<hr style="border:0;border-top:1px solid #dddddd;margin:14px 0;">',
N'<div style="font-size:14px;font-weight:600;margin-bottom:6px;">Invoice Details</div>',
N'<table width="100%" cellpadding="0" cellspacing="0" style="border-collapse:collapse;">',
N'<tr><th style="', @th, N'">Invoice No.</th><th style="', @th, N'">Tenant / Property</th><th style="', @th, N'text-align:right;">Amount (AED)</th><th style="', @th, N'">Status</th></tr>',
ISNULL(@detail, N'<tr><td colspan="4" style="padding:8px 6px;font-size:13px;color:#555555;">No invoices for this period.</td></tr>'),
N'</table>',
N'<hr style="border:0;border-top:1px solid #dddddd;margin:14px 0;">',
N'<div style="font-size:11px;color:#777777;">This is an automatically generated report. Please do not reply to this email.</div>',
N'</td></tr></table>',
N'<p style="font-size:14px;">Please review the report and inform the IT team of any discrepancies.</p>',
N'<p style="font-size:14px;">Regards,<br>IT Team</p>',
N'</body></html>');

    /* 5-6. Send when enabled, and log what happened. */
    DECLARE @enabled bit, @profile sysname, @to2 nvarchar(2000), @cc nvarchar(2000);
    SELECT @enabled = IsEnabled, @profile = ProfileName, @to2 = NULLIF(LTRIM(RTRIM(Recipients)), N''), @cc = NULLIF(LTRIM(RTRIM(CopyRecipients)), N'')
    FROM mr.InvoiceReportSetting WHERE SettingId = 1;

    DECLARE @mailId int = NULL, @status varchar(20), @error nvarchar(2000) = NULL;
    IF ISNULL(@enabled, 0) = 0 OR @to2 IS NULL
        SELECT @status = 'DISABLED', @error = CASE WHEN @to2 IS NULL THEN N'No recipients in mr.InvoiceReportSetting.' ELSE N'Report is switched off (mr.InvoiceReportSetting.IsEnabled = 0).' END;
    ELSE
    BEGIN TRY
        EXEC msdb.dbo.sp_send_dbmail
             @profile_name = @profile, @recipients = @to2, @copy_recipients = @cc,
             @subject = @subject, @body = @body, @body_format = 'HTML', @mailitem_id = @mailId OUTPUT;
        SET @status = 'QUEUED';
    END TRY
    BEGIN CATCH
        SELECT @status = 'FAILED', @error = LEFT(ERROR_MESSAGE(), 2000);
    END CATCH

    INSERT mr.InvoiceReportLog (PeriodCode, FromDate, ToDate, TotalInvoices, Successful, Failed, Pending, TaxableAmount, VatAmount,
        InvoiceAmount, MeterRowsAmount, ValidationOk, ValidationNote, Subject, Recipients, CopyRecipients, ProfileName, MailItemId,
        DispatchStatus, ErrorMessage, RequestedBy)
    VALUES (@PeriodCode, @From, @To, @total, @ok, @failed, @pending, @taxable, @vat,
        @amount, @rows, @valid, @note, @subject, @to2, @cc, @profile, @mailId,
        @status, @error, @RequestedBy);
    SET @LogId = SCOPE_IDENTITY();

    SELECT @status AS DispatchStatus, @LogId AS LogId, @mailId AS MailItemId, @total AS TotalInvoices, @ok AS Successful,
           @failed AS Failed, @pending AS Pending, @amount AS InvoiceAmount, @valid AS ValidationOk, @note AS ValidationNote, @error AS ErrorMessage;
END
GO

-- The API calls it after finalizing the month. The procedure belongs to dbo (schema mr), so it reads PMS's
-- invoices and writes the log for mr_api through ownership; msdb is another database, hence the role below.
IF DATABASE_PRINCIPAL_ID(N'mr_api') IS NULL
    RAISERROR(N'No user mr_api in this database: run db/008_create_api_login.sql here, then this script again.', 10, 1) WITH NOWAIT;
ELSE
    GRANT EXECUTE ON mr.usp_SendInvoiceReport TO [mr_api];
GO
DECLARE @login sysname = (SELECT SUSER_SNAME(sid) FROM sys.database_principals WHERE name = N'mr_api');
IF @login IS NOT NULL AND DATABASE_PRINCIPAL_ID(N'mr_api') IS NOT NULL
   AND EXISTS (SELECT 1 FROM msdb.sys.database_principals WHERE name = N'DatabaseMailUserRole' AND type = 'R')
BEGIN
    DECLARE @sql nvarchar(max) = N'
        IF DATABASE_PRINCIPAL_ID(N''mr_api'') IS NULL CREATE USER [mr_api] FOR LOGIN ' + QUOTENAME(@login) + N';
        IF IS_ROLEMEMBER(N''DatabaseMailUserRole'', N''mr_api'') = 0 ALTER ROLE DatabaseMailUserRole ADD MEMBER [mr_api];';
    EXEC msdb.sys.sp_executesql @sql;
    PRINT N'mr_api can send Database Mail (msdb DatabaseMailUserRole).';
END
ELSE
    RAISERROR(N'mr_api was not added to msdb DatabaseMailUserRole (no mr_api login here, or no Database Mail): the report can still be sent from SSMS.', 10, 1) WITH NOWAIT;
GO

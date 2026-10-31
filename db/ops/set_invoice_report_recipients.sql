/* ops/set_invoice_report_recipients.sql — who gets the Invoice Generation Report (db/034, FR-037.18) and whether
   it is sent. Run in PropertyManagementSystem. Addresses separated by ';'.
   @ProfileName: the Database Mail profile (SSMS: Management -> Database Mail); NULL for the default profile.
   To find the profiles:  SELECT name FROM msdb.dbo.sysmail_profile;
   Then a test: db/ops/send_invoice_report.sql. */
SET NOCOUNT ON;
DECLARE @IsEnabled      bit            = 1;
DECLARE @ProfileName    sysname        = NULL;                          -- e.g. N'PMS Mail'
DECLARE @Recipients     nvarchar(2000) = N'finance@example.ae';          -- change: who receives it
DECLARE @CopyRecipients nvarchar(2000) = NULL;                          -- e.g. N'it@example.ae'

IF @ProfileName IS NOT NULL AND NOT EXISTS (SELECT 1 FROM msdb.dbo.sysmail_profile WHERE name = @ProfileName)
BEGIN
    RAISERROR(N'There is no Database Mail profile with that name: SELECT name FROM msdb.dbo.sysmail_profile.', 16, 1);
    RETURN;
END
UPDATE mr.InvoiceReportSetting
SET IsEnabled = @IsEnabled, ProfileName = @ProfileName, Recipients = NULLIF(LTRIM(RTRIM(@Recipients)), N''),
    CopyRecipients = NULLIF(LTRIM(RTRIM(@CopyRecipients)), N''), UpdatedAtUtc = SYSUTCDATETIME(), UpdatedBy = SUSER_SNAME()
WHERE SettingId = 1;
SELECT * FROM mr.InvoiceReportSetting;

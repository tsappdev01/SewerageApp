/*
  ops/reopen_reading_period.sql — opens a closed reading period again (spec FR-037.11). Run in the app's database (PropertyManagementSystem since 2026-10-29).
  Phones can then send readings for it again (readings that waited on the phone go up by themselves)
  and properties can be finalized again. PMS's vw_MR_ReadingPeriod must still show it OPEN.
*/
DECLARE @PeriodCode char(7) = '2026-09';            -- yyyy-MM, the billing month
DECLARE @Note nvarchar(200) = N'Re-opened for corrections';

UPDATE mr.ReadingPeriodStatus
SET Status = 'OPEN', ChangedAtUtc = SYSUTCDATETIME(), ChangedBy = SUSER_SNAME(), Note = @Note
WHERE PeriodCode = @PeriodCode;

SELECT @@ROWCOUNT AS PeriodsReopened;
SELECT PeriodCode, Status, ChangedAtUtc, ChangedBy, Note FROM mr.ReadingPeriodStatus ORDER BY PeriodCode DESC;

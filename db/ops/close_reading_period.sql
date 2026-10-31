/*
  ops/close_reading_period.sql — closes a reading period for the app (spec FR-037.11). Run in the app's database (PropertyManagementSystem since 2026-10-29).
  Phones then cannot send new readings for it (PERIOD_CLOSED; readings wait on the phone) and no
  property can be finalized. The API also closes it by itself when the last property is finalized.
  To open it again: ops/reopen_reading_period.sql.
*/
DECLARE @PeriodCode char(7) = '2026-09';            -- yyyy-MM, the billing month
DECLARE @Note nvarchar(200) = N'Posting complete';

MERGE mr.ReadingPeriodStatus AS t
USING (SELECT @PeriodCode AS PeriodCode) AS s ON t.PeriodCode = s.PeriodCode
WHEN MATCHED THEN UPDATE SET Status = 'CLOSED', ChangedAtUtc = SYSUTCDATETIME(), ChangedBy = SUSER_SNAME(), Note = @Note
WHEN NOT MATCHED THEN INSERT (PeriodCode, Status, ChangedBy, Note) VALUES (@PeriodCode, 'CLOSED', SUSER_SNAME(), @Note);

SELECT PeriodCode, Status, ChangedAtUtc, ChangedBy, Note FROM mr.ReadingPeriodStatus ORDER BY PeriodCode DESC;

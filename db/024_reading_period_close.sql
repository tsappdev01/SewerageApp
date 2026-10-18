/*
  024_reading_period_close.sql
  Spec FR-037.11 (DIP 2026-10-18): once posting is complete (every property of the period finalized),
  the reading period is CLOSED and phones cannot send new readings until it is OPEN again.

  PMS's vw_MR_ReadingPeriod is never written to, so the close is kept here: a period with Status
  'CLOSED' in mr.ReadingPeriodStatus is closed for the app whatever the view says. The API closes it by
  itself when the last property is finalized; IT can close or re-open it by hand with
  ops/close_reading_period.sql and ops/reopen_reading_period.sql.

  The API's mr_api user already has SELECT, INSERT, UPDATE on schema mr (008). Run in MRDB after 023.
  Re-runnable.
*/
IF OBJECT_ID(N'mr.ReadingPeriodStatus', N'U') IS NULL
CREATE TABLE mr.ReadingPeriodStatus (
    PeriodCode    char(7)        NOT NULL CONSTRAINT PK_ReadingPeriodStatus PRIMARY KEY,   -- yyyy-MM
    Status        varchar(10)    NOT NULL CONSTRAINT CK_ReadingPeriodStatus_Status CHECK (Status IN ('OPEN', 'CLOSED')),
    ChangedAtUtc  datetime2(0)   NOT NULL CONSTRAINT DF_ReadingPeriodStatus_ChangedAtUtc DEFAULT SYSUTCDATETIME(),
    ChangedBy     nvarchar(100)  NOT NULL,
    Note          nvarchar(200)  NULL
);
GO

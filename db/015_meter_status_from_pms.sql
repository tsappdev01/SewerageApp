/*
  015_meter_status_from_pms.sql
  Meter statuses come from PMS (spec FR-006.2, DIP 2026-10-18): the app offers the rows of
  dbo.vw_MR_MeterStatus (StatusCode, StatusName, SequenceNo; in MRDB, over
  PropertyManagementSystem.dbo.vw_MR_MeterStatus) and mr.ReadingTransaction.MeterCondition now holds
  the StatusCode the reader picked (O.K, DMG, FWW, FLT, CHG). What a code means for the app (its kind:
  WORKING, DAMAGED, ...) is the API setting MeterStatus:Kinds, and is kept in the new ConditionKind.

  Readings stored before this keep their old value (WORKING, DAMAGED, ...) in MeterCondition, and
  ConditionKind is filled in from it. Run in MRDB after 014. Re-runnable.
*/
IF COL_LENGTH(N'mr.ReadingTransaction', N'ConditionKind') IS NULL
    ALTER TABLE mr.ReadingTransaction ADD ConditionKind varchar(20) NULL;
GO
UPDATE mr.ReadingTransaction SET ConditionKind = MeterCondition
WHERE ConditionKind IS NULL AND MeterCondition IN ('WORKING', 'DAMAGED', 'SUBMERSED', 'NOT_ACCESSIBLE', 'METER_REPLACED', 'REMOVED');
GO
-- MeterCondition takes PMS's codes now; the six kinds are checked on ConditionKind instead.
IF EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = N'CK_ReadingTransaction_Condition' AND parent_object_id = OBJECT_ID(N'mr.ReadingTransaction'))
    ALTER TABLE mr.ReadingTransaction DROP CONSTRAINT CK_ReadingTransaction_Condition;
GO
IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = N'CK_ReadingTransaction_ConditionKind' AND parent_object_id = OBJECT_ID(N'mr.ReadingTransaction'))
    ALTER TABLE mr.ReadingTransaction ADD CONSTRAINT CK_ReadingTransaction_ConditionKind
        CHECK (ConditionKind IS NULL OR ConditionKind IN ('WORKING', 'DAMAGED', 'SUBMERSED', 'NOT_ACCESSIBLE', 'METER_REPLACED', 'REMOVED'));
GO

-- What the app will offer: every status in the view. A code with no kind in MeterStatus:Kinds is not offered.
IF OBJECT_ID(N'dbo.vw_MR_MeterStatus', N'V') IS NULL
    SELECT N'dbo.vw_MR_MeterStatus is missing: the app offers its built-in statuses until it is created.' AS Note;
ELSE
    EXEC (N'SELECT StatusCode, StatusName, SequenceNo FROM dbo.vw_MR_MeterStatus ORDER BY SequenceNo');
GO

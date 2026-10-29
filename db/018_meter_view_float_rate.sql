/*
  018_meter_view_float_rate.sql
  MRDB's dbo.vw_MR_Meter over PropertyManagementSystem.dbo.vw_MR_Meter, now with FloatValue and RateFactor
  (DIP 2026-10-18). They price the readings in mr.MaintainFinalizedMeter (spec FR-037.5):
      Amount = Consumption * FloatValue * 1.2 * (RateFactor / 100)
  The API reads both columns from now on, so run this before deploying it. Run in MRDB as the view's
  owner. Re-runnable.

  Not for development databases: db/dev/000 makes its own stand-in.
*/
IF DB_NAME() = N'PropertyManagementSystem'
BEGIN
    -- In PMS this would replace PMS's own view with one that reads itself.
    RAISERROR(N'MRDB only: this makes MRDB''s wrapper view over PMS''s view of the same name. In PropertyManagementSystem run db/028_pms_app_views.sql instead.', 16, 1) WITH NOWAIT;
    SET NOEXEC ON;
END
GO
IF DB_ID(N'PropertyManagementSystem') IS NULL
BEGIN
    RAISERROR(N'Database PropertyManagementSystem not found on this server.', 16, 1);
    RETURN;
END
IF COL_LENGTH(N'PropertyManagementSystem.dbo.vw_MR_Meter', N'FloatValue') IS NULL
   OR COL_LENGTH(N'PropertyManagementSystem.dbo.vw_MR_Meter', N'RateFactor') IS NULL
BEGIN
    RAISERROR(N'PropertyManagementSystem.dbo.vw_MR_Meter has no FloatValue or RateFactor column: add them there first.', 16, 1);
    RETURN;
END
EXEC (N'
CREATE OR ALTER VIEW dbo.vw_MR_Meter AS
SELECT MeterId, MeterNumber, PropertyCode, MeterType, RegisterDigits, DecimalDigits, OpeningReading, LastConsumption,
       AvgConsumption, InstallDate, SerialNumber, RouteSequence, Status, FloatValue, RateFactor
FROM PropertyManagementSystem.dbo.vw_MR_Meter;');
PRINT N'dbo.vw_MR_Meter updated with FloatValue and RateFactor.';
GO
SET NOEXEC OFF;
GO

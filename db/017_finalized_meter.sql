/*
  017_finalized_meter.sql
  FR-037: when every meter of a property is read, the reader presses "Finalize property" and the
  API writes the property's readings for the period here, one row per meter number: the sewerage
  meter and the irrigation meter with the same number share a row; a meter with no partner of the
  other type has 0 on that side (meter number '0', readings 0), as in DIP's layout of 2026-10-18.

  Pressing it again (after a reading was read again) writes a new set and marks the earlier rows
  IsCurrent = 0; nothing is deleted. Read the current set with WHERE IsCurrent = 1.

  The API's mr_api user already has SELECT, INSERT, UPDATE on schema mr (008). Run in MRDB after 016.
  Re-runnable.
*/
IF OBJECT_ID(N'mr.MaintainFinalizedMeter', N'U') IS NULL
CREATE TABLE mr.MaintainFinalizedMeter (
    FinalizedMeterId        bigint IDENTITY(1, 1) NOT NULL CONSTRAINT PK_MaintainFinalizedMeter PRIMARY KEY,
    PeriodCode              char(7)          NOT NULL,
    PropertyId              bigint           NULL,
    PropertyCode            varchar(50)      NOT NULL,
    MeterNumberSewerage     varchar(50)      NOT NULL CONSTRAINT DF_MaintainFinalizedMeter_MeterNumberS DEFAULT '0',
    PrevReadingSewerage     decimal(18, 4)   NOT NULL CONSTRAINT DF_MaintainFinalizedMeter_PrevS DEFAULT 0,
    CurrentReadingSewerage  decimal(18, 4)   NOT NULL CONSTRAINT DF_MaintainFinalizedMeter_CurrentS DEFAULT 0,
    ConsumptionSewerage     decimal(18, 4)   NOT NULL CONSTRAINT DF_MaintainFinalizedMeter_ConsumptionS DEFAULT 0,
    MeterNumberIrrigation   varchar(50)      NOT NULL CONSTRAINT DF_MaintainFinalizedMeter_MeterNumberI DEFAULT '0',
    PrevReadingIrrigation   decimal(18, 4)   NOT NULL CONSTRAINT DF_MaintainFinalizedMeter_PrevI DEFAULT 0,
    CurrentReadingIrrigation decimal(18, 4)  NOT NULL CONSTRAINT DF_MaintainFinalizedMeter_CurrentI DEFAULT 0,
    ConsumptionIrrigation   decimal(18, 4)   NOT NULL CONSTRAINT DF_MaintainFinalizedMeter_ConsumptionI DEFAULT 0,
    -- The readings (mr.ReadingTransaction) each side came from; NULL on an empty side.
    SewerageTransactionId   uniqueidentifier NULL,
    IrrigationTransactionId uniqueidentifier NULL,
    FinalizedByReaderId     varchar(50)      NOT NULL,
    DeviceId                uniqueidentifier NULL,
    FinalizedAtUtc          datetime2(0)     NOT NULL CONSTRAINT DF_MaintainFinalizedMeter_FinalizedAtUtc DEFAULT SYSUTCDATETIME(),
    IsCurrent               bit              NOT NULL CONSTRAINT DF_MaintainFinalizedMeter_IsCurrent DEFAULT 1
);
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_MaintainFinalizedMeter_Period_Property' AND object_id = OBJECT_ID(N'mr.MaintainFinalizedMeter'))
CREATE INDEX IX_MaintainFinalizedMeter_Period_Property ON mr.MaintainFinalizedMeter (PeriodCode, PropertyCode, IsCurrent);
GO

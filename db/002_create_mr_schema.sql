/*
  002_create_mr_schema.sql
  The API's own tables: what the phone sends. Master data stays in the source views.
  Re-runnable: creates only what is missing; never alters or drops.
*/
IF SCHEMA_ID(N'mr') IS NULL EXEC (N'CREATE SCHEMA mr');
GO

IF OBJECT_ID(N'mr.Device', N'U') IS NULL
CREATE TABLE mr.Device (
    DeviceId          uniqueidentifier NOT NULL CONSTRAINT PK_Device PRIMARY KEY,
    LastReaderId      varchar(50)      NULL,
    Model             nvarchar(100)    NULL,
    AndroidVersion    varchar(20)      NULL,
    AppVersion        varchar(20)      NULL,
    RegisteredAtUtc   datetime2(0)     NOT NULL CONSTRAINT DF_Device_RegisteredAtUtc DEFAULT SYSUTCDATETIME(),
    LastSyncAtUtc     datetime2(0)     NULL,
    Status            varchar(10)      NOT NULL CONSTRAINT DF_Device_Status DEFAULT 'ACTIVE'
        CONSTRAINT CK_Device_Status CHECK (Status IN ('ACTIVE', 'REVOKED'))
);
GO

IF OBJECT_ID(N'mr.ReadingTransaction', N'U') IS NULL
CREATE TABLE mr.ReadingTransaction (
    TransactionId          uniqueidentifier NOT NULL CONSTRAINT PK_ReadingTransaction PRIMARY KEY,
    PeriodCode             char(7)          NOT NULL,
    MeterId                bigint           NOT NULL,
    ReaderId               varchar(50)      NOT NULL,
    DeviceId               uniqueidentifier NULL,
    MeterCondition         varchar(20)      NOT NULL
        CONSTRAINT CK_ReadingTransaction_Condition CHECK (MeterCondition IN
            ('WORKING', 'DAMAGED', 'SUBMERSED', 'NOT_ACCESSIBLE', 'METER_REPLACED', 'REMOVED')),
    ReasonCode             varchar(30)      NULL,
    Remarks                nvarchar(500)    NULL,
    NewReading             decimal(18, 3)   NULL,
    PreviousReading        decimal(18, 3)   NULL,
    Consumption            decimal(18, 3)   NULL,
    OldFinalReading        decimal(18, 3)   NULL,
    NewMeterNumber         varchar(30)      NULL,
    NewOpeningReading      decimal(18, 3)   NULL,
    NewCurrentReading      decimal(18, 3)   NULL,
    OcrValue               decimal(18, 3)   NULL,
    OcrConfidence          decimal(5, 4)    NULL,
    Latitude               decimal(9, 6)    NULL,
    Longitude              decimal(9, 6)    NULL,
    GpsAccuracyM           decimal(7, 2)    NULL,
    CapturedAtUtc          datetime2(0)     NOT NULL,
    ReceivedAtUtc          datetime2(0)     NOT NULL CONSTRAINT DF_ReadingTransaction_ReceivedAtUtc DEFAULT SYSUTCDATETIME(),
    Status                 varchar(25)      NOT NULL
        CONSTRAINT CK_ReadingTransaction_Status CHECK (Status IN
            ('ACCEPTED', 'EXCEPTION', 'APPROVED', 'REJECTED_BY_SUPERVISOR', 'POSTED', 'ACKNOWLEDGED', 'BILLING_FAILED', 'SUPERSEDED')),
    StatusNote             nvarchar(500)    NULL,
    StatusChangedAtUtc     datetime2(0)     NULL,
    PayloadHash            char(64)         NULL,
    CorrectsTransactionId  uniqueidentifier NULL
);
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_ReadingTransaction_Period_Meter' AND object_id = OBJECT_ID(N'mr.ReadingTransaction'))
CREATE INDEX IX_ReadingTransaction_Period_Meter ON mr.ReadingTransaction (PeriodCode, MeterId, ReceivedAtUtc DESC)
    INCLUDE (Status, StatusNote, MeterCondition, NewReading);
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_ReadingTransaction_Reader_Period' AND object_id = OBJECT_ID(N'mr.ReadingTransaction'))
CREATE INDEX IX_ReadingTransaction_Reader_Period ON mr.ReadingTransaction (ReaderId, PeriodCode, CapturedAtUtc DESC);
GO

IF OBJECT_ID(N'mr.ReadingImage', N'U') IS NULL
CREATE TABLE mr.ReadingImage (
    ImageId          uniqueidentifier NOT NULL CONSTRAINT PK_ReadingImage PRIMARY KEY,
    TransactionId    uniqueidentifier NOT NULL
        CONSTRAINT FK_ReadingImage_Transaction REFERENCES mr.ReadingTransaction (TransactionId),
    ImageRole        varchar(20)      NOT NULL,
    BlobPath         nvarchar(400)    NOT NULL,
    Sha256           char(64)         NOT NULL,
    SizeBytes        int              NOT NULL,
    Width            int              NULL,
    Height           int              NULL,
    CapturedAtUtc    datetime2(0)     NOT NULL,
    ReceivedAtUtc    datetime2(0)     NOT NULL CONSTRAINT DF_ReadingImage_ReceivedAtUtc DEFAULT SYSUTCDATETIME()
);
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_ReadingImage_Transaction' AND object_id = OBJECT_ID(N'mr.ReadingImage'))
CREATE INDEX IX_ReadingImage_Transaction ON mr.ReadingImage (TransactionId);
GO

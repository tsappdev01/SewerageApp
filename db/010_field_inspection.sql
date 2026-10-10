/*
  010_field_inspection.sql
  Field Inspection (docs/spec.md §21): what inspectors record when they visit a planned property.
  The plan and the units come from vw_MR_InspectionPlan and vw_MR_InspectionUnit (read only).
    mr.InspectionVisit       one visit to one plan row (period, property, tenant), sent from the phone
    mr.InspectionUnitResult  what was found in each unit; UnitId is NULL for a unit not on the list
    mr.InspectionImage       evidence photos and the signature; the bytes are in the image store
                             (mr.ReadingImageData by default, same as reading photos)
  A visit is stored once: the phone retries with the same VisitId. Run in MRDB after 009. Re-runnable.
*/
IF OBJECT_ID(N'mr.InspectionVisit', N'U') IS NULL
CREATE TABLE mr.InspectionVisit (
    VisitId         uniqueidentifier NOT NULL CONSTRAINT PK_InspectionVisit PRIMARY KEY,
    PeriodCode      varchar(10)      NOT NULL,
    PropertyCode    varchar(30)      NOT NULL,
    TenantCode      varchar(30)      NOT NULL,
    CompanyName     nvarchar(200)    NULL,
    PlanDate        date             NULL,
    InspectorId     varchar(50)      NOT NULL,
    DeviceId        uniqueidentifier NULL,
    StartedAtUtc    datetime2(0)     NOT NULL,
    FinishedAtUtc   datetime2(0)     NOT NULL,
    ReceivedAtUtc   datetime2(0)     NOT NULL CONSTRAINT DF_InspectionVisit_ReceivedAtUtc DEFAULT SYSUTCDATETIME(),
    Latitude        decimal(9,6)     NULL,
    Longitude       decimal(9,6)     NULL,
    GpsAccuracyM    decimal(9,2)     NULL,
    PersonMet       nvarchar(100)    NULL,
    ExpectedPhotos  int              NOT NULL CONSTRAINT DF_InspectionVisit_ExpectedPhotos DEFAULT 0,
    PayloadHash     char(64)         NOT NULL
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_InspectionVisit_Plan' AND object_id = OBJECT_ID(N'mr.InspectionVisit'))
CREATE INDEX IX_InspectionVisit_Plan ON mr.InspectionVisit (PeriodCode, PropertyCode, TenantCode) INCLUDE (FinishedAtUtc);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_InspectionVisit_Inspector' AND object_id = OBJECT_ID(N'mr.InspectionVisit'))
CREATE INDEX IX_InspectionVisit_Inspector ON mr.InspectionVisit (InspectorId, FinishedAtUtc);
GO

IF OBJECT_ID(N'mr.InspectionUnitResult', N'U') IS NULL
CREATE TABLE mr.InspectionUnitResult (
    ResultId           uniqueidentifier NOT NULL CONSTRAINT PK_InspectionUnitResult PRIMARY KEY,
    VisitId            uniqueidentifier NOT NULL CONSTRAINT FK_InspectionUnitResult_Visit REFERENCES mr.InspectionVisit (VisitId),
    UnitId             varchar(50)      NULL,      -- NULL: a unit found on site that is not on the list
    UnitCode           nvarchar(30)     NOT NULL,
    BuildingName       nvarchar(150)    NULL,
    Category           nvarchar(200)    NULL,
    SubTenantOnRecord  nvarchar(200)    NULL,
    ActiveOnRecord     bit              NULL,
    Result             varchar(20)      NOT NULL
        CONSTRAINT CK_InspectionUnitResult_Result CHECK (Result IN ('AS_RECORDED', 'VACANT', 'SUBLEASED', 'DISPUTED', 'REJECTED', 'PENDING')),
    PeopleSeen         int              NULL,
    OccupantName       nvarchar(200)    NULL,
    Reasons            varchar(200)     NULL,      -- reason codes, comma separated
    Note               nvarchar(500)    NULL,
    ExpectedPhotos     int              NOT NULL CONSTRAINT DF_InspectionUnitResult_ExpectedPhotos DEFAULT 0
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_InspectionUnitResult_Visit' AND object_id = OBJECT_ID(N'mr.InspectionUnitResult'))
CREATE INDEX IX_InspectionUnitResult_Visit ON mr.InspectionUnitResult (VisitId);
GO
-- A filtered index needs QUOTED_IDENTIFIER ON; sqlcmd's default is OFF (SSMS's is ON).
SET QUOTED_IDENTIFIER ON;
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_InspectionUnitResult_Unit' AND object_id = OBJECT_ID(N'mr.InspectionUnitResult'))
CREATE INDEX IX_InspectionUnitResult_Unit ON mr.InspectionUnitResult (UnitId) INCLUDE (Result) WHERE UnitId IS NOT NULL;
GO

IF OBJECT_ID(N'mr.InspectionImage', N'U') IS NULL
CREATE TABLE mr.InspectionImage (
    ImageId        uniqueidentifier NOT NULL CONSTRAINT PK_InspectionImage PRIMARY KEY,
    VisitId        uniqueidentifier NOT NULL CONSTRAINT FK_InspectionImage_Visit REFERENCES mr.InspectionVisit (VisitId),
    ResultId       uniqueidentifier NULL CONSTRAINT FK_InspectionImage_Result REFERENCES mr.InspectionUnitResult (ResultId),
    ImageRole      varchar(20)      NOT NULL CONSTRAINT CK_InspectionImage_Role CHECK (ImageRole IN ('EVIDENCE', 'SIGNATURE')),
    BlobPath       nvarchar(400)    NOT NULL,
    Sha256         char(64)         NOT NULL,
    SizeBytes      int              NOT NULL,
    CapturedAtUtc  datetime2(0)     NOT NULL,
    ReceivedAtUtc  datetime2(0)     NOT NULL CONSTRAINT DF_InspectionImage_ReceivedAtUtc DEFAULT SYSUTCDATETIME()
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_InspectionImage_Visit' AND object_id = OBJECT_ID(N'mr.InspectionImage'))
CREATE INDEX IX_InspectionImage_Visit ON mr.InspectionImage (VisitId);
GO

/* For the office: one row per unit result, newest visit first. */
CREATE OR ALTER VIEW mr.vw_InspectionResult AS
SELECT v.PeriodCode, v.PropertyCode, v.TenantCode, v.CompanyName, v.PlanDate, v.InspectorId,
       v.FinishedAtUtc, v.PersonMet, v.Latitude, v.Longitude,
       r.UnitId, r.UnitCode, r.BuildingName, r.Category, r.SubTenantOnRecord, r.ActiveOnRecord,
       r.Result, r.PeopleSeen, r.OccupantName, r.Reasons, r.Note,
       r.ExpectedPhotos, (SELECT COUNT(*) FROM mr.InspectionImage i WHERE i.ResultId = r.ResultId) AS PhotosReceived,
       v.VisitId, r.ResultId
FROM mr.InspectionVisit v
JOIN mr.InspectionUnitResult r ON r.VisitId = v.VisitId;
GO

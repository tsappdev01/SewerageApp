/*
  012_inspection_location.sql
  Field Inspection: whether the inspector said they were at the property when the visit started
  (spec FR-031.2), and the distance from the DIP office to the property worked out from the location
  then saved (Inspection:OfficeLatitude / OfficeLongitude). A visit started away from the property
  keeps no location. Run in MRDB after 010. Re-runnable.
*/
IF COL_LENGTH(N'mr.InspectionVisit', N'AtProperty') IS NULL
    ALTER TABLE mr.InspectionVisit ADD AtProperty bit NULL;
GO
IF COL_LENGTH(N'mr.InspectionVisit', N'DistanceFromOfficeKm') IS NULL
    ALTER TABLE mr.InspectionVisit ADD DistanceFromOfficeKm decimal(9,3) NULL;
GO

CREATE OR ALTER VIEW mr.vw_InspectionResult AS
SELECT v.PeriodCode, v.PropertyCode, v.TenantCode, v.CompanyName, v.PlanDate, v.InspectorId,
       v.FinishedAtUtc, v.PersonMet, v.AtProperty, v.Latitude, v.Longitude, v.DistanceFromOfficeKm,
       r.UnitId, r.UnitCode, r.BuildingName, r.Category, r.SubTenantOnRecord, r.ActiveOnRecord,
       r.Result, r.PeopleSeen, r.OccupantName, r.Reasons, r.Note,
       r.ExpectedPhotos, (SELECT COUNT(*) FROM mr.InspectionImage i WHERE i.ResultId = r.ResultId) AS PhotosReceived,
       v.VisitId, r.ResultId
FROM mr.InspectionVisit v
JOIN mr.InspectionUnitResult r ON r.VisitId = v.VisitId;
GO

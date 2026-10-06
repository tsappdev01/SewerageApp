namespace MeterReading.Api.Contracts;

// Field Inspection (spec §16). Same casing rules as Dtos.cs: what the Android app reads and sends.

/// <summary>Where a plan row stands: no visit yet, visited but units left to check, or every active unit checked.</summary>
public enum InspectionState { NOT_STARTED, COME_BACK, DONE }

/// <summary>What the inspector found in a unit (FR-032). AS_RECORDED and VACANT are the common, quick results.</summary>
public enum UnitResult { AS_RECORDED, VACANT, SUBLEASED, DISPUTED, REJECTED, PENDING }

/// <summary>One row of vw_MR_InspectionPlan with its progress from earlier visits.</summary>
public sealed record InspectionPlanDto(
    string PeriodCode,
    DateOnly PlanDate,
    string PropertyCode,
    string TenantCode,
    string? CompanyName,
    int ActiveUnits,
    int InactiveUnits,
    int TotalUnits,
    InspectionState State,
    /// <summary>Units whose latest result is anything but PENDING.</summary>
    int CheckedUnits,
    /// <summary>Units whose latest result is SUBLEASED, DISPUTED or REJECTED.</summary>
    int FlaggedUnits,
    DateTime? LastVisitAtUtc);

public sealed record InspectionPlanListDto(DateOnly Today, DateOnly From, DateOnly To, IReadOnlyList<InspectionPlanDto> Plans, DateTime ServerTimeUtc);

/// <summary>The latest result recorded for a unit, from any earlier visit.</summary>
public sealed record LastUnitResultDto(UnitResult Result, DateTime AtUtc, string? OccupantName, int? PeopleSeen);

/// <summary>A unit of vw_MR_InspectionUnit. CategoryName is the last non-empty level of Category ("Commercial> >" gives "Commercial").</summary>
public sealed record InspectionUnitDto(
    string UnitId,
    string? BuildingName,
    string UnitCode,
    string? Category,
    string? CategoryName,
    string? SubTenantName,
    bool Active,
    LastUnitResultDto? Last);

public sealed record InspectionUnitsDto(InspectionPlanDto Plan, IReadOnlyList<InspectionUnitDto> Units);

/// <summary>
/// One unit's result in a visit. UnitId is a unit of the plan row; null means a unit found on site that
/// is not on the list, and then UnitCode is required. PhotoCount photos follow with PUT.
/// </summary>
public sealed record UnitResultRequest(
    Guid ResultId,
    string? UnitId,
    UnitResult Result,
    int? PeopleSeen = null,
    string? OccupantName = null,
    IReadOnlyList<string>? Reasons = null,
    string? Note = null,
    int PhotoCount = 0,
    string? UnitCode = null,
    string? BuildingName = null,
    string? Category = null);

/// <summary>A finished visit, sent once from the phone; VisitId is made on the phone and reused on retry.</summary>
public sealed record SubmitInspectionRequest(
    Guid VisitId,
    string PeriodCode,
    string PropertyCode,
    string TenantCode,
    DateTime StartedAtUtc,
    DateTime FinishedAtUtc,
    IReadOnlyList<UnitResultRequest> Units,
    string? PersonMet = null,
    /// <summary>A signature image follows with role SIGNATURE.</summary>
    bool HasSignature = false,
    Guid? DeviceId = null,
    decimal? Latitude = null,
    decimal? Longitude = null,
    decimal? GpsAccuracyM = null);

public sealed record SubmitInspectionResponse(Guid VisitId, InspectionState State, int Units, int FlaggedUnits, int PhotosExpected);

public sealed record InspectionImageResponse(Guid ImageId, Guid VisitId, Guid? ResultId, string Role, int SizeBytes, string Sha256);

using MeterReading.Api.Domain;

namespace MeterReading.Api.Contracts;

// Response shapes. Names and casing match what the Android app expects (spec §14).

public sealed record PeriodDto(string Code, DateOnly StartDate, DateOnly EndDate, string Status);

public sealed record MeDto(string ReaderId, string DisplayName, string? TeamCode, PeriodDto? OpenPeriod);

public sealed record ZoneDto(string Code, string? Name);

/// <summary>A tenant of a property (vw_MR_Tenant). The reader checks it before saving a reading (FR-006.12).</summary>
public sealed record TenantDto(string Code, string? CompanyName);

/// <summary>
/// A property. The phone shows <c>CompanyName</c> (the tenant) when there is one, else <c>Name</c>.
/// <c>Tenants</c> are the tenants the reader chooses from on the check screen; empty means none on record.
/// </summary>
public sealed record PropertyDto(
    string Code, string? Name, string ZoneCode, int? RouteSequence, decimal? Latitude, decimal? Longitude,
    string? TenantCode = null, string? CompanyName = null, IReadOnlyList<TenantDto>? Tenants = null);

public sealed record MeterDto(
    /// <summary>The meter's barcode in the source system.</summary>
    string Id,
    string Number,
    string Type,
    string PropertyCode,
    string ZoneCode,
    int? RouteSequence,
    int RegisterDigits,
    int DecimalDigits,
    /// <summary>Last billed reading (the view's OpeningReading); 0 for a meter never read.</summary>
    decimal? PreviousReading,
    /// <summary>Consumption of the last reading, shown as "used last time".</summary>
    decimal? LastConsumption,
    /// <summary>True when the meter has no reading yet (spec BR-004).</summary>
    bool IsFirstReading,
    decimal? AverageConsumption,
    /// <summary>Above this the app warns "much bigger than usual" (BR-008). Null = no warning.</summary>
    decimal? ExpectedHigh,
    AssignmentState State,
    string? SupervisorNote,
    Guid? LastTransactionId);

public sealed record SyncDto(
    PeriodDto Period,
    IReadOnlyList<ZoneDto> Zones,
    IReadOnlyList<PropertyDto> Properties,
    IReadOnlyList<MeterDto> Meters,
    DateTime ServerTimeUtc);

public sealed record PropertyHitDto(PropertyDto Property, IReadOnlyList<MeterDto> Meters, int Done);

public sealed record SearchResultDto(string Query, IReadOnlyList<PropertyHitDto> Properties, IReadOnlyList<MeterDto> Meters);

public sealed record ReadingDto(
    Guid TransactionId,
    string MeterId,
    string? MeterNumber,
    string? MeterType,
    string Condition,
    string? ReasonCode,
    decimal? NewReading,
    decimal? Consumption,
    DateTime CapturedAtUtc,
    DateTime ReceivedAtUtc,
    string Status,
    AssignmentState State,
    string? Note,
    /// <summary>Photos the phone took for this reading, and how many have arrived.</summary>
    int PhotosExpected,
    int PhotosReceived,
    /// <summary>The tenant when the reading was taken, and the sub-tenant the reader typed.</summary>
    string? TenantCode = null,
    string? SubTenant = null);

public sealed record ZoneReconciliationDto(string ZoneCode, int Meters, int Read, int NotRead);

/// <summary>
/// Server side of "My summary" (FR-022). Meters and their buckets cover the zones asked for, read by
/// anyone; ReadByYou counts the signed-in reader's own. The phone adds its own count of readings
/// still waiting to upload. LastReceivedUtc is the reader's latest reading received by the server.
/// </summary>
public sealed record SummaryDto(
    string PeriodCode,
    int Meters,
    int Read,
    int Accepted,
    int Checking,
    int ReadAgain,
    int Revisit,
    int NotRead,
    int ReadByYou,
    /// <summary>Photos of the reader's readings that have not arrived yet.</summary>
    int PhotosWaiting,
    IReadOnlyList<ZoneReconciliationDto> Zones,
    DateTime? LastReceivedUtc);

public sealed record HistoryDto(string PeriodCode, DateOnly? ReadingDate, decimal? ReadingValue, decimal? Consumption, string ConsumptionBasis);

public sealed record MeterDetailDto(MeterDto Meter, PropertyDto Property, IReadOnlyList<HistoryDto> History, IReadOnlyList<ReadingDto> ThisPeriod);

/// <summary>A reading sent by the phone (spec §14.1). TransactionId is made on the phone and reused on retry.</summary>
public sealed record SubmitReadingRequest(
    Guid TransactionId,
    string MeterId,
    /// <summary>WORKING, DAMAGED, SUBMERSED, NOT_ACCESSIBLE, METER_REPLACED or REMOVED.</summary>
    string Condition,
    string? ReasonCode,
    string? Note,
    decimal? NewReading,
    decimal? OldFinalReading,
    string? NewMeterNumber,
    decimal? NewOpeningReading,
    decimal? NewCurrentReading,
    /// <summary>The reader saw the app's warning and said the number is correct.</summary>
    bool ReaderConfirmedWarning,
    DateTime CapturedAtUtc,
    /// <summary>How many photos will follow with PUT /readings/{id}/images/{imageId}.</summary>
    int PhotoCount = 0,
    /// <summary>Sub-tenant name typed by the reader, if the premises has one.</summary>
    string? SubTenant = null,
    Guid? DeviceId = null,
    decimal? Latitude = null,
    decimal? Longitude = null,
    decimal? GpsAccuracyM = null,
    /// <summary>The tenant the reader checked on site; must be a current tenant of the property (FR-006.12).</summary>
    string? TenantCode = null);

/// <summary>What the server did with a reading: ACCEPTED, or EXCEPTION with the reason codes.</summary>
public sealed record SubmitReadingResponse(
    Guid TransactionId,
    string MeterId,
    string Status,
    AssignmentState State,
    decimal? Consumption,
    IReadOnlyList<string> Exceptions);

/// <summary>A stored photo (spec FR-009).</summary>
public sealed record ImageUploadResponse(Guid ImageId, Guid TransactionId, string Role, int SizeBytes, string Sha256);

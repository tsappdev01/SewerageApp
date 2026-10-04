using MeterReading.Api.Domain;

namespace MeterReading.Api.Contracts;

// Response shapes. Names and casing match what the Android app expects (spec §14).

public sealed record PeriodDto(string Code, DateOnly StartDate, DateOnly EndDate, string Status);

public sealed record MeDto(string ReaderId, string DisplayName, string? TeamCode, PeriodDto? OpenPeriod);

public sealed record ZoneDto(string Code, string? Name);

public sealed record PropertyDto(string Code, string? Name, string ZoneCode, int? RouteSequence, decimal? Latitude, decimal? Longitude);

public sealed record MeterDto(
    long Id,
    string Number,
    string Type,
    string PropertyCode,
    string ZoneCode,
    int? RouteSequence,
    int RegisterDigits,
    int DecimalDigits,
    /// <summary>Last approved actual reading, or the opening reading for a new meter.</summary>
    decimal? PreviousReading,
    DateOnly? PreviousReadingDate,
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
    long MeterId,
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
    string? Note);

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
    IReadOnlyList<ZoneReconciliationDto> Zones,
    DateTime? LastReceivedUtc);

public sealed record HistoryDto(string PeriodCode, DateOnly? ReadingDate, decimal? ReadingValue, decimal? Consumption, string ConsumptionBasis);

public sealed record MeterDetailDto(MeterDto Meter, PropertyDto Property, IReadOnlyList<HistoryDto> History, IReadOnlyList<ReadingDto> ThisPeriod);

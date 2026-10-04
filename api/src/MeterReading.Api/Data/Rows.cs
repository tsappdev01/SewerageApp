namespace MeterReading.Api.Data;

// Dapper row types. Every column is CAST in SQL to the type declared here, so a view that
// uses tinyint, numeric or nvarchar still maps without surprises.

public sealed class ReaderRow
{
    public string ReaderId { get; init; } = "";
    public string LoginEmail { get; init; } = "";
    public string DisplayName { get; init; } = "";
    public string? TeamCode { get; init; }
    public string? SupervisorEmail { get; init; }
}

public sealed class PeriodRow
{
    public string PeriodCode { get; init; } = "";
    public DateTime StartDate { get; init; }
    public DateTime EndDate { get; init; }
    public string Status { get; init; } = "";
}

public sealed class MeterRow
{
    public string MeterId { get; init; } = "";
    public string MeterNumber { get; init; } = "";
    public string MeterType { get; init; } = "";
    public string PropertyCode { get; init; } = "";
    public string? PropertyName { get; init; }
    public string? TenantCode { get; init; }
    public string? CompanyName { get; init; }
    public long? PropertyId { get; init; }
    /// <summary>MeterType exactly as the source view gives it, for the readings table's Type column.</summary>
    public string? SourceMeterType { get; init; }
    public int? PropertyRoute { get; init; }
    public decimal? Latitude { get; init; }
    public decimal? Longitude { get; init; }
    public string ZoneCode { get; init; } = "";
    public string? ZoneName { get; init; }
    public int? MeterRoute { get; init; }
    public int RegisterDigits { get; init; }
    public int DecimalDigits { get; init; }
    /// <summary>The meter's last billed reading (the view's OpeningReading). 0 or null: never read.</summary>
    public decimal? LastReading { get; init; }
    public decimal? LastConsumption { get; init; }
    public decimal? AverageConsumption { get; init; }
}

public sealed class LatestTransactionRow
{
    public string MeterId { get; init; } = "";
    public Guid TransactionId { get; init; }
    public string Status { get; init; } = "";
    public string? StatusNote { get; init; }
    public string MeterCondition { get; init; } = "";
    public DateTime ReceivedAtUtc { get; init; }
}

public sealed class TransactionRow
{
    public string ReaderId { get; init; } = "";
    public string? PayloadHash { get; init; }
    public string PeriodCode { get; init; } = "";
    public int? ExpectedPhotos { get; init; }
    public int PhotosReceived { get; init; }
    public string? TenantCode { get; init; }
    public string? SubTenant { get; init; }
    public Guid TransactionId { get; init; }
    public string MeterId { get; init; } = "";
    public string MeterCondition { get; init; } = "";
    public string? ReasonCode { get; init; }
    public decimal? NewReading { get; init; }
    public decimal? Consumption { get; init; }
    public DateTime CapturedAtUtc { get; init; }
    public DateTime ReceivedAtUtc { get; init; }
    public string Status { get; init; } = "";
    public string? StatusNote { get; init; }
}

public sealed class HistoryRow
{
    public string PeriodCode { get; init; } = "";
    public DateTime? ReadingDate { get; init; }
    public decimal? ReadingValue { get; init; }
    public decimal? Consumption { get; init; }
    public string ConsumptionBasis { get; init; } = "";
}

public sealed class AverageRow
{
    public string MeterId { get; init; } = "";
    public decimal Average { get; init; }
}

/// <summary>A reading to insert into mr.ReadingTransaction.</summary>
public sealed class NewTransactionRow
{
    public Guid TransactionId { get; init; }
    public string PeriodCode { get; init; } = "";
    public string MeterId { get; init; } = "";
    public string ReaderId { get; init; } = "";
    public Guid? DeviceId { get; init; }
    public string MeterCondition { get; init; } = "";
    public string? ReasonCode { get; init; }
    public string? Remarks { get; init; }
    public decimal? NewReading { get; init; }
    public decimal? PreviousReading { get; init; }
    public decimal? Consumption { get; init; }
    public decimal? OldFinalReading { get; init; }
    public string? NewMeterNumber { get; init; }
    public decimal? NewOpeningReading { get; init; }
    public decimal? NewCurrentReading { get; init; }
    public decimal? Latitude { get; init; }
    public decimal? Longitude { get; init; }
    public decimal? GpsAccuracyM { get; init; }
    public DateTime CapturedAtUtc { get; init; }
    public string Status { get; init; } = "";
    public string? StatusNote { get; init; }
    public string PayloadHash { get; init; } = "";
    public int ExpectedPhotos { get; init; }
    public long? PropertyId { get; init; }
    public string? PropertyCode { get; init; }
    public string? MeterNumber { get; init; }
    public string? MeterType { get; init; }
    public string? TenantCode { get; init; }
    public string? SubTenant { get; init; }
}

public sealed class ImageRow
{
    public Guid ImageId { get; init; }
    public Guid TransactionId { get; init; }
    public string ImageRole { get; init; } = "";
    public string BlobPath { get; init; } = "";
    public string Sha256 { get; init; } = "";
    public int SizeBytes { get; init; }
    public DateTime CapturedAtUtc { get; init; }
}

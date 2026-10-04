using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using MeterReading.Api.Contracts;
using MeterReading.Api.Data;

namespace MeterReading.Api.Domain;

/// <summary>Outcome of a submission: a stored reading, or a rejection with a problem code.</summary>
public sealed record SubmitOutcome(SubmitReadingResponse? Response, bool Created, int? ErrorStatus = null, string? ErrorCode = null, string? ErrorTitle = null)
{
    public static SubmitOutcome Reject(int status, string code, string title) => new(null, false, status, code, title);
}

/// <summary>
/// Accepts a reading from the phone (spec §7.2). Rejections store nothing. Accepted readings are
/// ACCEPTED, or EXCEPTION when a rule needs a supervisor; the reason codes go in StatusNote.
/// A repeat with the same TransactionId and payload returns the stored result (BR-013).
/// </summary>
public sealed class SubmitService(MeterReadingRepository repo, ReaderService readers, TimeProvider clock, Microsoft.Extensions.Options.IOptions<ImageStoreOptions> images)
{
    private int MaxImages => images.Value.MaxImagesPerReading;

    private static readonly HashSet<string> Conditions = ["WORKING", "DAMAGED", "SUBMERSED", "NOT_ACCESSIBLE", "METER_REPLACED", "REMOVED"];
    private static readonly HashSet<string> NeedReason = ["DAMAGED", "NOT_ACCESSIBLE", "METER_REPLACED", "REMOVED"];
    private static readonly HashSet<string> NeedNote = ["DAMAGED", "SUBMERSED", "NOT_ACCESSIBLE", "REMOVED"];
    private static readonly TimeSpan ClockSkew = TimeSpan.FromMinutes(5);
    private static readonly TimeSpan MaxQueueAge = TimeSpan.FromDays(7);

    public async Task<SubmitOutcome> SubmitAsync(ReaderRow reader, SubmitReadingRequest r, CancellationToken ct)
    {
        if (r.TransactionId == Guid.Empty || string.IsNullOrWhiteSpace(r.MeterId) || r.MeterId.Length > 50)
            return SubmitOutcome.Reject(422, "VALIDATION_FAILED", "TransactionId and MeterId are required.");
        if (r.PhotoCount < 0 || r.PhotoCount > MaxImages)
            return SubmitOutcome.Reject(422, "VALIDATION_FAILED", $"A reading can have at most {MaxImages} photos.");
        if (!Conditions.Contains(r.Condition))
            return SubmitOutcome.Reject(422, "INVALID_LOV_CODE", $"Unknown meter condition '{r.Condition}'.");

        var hash = PayloadHash(r);
        var existing = await repo.GetTransactionAsync(r.TransactionId, ct);
        if (existing is not null)
        {
            return existing.PayloadHash == hash && existing.ReaderId == reader.ReaderId
                ? new SubmitOutcome(ToResponse(existing), Created: false)
                : SubmitOutcome.Reject(409, "TRANSACTION_ID_REUSED", "This transaction id was already used for a different reading.");
        }

        var period = await repo.GetOpenPeriodAsync(ct);
        if (period is null) return SubmitOutcome.Reject(409, "NO_OPEN_PERIOD", "There is no open reading period.");

        var now = clock.GetUtcNow().UtcDateTime;
        var captured = DateTime.SpecifyKind(r.CapturedAtUtc, DateTimeKind.Utc);
        if (captured > now + ClockSkew || captured < now - MaxQueueAge)
            return SubmitOutcome.Reject(422, "CAPTURE_TIME_INVALID", "The phone's time is wrong or the reading is more than 7 days old.");

        var meter = (await readers.LoadAsync(period.PeriodCode, new MeterFilter(MeterIds: [r.MeterId]), ct)).Meters.SingleOrDefault();
        if (meter is null) return SubmitOutcome.Reject(404, "METER_NOT_FOUND", "There is no active meter with this id.");

        // FR-006.12: the reader checked the tenant on site, and it is still a tenant of the property.
        var tenants = await repo.GetTenantsAsync([meter.PropertyCode], ct);
        if (tenants.Count == 0)
            return SubmitOutcome.Reject(422, "NO_TENANT", "This property has no tenant on record. Tell your supervisor.");
        if (string.IsNullOrWhiteSpace(r.TenantCode))
            return SubmitOutcome.Reject(422, "TENANT_NOT_CONFIRMED", "Check the tenant before saving the reading.");
        var tenant = tenants.FirstOrDefault(t => string.Equals(t.TenantCode, r.TenantCode.Trim(), StringComparison.OrdinalIgnoreCase));
        if (tenant is null)
            return SubmitOutcome.Reject(409, "TENANT_CHANGED", "The tenant of this property has changed. Refresh and check the tenant again.");

        if (r.SubTenant is { Length: > 100 })
            return SubmitOutcome.Reject(422, "VALIDATION_FAILED", "The sub-tenant name can be at most 100 characters.");
        if (Missing(r) is { } missing)
            return SubmitOutcome.Reject(422, "MANDATORY_FIELD_MISSING", $"{missing} is needed for a {r.Condition} meter.");
        foreach (var value in new[] { r.NewReading, r.OldFinalReading, r.NewOpeningReading, r.NewCurrentReading })
        {
            if (value is < 0) return SubmitOutcome.Reject(422, "VALIDATION_FAILED", "Readings cannot be negative.");
            if (value is { } v && Consumption.WholeDigits(v) > meter.RegisterDigits)
                return SubmitOutcome.Reject(422, "READING_EXCEEDS_REGISTER", $"The meter has {meter.RegisterDigits} digits.");
        }

        // Property and meter as they are now, so the reading keeps them if they change later.
        var source = (await repo.GetMetersAsync(new MeterFilter(MeterIds: [meter.Id]), ct)).Single();
        var (consumption, exceptions) = Evaluate(r, meter);
        var row = new NewTransactionRow
        {
            TransactionId = r.TransactionId,
            PeriodCode = period.PeriodCode,
            MeterId = meter.Id,
            ReaderId = reader.ReaderId,
            DeviceId = r.DeviceId,
            MeterCondition = r.Condition,
            ReasonCode = Trimmed(r.ReasonCode, 30),
            Remarks = Trimmed(r.Note, 500),
            NewReading = r.NewReading,
            PreviousReading = meter.PreviousReading,
            Consumption = consumption,
            OldFinalReading = r.OldFinalReading,
            NewMeterNumber = Trimmed(r.NewMeterNumber, 30),
            NewOpeningReading = r.NewOpeningReading,
            NewCurrentReading = r.NewCurrentReading,
            Latitude = r.Latitude,
            Longitude = r.Longitude,
            GpsAccuracyM = r.GpsAccuracyM,
            CapturedAtUtc = captured,
            Status = exceptions.Count > 0 ? "EXCEPTION" : "ACCEPTED",
            StatusNote = exceptions.Count > 0 ? string.Join(',', exceptions) : null,
            PayloadHash = hash,
            ExpectedPhotos = r.PhotoCount,
            PropertyId = source.PropertyId,
            PropertyCode = source.PropertyCode,
            MeterNumber = source.MeterNumber,
            MeterType = source.SourceMeterType,
            TenantCode = tenant.TenantCode,
            SubTenant = Trimmed(r.SubTenant, 100),
        };

        // ALREADY_READ is checked under a lock on the meter's rows, so two phones cannot both read it.
        var stored = await repo.InsertTransactionUnlessReadAsync(row, ct);
        if (!stored) return SubmitOutcome.Reject(409, "ALREADY_READ", "This meter was already read this period.");
        return new SubmitOutcome(ToResponse(await repo.GetTransactionAsync(r.TransactionId, ct) ?? throw new InvalidOperationException("Inserted row not found.")), Created: true);
    }

    /// <summary>Consumption and exception codes (spec §6, §7.2).</summary>
    public static (decimal? Consumption, List<string> Exceptions) Evaluate(SubmitReadingRequest r, MeterDto meter)
    {
        var exceptions = new List<string>();
        decimal? consumption = null;
        var previous = meter.PreviousReading ?? 0m;

        void Add(Consumption.Result result)
        {
            switch (result.Kind)
            {
                case Domain.Consumption.Kind.Lower: exceptions.Add("LOWER_THAN_PREVIOUS"); break;
                case Domain.Consumption.Kind.High: exceptions.Add("HIGH_CONSUMPTION"); break;
                case Domain.Consumption.Kind.RolloverHigh: exceptions.Add("ROLLOVER_OUT_OF_RANGE"); break;
            }
        }

        switch (r.Condition)
        {
            case "METER_REPLACED":
                var old = Domain.Consumption.Check(previous, r.OldFinalReading!.Value, meter.RegisterDigits, null);
                Add(old);
                if (old.Value is { } o) consumption = o + (r.NewCurrentReading!.Value - r.NewOpeningReading!.Value);
                exceptions.Add("METER_REPLACEMENT");
                break;
            case "NOT_ACCESSIBLE":
                break;
            default:
                if (r.NewReading is { } reading)
                {
                    var check = Domain.Consumption.Check(previous, reading, meter.RegisterDigits, meter.ExpectedHigh);
                    Add(check);
                    consumption = check.Value;
                }
                if (r.Condition == "DAMAGED") exceptions.Add("DAMAGED_METER");
                if (r.Condition == "REMOVED") exceptions.Add("METER_REMOVAL");
                break;
        }
        return (consumption, exceptions.Distinct().ToList());
    }

    private static string? Missing(SubmitReadingRequest r)
    {
        if (r.Condition == "WORKING" && r.NewReading is null) return "The reading";
        if (r.Condition == "METER_REPLACED")
        {
            if (r.OldFinalReading is null) return "The old meter's last reading";
            if (string.IsNullOrWhiteSpace(r.NewMeterNumber)) return "The new meter number";
            if (r.NewOpeningReading is null) return "The new meter's first reading";
            if (r.NewCurrentReading is null) return "The new meter's reading now";
        }
        if (NeedReason.Contains(r.Condition) && string.IsNullOrWhiteSpace(r.ReasonCode)) return "A reason";
        if (NeedNote.Contains(r.Condition) && string.IsNullOrWhiteSpace(r.Note)) return "A note";
        return null;
    }

    private static string? Trimmed(string? text, int max) =>
        string.IsNullOrWhiteSpace(text) ? null : text.Trim() is var t && t.Length > max ? t[..max] : text.Trim();

    /// <summary>SHA-256 of the request as the server reads it, so a retry of the same reading matches.</summary>
    public static string PayloadHash(SubmitReadingRequest r) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(r with { CapturedAtUtc = DateTime.SpecifyKind(r.CapturedAtUtc, DateTimeKind.Utc) }))));

    private static SubmitReadingResponse ToResponse(TransactionRow t) => new(
        t.TransactionId, t.MeterId, t.Status, AssignmentStates.From(t.Status, t.MeterCondition), t.Consumption,
        t.Status == "EXCEPTION" && t.StatusNote is { } note ? note.Split(',') : []);
}

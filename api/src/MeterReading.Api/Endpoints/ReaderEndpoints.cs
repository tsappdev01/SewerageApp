using System.Text.RegularExpressions;
using MeterReading.Api.Auth;
using MeterReading.Api.Contracts;
using MeterReading.Api.Data;
using MeterReading.Api.Domain;

namespace MeterReading.Api.Endpoints;

/// <summary>
/// Read endpoints for the Meter Reader app (spec §14.1). Work is not assigned: every active reader
/// sees every active meter, narrowed by zone. "My readings" and "read by you" are the reader's own.
/// </summary>
public static partial class ReaderEndpoints
{
    public const string Policy = "Reader";

    public static void MapReaderEndpoints(this IEndpointRouteBuilder app)
    {
        var api = app.MapGroup("/api/v1").RequireAuthorization(Policy).WithTags("Meter reader");

        api.MapGet("/me", GetMe).WithSummary("Signed-in reader and the open reading period.");
        api.MapGet("/sync/meters", GetMeters).WithSummary("Zones, properties and meters to read this period, with each meter's state. ?zone=597,598 narrows it.");
        api.MapGet("/properties/search", SearchProperties).WithSummary("Find a Property by code, name or meter number.");
        api.MapGet("/readings/mine", GetMyReadings).WithSummary("The reader's own submissions in a period.");
        api.MapGet("/summary", GetSummary).WithSummary("Reconciliation: meters, read, read by you, not read. ?zone=597,598 narrows it.");
        api.MapGet("/meters/{meterId}", GetMeter).WithSummary("One meter with its reading history.");
    }

    private static async Task<IResult> GetMe(CurrentReader current, MeterReadingRepository repo, CancellationToken ct)
    {
        var (reader, readerProblem) = await current.ResolveAsync(ct);
        if (readerProblem is not null) return readerProblem;
        var period = await repo.GetOpenPeriodAsync(ct);
        return Results.Ok(new MeDto(reader!.ReaderId, reader!.DisplayName, reader!.TeamCode, period is null ? null : ToDto(period)));
    }

    private static async Task<IResult> GetMeters(
        CurrentReader current, MeterReadingRepository repo, ReaderService service, string? zone, CancellationToken ct)
    {
        if (!TryParseZones(zone, out var zones)) return Problems.Invalid("zone must be zone codes separated by commas, e.g. 597,598.");
        var (reader, readerProblem) = await current.ResolveAsync(ct);
        if (readerProblem is not null) return readerProblem;
        var period = await repo.GetOpenPeriodAsync(ct);
        if (period is null) return Problems.NoOpenPeriod();
        var set = await service.LoadAsync(period.PeriodCode, new MeterFilter(ZoneCodes: zones), ct);
        return Results.Ok(new SyncDto(ToDto(period), set.Zones, set.Properties, set.Meters, DateTime.UtcNow));
    }

    private static async Task<IResult> SearchProperties(
        CurrentReader current, MeterReadingRepository repo, ReaderService service,
        string? q, string? zone, string? done, string? type, CancellationToken ct)
    {
        if (q is { Length: > 30 }) return Problems.Invalid("Search text can be at most 30 characters.");
        if (!TryParseZones(zone, out var zones)) return Problems.Invalid("zone must be zone codes separated by commas, e.g. 597,598.");
        var doneFilter = DoneFilter.ALL;
        if (done is not null && !Enum.TryParse(done, ignoreCase: true, out doneFilter))
            return Problems.Invalid("done must be ALL, TO_READ or DONE.");
        if (type is not null && type.ToUpperInvariant() is not ("IRRIGATION" or "SEWERAGE"))
            return Problems.Invalid("type must be IRRIGATION or SEWERAGE.");

        var (reader, readerProblem) = await current.ResolveAsync(ct);
        if (readerProblem is not null) return readerProblem;
        var period = await repo.GetOpenPeriodAsync(ct);
        if (period is null) return Problems.NoOpenPeriod();
        var set = await service.LoadAsync(period.PeriodCode, new MeterFilter(ZoneCodes: zones), ct);
        return Results.Ok(ReaderService.Search(set, q, zone: null, doneFilter, type?.ToUpperInvariant()));
    }

    private static async Task<IResult> GetMyReadings(
        CurrentReader current, MeterReadingRepository repo, ReaderService service, string? period, CancellationToken ct)
    {
        var (reader, readerProblem) = await current.ResolveAsync(ct);
        if (readerProblem is not null) return readerProblem;
        var (p, problem) = await ResolvePeriodAsync(repo, period, ct);
        if (problem is not null) return problem;

        var readings = await repo.GetReaderTransactionsAsync(reader!.ReaderId, p!.PeriodCode, ct);
        var ids = readings.Select(r => r.MeterId).Distinct(StringComparer.Ordinal).ToArray();
        var meters = ids.Length == 0
            ? new Dictionary<string, MeterDto>()
            : (await service.LoadAsync(p.PeriodCode, new MeterFilter(MeterIds: ids), ct)).Meters.ToDictionary(m => m.Id, StringComparer.Ordinal);
        return Results.Ok(readings.Select(r => ToDto(r, meters.GetValueOrDefault(r.MeterId))).ToList());
    }

    private static async Task<IResult> GetSummary(
        CurrentReader current, MeterReadingRepository repo, ReaderService service, string? period, string? zone, CancellationToken ct)
    {
        if (!TryParseZones(zone, out var zones)) return Problems.Invalid("zone must be zone codes separated by commas, e.g. 597,598.");
        var (reader, readerProblem) = await current.ResolveAsync(ct);
        if (readerProblem is not null) return readerProblem;
        var (p, problem) = await ResolvePeriodAsync(repo, period, ct);
        if (problem is not null) return problem;

        var set = await service.LoadAsync(p!.PeriodCode, new MeterFilter(ZoneCodes: zones), ct);
        var inScope = set.Meters.Select(m => m.Id).ToHashSet(StringComparer.Ordinal);
        var mine = (await repo.GetReaderTransactionsAsync(reader!.ReaderId, p.PeriodCode, ct))
            .Where(r => inScope.Contains(r.MeterId)).ToList();
        return Results.Ok(ReaderService.Summarize(p.PeriodCode, set, mine));
    }

    private static async Task<IResult> GetMeter(
        string meterId, CurrentReader current, MeterReadingRepository repo, ReaderService service, CancellationToken ct)
    {
        if (meterId.Length > 50) return Problems.Invalid("Meter id can be at most 50 characters.");
        var (reader, readerProblem) = await current.ResolveAsync(ct);
        if (readerProblem is not null) return readerProblem;
        var period = await repo.GetOpenPeriodAsync(ct);
        if (period is null) return Problems.NoOpenPeriod();

        var set = await service.LoadAsync(period.PeriodCode, new MeterFilter(MeterIds: [meterId]), ct);
        var meter = set.Meters.SingleOrDefault();
        if (meter is null) return Problems.Of(StatusCodes.Status404NotFound, "METER_NOT_FOUND", "There is no active meter with this id.");

        var history = await repo.GetHistoryAsync(meterId, 12, ct);
        var thisPeriod = await repo.GetMeterTransactionsAsync(meterId, period.PeriodCode, ct);
        return Results.Ok(new MeterDetailDto(
            meter,
            set.Properties.Single(),
            history.Select(h => new HistoryDto(h.PeriodCode, h.ReadingDate is { } d ? DateOnly.FromDateTime(d) : null, h.ReadingValue, h.Consumption, h.ConsumptionBasis)).ToList(),
            thisPeriod.Select(t => ToDto(t, meter)).ToList()));
    }

    /// <summary>"597,598" to ["597", "598"]; empty means all zones.</summary>
    private static bool TryParseZones(string? text, out IReadOnlyCollection<string>? zones)
    {
        zones = null;
        if (string.IsNullOrWhiteSpace(text)) return true;
        var parts = text.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        if (parts.Length is 0 or > 50 || parts.Any(z => !ZoneCode().IsMatch(z))) return false;
        zones = parts.Distinct(StringComparer.Ordinal).ToArray();
        return true;
    }

    private static async Task<(PeriodRow? Period, IResult? Problem)> ResolvePeriodAsync(MeterReadingRepository repo, string? code, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(code))
        {
            var open = await repo.GetOpenPeriodAsync(ct);
            return open is null ? (null, Problems.NoOpenPeriod()) : (open, null);
        }
        if (!PeriodCode().IsMatch(code)) return (null, Problems.Invalid("period must look like 2026-10."));
        var found = await repo.GetPeriodAsync(code, ct);
        return found is null ? (null, Problems.PeriodNotFound(code)) : (found, null);
    }

    private static PeriodDto ToDto(PeriodRow p) =>
        new(p.PeriodCode, DateOnly.FromDateTime(p.StartDate), DateOnly.FromDateTime(p.EndDate), p.Status);

    private static ReadingDto ToDto(TransactionRow r, MeterDto? meter) => new(
        r.TransactionId, r.MeterId, meter?.Number, meter?.Type, r.MeterCondition, r.ReasonCode, r.NewReading, r.Consumption,
        DateTime.SpecifyKind(r.CapturedAtUtc, DateTimeKind.Utc), DateTime.SpecifyKind(r.ReceivedAtUtc, DateTimeKind.Utc),
        r.Status, AssignmentStates.From(r.Status, r.MeterCondition),
        r.Status is "REJECTED_BY_SUPERVISOR" or "EXCEPTION" ? r.StatusNote : null,
        r.ExpectedPhotos ?? 0, r.PhotosReceived, r.TenantCode, r.SubTenant);

    [GeneratedRegex(@"^\d{4}-(0[1-9]|1[0-2])$")]
    private static partial Regex PeriodCode();

    [GeneratedRegex(@"^[A-Za-z0-9-]{1,20}$")]
    private static partial Regex ZoneCode();
}

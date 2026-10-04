using System.Text.RegularExpressions;
using MeterReading.Api.Auth;
using MeterReading.Api.Contracts;
using MeterReading.Api.Data;
using MeterReading.Api.Domain;

namespace MeterReading.Api.Endpoints;

/// <summary>Read endpoints for the Meter Reader app (spec §14.1). Every call is scoped to the signed-in reader.</summary>
public static partial class ReaderEndpoints
{
    public const string Policy = "Reader";

    public static void MapReaderEndpoints(this IEndpointRouteBuilder app)
    {
        var api = app.MapGroup("/api/v1").RequireAuthorization(Policy).WithTags("Meter reader");

        api.MapGet("/me", GetMe).WithSummary("Signed-in reader and the open reading period.");
        api.MapGet("/sync/assignments", GetAssignments).WithSummary("Zones, properties and meters to read this period, with each meter's state.");
        api.MapGet("/properties/search", SearchProperties).WithSummary("Find a Property by code, name or meter number.");
        api.MapGet("/readings/mine", GetMyReadings).WithSummary("The reader's submissions in a period.");
        api.MapGet("/summary", GetSummary).WithSummary("Reconciliation: meters assigned, read and received by the server.");
        api.MapGet("/meters/{meterId:long}", GetMeter).WithSummary("One assigned meter with its reading history.");
    }

    private static async Task<IResult> GetMe(CurrentReader current, MeterReadingRepository repo, CancellationToken ct)
    {
        var reader = await current.GetAsync(ct);
        if (reader is null) return Problems.ReaderNotFound();
        var period = await repo.GetOpenPeriodAsync(ct);
        return Results.Ok(new MeDto(reader.ReaderId, reader.DisplayName, reader.TeamCode, period is null ? null : ToDto(period)));
    }

    private static async Task<IResult> GetAssignments(CurrentReader current, MeterReadingRepository repo, ReaderService service, CancellationToken ct)
    {
        var reader = await current.GetAsync(ct);
        if (reader is null) return Problems.ReaderNotFound();
        var period = await repo.GetOpenPeriodAsync(ct);
        if (period is null) return Problems.NoOpenPeriod();
        var work = await service.LoadAsync(reader.ReaderId, period.PeriodCode, ct);
        return Results.Ok(new SyncDto(ToDto(period), work.Zones, work.Properties, work.Meters, DateTime.UtcNow));
    }

    private static async Task<IResult> SearchProperties(
        CurrentReader current, MeterReadingRepository repo, ReaderService service,
        string? q, string? zone, string? done, string? type, CancellationToken ct)
    {
        if (q is { Length: > 30 }) return Problems.Invalid("Search text can be at most 30 characters.");
        var doneFilter = DoneFilter.ALL;
        if (done is not null && !Enum.TryParse(done, ignoreCase: true, out doneFilter))
            return Problems.Invalid("done must be ALL, TO_READ or DONE.");
        if (type is not null && type.ToUpperInvariant() is not ("IRRIGATION" or "SEWERAGE"))
            return Problems.Invalid("type must be IRRIGATION or SEWERAGE.");

        var reader = await current.GetAsync(ct);
        if (reader is null) return Problems.ReaderNotFound();
        var period = await repo.GetOpenPeriodAsync(ct);
        if (period is null) return Problems.NoOpenPeriod();
        var work = await service.LoadAsync(reader.ReaderId, period.PeriodCode, ct);
        return Results.Ok(ReaderService.Search(work, q, string.IsNullOrWhiteSpace(zone) ? null : zone, doneFilter, type?.ToUpperInvariant()));
    }

    private static async Task<IResult> GetMyReadings(
        CurrentReader current, MeterReadingRepository repo, ReaderService service, string? period, CancellationToken ct)
    {
        var reader = await current.GetAsync(ct);
        if (reader is null) return Problems.ReaderNotFound();
        var (p, problem) = await ResolvePeriodAsync(repo, period, ct);
        if (problem is not null) return problem;

        var readings = await repo.GetReaderTransactionsAsync(reader.ReaderId, p!.PeriodCode, ct);
        var meters = (await service.LoadAsync(reader.ReaderId, p.PeriodCode, ct)).Meters.ToDictionary(m => m.Id);
        return Results.Ok(readings.Select(r => ToDto(r, meters.GetValueOrDefault(r.MeterId))).ToList());
    }

    private static async Task<IResult> GetSummary(
        CurrentReader current, MeterReadingRepository repo, ReaderService service, string? period, CancellationToken ct)
    {
        var reader = await current.GetAsync(ct);
        if (reader is null) return Problems.ReaderNotFound();
        var (p, problem) = await ResolvePeriodAsync(repo, period, ct);
        if (problem is not null) return problem;

        var work = await service.LoadAsync(reader.ReaderId, p!.PeriodCode, ct);
        var received = await repo.GetReaderTransactionsAsync(reader.ReaderId, p.PeriodCode, ct);
        return Results.Ok(ReaderService.Summarize(p.PeriodCode, work, received));
    }

    private static async Task<IResult> GetMeter(
        long meterId, CurrentReader current, MeterReadingRepository repo, ReaderService service, CancellationToken ct)
    {
        var reader = await current.GetAsync(ct);
        if (reader is null) return Problems.ReaderNotFound();
        var period = await repo.GetOpenPeriodAsync(ct);
        if (period is null) return Problems.NoOpenPeriod();

        var work = await service.LoadAsync(reader.ReaderId, period.PeriodCode, ct);
        var meter = work.Meters.FirstOrDefault(m => m.Id == meterId);
        // Same answer for "does not exist" and "not yours", so ids cannot be probed.
        if (meter is null) return Problems.Of(StatusCodes.Status404NotFound, "METER_NOT_ASSIGNED", "This meter is not assigned to you.");

        var history = await repo.GetHistoryAsync(meterId, 12, ct);
        var thisPeriod = await repo.GetMeterTransactionsAsync(meterId, period.PeriodCode, ct);
        return Results.Ok(new MeterDetailDto(
            meter,
            work.Properties.First(p => p.Code == meter.PropertyCode),
            history.Select(h => new HistoryDto(h.PeriodCode, h.ReadingDate is { } d ? DateOnly.FromDateTime(d) : null, h.ReadingValue, h.Consumption, h.ConsumptionBasis)).ToList(),
            thisPeriod.Select(t => ToDto(t, meter)).ToList()));
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
        r.Status is "REJECTED_BY_SUPERVISOR" or "EXCEPTION" ? r.StatusNote : null);

    [GeneratedRegex(@"^\d{4}-(0[1-9]|1[0-2])$")]
    private static partial Regex PeriodCode();
}

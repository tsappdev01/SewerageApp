using MeterReading.Api.Contracts;
using MeterReading.Api.Data;

namespace MeterReading.Api.Domain;

/// <summary>One meter of the property as it goes into mr.MaintainFinalizedMeter.</summary>
public sealed record FinalizeMeter(
    string MeterId, string Number, string Type, decimal Previous, decimal Current, decimal Consumption, Guid? TransactionId,
    decimal? FloatValue = null, decimal? RateFactor = null)
{
    /// <summary>FR-037.5: what the consumption costs.</summary>
    public decimal Amount => FinalizeService.Amount(Consumption, FloatValue, RateFactor);
}

/// <summary>A sewerage meter and an irrigation meter side by side; an empty side is meter number "0" and 0s.</summary>
public sealed record FinalizedPair(FinalizeMeter? Sewerage, FinalizeMeter? Irrigation);

/// <summary>
/// FR-037: "Finalize property". Once every meter of the property is read this period, its readings
/// are written to mr.MaintainFinalizedMeter, one row per meter number (FR-037.3).
/// </summary>
public sealed class FinalizeService(MeterReadingRepository repo, ReaderService readers)
{
    public sealed record Outcome(FinalizedPropertyDto? Result, int ErrorStatus = 0, string? ErrorCode = null, string? ErrorTitle = null);

    /// <summary>The 1.2 in DIP's pricing (2026-10-18).</summary>
    public const decimal AmountMultiplier = 1.2m;

    /// <summary>
    /// FR-037.5: Consumption * FloatValue * 1.2 * (RateFactor / 100), rounded to 2 decimals (half away from
    /// zero). 0 when the meter has no FloatValue or RateFactor in vw_MR_Meter.
    /// </summary>
    public static decimal Amount(decimal consumption, decimal? floatValue, decimal? rateFactor) =>
        floatValue is { } f && rateFactor is { } r
            ? Math.Round(consumption * f * AmountMultiplier * (r / 100m), 2, MidpointRounding.AwayFromZero)
            : 0m;

    /// <summary>FR-037.2: a meter counts as read once its reading is stored (sent, or "visit again").</summary>
    public static bool IsRead(AssignmentState s) => s is AssignmentState.SENT or AssignmentState.REVISIT;

    /// <summary>
    /// FR-037.3: the sewerage and irrigation meters with the same number share a row; a meter with no
    /// partner of the other type has a row of its own. Rows in meter-number order; two meters of one type
    /// with the same number take a row each.
    /// </summary>
    public static IReadOnlyList<FinalizedPair> Pair(IEnumerable<FinalizeMeter> meters) =>
        meters
            .GroupBy(m => m.Number.Trim(), StringComparer.OrdinalIgnoreCase)
            .OrderBy(g => g.Key, StringComparer.Ordinal)
            .SelectMany(g =>
            {
                var s = g.Where(m => m.Type == "SEWERAGE").OrderBy(m => m.MeterId, StringComparer.Ordinal).ToList();
                var i = g.Where(m => m.Type == "IRRIGATION").OrderBy(m => m.MeterId, StringComparer.Ordinal).ToList();
                return Enumerable.Range(0, Math.Max(s.Count, i.Count))
                    .Select(n => new FinalizedPair(n < s.Count ? s[n] : null, n < i.Count ? i[n] : null));
            })
            .ToList();

    public async Task<Outcome> FinalizeAsync(ReaderRow reader, Guid? deviceId, string propertyCode, CancellationToken ct)
    {
        var period = await repo.GetOpenPeriodAsync(ct);
        if (period is null) return new(null, StatusCodes.Status409Conflict, "NO_OPEN_PERIOD", "There is no open reading period.");

        var set = await readers.LoadAsync(period.PeriodCode, new MeterFilter(), ct);
        var meters = set.Meters.Where(m => string.Equals(m.PropertyCode, propertyCode, StringComparison.OrdinalIgnoreCase)).ToList();
        if (meters.Count == 0)
            return new(null, StatusCodes.Status404NotFound, "PROPERTY_NOT_FOUND", "There is no active property with this code.");

        var left = meters.Count(m => !IsRead(m.State));
        if (left > 0)
            return new(null, StatusCodes.Status409Conflict, "PROPERTY_NOT_COMPLETE",
                left == 1 ? "1 meter of this property is still to read." : $"{left} meters of this property are still to read.");

        var ids = meters.Select(m => m.Id).ToArray();
        var readings = await repo.GetFinalizeReadingsAsync(period.PeriodCode, ids, ct);
        var source = await readers.SourceRowsAsync(ids, ct);
        var rows = Pair(meters.Select(m =>
        {
            readings.TryGetValue(m.Id, out var t);
            source.TryGetValue(m.Id, out var s);
            return new FinalizeMeter(m.Id, m.Number, m.Type,
                t?.PreviousReading ?? m.PreviousReading ?? 0m,
                t?.CurrentReading ?? 0m,
                t?.Consumption ?? 0m,
                t?.TransactionId,
                s?.FloatValue, s?.RateFactor);
        }));
        var code = meters[0].PropertyCode;
        var propertyId = readings.Values.Select(r => r.PropertyId).FirstOrDefault(id => id is not null);

        var at = await repo.InsertFinalizedAsync(period.PeriodCode, code, propertyId, rows, reader.ReaderId, deviceId, ct);
        return new(new FinalizedPropertyDto(period.PeriodCode, code, propertyId, DateTime.SpecifyKind(at, DateTimeKind.Utc),
            rows.Select(ToDto).ToList()));
    }

    private static FinalizedMeterDto ToDto(FinalizedPair p) => new(
        p.Sewerage?.Number ?? "0", p.Sewerage?.Previous ?? 0m, p.Sewerage?.Current ?? 0m, p.Sewerage?.Consumption ?? 0m,
        p.Irrigation?.Number ?? "0", p.Irrigation?.Previous ?? 0m, p.Irrigation?.Current ?? 0m, p.Irrigation?.Consumption ?? 0m,
        p.Sewerage?.Amount ?? 0m, p.Irrigation?.Amount ?? 0m);
}

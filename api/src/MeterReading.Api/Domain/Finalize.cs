using MeterReading.Api.Contracts;
using MeterReading.Api.Data;

namespace MeterReading.Api.Domain;

/// <summary>One meter of the property as it goes into mr.MaintainFinalizedMeter.</summary>
public sealed record FinalizeMeter(
    string MeterId, string Number, string Type, decimal Previous, decimal Current, decimal Consumption, Guid? TransactionId,
    decimal? FloatValue = null, decimal? RateFactor = null, bool NoCharge = false, string? TranCode = null, string? BaanCode = null, string? TenantCode = null)
{
    /// <summary>FR-037.5: what the consumption costs; 0 when not charged (FR-037.7).</summary>
    public decimal Amount => NoCharge ? 0m : FinalizeService.Amount(Consumption, FloatValue, RateFactor);
}

/// <summary>A sewerage meter and an irrigation meter side by side; an empty side is meter number "0" and 0s.</summary>
public sealed record FinalizedPair(FinalizeMeter? Sewerage, FinalizeMeter? Irrigation)
{
    public decimal SewerageAmount => Sewerage?.Amount ?? 0m;
    /// <summary>FR-037.5: the irrigation consumption priced, exclusive of VAT, before the FR-037.9 minimum.</summary>
    public decimal IrrigationAmountCalculated => Irrigation?.Amount ?? 0m;
    /// <summary>FR-037.9: what irrigation is billed; 0 with no irrigation meter on the row or when not charged (FR-037.7).</summary>
    public decimal IrrigationAmount => Irrigation is null || Irrigation.NoCharge ? 0m : FinalizeService.IrrigationBilled(IrrigationAmountCalculated, TenPercentAmount);
    public decimal SewerageVat => FinalizeService.Vat(SewerageAmount);
    public decimal IrrigationVat => FinalizeService.Vat(IrrigationAmount);
    /// <summary>FR-037.6: both amounts and both VATs.</summary>
    public decimal TotalAmount => SewerageAmount + IrrigationAmount + SewerageVat + IrrigationVat;
    /// <summary>FR-037.8: 10% of the sewerage amount (before VAT); the base of the irrigation minimum (FR-037.9).</summary>
    public decimal TenPercentAmount => Math.Round(SewerageAmount * 0.10m, 2, MidpointRounding.AwayFromZero);
    /// <summary>FR-037.10: the TranCode kept with the row's readings (sewerage first).</summary>
    public string? TranCode => Sewerage?.TranCode ?? Irrigation?.TranCode;
    /// <summary>FR-037.10: the tenant's BaanCode kept with the row's readings (sewerage first).</summary>
    public string? BaanCode => Sewerage?.BaanCode ?? Irrigation?.BaanCode;
    /// <summary>FR-037.10: the tenant the row's readings were taken for (sewerage first).</summary>
    public string? TenantCode => Sewerage?.TenantCode ?? Irrigation?.TenantCode;
    /// <summary>FR-037.7: the irrigation side is not charged.</summary>
    public bool IrrigationNoCharge => Irrigation?.NoCharge ?? false;
}

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

    /// <summary>FR-037.10: the first day of the billing month (PeriodCode yyyy-MM).</summary>
    public static DateOnly FromDate(string periodCode) =>
        new(int.Parse(periodCode[..4]), int.Parse(periodCode.Substring(5, 2)), 1);

    /// <summary>FR-037.10: the last day of the billing month.</summary>
    public static DateOnly EndDate(string periodCode) => FromDate(periodCode).AddMonths(1).AddDays(-1);

    /// <summary>
    /// FR-037.10: the tenant of a reading; a reading without one (stored before tenants were checked) takes the
    /// property's tenant when it has exactly one.
    /// </summary>
    public static string? TenantFor(string? readingTenant, IReadOnlyList<TenantRow> tenants) =>
        !string.IsNullOrWhiteSpace(readingTenant) ? readingTenant.Trim()
        : tenants.Select(t => t.TenantCode).Distinct(StringComparer.OrdinalIgnoreCase).Count() == 1 ? tenants[0].TenantCode
        : null;

    /// <summary>The cap of the irrigation minimum (FR-037.9).</summary>
    public const decimal IrrigationMinimumCap = 500m;

    /// <summary>
    /// FR-037.9, DIP's rule of 2026-10-18 as given: the calculated irrigation amount (exclusive of VAT) is billed
    /// when it is above 10% of the sewerage amount or above 500; otherwise 10% of the sewerage amount, at most 500.
    /// In short: max(calculated, min(tenPercent, 500)).
    /// </summary>
    public static decimal IrrigationBilled(decimal calculated, decimal tenPercentOfSewerage)
    {
        if (calculated > tenPercentOfSewerage || calculated > IrrigationMinimumCap) return calculated;
        if (tenPercentOfSewerage <= IrrigationMinimumCap) return tenPercentOfSewerage;
        return tenPercentOfSewerage > 0 ? IrrigationMinimumCap : calculated;
    }

    /// <summary>FR-037.6: VAT, 5% of each amount (DIP 2026-10-18).</summary>
    public const decimal VatRate = 0.05m;

    public static decimal Vat(decimal amount) => Math.Round(amount * VatRate, 2, MidpointRounding.AwayFromZero);

    /// <summary>
    /// FR-037.7: an irrigation reading of a property in mr.IrrigationNoCharge, taken (UAE date) after its
    /// NoChargeAfter and up to its ToDate, is not charged.
    /// </summary>
    public static bool IsNoCharge(DateOnly? readingDate, IrrigationNoChargeRow? rule) =>
        rule is not null && readingDate is { } d && d > rule.NoChargeAfter && (rule.ToDate is null || d <= rule.ToDate);

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
        var noCharge = await repo.GetIrrigationNoChargeAsync(meters[0].PropertyCode, ct);

        // FR-037.10: tenant, BaanCode (vw_MR_Tenant) and TranCode (vw_MR_Property) as the views have them now,
        // for the tenant the reading was taken for. A reading without a tenant (stored before FR-006.12) takes
        // the property's only tenant. What the reading kept is used only when the views no longer have it.
        var tenants = await repo.GetTenantsAsync([meters[0].PropertyCode], ct);
        var tranCodes = new Dictionary<string, string?>(StringComparer.OrdinalIgnoreCase);
        foreach (var tenantCode in readings.Values.Select(r => TenantFor(r.TenantCode, tenants)).OfType<string>().Distinct(StringComparer.OrdinalIgnoreCase))
            tranCodes[tenantCode] = await repo.GetTranCodeAsync(meters[0].PropertyCode, tenantCode, ct);

        var rows = Pair(meters.Select(m =>
        {
            readings.TryGetValue(m.Id, out var t);
            source.TryGetValue(m.Id, out var s);
            var tenantCode = TenantFor(t?.TenantCode, tenants);
            var tenant = tenants.FirstOrDefault(x => string.Equals(x.TenantCode, tenantCode, StringComparison.OrdinalIgnoreCase));
            return new FinalizeMeter(m.Id, m.Number, m.Type,
                t?.PreviousReading ?? m.PreviousReading ?? 0m,
                t?.CurrentReading ?? 0m,
                t?.Consumption ?? 0m,
                t?.TransactionId,
                s?.FloatValue, s?.RateFactor,
                TranCode: (tenantCode is null ? null : tranCodes.GetValueOrDefault(tenantCode)) ?? t?.TranCode,
                BaanCode: tenant?.BaanCode ?? t?.BaanCode,
                TenantCode: tenantCode,
                NoCharge: m.Type == "IRRIGATION" && IsNoCharge(t?.ReadingDate is { } d ? (DateOnly?)DateOnly.FromDateTime(d) : null, noCharge));
        }));
        var code = meters[0].PropertyCode;
        var propertyId = readings.Values.Select(r => r.PropertyId).FirstOrDefault(id => id is not null);

        var at = await repo.InsertFinalizedAsync(period.PeriodCode, code, propertyId, rows, reader.ReaderId, deviceId, ct);
        return new(new FinalizedPropertyDto(period.PeriodCode, code, propertyId, DateTime.SpecifyKind(at, DateTimeKind.Utc),
            FromDate(period.PeriodCode), EndDate(period.PeriodCode),
            rows.Select(ToDto).ToList()));
    }

    private static FinalizedMeterDto ToDto(FinalizedPair p) => new(
        p.Sewerage?.Number ?? "0", p.Sewerage?.Previous ?? 0m, p.Sewerage?.Current ?? 0m, p.Sewerage?.Consumption ?? 0m,
        p.Irrigation?.Number ?? "0", p.Irrigation?.Previous ?? 0m, p.Irrigation?.Current ?? 0m, p.Irrigation?.Consumption ?? 0m,
        p.SewerageAmount, p.IrrigationAmount, p.SewerageVat, p.IrrigationVat, p.TotalAmount, p.IrrigationNoCharge, p.TenPercentAmount, p.IrrigationAmountCalculated, p.TranCode, p.BaanCode, p.TenantCode);
}

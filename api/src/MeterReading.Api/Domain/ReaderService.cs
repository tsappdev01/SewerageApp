using MeterReading.Api.Contracts;
using MeterReading.Api.Data;
using Microsoft.Extensions.Options;

namespace MeterReading.Api.Domain;

/// <summary>A reader's work for one period, joined from the source views and the mr tables.</summary>
public sealed record ReaderWork(IReadOnlyList<MeterDto> Meters, IReadOnlyList<PropertyDto> Properties, IReadOnlyList<ZoneDto> Zones);

public sealed class ReaderService(MeterReadingRepository repo, IOptions<ReadingRulesOptions> rules)
{
    public async Task<ReaderWork> LoadAsync(string readerId, string periodCode, CancellationToken ct)
    {
        var rows = await repo.GetAssignedMetersAsync(readerId, periodCode, ct);
        var ids = rows.Select(r => r.MeterId).ToArray();
        var needAverage = rows.Where(r => r.AverageConsumption is null).Select(r => r.MeterId).ToArray();

        // Different databases are allowed, so these run on their own connections, side by side.
        var latestTask = repo.GetLatestTransactionsAsync(periodCode, ids, ct);
        var averagesTask = repo.GetHistoryAveragesAsync(needAverage, rules.Value.AveragePeriods, ct);
        var latest = await latestTask;
        var averages = await averagesTask;

        var ordered = rows
            .OrderBy(r => r.ZoneCode, StringComparer.Ordinal)
            .ThenBy(r => r.PropertyRoute ?? int.MaxValue).ThenBy(r => r.PropertyCode, StringComparer.Ordinal)
            .ThenBy(r => r.MeterRoute ?? int.MaxValue).ThenBy(r => r.MeterNumber, StringComparer.Ordinal)
            .ToList();

        var meters = ordered.Select(r =>
        {
            latest.TryGetValue(r.MeterId, out var t);
            var average = ExpectedRange.Average(r.AverageConsumption, averages.TryGetValue(r.MeterId, out var a) ? a : null, r.MeterType, rules.Value);
            var state = AssignmentStates.From(t?.Status, t?.MeterCondition);
            var isFirst = r.LastReading is null;
            return new MeterDto(
                r.MeterId, r.MeterNumber, r.MeterType, r.PropertyCode, r.ZoneCode, r.MeterRoute,
                r.RegisterDigits, r.DecimalDigits,
                PreviousReading: isFirst ? r.OpeningReading ?? 0m : r.LastReading,
                PreviousReadingDate: r.LastReadingDate is { } d ? DateOnly.FromDateTime(d) : null,
                IsFirstReading: isFirst,
                AverageConsumption: average,
                ExpectedHigh: ExpectedRange.High(average, r.MeterType, rules.Value),
                State: state,
                SupervisorNote: state == AssignmentState.READ_AGAIN ? t?.StatusNote : null,
                LastTransactionId: t?.TransactionId);
        }).ToList();

        var properties = ordered
            .DistinctBy(r => r.PropertyCode)
            .Select(r => new PropertyDto(r.PropertyCode, r.PropertyName, r.ZoneCode, r.PropertyRoute, r.Latitude, r.Longitude))
            .ToList();
        var zones = ordered.DistinctBy(r => r.ZoneCode).Select(r => new ZoneDto(r.ZoneCode, r.ZoneName)).ToList();
        return new ReaderWork(meters, properties, zones);
    }

    public static SearchResultDto Search(ReaderWork work, string? query, string? zone, DoneFilter done, string? type)
    {
        var q = PropertySearch.Normalize(query);
        var byProperty = work.Meters.ToLookup(m => m.PropertyCode);
        var inScope = work.Properties.Where(p => zone is null || p.ZoneCode == zone).ToList();

        var propertyHits = inScope
            .Where(p => PropertySearch.Matches(p.Code, q) || PropertySearch.Matches(p.Name, q))
            .Select(p => new PropertyHitDto(
                p,
                byProperty[p.Code].Where(m => PropertySearch.Keep(m.State, m.Type, done, type)).ToList(),
                byProperty[p.Code].Count(m => !m.State.CanCapture())))
            .Where(h => h.Meters.Count > 0)
            .ToList();

        // Meter numbers are listed only when there is text and their building did not match already.
        var matched = propertyHits.Select(h => h.Property.Code).ToHashSet();
        var meterHits = q.Length == 0
            ? []
            : inScope.SelectMany(p => byProperty[p.Code])
                .Where(m => !matched.Contains(m.PropertyCode) && PropertySearch.Matches(m.Number, q) && PropertySearch.Keep(m.State, m.Type, done, type))
                .ToList();

        return new SearchResultDto(query ?? "", propertyHits, meterHits);
    }

    public static SummaryDto Summarize(string periodCode, ReaderWork work, IReadOnlyList<TransactionRow> received)
    {
        int Count(AssignmentState s) => work.Meters.Count(m => m.State == s);
        var zones = work.Meters
            .GroupBy(m => m.ZoneCode)
            .OrderBy(g => g.Key, StringComparer.Ordinal)
            .Select(g => new ZoneReconciliationDto(g.Key, g.Count(), g.Count(m => m.State != AssignmentState.PENDING), g.Count(m => m.State == AssignmentState.PENDING)))
            .ToList();
        var notRead = Count(AssignmentState.PENDING);
        return new SummaryDto(
            periodCode,
            Meters: work.Meters.Count,
            Read: work.Meters.Count - notRead,
            Accepted: Count(AssignmentState.SENT),
            Checking: Count(AssignmentState.CHECKING),
            ReadAgain: Count(AssignmentState.READ_AGAIN),
            Revisit: Count(AssignmentState.REVISIT),
            NotRead: notRead,
            Zones: zones,
            LastReceivedUtc: received.Count == 0 ? null : DateTime.SpecifyKind(received.Max(r => r.ReceivedAtUtc), DateTimeKind.Utc));
    }
}

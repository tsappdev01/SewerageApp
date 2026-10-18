using MeterReading.Api.Data;
using Microsoft.Extensions.Options;

namespace MeterReading.Api.Domain;

/// <summary>
/// Meter statuses (spec FR-006.2, DIP 2026-10-18): the list the reader picks from comes from PMS
/// (vw_MR_MeterStatus: StatusCode, StatusName, SequenceNo) and its StatusCode is what is stored in
/// mr.ReadingTransaction.MeterCondition and sent to PMS. What each status asks of the reader and does
/// with the reading (its kind: WORKING, DAMAGED, ...) is set here, per code, in settings.
/// </summary>
public sealed class MeterStatusOptions
{
    public const string Section = "MeterStatus";

    /// <summary>StatusCode → kind. A code in the view with no kind here is not offered to readers.</summary>
    public Dictionary<string, string> Kinds { get; set; } = new(StringComparer.OrdinalIgnoreCase)
    {
        ["O.K"] = "WORKING", ["DMG"] = "DAMAGED", ["FWW"] = "SUBMERSED", ["FLT"] = "NOT_ACCESSIBLE", ["CHG"] = "METER_REPLACED",
    };

    /// <summary>Kind → StatusCode, for a phone that sends only the kind (apps before 2026-10-18). Meter gone is DMG.</summary>
    public Dictionary<string, string> CodeForKind { get; set; } = new(StringComparer.OrdinalIgnoreCase)
    {
        ["WORKING"] = "O.K", ["DAMAGED"] = "DMG", ["SUBMERSED"] = "FWW", ["NOT_ACCESSIBLE"] = "FLT", ["METER_REPLACED"] = "CHG", ["REMOVED"] = "DMG",
    };
}

/// <summary>One status as the phone shows it: PMS's code and name, in PMS's order, and its kind.</summary>
public sealed record MeterStatusDto(string Code, string Name, string Kind, int Sequence);

public sealed class MeterStatusService(MeterReadingRepository repo, IOptions<MeterStatusOptions> options, ILogger<MeterStatusService> log)
{
    public static readonly string[] KnownKinds = ["WORKING", "DAMAGED", "SUBMERSED", "NOT_ACCESSIBLE", "METER_REPLACED", "REMOVED"];

    private MeterStatusOptions O => options.Value;

    /// <summary>The view's statuses that have a kind, by SequenceNo. Without the view: the six kinds as codes.</summary>
    public async Task<IReadOnlyList<MeterStatusDto>> GetAsync(CancellationToken ct)
    {
        var rows = await repo.GetMeterStatusesAsync(ct);
        if (rows is null)
            return KnownKinds.Select((k, i) => new MeterStatusDto(k, k, k, i + 1)).ToList();
        var list = new List<MeterStatusDto>();
        foreach (var r in rows.OrderBy(r => r.SequenceNo ?? int.MaxValue).ThenBy(r => r.StatusCode))
        {
            var code = r.StatusCode.Trim();
            if (O.Kinds.TryGetValue(code, out var kind) && KnownKinds.Contains(kind, StringComparer.OrdinalIgnoreCase))
                list.Add(new MeterStatusDto(code, string.IsNullOrWhiteSpace(r.StatusName) ? code : r.StatusName.Trim(), kind.ToUpperInvariant(), r.SequenceNo ?? 0));
            else
                log.LogWarning("Meter status {Code} in vw_MR_MeterStatus has no kind (MeterStatus:Kinds) and is not offered to readers.", code);
        }
        return list;
    }

    /// <summary>The StatusCode to store for a reading that came with only a kind.</summary>
    public string CodeFor(string kind) =>
        O.CodeForKind.TryGetValue(kind, out var code) ? code
        : O.Kinds.FirstOrDefault(k => string.Equals(k.Value, kind, StringComparison.OrdinalIgnoreCase)).Key ?? kind;
}

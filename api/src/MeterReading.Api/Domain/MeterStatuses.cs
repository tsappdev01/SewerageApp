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

    private static readonly Dictionary<string, string> PlainNames = new(StringComparer.OrdinalIgnoreCase)
    {
        ["WORKING"] = "Working", ["DAMAGED"] = "Damaged", ["SUBMERSED"] = "Under water",
        ["NOT_ACCESSIBLE"] = "Can't reach", ["METER_REPLACED"] = "New meter fitted", ["REMOVED"] = "Meter gone",
    };

    /// <summary>
    /// The view's statuses that have a kind, by SequenceNo. When the view is missing or cannot be read
    /// (e.g. no SELECT for the API's login: db/016), the codes in MeterStatus:Kinds with plain names, so the
    /// meter list never fails because of the statuses and readings still carry PMS's codes.
    /// </summary>
    public async Task<IReadOnlyList<MeterStatusDto>> GetAsync(CancellationToken ct)
    {
        IReadOnlyList<MeterStatusRow>? rows;
        try
        {
            rows = await repo.GetMeterStatusesAsync(ct);
        }
        catch (Microsoft.Data.SqlClient.SqlException e)
        {
            log.LogError("vw_MR_MeterStatus cannot be read ({Error}); using MeterStatus:Kinds. Grant SELECT with db/016.", e.Message);
            rows = null;
        }
        if (rows is null)
            return O.Kinds
                .Where(k => KnownKinds.Contains(k.Value, StringComparer.OrdinalIgnoreCase))
                .OrderBy(k => Array.FindIndex(KnownKinds, x => string.Equals(x, k.Value, StringComparison.OrdinalIgnoreCase)))
                .Select((k, i) => new MeterStatusDto(k.Key, PlainNames.GetValueOrDefault(k.Value, k.Key), k.Value.ToUpperInvariant(), i + 1))
                .ToList();
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

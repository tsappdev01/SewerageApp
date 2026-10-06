using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using MeterReading.Api.Contracts;
using MeterReading.Api.Data;
using Microsoft.Extensions.Options;

namespace MeterReading.Api.Domain;

public sealed record InspectionOutcome(SubmitInspectionResponse? Response, bool Created, int? ErrorStatus = null, string? ErrorCode = null, string? ErrorTitle = null)
{
    public static InspectionOutcome Reject(int status, string code, string title) => new(null, false, status, code, title);
}

/// <summary>
/// Field Inspection (spec §21). Plans are not assigned: every active reader can inspect, like meter
/// reading (FR-030). A plan row's progress is the latest result of each of its units over all visits,
/// so a unit left PENDING on one visit is finished on the next (FR-034).
/// </summary>
public sealed partial class InspectionService(InspectionRepository repo, TimeProvider clock, IOptions<InspectionOptions> options)
{
    private static readonly HashSet<UnitResult> Flagged = [UnitResult.SUBLEASED, UnitResult.DISPUTED, UnitResult.REJECTED];
    private static readonly TimeSpan ClockSkew = TimeSpan.FromMinutes(5);
    private static readonly TimeSpan MaxQueueAge = TimeSpan.FromDays(7);
    private const int MaxUnitsPerVisit = 500;

    private InspectionOptions O => options.Value;

    /// <summary>Today in the UAE (or the configured offset).</summary>
    public DateOnly Today => DateOnly.FromDateTime(clock.GetUtcNow().UtcDateTime.AddHours(O.UtcOffsetHours));

    public async Task<IReadOnlyList<InspectionPlanDto>> LoadPlanAsync(DateOnly from, DateOnly to, CancellationToken ct)
    {
        var plans = await repo.GetPlanAsync(from, to, ct);
        return await WithProgressAsync(plans, ct);
    }

    public async Task<IReadOnlyList<InspectionPlanDto>> WithProgressAsync(IReadOnlyList<InspectionPlanRow> plans, CancellationToken ct)
    {
        var codes = plans.Select(p => p.PropertyCode).Distinct(StringComparer.Ordinal).ToArray();
        // Separate connections (the views may be in another database), side by side.
        var unitsTask = repo.GetUnitsAsync(codes, ct);
        var latestTask = repo.GetLatestResultsAsync(codes, ct);
        var visitsTask = repo.GetVisitSummariesAsync(codes, ct);
        var units = (await unitsTask).ToLookup(u => u.PropertyCode);
        var latest = (await latestTask).ToLookup(r => (r.PeriodCode, r.PropertyCode, r.TenantCode));
        var visits = (await visitsTask).ToDictionary(v => (v.PeriodCode, v.PropertyCode, v.TenantCode), v => v.LastFinishedAtUtc);

        return plans
            .OrderBy(p => p.PlanDate).ThenBy(p => p.PropertyCode, StringComparer.Ordinal).ThenBy(p => p.TenantCode, StringComparer.Ordinal)
            .Select(p => Progress(p,
                units[p.PropertyCode].Where(u => u.BelongsTo(p.TenantCode)).ToList(),
                latest[(p.PeriodCode, p.PropertyCode, p.TenantCode)].ToList(),
                visits.TryGetValue((p.PeriodCode, p.PropertyCode, p.TenantCode), out var last) ? last : null))
            .ToList();
    }

    /// <summary>
    /// FR-034: DONE when every active unit has a result other than PENDING and no unit's latest result is
    /// PENDING; COME_BACK when visited but not done; NOT_STARTED before the first visit.
    /// </summary>
    public static InspectionPlanDto Progress(InspectionPlanRow p, IReadOnlyList<InspectionUnitRow> units, IReadOnlyList<LatestUnitResultRow> latest, DateTime? lastVisit)
    {
        var onList = units.Select(u => u.UnitId).ToHashSet(StringComparer.Ordinal);
        var results = latest.Where(r => onList.Contains(r.UnitId)).ToDictionary(r => r.UnitId, r => Parse(r.Result), StringComparer.Ordinal);
        var activeChecked = units.Where(u => u.Active).All(u => results.TryGetValue(u.UnitId, out var r) && r != UnitResult.PENDING);
        var anyPending = results.Values.Any(r => r == UnitResult.PENDING);
        var state = lastVisit is null ? InspectionState.NOT_STARTED
            : activeChecked && !anyPending ? InspectionState.DONE
            : InspectionState.COME_BACK;
        return new InspectionPlanDto(
            p.PeriodCode, DateOnly.FromDateTime(p.PlanDate), p.PropertyCode, p.TenantCode, p.CompanyName,
            // Counted from the units we can show, so the phone's numbers always add up.
            units.Count(u => u.Active), units.Count(u => !u.Active), units.Count,
            state,
            CheckedUnits: results.Values.Count(r => r != UnitResult.PENDING),
            FlaggedUnits: results.Values.Count(Flagged.Contains),
            LastVisitAtUtc: lastVisit is { } t ? DateTime.SpecifyKind(t, DateTimeKind.Utc) : null);
    }

    public async Task<InspectionUnitsDto?> LoadUnitsAsync(string periodCode, string propertyCode, string tenantCode, CancellationToken ct)
    {
        var plan = await repo.GetPlanRowAsync(periodCode, propertyCode, tenantCode, ct);
        if (plan is null) return null;
        var unitsTask = repo.GetUnitsAsync([propertyCode], ct);
        var latestTask = repo.GetLatestResultsAsync([propertyCode], ct);
        var visitsTask = repo.GetVisitSummariesAsync([propertyCode], ct);
        var units = (await unitsTask).Where(u => u.BelongsTo(tenantCode)).ToList();
        var latest = await latestTask;
        var visits = await visitsTask;

        // "Last inspection" of a unit: its latest result in any plan row (also earlier periods).
        var lastByUnit = latest.GroupBy(r => r.UnitId, StringComparer.Ordinal)
            .ToDictionary(g => g.Key, g => g.MaxBy(r => r.FinishedAtUtc)!, StringComparer.Ordinal);
        var lastVisit = visits.Where(v => v.PeriodCode == periodCode && v.TenantCode == tenantCode).Select(v => (DateTime?)v.LastFinishedAtUtc).Max();
        var progress = Progress(plan, units, latest.Where(r => r.PeriodCode == periodCode && r.TenantCode == tenantCode).ToList(), lastVisit);

        var list = units
            .OrderByDescending(u => u.Active)
            .ThenBy(u => u.BuildingName, StringComparer.OrdinalIgnoreCase)
            .ThenBy(u => u.UnitCode.PadLeft(10, '0'), StringComparer.OrdinalIgnoreCase)
            .Select(u => new InspectionUnitDto(
                u.UnitId, u.BuildingName, u.UnitCode, u.Category, CategoryName(u.Category), u.SubTenantName, u.Active,
                lastByUnit.TryGetValue(u.UnitId, out var l)
                    ? new LastUnitResultDto(Parse(l.Result), DateTime.SpecifyKind(l.FinishedAtUtc, DateTimeKind.Utc), l.OccupantName, l.PeopleSeen)
                    : null))
            .ToList();
        return new InspectionUnitsDto(progress, list);
    }

    /// <summary>"Commercial>Warehouse>Warehouse" gives "Warehouse"; "Commercial> >" gives "Commercial".</summary>
    public static string? CategoryName(string? category) =>
        category?.Split('>', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries).LastOrDefault();

    public async Task<InspectionOutcome> SubmitAsync(ReaderRow inspector, SubmitInspectionRequest r, CancellationToken ct)
    {
        if (r.VisitId == Guid.Empty || Blank(r.PeriodCode) || Blank(r.PropertyCode) || Blank(r.TenantCode))
            return InspectionOutcome.Reject(422, "VALIDATION_FAILED", "VisitId, PeriodCode, PropertyCode and TenantCode are required.");
        if (r.Units is null || r.Units.Count == 0 || r.Units.Count > MaxUnitsPerVisit)
            return InspectionOutcome.Reject(422, "VALIDATION_FAILED", $"A visit has between 1 and {MaxUnitsPerVisit} units.");
        if (r.PersonMet is { Length: > 100 })
            return InspectionOutcome.Reject(422, "VALIDATION_FAILED", "The name of the person met can be at most 100 characters.");
        if (Validate(r.Units, O.MaxPhotosPerUnit) is { } invalid) return invalid;

        var hash = PayloadHash(r);
        var existing = await repo.GetVisitAsync(r.VisitId, ct);
        if (existing is not null)
        {
            return existing.PayloadHash == hash && existing.InspectorId == inspector.ReaderId
                ? new InspectionOutcome(await ResponseAsync(r, existing.ExpectedPhotos, ct), Created: false)
                : InspectionOutcome.Reject(409, "VISIT_ID_REUSED", "This visit id was already used for a different inspection.");
        }

        var now = clock.GetUtcNow().UtcDateTime;
        var started = DateTime.SpecifyKind(r.StartedAtUtc, DateTimeKind.Utc);
        var finished = DateTime.SpecifyKind(r.FinishedAtUtc, DateTimeKind.Utc);
        if (finished > now + ClockSkew || finished < now - MaxQueueAge || started > finished)
            return InspectionOutcome.Reject(422, "CAPTURE_TIME_INVALID", "The phone's time is wrong or the inspection is more than 7 days old.");

        var periodCode = r.PeriodCode.Trim();
        var propertyCode = r.PropertyCode.Trim();
        var tenantCode = r.TenantCode.Trim();
        var plan = await repo.GetPlanRowAsync(periodCode, propertyCode, tenantCode, ct);
        if (plan is null) return InspectionOutcome.Reject(404, "PLAN_NOT_FOUND", "This property is not on the inspection plan for this period and tenant.");

        var units = (await repo.GetUnitsAsync([propertyCode], ct)).Where(u => u.BelongsTo(tenantCode)).ToDictionary(u => u.UnitId, StringComparer.Ordinal);
        var rows = new List<NewUnitResultRow>();
        foreach (var u in r.Units)
        {
            InspectionUnitRow? unit = null;
            if (u.UnitId is { } id && !units.TryGetValue(id.Trim(), out unit))
                return InspectionOutcome.Reject(404, "UNIT_NOT_FOUND", $"Unit {id} is not a unit of this property and tenant. Refresh the plan.");
            rows.Add(new NewUnitResultRow
            {
                ResultId = u.ResultId,
                VisitId = r.VisitId,
                UnitId = unit?.UnitId,
                UnitCode = unit?.UnitCode ?? u.UnitCode!.Trim(),
                BuildingName = unit is null ? Trimmed(u.BuildingName, 150) : unit.BuildingName,
                Category = unit is null ? Trimmed(u.Category, 200) : unit.Category,
                SubTenantOnRecord = unit?.SubTenantName,
                ActiveOnRecord = unit?.Active,
                Result = u.Result.ToString(),
                PeopleSeen = u.PeopleSeen,
                OccupantName = Trimmed(u.OccupantName, 200),
                Reasons = u.Reasons is { Count: > 0 } reasons ? string.Join(',', reasons.Select(x => x.Trim().ToUpperInvariant()).Distinct()) : null,
                Note = Trimmed(u.Note, 500),
                ExpectedPhotos = u.PhotoCount,
            });
        }

        var photos = r.Units.Sum(u => u.PhotoCount) + (r.HasSignature ? 1 : 0);
        var visit = new NewVisitRow
        {
            VisitId = r.VisitId,
            PeriodCode = plan.PeriodCode,
            PropertyCode = plan.PropertyCode,
            TenantCode = plan.TenantCode,
            CompanyName = plan.CompanyName,
            PlanDate = plan.PlanDate,
            InspectorId = inspector.ReaderId,
            DeviceId = r.DeviceId,
            StartedAtUtc = started,
            FinishedAtUtc = finished,
            Latitude = r.Latitude,
            Longitude = r.Longitude,
            GpsAccuracyM = r.GpsAccuracyM,
            PersonMet = Trimmed(r.PersonMet, 100),
            ExpectedPhotos = photos,
            PayloadHash = hash,
        };
        if (!await repo.InsertVisitAsync(visit, rows, ct))
        {
            // Sent twice at the same moment: the first one stored it.
            var stored = await repo.GetVisitAsync(r.VisitId, ct);
            return stored is not null && stored.PayloadHash == hash
                ? new InspectionOutcome(await ResponseAsync(r, stored.ExpectedPhotos, ct), Created: false)
                : InspectionOutcome.Reject(409, "VISIT_ID_REUSED", "This visit id was already used for a different inspection.");
        }
        return new InspectionOutcome(await ResponseAsync(r, photos, ct), Created: true);
    }

    /// <summary>FR-032, FR-033: what each result needs. Mirrors android data/InspectionRules.kt.</summary>
    public static InspectionOutcome? Validate(IReadOnlyList<UnitResultRequest> units, int maxPhotos)
    {
        var resultIds = new HashSet<Guid>();
        var unitIds = new HashSet<string>(StringComparer.Ordinal);
        foreach (var u in units)
        {
            if (u.ResultId == Guid.Empty || !resultIds.Add(u.ResultId))
                return InspectionOutcome.Reject(422, "VALIDATION_FAILED", "Each unit needs its own ResultId.");
            if (!Enum.IsDefined(u.Result))
                return InspectionOutcome.Reject(422, "INVALID_LOV_CODE", "Unknown unit result.");
            if (u.UnitId is { } id)
            {
                if (id.Trim().Length is 0 or > 50) return InspectionOutcome.Reject(422, "VALIDATION_FAILED", "UnitId can be at most 50 characters.");
                if (!unitIds.Add(id.Trim())) return InspectionOutcome.Reject(422, "VALIDATION_FAILED", $"Unit {id} is in the visit twice.");
            }
            else if (Blank(u.UnitCode) || u.UnitCode!.Trim().Length > 30)
            {
                return InspectionOutcome.Reject(422, "MANDATORY_FIELD_MISSING", "A unit not on the list needs the unit number on the door (at most 30 characters).");
            }
            if (u.PeopleSeen is < 0 or > 999) return InspectionOutcome.Reject(422, "VALIDATION_FAILED", "People seen must be between 0 and 999.");
            if (u.PhotoCount < 0 || u.PhotoCount > maxPhotos) return InspectionOutcome.Reject(422, "VALIDATION_FAILED", $"A unit can have at most {maxPhotos} photos.");
            if (u.Reasons is { } reasons && (reasons.Count > 10 || reasons.Any(x => x is null || !ReasonCode().IsMatch(x.Trim().ToUpperInvariant()))))
                return InspectionOutcome.Reject(422, "INVALID_LOV_CODE", "Reasons are up to 10 codes like OTHER_COMPANY_SIGN.");
            if (u.Note is { Length: > 500 } || u.OccupantName is { Length: > 200 })
                return InspectionOutcome.Reject(422, "VALIDATION_FAILED", "The note can be at most 500 characters and the occupant's name 200.");
            if (Missing(u) is { } missing)
                return InspectionOutcome.Reject(422, "MANDATORY_FIELD_MISSING", $"{missing} is needed for a {u.Result} unit.");
        }
        return null;
    }

    private static string? Missing(UnitResultRequest u)
    {
        var hasReason = u.Reasons is { Count: > 0 } || !Blank(u.Note);
        return u.Result switch
        {
            UnitResult.SUBLEASED when Blank(u.OccupantName) => "The name of who is using the unit",
            UnitResult.DISPUTED or UnitResult.REJECTED or UnitResult.PENDING when !hasReason => "A reason or a note",
            _ when Flagged.Contains(u.Result) && u.PhotoCount < 1 => "At least one photo",
            _ => null,
        };
    }

    private async Task<SubmitInspectionResponse> ResponseAsync(SubmitInspectionRequest r, int photos, CancellationToken ct)
    {
        var plan = await repo.GetPlanRowAsync(r.PeriodCode.Trim(), r.PropertyCode.Trim(), r.TenantCode.Trim(), ct);
        var state = plan is null ? InspectionState.COME_BACK : (await WithProgressAsync([plan], ct)).Single().State;
        return new SubmitInspectionResponse(r.VisitId, state, r.Units.Count, r.Units.Count(u => Flagged.Contains(u.Result)), photos);
    }

    private static UnitResult Parse(string text) => Enum.TryParse<UnitResult>(text, out var r) ? r : UnitResult.PENDING;

    private static bool Blank(string? s) => string.IsNullOrWhiteSpace(s);

    private static string? Trimmed(string? text, int max) =>
        string.IsNullOrWhiteSpace(text) ? null : text.Trim() is var t && t.Length > max ? t[..max] : text.Trim();

    /// <summary>SHA-256 of the request as the server reads it, so a retry of the same visit matches.</summary>
    public static string PayloadHash(SubmitInspectionRequest r) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(r with
        {
            StartedAtUtc = DateTime.SpecifyKind(r.StartedAtUtc, DateTimeKind.Utc),
            FinishedAtUtc = DateTime.SpecifyKind(r.FinishedAtUtc, DateTimeKind.Utc),
        }))));

    [GeneratedRegex("^[A-Z][A-Z0-9_]{0,29}$")]
    private static partial Regex ReasonCode();
}

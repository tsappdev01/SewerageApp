using System.Security.Cryptography;
using System.Text.RegularExpressions;
using MeterReading.Api.Auth;
using MeterReading.Api.Contracts;
using MeterReading.Api.Data;
using MeterReading.Api.Domain;
using Microsoft.Extensions.Options;

namespace MeterReading.Api.Endpoints;

/// <summary>
/// Field Inspection (spec §21). The same people and phones as meter reading: any active reader in
/// vw_MR_Reader can inspect (FR-030). When the inspection views are missing every call answers
/// 404 INSPECTION_OFF and /me says CanInspect = false, so the phone hides the job.
/// </summary>
public static partial class InspectionEndpoints
{
    public static void MapInspectionEndpoints(this IEndpointRouteBuilder app)
    {
        var api = app.MapGroup("/api/v1/inspections").RequireAuthorization(ReaderEndpoints.Policy).WithTags("Field inspection");
        api.MapGet("/plan", GetPlan).WithSummary("Plan rows from ?from= to ?to= (default: 60 days back to 14 ahead), with each one's progress.");
        api.MapGet("/units", GetUnits).WithSummary("The units of one plan row: ?period=&property=&tenant=, with each unit's last result.");
        api.MapPost("", Submit).WithSummary("Send one finished visit with all its unit results. 201 stored, 200 when the same VisitId was already stored.");
        api.MapPut("/{visitId:guid}/images/{imageId:guid}", Upload)
            .WithSummary("Upload one photo. Body: the JPEG. Header X-Content-SHA256. Query: role (EVIDENCE with result=, or SIGNATURE), capturedAtUtc.");
        api.MapGet("/{visitId:guid}/images/{imageId:guid}", Download).WithSummary("One photo of the signed-in inspector's own visit.");
    }

    private static async Task<(ReaderRow? Reader, IResult? Problem)> ReadyAsync(CurrentReader current, InspectionRepository repo, CancellationToken ct)
    {
        var (views, tables) = await repo.AvailableAsync(ct);
        if (!views) return (null, Problems.Of(404, "INSPECTION_OFF", "Field inspection is not set up on this server."));
        if (!tables) return (null, Problems.Of(503, "INSPECTION_OFF", "Field inspection tables are missing (db/010_field_inspection.sql, db/012_inspection_location.sql)."));
        return await current.ResolveAsync(ct);
    }

    private static async Task<IResult> GetPlan(
        DateOnly? from, DateOnly? to, CurrentReader current, InspectionRepository repo, InspectionService service,
        IOptions<InspectionOptions> options, CancellationToken ct)
    {
        var (_, problem) = await ReadyAsync(current, repo, ct);
        if (problem is not null) return problem;
        var today = service.Today;
        var f = from ?? today.AddDays(-options.Value.PlanDaysBack);
        var t = to ?? today.AddDays(options.Value.PlanDaysAhead);
        if (t < f || t.DayNumber - f.DayNumber > 366) return Problems.Invalid("from must be before to, at most a year apart.");
        var plans = await service.LoadPlanAsync(f, t, ct);
        return Results.Ok(new InspectionPlanListDto(today, f, t, plans, DateTime.UtcNow, options.Value.OfficeLatitude, options.Value.OfficeLongitude));
    }

    private static async Task<IResult> GetUnits(
        string? period, string? property, string? tenant, CurrentReader current, InspectionRepository repo, InspectionService service, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(period) || string.IsNullOrWhiteSpace(property) || string.IsNullOrWhiteSpace(tenant)
            || period.Length > 10 || property.Length > 30 || tenant.Length > 30)
            return Problems.Invalid("period, property and tenant are required.");
        var (_, problem) = await ReadyAsync(current, repo, ct);
        if (problem is not null) return problem;
        var units = await service.LoadUnitsAsync(period.Trim(), property.Trim(), tenant.Trim(), ct);
        return units is null
            ? Problems.Of(404, "PLAN_NOT_FOUND", "This property is not on the inspection plan for this period and tenant.")
            : Results.Ok(units);
    }

    private static async Task<IResult> Submit(SubmitInspectionRequest request, CurrentReader current, InspectionRepository repo, InspectionService service, CancellationToken ct)
    {
        var (inspector, problem) = await ReadyAsync(current, repo, ct);
        if (problem is not null) return problem;
        // FR-002: the visit is kept with the phone that signed in, whatever the body says.
        if (current.DeviceId is { } device) request = request with { DeviceId = device };
        var outcome = await service.SubmitAsync(inspector!, request, ct);
        if (outcome.Response is not { } response) return Problems.Of(outcome.ErrorStatus!.Value, outcome.ErrorCode!, outcome.ErrorTitle!);
        return outcome.Created ? Results.Created($"/api/v1/inspections/{response.VisitId}", response) : Results.Ok(response);
    }

    private static async Task<IResult> Upload(
        Guid visitId, Guid imageId, string? role, Guid? result, DateTime? capturedAtUtc,
        HttpRequest request, CurrentReader current, InspectionRepository repo, IImageStore store,
        IOptions<ImageStoreOptions> images, IOptions<InspectionOptions> options, CancellationToken ct)
    {
        var (inspector, problem) = await ReadyAsync(current, repo, ct);
        if (problem is not null) return problem;

        var roleCode = role?.ToUpperInvariant();
        if (roleCode is not ("EVIDENCE" or "SIGNATURE")) return Problems.Of(422, "INVALID_LOV_CODE", "role must be EVIDENCE or SIGNATURE.");
        if ((roleCode == "EVIDENCE") != result.HasValue)
            return Problems.Invalid("An EVIDENCE photo needs result= (the unit's ResultId); a SIGNATURE has none.");
        var claimedHash = request.Headers["X-Content-SHA256"].ToString().ToUpperInvariant();
        if (!Sha256Hex().IsMatch(claimedHash)) return Problems.Invalid("Header X-Content-SHA256 must be the photo's SHA-256 in hex.");

        var visit = await repo.GetVisitAsync(visitId, ct);
        if (visit is null || visit.InspectorId != inspector!.ReaderId)
            return Problems.Of(404, "VISIT_NOT_FOUND", "There is no inspection of yours with this id. Send the inspection first.");
        if (result is { } resultId && !(await repo.GetResultPhotoLimitsAsync(visitId, ct)).ContainsKey(resultId))
            return Problems.Of(404, "UNIT_NOT_FOUND", "This inspection has no unit with this result id.");

        var limit = images.Value.MaxImageBytes;
        if (request.ContentLength > limit) return TooLarge(limit);
        var bytes = await ReadUpToAsync(request.Body, limit + 1, ct);
        if (bytes.Length > limit) return TooLarge(limit);
        if (bytes.Length < 4 || bytes[0] != 0xFF || bytes[1] != 0xD8 || bytes[2] != 0xFF)
            return Problems.Of(422, "IMAGE_INVALID", "The photo must be a JPEG.");
        var hash = Convert.ToHexString(SHA256.HashData(bytes));
        if (hash != claimedHash) return Problems.Of(422, "IMAGE_HASH_MISMATCH", "The photo was damaged on the way. Send it again.");

        var existing = await repo.GetImageAsync(imageId, ct);
        if (existing is not null)
        {
            return existing.VisitId == visitId && existing.Sha256 == hash
                ? Results.Ok(ToResponse(existing))
                : Problems.Of(409, "IMAGE_ID_REUSED", "This image id was already used for a different photo.");
        }

        var image = new InspectionImageRow
        {
            ImageId = imageId,
            VisitId = visitId,
            ResultId = result,
            ImageRole = roleCode,
            BlobPath = BlobPath(visit, imageId),
            Sha256 = hash,
            SizeBytes = bytes.Length,
            CapturedAtUtc = capturedAtUtc is { } t ? DateTime.SpecifyKind(t.ToUniversalTime(), DateTimeKind.Utc) : visit.FinishedAtUtc,
        };
        await store.SaveAsync(image.BlobPath, bytes, ct);
        var max = roleCode == "SIGNATURE" ? 1 : options.Value.MaxPhotosPerUnit;
        if (!await repo.InsertImageAsync(image, max, ct))
            return Problems.Of(422, "TOO_MANY_IMAGES", roleCode == "SIGNATURE" ? "This inspection already has a signature." : $"A unit can have at most {max} photos.");
        return Results.Created($"/api/v1/inspections/{visitId}/images/{imageId}", ToResponse(image));
    }

    private static async Task<IResult> Download(
        Guid visitId, Guid imageId, CurrentReader current, InspectionRepository repo, IImageStore store, CancellationToken ct)
    {
        var (inspector, problem) = await ReadyAsync(current, repo, ct);
        if (problem is not null) return problem;
        var visit = await repo.GetVisitAsync(visitId, ct);
        var image = await repo.GetImageAsync(imageId, ct);
        if (visit is null || visit.InspectorId != inspector!.ReaderId || image is null || image.VisitId != visitId)
            return Problems.Of(404, "IMAGE_NOT_FOUND", "There is no photo of yours with this id.");
        var stream = await store.OpenAsync(image.BlobPath, ct);
        return stream is null ? Problems.Of(404, "IMAGE_NOT_FOUND", "The photo is recorded but missing from storage.") : Results.Stream(stream, "image/jpeg");
    }

    /// <summary>inspections/{yyyy}/{MM}/{property}/{visitId}/{imageId}.jpg, by the month of the visit.</summary>
    private static string BlobPath(VisitRow visit, Guid imageId) =>
        $"inspections/{visit.FinishedAtUtc:yyyy}/{visit.FinishedAtUtc:MM}/{SafeSegment().Replace(visit.PropertyCode, "_")}/{visit.VisitId:D}/{imageId:D}.jpg";

    private static async Task<byte[]> ReadUpToAsync(Stream body, int max, CancellationToken ct)
    {
        using var buffer = new MemoryStream();
        var chunk = new byte[81920];
        int read;
        while ((read = await body.ReadAsync(chunk, ct)) > 0)
        {
            buffer.Write(chunk, 0, read);
            if (buffer.Length >= max) break;
        }
        return buffer.ToArray();
    }

    private static IResult TooLarge(int limit) => Problems.Of(413, "IMAGE_TOO_LARGE", $"The photo is larger than {limit / 1_000_000.0:0.#} MB.");

    private static InspectionImageResponse ToResponse(InspectionImageRow i) => new(i.ImageId, i.VisitId, i.ResultId, i.ImageRole, i.SizeBytes, i.Sha256);

    [GeneratedRegex("^[0-9A-F]{64}$")]
    private static partial Regex Sha256Hex();

    [GeneratedRegex("[^A-Za-z0-9_-]")]
    private static partial Regex SafeSegment();
}

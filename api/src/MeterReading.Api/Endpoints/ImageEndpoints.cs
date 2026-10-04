using System.Security.Cryptography;
using System.Text.RegularExpressions;
using MeterReading.Api.Auth;
using MeterReading.Api.Contracts;
using MeterReading.Api.Data;
using Microsoft.Extensions.Options;

namespace MeterReading.Api.Endpoints;

/// <summary>
/// Reading photos (spec FR-008, FR-009). The phone sends the reading first, then each photo with
/// PUT, so a slow photo never holds up the reading. A photo is checked against its SHA-256, must
/// be a JPEG, and is stored once: repeating the same upload is safe.
/// </summary>
public static partial class ImageEndpoints
{
    private static readonly HashSet<string> Roles = ["DISPLAY", "CONTEXT", "OBSTRUCTION", "DAMAGE", "OLD_METER_FINAL", "NEW_METER"];

    public static void MapImageEndpoints(this IEndpointRouteBuilder app)
    {
        var api = app.MapGroup("/api/v1/readings/{transactionId:guid}/images").RequireAuthorization(ReaderEndpoints.Policy).WithTags("Meter reader");
        api.MapPut("/{imageId:guid}", Upload)
            .WithSummary("Upload one photo of a reading. Body: the JPEG. Header X-Content-SHA256: its hash in hex. Query: role, capturedAtUtc.");
        api.MapGet("/{imageId:guid}", Download).WithSummary("One of the signed-in reader's photos.");
    }

    private static async Task<IResult> Upload(
        Guid transactionId, Guid imageId, string? role, DateTime? capturedAtUtc,
        HttpRequest request, CurrentReader current, MeterReadingRepository repo, IImageStore store,
        IOptions<ImageStoreOptions> options, CancellationToken ct)
    {
        var (reader, problem) = await current.ResolveAsync(ct);
        if (problem is not null) return problem;

        var roleCode = role?.ToUpperInvariant();
        if (roleCode is null || !Roles.Contains(roleCode)) return Problems.Of(422, "INVALID_LOV_CODE", "role must be DISPLAY, CONTEXT, OBSTRUCTION, DAMAGE, OLD_METER_FINAL or NEW_METER.");
        var claimedHash = request.Headers["X-Content-SHA256"].ToString().ToUpperInvariant();
        if (!Sha256Hex().IsMatch(claimedHash)) return Problems.Invalid("Header X-Content-SHA256 must be the photo's SHA-256 in hex.");

        var reading = await repo.GetTransactionAsync(transactionId, ct);
        if (reading is null || reading.ReaderId != reader!.ReaderId)
            return Problems.Of(404, "READING_NOT_FOUND", "There is no reading of yours with this id. Send the reading first.");

        var limit = options.Value.MaxImageBytes;
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
            return existing.TransactionId == transactionId && existing.Sha256 == hash
                ? Results.Ok(ToResponse(existing))
                : Problems.Of(409, "IMAGE_ID_REUSED", "This image id was already used for a different photo.");
        }

        var captured = capturedAtUtc is { } t ? DateTime.SpecifyKind(t.ToUniversalTime(), DateTimeKind.Utc) : reading.CapturedAtUtc;
        var image = new ImageRow
        {
            ImageId = imageId,
            TransactionId = transactionId,
            ImageRole = roleCode,
            BlobPath = BlobPath(reading, imageId),
            Sha256 = hash,
            SizeBytes = bytes.Length,
            CapturedAtUtc = captured,
        };
        // Save first, then record: a recorded photo always exists in the store.
        await store.SaveAsync(image.BlobPath, bytes, ct);
        if (!await repo.InsertImageAsync(image, options.Value.MaxImagesPerReading, ct))
            return Problems.Of(422, "TOO_MANY_IMAGES", $"A reading can have at most {options.Value.MaxImagesPerReading} photos.");
        return Results.Created($"/api/v1/readings/{transactionId}/images/{imageId}", ToResponse(image));
    }

    private static async Task<IResult> Download(
        Guid transactionId, Guid imageId, CurrentReader current, MeterReadingRepository repo, IImageStore store, CancellationToken ct)
    {
        var (reader, problem) = await current.ResolveAsync(ct);
        if (problem is not null) return problem;
        var reading = await repo.GetTransactionAsync(transactionId, ct);
        var image = await repo.GetImageAsync(imageId, ct);
        if (reading is null || reading.ReaderId != reader!.ReaderId || image is null || image.TransactionId != transactionId)
            return Problems.Of(404, "IMAGE_NOT_FOUND", "There is no photo of yours with this id.");
        var stream = await store.OpenAsync(image.BlobPath, ct);
        return stream is null
            ? Problems.Of(404, "IMAGE_NOT_FOUND", "The photo is recorded but missing from storage.")
            : Results.Stream(stream, "image/jpeg");
    }

    /// <summary>readings/{yyyy}/{MM}/{meterId}/{transactionId}/{imageId}.jpg (FR-009.3), by reading period.</summary>
    private static string BlobPath(TransactionRow reading, Guid imageId)
    {
        var year = reading.PeriodCode[..4];
        var month = reading.PeriodCode[5..7];
        var meter = SafeSegment().Replace(reading.MeterId, "_");
        return $"readings/{year}/{month}/{meter}/{reading.TransactionId:D}/{imageId:D}.jpg";
    }

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

    private static IResult TooLarge(int limit) =>
        Problems.Of(413, "IMAGE_TOO_LARGE", $"The photo is larger than {limit / 1_000_000.0:0.#} MB.");

    private static ImageUploadResponse ToResponse(ImageRow i) => new(i.ImageId, i.TransactionId, i.ImageRole, i.SizeBytes, i.Sha256);

    [GeneratedRegex("^[0-9A-F]{64}$")]
    private static partial Regex Sha256Hex();

    [GeneratedRegex("[^A-Za-z0-9_-]")]
    private static partial Regex SafeSegment();
}

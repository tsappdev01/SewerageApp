using System.Security.Claims;
using MeterReading.Api.Data;

namespace MeterReading.Api.Auth;

/// <summary>Finds the signed-in user in vw_MR_Reader by their sign-in name.</summary>
public sealed class CurrentReader(IHttpContextAccessor http, MeterReadingRepository repo)
{
    // Entra v2 tokens carry the sign-in name in preferred_username; older ones in upn or email.
    private static readonly string[] LoginClaims = ["preferred_username", "upn", ClaimTypes.Upn, "email", ClaimTypes.Email];

    public string? LoginEmail =>
        LoginClaims.Select(c => http.HttpContext?.User.FindFirst(c)?.Value).FirstOrDefault(v => !string.IsNullOrWhiteSpace(v));

    public async Task<ReaderRow?> GetAsync(CancellationToken ct) =>
        LoginEmail is { } email ? await repo.FindReaderAsync(email, ct) : null;
}

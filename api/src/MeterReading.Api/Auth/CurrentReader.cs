using System.Security.Claims;
using MeterReading.Api.Data;
using MeterReading.Api.Endpoints;

namespace MeterReading.Api.Auth;

/// <summary>Finds the signed-in user in vw_MR_Reader by their sign-in name.</summary>
public sealed class CurrentReader(IHttpContextAccessor http, MeterReadingRepository repo)
{
    // Entra v2 tokens carry the sign-in name in preferred_username; older ones in upn or email.
    private static readonly string[] LoginClaims = ["preferred_username", "upn", ClaimTypes.Upn, "email", ClaimTypes.Email];

    public string? LoginEmail =>
        LoginClaims.Select(c => http.HttpContext?.User.FindFirst(c)?.Value).FirstOrDefault(v => !string.IsNullOrWhiteSpace(v));

    /// <summary>
    /// The registered phone that signed in (Device mode, FR-002), from its key, never from what the
    /// request says. Null with other sign-ins.
    /// </summary>
    public Guid? DeviceId =>
        Guid.TryParse(http.HttpContext?.User.FindFirst(DeviceAuthHandler.DeviceIdClaim)?.Value, out var id) ? id : null;

    /// <summary>The reader, or the problem to return: not set up, or several readers share the sign-in name.</summary>
    public async Task<(ReaderRow? Reader, IResult? Problem)> ResolveAsync(CancellationToken ct)
    {
        if (LoginEmail is not { } email) return (null, Problems.ReaderNotFound());
        var readers = await repo.FindReadersAsync(email, ct);
        return readers.Count switch
        {
            0 => (null, Problems.ReaderNotFound()),
            1 => (readers[0], null),
            _ => (null, Problems.Of(StatusCodes.Status409Conflict, "LOGIN_NOT_UNIQUE",
                "More than one active reader has this sign-in name in vw_MR_Reader. Each reader needs their own LoginEmail.")),
        };
    }
}

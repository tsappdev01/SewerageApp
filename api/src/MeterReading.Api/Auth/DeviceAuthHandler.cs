using System.Security.Claims;
using System.Text.Encodings.Web;
using System.Text.Json;
using MeterReading.Api.Data;
using Microsoft.AspNetCore.Authentication;
using Microsoft.Extensions.Options;

namespace MeterReading.Api.Auth;

/// <summary>
/// Registered phones (spec FR-002): each request carries the phone's id and secret key
/// (X-Device-Id, X-Device-Key) and the reader set on the phone (X-Reader, their LoginEmail).
/// The key proves the call comes from a phone IT registered; the reader is then looked up in
/// vw_MR_Reader as usual. A revoked phone is refused with DEVICE_REVOKED (FR-002.3).
/// </summary>
public sealed class DeviceAuthHandler(
    IOptionsMonitor<AuthenticationSchemeOptions> options,
    ILoggerFactory logger,
    UrlEncoder encoder,
    IOptions<AuthOptions> auth)
    : AuthenticationHandler<AuthenticationSchemeOptions>(options, logger, encoder)
{
    public const string SchemeName = "Device";
    public const string IdHeader = "X-Device-Id";
    public const string KeyHeader = "X-Device-Key";
    public const string ReaderHeader = "X-Reader";
    private const string FailureKey = "device-auth-failure";
    private const string NotRegistered = "This phone is not registered. Ask your supervisor to register it in Settings.";

    protected override async Task<AuthenticateResult> HandleAuthenticateAsync()
    {
        var idText = Request.Headers[IdHeader].ToString();
        var key = Request.Headers[KeyHeader].ToString();
        if (idText.Length == 0 && key.Length == 0) return Fail("DEVICE_NOT_REGISTERED", NotRegistered, "DEVICE_HEADERS_MISSING");
        if (!Guid.TryParse(idText, out var deviceId) || key.Length == 0) return Fail("DEVICE_NOT_REGISTERED", NotRegistered, "DEVICE_HEADERS_INVALID");

        var devices = Context.RequestServices.GetRequiredService<DeviceRepository>();
        var device = await devices.FindAsync(deviceId, Context.RequestAborted);
        if (device?.KeyHash is not { } hash) return Fail("DEVICE_NOT_REGISTERED", NotRegistered, "DEVICE_UNKNOWN");
        if (!DeviceKeys.Matches(key, hash)) return Fail("DEVICE_NOT_REGISTERED", NotRegistered, "DEVICE_KEY_WRONG");
        if (!string.Equals(device.Status, "ACTIVE", StringComparison.OrdinalIgnoreCase))
            return Fail("DEVICE_REVOKED", "This phone has been blocked. Give it to your supervisor. Readings on it are kept.", "DEVICE_REVOKED");

        var reader = Request.Headers[ReaderHeader].ToString().Trim();
        await devices.TouchAsync(deviceId, reader.Length > 0 ? reader : null, Context.RequestAborted);

        var claims = new List<Claim> { new("device_id", deviceId.ToString()), new(ClaimTypes.Role, auth.Value.ReaderRole) };
        if (reader.Length > 0) claims.Add(new Claim("preferred_username", reader));
        return AuthenticateResult.Success(new AuthenticationTicket(new ClaimsPrincipal(new ClaimsIdentity(claims, SchemeName)), SchemeName));
    }

    /// <summary>The phone is told only DEVICE_NOT_REGISTERED; the audit log gets the finer <paramref name="reason"/>.</summary>
    private AuthenticateResult Fail(string code, string title, string reason)
    {
        Audit.Reason(Context, reason);
        Context.Items[FailureKey] = (code, title);
        return AuthenticateResult.Fail(code);
    }

    /// <summary>Refusals are problem details with the code the phone acts on (spec Appendix A: 403).</summary>
    protected override async Task HandleChallengeAsync(AuthenticationProperties properties)
    {
        var (code, title) = Context.Items[FailureKey] is (string c, string t)
            ? (c, t)
            : ("DEVICE_NOT_REGISTERED", "This phone is not registered. Ask your supervisor to register it in Settings.");
        Response.StatusCode = StatusCodes.Status403Forbidden;
        Response.ContentType = "application/problem+json";
        await Response.WriteAsync(JsonSerializer.Serialize(new { title, status = 403, code }));
    }
}

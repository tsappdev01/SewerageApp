using System.Security.Claims;
using System.Text.Encodings.Web;
using Microsoft.AspNetCore.Authentication;
using Microsoft.Extensions.Options;

namespace MeterReading.Api.Auth;

/// <summary>
/// Development only: trusts the X-Dev-User header (a reader's LoginEmail) so the API can be
/// tried without Entra ID. Program.cs refuses to start with it outside Development.
/// </summary>
public sealed class DevelopmentAuthHandler(
    IOptionsMonitor<AuthenticationSchemeOptions> options,
    ILoggerFactory logger,
    UrlEncoder encoder,
    IOptions<AuthOptions> auth)
    : AuthenticationHandler<AuthenticationSchemeOptions>(options, logger, encoder)
{
    public const string SchemeName = "Development";
    public const string Header = "X-Dev-User";

    protected override Task<AuthenticateResult> HandleAuthenticateAsync()
    {
        // X-Reader too, so a registered phone also works against a development API.
        var user = Request.Headers[Header].ToString() is { Length: > 0 } dev ? dev : Request.Headers[DeviceAuthHandler.ReaderHeader].ToString();
        if (string.IsNullOrWhiteSpace(user)) return Task.FromResult(AuthenticateResult.NoResult());
        var identity = new ClaimsIdentity(
            [new Claim("preferred_username", user), new Claim(ClaimTypes.Role, auth.Value.ReaderRole)], SchemeName);
        return Task.FromResult(AuthenticateResult.Success(new AuthenticationTicket(new ClaimsPrincipal(identity), SchemeName)));
    }
}

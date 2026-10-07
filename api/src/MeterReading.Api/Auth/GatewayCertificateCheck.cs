using MeterReading.Api.Endpoints;
using Microsoft.Extensions.Options;

namespace MeterReading.Api.Auth;

/// <summary>
/// With Gateway:RequireClientCertificate, only the DMZ gateway may call the API: every request must
/// come with the gateway's client certificate (mutual TLS). /health/* stays open for internal
/// monitoring. On IIS, set the site's SSL settings to "Accept" (or "Require", which also covers /health).
/// </summary>
public sealed class GatewayCertificateCheck(RequestDelegate next, IOptions<GatewayTrustOptions> options)
{
    private readonly HashSet<string> _allowed = options.Value.ClientCertificateThumbprints
        .Select(t => new string(t.Where(Uri.IsHexDigit).ToArray()))
        .ToHashSet(StringComparer.OrdinalIgnoreCase);

    public async Task InvokeAsync(HttpContext http)
    {
        if (http.Request.Path.StartsWithSegments("/health"))
        {
            await next(http);
            return;
        }
        var certificate = http.Connection.ClientCertificate ?? await http.Connection.GetClientCertificateAsync(http.RequestAborted);
        if (certificate is null || !_allowed.Contains(certificate.Thumbprint))
        {
            Audit.Reason(http, certificate is null ? "GATEWAY_CERT_MISSING" : "GATEWAY_CERT_UNKNOWN");
            await Problems.Of(StatusCodes.Status403Forbidden, "GATEWAY_REQUIRED", "This server is only reached through the gateway.").ExecuteAsync(http);
            return;
        }
        await next(http);
    }
}

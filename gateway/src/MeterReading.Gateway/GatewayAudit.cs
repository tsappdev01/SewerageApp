using System.Diagnostics;
using Yarp.ReverseProxy.Model;

namespace MeterReading.Gateway;

/// <summary>
/// One log line per request under the category "Audit", for IT's monitoring (DMZ 3.7): route, result,
/// time taken, phone id, internet address, trace id, and a reason code when refused. Refusals made by
/// the gateway carry its own code (NOT_FOUND, METHOD_NOT_ALLOWED, REQUEST_TOO_LARGE, RATE_LIMITED,
/// API_UNAVAILABLE); a refusal from the API is API_REFUSED, and the API logs why. Never logged: the
/// device key, bodies, query strings.
/// </summary>
public sealed class GatewayAudit(RequestDelegate next, ILoggerFactory loggers)
{
    public const string Category = "Audit";
    private const string ReasonKey = "audit-reason";
    private readonly ILogger _log = loggers.CreateLogger(Category);

    public static void Reason(HttpContext http, string code) => http.Items[ReasonKey] = code;

    public async Task InvokeAsync(HttpContext http)
    {
        var started = Stopwatch.GetTimestamp();
        try
        {
            await next(http);
        }
        finally
        {
            Write(http, Stopwatch.GetElapsedTime(started));
        }
    }

    private void Write(HttpContext http, TimeSpan took)
    {
        var status = http.Response.StatusCode;
        var route = http.Features.Get<IReverseProxyFeature>()?.Route.Config.RouteId ?? Clip(http.Request.Path.Value ?? "", 100);
        var reason = http.Items[ReasonKey] as string
                     ?? (status == StatusCodes.Status429TooManyRequests ? "RATE_LIMITED" : status >= 400 ? "API_REFUSED" : null);
        var level = status >= 500 ? LogLevel.Error : status >= 400 ? LogLevel.Warning : LogLevel.Information;
        _log.Log(level, "{Method} {Route} {Status} {ElapsedMs}ms device={DeviceId} client={Client} trace={TraceId} reason={Reason}",
            Clip(http.Request.Method, 10), route, status, (long)took.TotalMilliseconds,
            Clip(http.Request.Headers["X-Device-Id"].ToString(), 40), http.Connection.RemoteIpAddress?.ToString(), http.TraceIdentifier, reason ?? "-");
    }

    /// <summary>Values the caller chose: kept short so they cannot flood the log.</summary>
    private static string Clip(string value, int max) => value.Length == 0 ? "-" : value.Length > max ? value[..max] : value;
}

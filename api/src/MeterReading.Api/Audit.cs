using System.Diagnostics;
using System.Text.Json;
using MeterReading.Api.Auth;
using Microsoft.AspNetCore.Diagnostics;

namespace MeterReading.Api;

/// <summary>
/// One log line per request, for IT's monitoring (SIEM): route, result, time taken, phone, reader and
/// trace id; a refusal also carries a reason code so probing can be alerted on. Logged under the
/// category "Audit". Never logged: device keys, tokens, bodies, query strings.
/// </summary>
public static class Audit
{
    public const string Category = "Audit";
    private const string ReasonKey = "audit-reason";

    /// <summary>Why this request was refused, for the audit line (finer than the code the phone gets).</summary>
    public static void Reason(HttpContext http, string code) => http.Items[ReasonKey] = code;

    public static string? ReasonOf(HttpContext http) => http.Items[ReasonKey] as string;
}

public sealed class RequestAudit(RequestDelegate next, ILoggerFactory loggers)
{
    private readonly ILogger _log = loggers.CreateLogger(Audit.Category);

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
        // The route's pattern, not the path: ids stay out of the log's grouping.
        var route = (http.GetEndpoint() as RouteEndpoint)?.RoutePattern.RawText ?? http.Request.Path.Value;
        var device = http.Request.Headers[DeviceAuthHandler.IdHeader].ToString();
        var reader = http.User.FindFirst("preferred_username")?.Value ?? http.Request.Headers[DeviceAuthHandler.ReaderHeader].ToString();
        var reason = Audit.ReasonOf(http) ?? (status == StatusCodes.Status429TooManyRequests ? "RATE_LIMITED" : null);
        var level = status >= 500 ? LogLevel.Error : status >= 400 ? LogLevel.Warning : LogLevel.Information;
        _log.Log(level,
            "{Method} {Route} {Status} {ElapsedMs}ms device={DeviceId} reader={Reader} client={Client} trace={TraceId} reason={Reason}",
            http.Request.Method, route, status, (long)took.TotalMilliseconds,
            Clip(device), Clip(reader), http.Connection.RemoteIpAddress?.ToString(), http.TraceIdentifier, reason ?? "-");
    }

    /// <summary>Header values come from the caller: keep them short so a long one cannot flood the log.</summary>
    private static string Clip(string value) => value.Length == 0 ? "-" : value.Length > 80 ? value[..80] : value;
}

/// <summary>
/// A body the API cannot read (bad JSON, or a field the API does not know) is VALIDATION_FAILED, with
/// no detail of the parser's message (spec Appendix A; DMZ 3.5: unknown properties are refused).
/// </summary>
public sealed class BadRequestHandler : IExceptionHandler
{
    public async ValueTask<bool> TryHandleAsync(HttpContext http, Exception exception, CancellationToken ct)
    {
        if (exception is not BadHttpRequestException bad) return false;
        var unreadable = bad.InnerException is JsonException;
        Audit.Reason(http, unreadable ? "BODY_UNREADABLE" : "BAD_REQUEST");
        http.Response.StatusCode = bad.StatusCode;
        var title = unreadable
            ? "The app sent something this server does not understand. Update the app, or ask IT."
            : "This request is not complete.";
        await Results.Problem(title: title, statusCode: bad.StatusCode, extensions: new Dictionary<string, object?> { ["code"] = "VALIDATION_FAILED" })
            .ExecuteAsync(http);
        return true;
    }
}

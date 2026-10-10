using System.Net;
using System.Security.Cryptography.X509Certificates;
using System.Threading.RateLimiting;
using MeterReading.Gateway;
using Microsoft.AspNetCore.Http.Features;
using Yarp.ReverseProxy.Configuration;
using Yarp.ReverseProxy.Forwarder;
using Yarp.ReverseProxy.Transforms;

// The DMZ gateway (docs/deployment.md, "DMZ gateway"): phones talk to it on 443; it passes the app's
// own requests on to the internal Meter Reading API and nothing else. It has no database and stores
// nothing. The internal API still checks the device key, the reader and every business rule.
var builder = WebApplication.CreateBuilder(args);
builder.WebHost.ConfigureKestrel(k => k.AddServerHeader = false);
var options = builder.Configuration.GetSection(GatewayOptions.Section).Get<GatewayOptions>() ?? new GatewayOptions();
if (!Uri.TryCreate(options.ApiBaseUrl, UriKind.Absolute, out var apiUri))
    throw new InvalidOperationException("Gateway:ApiBaseUrl is not set (the internal Meter Reading API, e.g. https://meterreading-api.internal/).");
var clientCertificate = LoadClientCertificate(options.ClientCertificate);
if (apiUri.Scheme == Uri.UriSchemeHttps && clientCertificate is null && !builder.Environment.IsDevelopment())
    throw new InvalidOperationException("Gateway:ClientCertificate is not set: the internal API only accepts the gateway with its certificate.");

builder.Services.AddProblemDetails();
builder.Services.AddRateLimiter(o =>
{
    o.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
    o.OnRejected = (context, ct) => new ValueTask(Problem(context.HttpContext, 429, "RATE_LIMITED", "Too many requests. Wait a minute and try again.").ExecuteAsync(context.HttpContext));
    // DMZ 3.5: per phone (uploads apart from the rest), with a ceiling per address and one for everyone.
    o.GlobalLimiter = PartitionedRateLimiter.CreateChained(
        PartitionedRateLimiter.Create<HttpContext, string>(http => PerMinute(ClientAddress(http), options.RequestsPerMinute)),
        PartitionedRateLimiter.Create<HttpContext, string>(http => PhoneId(http) is not { } phone
            ? RateLimitPartition.GetNoLimiter("no-phone")
            : Routes.IsImageUpload(http.Request)
                ? PerMinute("upload:" + phone, options.UploadsPerMinutePerPhone)
                : PerMinute("phone:" + phone, options.RequestsPerMinutePerPhone)),
        PartitionedRateLimiter.Create<HttpContext, string>(_ => PerMinute("all", options.TotalRequestsPerMinute)));
    o.AddPolicy(Routes.RegisterPolicy, http => PerMinute(ClientAddress(http), options.RegistrationsPerMinute));
});

builder.Services.AddReverseProxy()
    .LoadFromMemory(Routes.Build(),
    [
        new ClusterConfig
        {
            ClusterId = Routes.Cluster,
            Destinations = new Dictionary<string, DestinationConfig> { ["api"] = new() { Address = options.ApiBaseUrl } },
            HttpRequest = new ForwarderRequestConfig { ActivityTimeout = TimeSpan.FromSeconds(options.TimeoutSeconds) },
        },
    ])
    .ConfigureHttpClient((_, handler) =>
    {
        if (clientCertificate is not null) handler.SslOptions.ClientCertificates = new X509CertificateCollection { clientCertificate };
        if (!string.IsNullOrWhiteSpace(options.ApiCertificateThumbprint))
        {
            var pinned = Normalize(options.ApiCertificateThumbprint);
            handler.SslOptions.RemoteCertificateValidationCallback = (_, certificate, _, _) =>
                certificate is X509Certificate2 c && string.Equals(c.Thumbprint, pinned, StringComparison.OrdinalIgnoreCase);
        }
    })
    .AddTransforms(t =>
    {
        // Nothing from the internet may pose as the test sign-in or as an earlier proxy.
        t.AddRequestHeaderRemove("X-Dev-User");
        t.AddRequestHeaderRemove("Forwarded");
        t.AddRequestHeaderRemove("X-Original-For");
        // Our own X-Forwarded-* replace any the caller sent, so the API sees the phone's real address.
        t.AddXForwarded(ForwardedTransformActions.Set);
        t.AddResponseHeaderRemove("Server", ResponseCondition.Always);
        t.AddResponseHeaderRemove("X-Powered-By", ResponseCondition.Always);
    });

var app = builder.Build();

// One audit line per request, with a reason code for every refusal (DMZ 3.7; GatewayAudit.cs).
app.UseMiddleware<GatewayAudit>();

// DMZ 3.5: on every answer, the gateway's own or the API's.
app.Use(async (http, next) =>
{
    http.Response.OnStarting(() =>
    {
        var headers = http.Response.Headers;
        headers.StrictTransportSecurity = "max-age=31536000";
        headers.XContentTypeOptions = "nosniff";
        headers.CacheControl = "no-store";
        headers.Pragma = "no-cache";
        // An API answers JSON only: nothing may frame it, run in it, or learn where it was called from.
        headers.XFrameOptions = "DENY";
        headers.ContentSecurityPolicy = "default-src 'none'; frame-ancestors 'none'";
        headers["Referrer-Policy"] = "no-referrer";
        return Task.CompletedTask;
    });
    await next();
});

// Size limits first: a body larger than its route allows never reaches the API.
app.Use(async (http, next) =>
{
    var limit = Routes.IsImageUpload(http.Request) ? options.MaxImageBytes
        : Routes.IsInspectionSubmit(http.Request) ? options.MaxInspectionBytes
        : options.MaxJsonBytes;
    if (http.Request.ContentLength > limit)
    {
        await Problem(http, 413, "REQUEST_TOO_LARGE", "This is too large to send.").ExecuteAsync(http);
        return;
    }
    if (http.Features.Get<IHttpMaxRequestBodySizeFeature>() is { IsReadOnly: false } size) size.MaxRequestBodySize = limit;
    await next();
});
app.UseRateLimiter();

// The gateway's own check, for IT's monitoring: answers without asking the API.
app.MapGet("/gateway/health", () => Results.Ok(new { status = "live" }));
app.MapReverseProxy(proxy => proxy.Use(async (http, next) =>
{
    await next();
    // The internal API could not be reached: phones keep the reading and try again later.
    var error = http.Features.Get<IForwarderErrorFeature>();
    if (error is not null && !http.Response.HasStarted)
    {
        var timeout = error.Error is ForwarderError.RequestTimedOut;
        await Problem(http, timeout ? 504 : 502, "API_UNAVAILABLE", "The server cannot be reached just now. Your readings are kept on the phone.").ExecuteAsync(http);
    }
}));
// "{**path}": also paths that look like files (/web.config), which the plain fallback skips.
app.MapFallback("{**path}", http => (Routes.IsKnownPath(http.Request.Path)
    ? Problem(http, 405, "METHOD_NOT_ALLOWED", "This is not allowed here.")
    : Problem(http, 404, "NOT_FOUND", "There is nothing here.")).ExecuteAsync(http));

app.Run();

static IResult Problem(HttpContext http, int status, string code, string title)
{
    GatewayAudit.Reason(http, code);
    return Results.Problem(title: title, statusCode: status, extensions: new Dictionary<string, object?> { ["code"] = code });
}

static RateLimitPartition<string> PerMinute(string key, int limit) =>
    RateLimitPartition.GetFixedWindowLimiter(key, _ => new FixedWindowRateLimiterOptions { PermitLimit = limit, Window = TimeSpan.FromMinutes(1) });

// The phone's id as it sends it. Not proven here (the API checks its key), so it only spreads the limits;
// the per-address and total limits hold for anyone who makes ids up.
static string? PhoneId(HttpContext http) =>
    Guid.TryParse(http.Request.Headers["X-Device-Id"].ToString(), out var id) ? id.ToString() : null;

// The gateway faces the internet directly, so the connection's address is the phone's (or its carrier's).
static string ClientAddress(HttpContext http) => http.Connection.RemoteIpAddress?.ToString() ?? "unknown";

static string Normalize(string thumbprint) => new(thumbprint.Where(Uri.IsHexDigit).ToArray());

static X509Certificate2? LoadClientCertificate(ClientCertificateOptions o)
{
    if (!string.IsNullOrWhiteSpace(o.Path))
        return X509CertificateLoader.LoadPkcs12FromFile(o.Path, o.Password, X509KeyStorageFlags.MachineKeySet);
    if (string.IsNullOrWhiteSpace(o.Thumbprint)) return null;
    using var store = new X509Store(StoreName.My, StoreLocation.LocalMachine);
    store.Open(OpenFlags.ReadOnly);
    var found = store.Certificates.Find(X509FindType.FindByThumbprint, Normalize(o.Thumbprint), validOnly: false);
    return found.Count > 0 ? found[0] : throw new InvalidOperationException($"Gateway:ClientCertificate:Thumbprint {o.Thumbprint} is not in LocalMachine\\My.");
}

public partial class Program;

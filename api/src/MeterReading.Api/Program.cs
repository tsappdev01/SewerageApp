using System.Text.Json.Serialization;
using MeterReading.Api;
using MeterReading.Api.Auth;
using MeterReading.Api.Data;
using MeterReading.Api.Domain;
using MeterReading.Api.Endpoints;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.Identity.Web;

var builder = WebApplication.CreateBuilder(args);
var config = builder.Configuration;

builder.Services.Configure<SourceViewsOptions>(config.GetSection(SourceViewsOptions.Section));
builder.Services.Configure<ReadingRulesOptions>(config.GetSection(ReadingRulesOptions.Section));
builder.Services.Configure<AuthOptions>(config.GetSection(AuthOptions.Section));
builder.Services.Configure<ImageStoreOptions>(config.GetSection(ImageStoreOptions.Section));
builder.Services.Configure<PmsTransferOptions>(config.GetSection(PmsTransferOptions.Section));
builder.Services.Configure<GatewayTrustOptions>(config.GetSection(GatewayTrustOptions.Section));
builder.Services.Configure<InspectionOptions>(config.GetSection(InspectionOptions.Section));
builder.Services.AddScoped<PmsTransferService>();
builder.Services.AddHostedService<PmsTransferWorker>();
switch ((config[$"{ImageStoreOptions.Section}:Kind"] ?? "Database").ToUpperInvariant())
{
    case "DATABASE": builder.Services.AddSingleton<IImageStore, DatabaseImageStore>(); break;
    case "FILESYSTEM": builder.Services.AddSingleton<IImageStore, FileSystemImageStore>(); break;
    case "AZUREBLOB": builder.Services.AddSingleton<IImageStore, AzureBlobImageStore>(); break;
    default: throw new InvalidOperationException("ImageStore:Kind must be Database, FileSystem or AzureBlob.");
}

builder.Services.AddSingleton<SqlConnectionFactory>();
builder.Services.AddScoped<MeterReadingRepository>();
builder.Services.AddScoped<DeviceRepository>();
builder.Services.AddScoped<InspectionRepository>();
builder.Services.AddScoped<InspectionService>();
// Registration codes can be tried at most Devices:RegisterPerMinute (10) times a minute per address.
var registerPerMinute = config.GetValue("Devices:RegisterPerMinute", 10);
builder.Services.AddRateLimiter(o =>
{
    o.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
    o.AddPolicy(DeviceEndpoints.RateLimitPolicy, http => System.Threading.RateLimiting.RateLimitPartition.GetFixedWindowLimiter(
        http.Connection.RemoteIpAddress?.ToString() ?? "unknown",
        _ => new System.Threading.RateLimiting.FixedWindowRateLimiterOptions { PermitLimit = registerPerMinute, Window = TimeSpan.FromMinutes(1) }));
});
builder.Services.AddScoped<ReaderService>();
builder.Services.AddScoped<SubmitService>();
builder.Services.AddSingleton(TimeProvider.System);
builder.Services.AddScoped<CurrentReader>();
builder.Services.AddHttpContextAccessor();
builder.Services.AddProblemDetails();
builder.Services.AddOpenApi();
builder.Services.ConfigureHttpJsonOptions(o => o.SerializerOptions.Converters.Add(new JsonStringEnumConverter()));

// DMZ gateway (gateway/): trust its X-Forwarded-For, and optionally require its client certificate.
var gateway = config.GetSection(GatewayTrustOptions.Section).Get<GatewayTrustOptions>() ?? new GatewayTrustOptions();
if (gateway.RequireClientCertificate && gateway.ClientCertificateThumbprints.Length == 0)
    throw new InvalidOperationException("Gateway:RequireClientCertificate is on but Gateway:ClientCertificateThumbprints is empty.");
builder.Services.Configure<ForwardedHeadersOptions>(o =>
{
    o.ForwardedHeaders = ForwardedHeaders.XForwardedFor | ForwardedHeaders.XForwardedProto;
    o.ForwardLimit = 1;
    o.KnownProxies.Clear();
    o.KnownIPNetworks.Clear(); // only the gateway's own addresses, never a whole network
    foreach (var address in gateway.KnownProxies) o.KnownProxies.Add(System.Net.IPAddress.Parse(address.Trim()));
});

var auth = config.GetSection(AuthOptions.Section).Get<AuthOptions>() ?? new AuthOptions();
if (string.Equals(auth.Mode, "Development", StringComparison.OrdinalIgnoreCase))
{
    if (!builder.Environment.IsDevelopment())
        throw new InvalidOperationException("Auth:Mode 'Development' trusts a request header and is refused outside the Development environment.");
    builder.Services.AddAuthentication(DevelopmentAuthHandler.SchemeName)
        .AddScheme<AuthenticationSchemeOptions, DevelopmentAuthHandler>(DevelopmentAuthHandler.SchemeName, null);
}
else if (string.Equals(auth.Mode, "Device", StringComparison.OrdinalIgnoreCase))
{
    // FR-002: registered phones with their own secret key (db/009, db/ops/). Safe in Production.
    builder.Services.AddAuthentication(DeviceAuthHandler.SchemeName)
        .AddScheme<AuthenticationSchemeOptions, DeviceAuthHandler>(DeviceAuthHandler.SchemeName, null);
}
else
{
    // FR-001: Microsoft Entra ID access tokens; app roles arrive in the "roles" claim.
    builder.Services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
        .AddMicrosoftIdentityWebApi(config.GetSection("AzureAd"));
}
builder.Services.AddAuthorization(o =>
    o.AddPolicy(ReaderEndpoints.Policy, p => p.RequireAuthenticatedUser().RequireRole(auth.ReaderRole)));

var app = builder.Build();

// Fail at startup, not on the first request, if the view schema setting is unusable.
_ = app.Services.GetRequiredService<SqlConnectionFactory>();

app.UseExceptionHandler();
app.UseStatusCodePages();
// The phone's address from the gateway, before the rate limiter counts per address.
if (gateway.KnownProxies.Length > 0) app.UseForwardedHeaders();
if (gateway.RequireClientCertificate) app.UseMiddleware<GatewayCertificateCheck>();
app.UseAuthentication();
app.UseAuthorization();
app.UseRateLimiter();

if (app.Environment.IsDevelopment()) app.MapOpenApi();

app.MapGet("/health/live", () => Results.Ok(new { status = "live" })).AllowAnonymous().ExcludeFromDescription();
app.MapGet("/health/ready", async (MeterReadingRepository repo, PmsTransferService transfer, DeviceRepository devices, InspectionRepository inspections, Microsoft.Extensions.Options.IOptions<PmsTransferOptions> transferOptions, ILogger<Program> log, CancellationToken ct) =>
{
    try
    {
        var missing = (await repo.FindMissingViewsAsync(ct)).ToList();
        if (transferOptions.Value.Enabled && await transfer.FindProblemAsync(ct) is { } problem) missing.Add(problem);
        if (string.Equals(auth.Mode, "Device", StringComparison.OrdinalIgnoreCase) && !await devices.TablesExistAsync(ct))
            missing.Add("mr.DeviceRegistrationCode (run db/009_device_keys.sql)");
        // Field inspection is optional: only when its views exist must its tables exist too.
        if (await inspections.AvailableAsync(ct) is (true, false))
            missing.Add("mr.InspectionVisit or its AtProperty column (run db/010_field_inspection.sql and db/012_inspection_location.sql)");
        return missing.Count == 0
            ? Results.Ok(new { status = "ready" })
            : Results.Json(new { status = "not ready", missing }, statusCode: StatusCodes.Status503ServiceUnavailable);
    }
    catch (Exception e)
    {
        log.LogError(e, "Readiness check could not reach the database");
        return Results.Json(new { status = "not ready", error = "database unreachable" }, statusCode: StatusCodes.Status503ServiceUnavailable);
    }
}).AllowAnonymous().ExcludeFromDescription();

app.MapReaderEndpoints();
app.MapReadingEndpoints();
app.MapImageEndpoints();
app.MapDeviceEndpoints();
app.MapInspectionEndpoints();

app.Run();

public partial class Program;

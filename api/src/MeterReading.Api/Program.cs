using System.Text.Json.Serialization;
using MeterReading.Api;
using MeterReading.Api.Auth;
using MeterReading.Api.Data;
using MeterReading.Api.Domain;
using MeterReading.Api.Endpoints;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.Identity.Web;

var builder = WebApplication.CreateBuilder(args);
var config = builder.Configuration;

builder.Services.Configure<SourceViewsOptions>(config.GetSection(SourceViewsOptions.Section));
builder.Services.Configure<ReadingRulesOptions>(config.GetSection(ReadingRulesOptions.Section));
builder.Services.Configure<AuthOptions>(config.GetSection(AuthOptions.Section));

builder.Services.AddSingleton<SqlConnectionFactory>();
builder.Services.AddScoped<MeterReadingRepository>();
builder.Services.AddScoped<ReaderService>();
builder.Services.AddScoped<SubmitService>();
builder.Services.AddSingleton(TimeProvider.System);
builder.Services.AddScoped<CurrentReader>();
builder.Services.AddHttpContextAccessor();
builder.Services.AddProblemDetails();
builder.Services.AddOpenApi();
builder.Services.ConfigureHttpJsonOptions(o => o.SerializerOptions.Converters.Add(new JsonStringEnumConverter()));

var auth = config.GetSection(AuthOptions.Section).Get<AuthOptions>() ?? new AuthOptions();
if (string.Equals(auth.Mode, "Development", StringComparison.OrdinalIgnoreCase))
{
    if (!builder.Environment.IsDevelopment())
        throw new InvalidOperationException("Auth:Mode 'Development' trusts a request header and is refused outside the Development environment.");
    builder.Services.AddAuthentication(DevelopmentAuthHandler.SchemeName)
        .AddScheme<AuthenticationSchemeOptions, DevelopmentAuthHandler>(DevelopmentAuthHandler.SchemeName, null);
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
app.UseAuthentication();
app.UseAuthorization();

if (app.Environment.IsDevelopment()) app.MapOpenApi();

app.MapGet("/health/live", () => Results.Ok(new { status = "live" })).AllowAnonymous().ExcludeFromDescription();
app.MapGet("/health/ready", async (MeterReadingRepository repo, ILogger<Program> log, CancellationToken ct) =>
{
    try
    {
        var missing = await repo.FindMissingViewsAsync(ct);
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

app.Run();

public partial class Program;

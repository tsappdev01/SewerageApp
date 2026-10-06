using Yarp.ReverseProxy.Configuration;

namespace MeterReading.Gateway;

/// <summary>
/// The only requests the gateway passes on: what the Meter Reader app calls, with its methods. Fixed
/// in code on purpose, so a configuration slip cannot open more of the internal API. Anything else is
/// answered here with 404 (unknown path) or 405 (known path, wrong method).
/// </summary>
public static class Routes
{
    public const string Cluster = "api";
    public const string RegisterPolicy = "register";

    /// <summary>(id, path, methods). Paths use the API's own route shapes.</summary>
    public static readonly IReadOnlyList<(string Id, string Path, string[] Methods)> Allowed =
    [
        ("register", "/api/v1/devices/register", ["POST"]),
        ("me", "/api/v1/me", ["GET"]),
        ("sync", "/api/v1/sync/meters", ["GET"]),
        ("search", "/api/v1/properties/search", ["GET"]),
        ("mine", "/api/v1/readings/mine", ["GET"]),
        ("summary", "/api/v1/summary", ["GET"]),
        ("meter", "/api/v1/meters/{meterId}", ["GET"]),
        ("submit", "/api/v1/readings", ["POST"]),
        ("image", "/api/v1/readings/{transactionId}/images/{imageId}", ["GET", "PUT"]),
        // Field inspection (spec §16).
        ("inspection-plan", "/api/v1/inspections/plan", ["GET"]),
        ("inspection-units", "/api/v1/inspections/units", ["GET"]),
        ("inspection-submit", "/api/v1/inspections", ["POST"]),
        ("inspection-image", "/api/v1/inspections/{visitId}/images/{imageId}", ["GET", "PUT"]),
        // The app's Settings → Test button. /health/ready stays internal: it names views and tables.
        ("live", "/health/live", ["GET"]),
    ];

    public static IReadOnlyList<RouteConfig> Build() => Allowed.Select(r => new RouteConfig
    {
        RouteId = r.Id,
        ClusterId = Cluster,
        RateLimiterPolicy = r.Id == "register" ? RegisterPolicy : null,
        Match = new RouteMatch { Path = r.Path, Methods = r.Methods },
    }).ToList();

    /// <summary>True when the path is one of ours (for 405 instead of 404 on a wrong method).</summary>
    public static bool IsKnownPath(PathString path)
    {
        var segments = path.Value?.Trim('/').Split('/') ?? [];
        return Allowed.Any(r =>
        {
            var pattern = r.Path.Trim('/').Split('/');
            return pattern.Length == segments.Length &&
                   pattern.Zip(segments).All(p => p.First.StartsWith('{') || string.Equals(p.First, p.Second, StringComparison.OrdinalIgnoreCase));
        });
    }

    public static bool IsImageUpload(HttpRequest request) =>
        HttpMethods.IsPut(request.Method)
        && (request.Path.StartsWithSegments("/api/v1/readings") || request.Path.StartsWithSegments("/api/v1/inspections"))
        && request.Path.Value!.Contains("/images/", StringComparison.OrdinalIgnoreCase);

    /// <summary>A whole inspection visit: one result per unit, so larger than a reading.</summary>
    public static bool IsInspectionSubmit(HttpRequest request) =>
        HttpMethods.IsPost(request.Method) && string.Equals(request.Path.Value?.TrimEnd('/'), "/api/v1/inspections", StringComparison.OrdinalIgnoreCase);
}

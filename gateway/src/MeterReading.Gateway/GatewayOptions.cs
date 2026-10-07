namespace MeterReading.Gateway;

/// <summary>Settings for the DMZ gateway (appsettings.json section "Gateway", or Gateway__* environment variables).</summary>
public sealed class GatewayOptions
{
    public const string Section = "Gateway";

    /// <summary>The internal Meter Reading API, e.g. https://meterreading-api.internal/. The only place the gateway connects to.</summary>
    public string ApiBaseUrl { get; set; } = "";

    /// <summary>The gateway's client certificate, shown to the internal API (mutual TLS).</summary>
    public ClientCertificateOptions ClientCertificate { get; set; } = new();

    /// <summary>
    /// Optional: the internal API's TLS certificate thumbprint. When set, only that certificate is accepted,
    /// even if the API uses a certificate the DMZ server does not otherwise trust (e.g. self-signed).
    /// </summary>
    public string? ApiCertificateThumbprint { get; set; }

    /// <summary>Largest JSON body (readings, registration). Photos have their own limit.</summary>
    public long MaxJsonBytes { get; set; } = 64 * 1024;

    /// <summary>Largest inspection visit (one result per unit; a labour camp can have hundreds of rooms).</summary>
    public long MaxInspectionBytes { get; set; } = 512 * 1024;

    /// <summary>Largest photo upload; a little above the API's 2 MB limit so the API can answer with its own message.</summary>
    public long MaxImageBytes { get; set; } = 2_100_000;

    /// <summary>
    /// Requests per minute per internet address, all routes together: a ceiling only, because many phones
    /// on one mobile network can share an address. The per-phone limits below do the real work.
    /// </summary>
    public int RequestsPerMinute { get; set; } = 3000;

    /// <summary>Requests per minute per phone (its X-Device-Id), photo uploads not counted (DMZ 3.5).</summary>
    public int RequestsPerMinutePerPhone { get; set; } = 120;

    /// <summary>Photo uploads per minute per phone: a day's photos waiting on a phone go up one after another.</summary>
    public int UploadsPerMinutePerPhone { get; set; } = 120;

    /// <summary>All requests through the gateway together, from every phone.</summary>
    public int TotalRequestsPerMinute { get; set; } = 6000;

    /// <summary>Phone registrations per minute per address (one-time codes must not be guessable).</summary>
    public int RegistrationsPerMinute { get; set; } = 10;

    /// <summary>How long the gateway waits for the internal API.</summary>
    public int TimeoutSeconds { get; set; } = 60;
}

public sealed class ClientCertificateOptions
{
    /// <summary>A .pfx file with the private key. Use this or <see cref="Thumbprint"/>.</summary>
    public string? Path { get; set; }

    public string? Password { get; set; }

    /// <summary>A certificate in the Windows store LocalMachine\My (the app pool needs read access to its key).</summary>
    public string? Thumbprint { get; set; }
}

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

    /// <summary>Largest photo upload; a little above the API's 2 MB limit so the API can answer with its own message.</summary>
    public long MaxImageBytes { get; set; } = 2_100_000;

    /// <summary>Requests per minute per address, for all routes together.</summary>
    public int RequestsPerMinute { get; set; } = 600;

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

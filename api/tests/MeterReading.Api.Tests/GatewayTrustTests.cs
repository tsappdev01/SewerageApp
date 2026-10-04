using System.Net;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;

namespace MeterReading.Api.Tests;

/// <summary>
/// The API behind the DMZ gateway: X-Forwarded-For is trusted only from the gateway's address, and
/// with RequireClientCertificate only the gateway's certificate gets in.
/// </summary>
public sealed class GatewayTrustTests
{
    private const string GatewayAddress = "10.9.9.9";

    private static X509Certificate2 Certificate(string name)
    {
        using var key = RSA.Create(2048);
        using var cert = new CertificateRequest($"CN={name}", key, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1)
            .CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddDays(30));
        return X509CertificateLoader.LoadCertificate(cert.Export(X509ContentType.Cert));
    }

    private static readonly X509Certificate2 GatewayCert = Certificate("meterreading-gateway");
    private static readonly X509Certificate2 OtherCert = Certificate("someone-else");

    /// <summary>TestServer has no real connection: the test says where a request comes from and which certificate it shows.</summary>
    private sealed class Connection : IStartupFilter
    {
        public Action<IApplicationBuilder> Configure(Action<IApplicationBuilder> next) => app =>
        {
            app.Use(async (http, go) =>
            {
                http.Connection.RemoteIpAddress = IPAddress.Parse(http.Request.Headers["X-Test-Remote"].FirstOrDefault() ?? GatewayAddress);
                http.Connection.ClientCertificate = http.Request.Headers["X-Test-Cert"].FirstOrDefault() switch
                {
                    "gateway" => GatewayCert,
                    "other" => OtherCert,
                    _ => null,
                };
                await go();
            });
            next(app);
        };
    }

    private sealed class Factory(Dictionary<string, string?> settings) : WebApplicationFactory<Program>
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            builder.UseEnvironment("Development");
            builder.UseSetting("ConnectionStrings:MeterReading", ApiFactory.ConnectionString);
            builder.UseSetting("SourceViews:HasReadingHistory", "true");
            builder.UseSetting("Auth:Mode", "Development");
            foreach (var (k, v) in settings) builder.UseSetting(k, v);
            builder.ConfigureServices(s => s.AddSingleton<IStartupFilter, Connection>());
        }
    }

    private static HttpRequestMessage Request(HttpMethod method, string path, string? cert = null, string? forwardedFor = null, string? remote = null)
    {
        var request = new HttpRequestMessage(method, path);
        request.Headers.Add("X-Dev-User", "rashid@dip.example");
        if (cert is not null) request.Headers.Add("X-Test-Cert", cert);
        if (forwardedFor is not null) request.Headers.Add("X-Forwarded-For", forwardedFor);
        if (remote is not null) request.Headers.Add("X-Test-Remote", remote);
        if (method == HttpMethod.Post) request.Content = JsonContent.Create(new { code = "ZZZZ-ZZZZ-ZZZZ" });
        return request;
    }

    [Fact]
    public async Task With_the_gateway_certificate_required_only_the_gateway_gets_in()
    {
        await using var factory = new Factory(new()
        {
            ["Gateway:RequireClientCertificate"] = "true",
            ["Gateway:ClientCertificateThumbprints:0"] = GatewayCert.Thumbprint.ToLowerInvariant(),
        });
        var client = factory.CreateClient();

        var none = await client.SendAsync(Request(HttpMethod.Get, "/api/v1/me"));
        Assert.Equal(HttpStatusCode.Forbidden, none.StatusCode);
        Assert.Equal("GATEWAY_REQUIRED", (await none.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("code").GetString());
        Assert.Equal(HttpStatusCode.Forbidden, (await client.SendAsync(Request(HttpMethod.Get, "/api/v1/me", cert: "other"))).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await client.SendAsync(Request(HttpMethod.Get, "/api/v1/me", cert: "gateway"))).StatusCode);
        // Internal monitoring still works without the certificate.
        Assert.Equal(HttpStatusCode.OK, (await client.SendAsync(Request(HttpMethod.Get, "/health/live"))).StatusCode);
    }

    [Fact]
    public async Task Behind_the_gateway_rate_limits_count_per_phone()
    {
        await using var factory = new Factory(new()
        {
            ["Gateway:KnownProxies:0"] = GatewayAddress,
            ["Devices:RegisterPerMinute"] = "2",
        });
        var client = factory.CreateClient();
        async Task<HttpStatusCode> Register(string phone) => (await client.SendAsync(Request(HttpMethod.Post, "/api/v1/devices/register", forwardedFor: phone))).StatusCode;

        Assert.Equal(HttpStatusCode.UnprocessableEntity, await Register("203.0.113.10")); // wrong code, but counted
        Assert.Equal(HttpStatusCode.UnprocessableEntity, await Register("203.0.113.10"));
        Assert.Equal(HttpStatusCode.TooManyRequests, await Register("203.0.113.10"));
        // Another phone behind the same gateway is not held up.
        Assert.Equal(HttpStatusCode.UnprocessableEntity, await Register("203.0.113.20"));
    }

    [Fact]
    public async Task X_Forwarded_For_from_anyone_but_the_gateway_is_ignored()
    {
        await using var factory = new Factory(new()
        {
            ["Gateway:KnownProxies:0"] = GatewayAddress,
            ["Devices:RegisterPerMinute"] = "2",
        });
        var client = factory.CreateClient();
        async Task<HttpStatusCode> Register(string claimed) =>
            (await client.SendAsync(Request(HttpMethod.Post, "/api/v1/devices/register", forwardedFor: claimed, remote: "198.51.100.7"))).StatusCode;

        // A caller that is not the gateway cannot dodge the limit by claiming new addresses.
        Assert.Equal(HttpStatusCode.UnprocessableEntity, await Register("203.0.113.1"));
        Assert.Equal(HttpStatusCode.UnprocessableEntity, await Register("203.0.113.2"));
        Assert.Equal(HttpStatusCode.TooManyRequests, await Register("203.0.113.3"));
    }
}

using System.Collections.Concurrent;
using System.Net;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.Server.Kestrel.Https;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace MeterReading.Gateway.Tests;

/// <summary>A stand-in for the internal Meter Reading API on a real port: it records what reached it.</summary>
public sealed class FakeApi : IAsyncDisposable
{
    private readonly WebApplication _app;
    public ConcurrentQueue<(string Method, string Path, Dictionary<string, string> Headers, long BodyBytes, string? ClientThumbprint)> Seen { get; } = new();
    public string BaseUrl { get; }

    private FakeApi(WebApplication app, string baseUrl)
    {
        _app = app;
        BaseUrl = baseUrl;
    }

    /// <param name="server">HTTPS with this certificate and a required client certificate; null for plain http.</param>
    public static async Task<FakeApi> StartAsync(X509Certificate2? server = null, string? requiredClientThumbprint = null)
    {
        var builder = WebApplication.CreateSlimBuilder();
        builder.WebHost.ConfigureKestrel(k => k.Listen(IPAddress.Loopback, 0, listen =>
        {
            if (server is null) return;
            listen.UseHttps(https =>
            {
                https.ServerCertificate = server;
                https.ClientCertificateMode = ClientCertificateMode.RequireCertificate;
                https.ClientCertificateValidation = (cert, _, _) => string.Equals(cert.Thumbprint, requiredClientThumbprint, StringComparison.OrdinalIgnoreCase);
            });
        }));
        var app = builder.Build();
        FakeApi? self = null;
        app.Run(async http =>
        {
            using var body = new MemoryStream();
            await http.Request.Body.CopyToAsync(body);
            self!.Seen.Enqueue((http.Request.Method, http.Request.Path + http.Request.QueryString,
                http.Request.Headers.ToDictionary(h => h.Key, h => h.Value.ToString(), StringComparer.OrdinalIgnoreCase),
                body.Length, http.Connection.ClientCertificate?.Thumbprint));
            http.Response.Headers["Server"] = "Kestrel";
            http.Response.Headers["X-Powered-By"] = "ASP.NET";
            await http.Response.WriteAsJsonAsync(new { ok = true });
        });
        await app.StartAsync();
        var address = app.Urls.First().Replace("[::]", "127.0.0.1").Replace("0.0.0.0", "127.0.0.1");
        self = new FakeApi(app, address.Replace("http://", server is null ? "http://" : "https://") + "/");
        return self;
    }

    public async ValueTask DisposeAsync() => await _app.DisposeAsync();
}

public sealed class GatewayFactory(string apiBaseUrl, Dictionary<string, string?>? settings = null) : WebApplicationFactory<Program>
{
    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.UseEnvironment("Development"); // plain http to the fake API is allowed only here
        builder.UseSetting("Gateway:ApiBaseUrl", apiBaseUrl);
        foreach (var (k, v) in settings ?? []) builder.UseSetting(k, v);
    }
}

public sealed class GatewayTests : IAsyncLifetime
{
    private FakeApi _api = null!;
    private GatewayFactory _gateway = null!;

    public async Task InitializeAsync()
    {
        _api = await FakeApi.StartAsync();
        _gateway = new GatewayFactory(_api.BaseUrl);
    }

    public async Task DisposeAsync()
    {
        await _gateway.DisposeAsync();
        await _api.DisposeAsync();
    }

    private static async Task<string?> Code(HttpResponseMessage r) =>
        (await r.Content.ReadFromJsonAsync<JsonElement>()).TryGetProperty("code", out var c) ? c.GetString() : null;

    [Theory]
    [InlineData("POST", "/api/v1/devices/register")]
    [InlineData("GET", "/api/v1/me")]
    [InlineData("GET", "/api/v1/sync/meters?zone=597")]
    [InlineData("GET", "/api/v1/properties/search?q=1499")]
    [InlineData("GET", "/api/v1/readings/mine")]
    [InlineData("GET", "/api/v1/summary")]
    [InlineData("GET", "/api/v1/meters/BC0006")]
    [InlineData("POST", "/api/v1/readings")]
    [InlineData("PUT", "/api/v1/readings/6f1c2b4a-1111-4222-8333-444455556666/images/7f1c2b4a-1111-4222-8333-444455556666?role=DISPLAY")]
    [InlineData("GET", "/api/v1/readings/6f1c2b4a-1111-4222-8333-444455556666/images/7f1c2b4a-1111-4222-8333-444455556666")]
    [InlineData("GET", "/api/v1/inspections/plan?from=2026-10-01&to=2026-10-31")]
    [InlineData("GET", "/api/v1/inspections/units?period=2026-10&property=597-4900(Bldg17)&tenant=T15")]
    [InlineData("POST", "/api/v1/inspections")]
    [InlineData("PUT", "/api/v1/inspections/6f1c2b4a-1111-4222-8333-444455556666/images/7f1c2b4a-1111-4222-8333-444455556666?role=EVIDENCE&result=8f1c2b4a-1111-4222-8333-444455556666")]
    [InlineData("GET", "/api/v1/inspections/6f1c2b4a-1111-4222-8333-444455556666/images/7f1c2b4a-1111-4222-8333-444455556666")]
    [InlineData("GET", "/health/live")]
    public async Task The_apps_own_requests_are_passed_on(string method, string path)
    {
        var request = new HttpRequestMessage(new HttpMethod(method), path);
        if (method is "POST" or "PUT") request.Content = new ByteArrayContent([1, 2, 3]);
        var response = await _gateway.CreateClient().SendAsync(request);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.True(_api.Seen.TryDequeue(out var seen));
        Assert.Equal(method, seen.Method);
        Assert.Equal(path, seen.Path);
    }

    [Theory]
    [InlineData("GET", "/health/ready")]          // names the database's views and tables
    [InlineData("GET", "/openapi/v1.json")]
    [InlineData("GET", "/swagger")]
    [InlineData("GET", "/api/v1/readings/x/images")]
    [InlineData("GET", "/web.config")]
    [InlineData("POST", "/api/v1/admin/anything")]
    public async Task Anything_else_stops_at_the_gateway(string method, string path)
    {
        var response = await _gateway.CreateClient().SendAsync(new HttpRequestMessage(new HttpMethod(method), path));
        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
        Assert.Equal("NOT_FOUND", await Code(response));
        Assert.Empty(_api.Seen);
    }

    [Theory]
    [InlineData("DELETE", "/api/v1/readings")]
    [InlineData("GET", "/api/v1/devices/register")]
    [InlineData("POST", "/api/v1/me")]
    [InlineData("GET", "/api/v1/inspections")]
    [InlineData("DELETE", "/api/v1/inspections/6f1c2b4a-1111-4222-8333-444455556666/images/7f1c2b4a-1111-4222-8333-444455556666")]
    public async Task A_wrong_method_on_a_known_path_is_refused(string method, string path)
    {
        var response = await _gateway.CreateClient().SendAsync(new HttpRequestMessage(new HttpMethod(method), path));
        Assert.Equal(HttpStatusCode.MethodNotAllowed, response.StatusCode);
        Assert.Empty(_api.Seen);
    }

    [Fact]
    public async Task The_test_sign_in_header_and_forged_forwarding_headers_are_removed()
    {
        var request = new HttpRequestMessage(HttpMethod.Get, "/api/v1/me");
        request.Headers.Add("X-Dev-User", "rashid@dip.example");
        request.Headers.Add("X-Forwarded-For", "10.0.0.66");
        request.Headers.Add("Forwarded", "for=10.0.0.66");
        request.Headers.Add("X-Device-Id", "6f1c2b4a-1111-4222-8333-444455556666");
        request.Headers.Add("X-Device-Key", "secret");
        request.Headers.Add("X-Reader", "rashid@dip.ae");
        await _gateway.CreateClient().SendAsync(request);

        Assert.True(_api.Seen.TryDequeue(out var seen));
        Assert.False(seen.Headers.ContainsKey("X-Dev-User"));
        Assert.False(seen.Headers.ContainsKey("Forwarded"));
        Assert.DoesNotContain("10.0.0.66", seen.Headers.GetValueOrDefault("X-Forwarded-For") ?? "");
        // What the app needs goes through untouched.
        Assert.Equal("secret", seen.Headers["X-Device-Key"]);
        Assert.Equal("rashid@dip.ae", seen.Headers["X-Reader"]);
        Assert.Equal("6f1c2b4a-1111-4222-8333-444455556666", seen.Headers["X-Device-Id"]);
    }

    [Fact]
    public async Task The_servers_own_headers_are_not_shown_to_the_internet()
    {
        var response = await _gateway.CreateClient().GetAsync("/api/v1/me");
        Assert.False(response.Headers.Contains("Server"));
        Assert.False(response.Headers.Contains("X-Powered-By"));
    }

    [Fact]
    public async Task A_body_larger_than_its_route_allows_never_reaches_the_api()
    {
        var json = await _gateway.CreateClient().PostAsync("/api/v1/readings", new ByteArrayContent(new byte[70_000]));
        Assert.Equal(HttpStatusCode.RequestEntityTooLarge, json.StatusCode);
        var photo = await _gateway.CreateClient().PutAsync("/api/v1/readings/6f1c2b4a-1111-4222-8333-444455556666/images/7f1c2b4a-1111-4222-8333-444455556666", new ByteArrayContent(new byte[2_200_000]));
        Assert.Equal(HttpStatusCode.RequestEntityTooLarge, photo.StatusCode);
        Assert.Empty(_api.Seen);
        // A normal photo is fine.
        var ok = await _gateway.CreateClient().PutAsync("/api/v1/readings/6f1c2b4a-1111-4222-8333-444455556666/images/7f1c2b4a-1111-4222-8333-444455556666", new ByteArrayContent(new byte[500_000]));
        Assert.Equal(HttpStatusCode.OK, ok.StatusCode);
    }

    [Fact]
    public async Task An_inspection_visit_may_be_larger_than_a_reading_but_not_unlimited()
    {
        var visit = await _gateway.CreateClient().PostAsync("/api/v1/inspections", new ByteArrayContent(new byte[200_000]));
        Assert.Equal(HttpStatusCode.OK, visit.StatusCode);
        var tooBig = await _gateway.CreateClient().PostAsync("/api/v1/inspections", new ByteArrayContent(new byte[600_000]));
        Assert.Equal(HttpStatusCode.RequestEntityTooLarge, tooBig.StatusCode);
        var photo = await _gateway.CreateClient().PutAsync("/api/v1/inspections/6f1c2b4a-1111-4222-8333-444455556666/images/7f1c2b4a-1111-4222-8333-444455556666", new ByteArrayContent(new byte[2_200_000]));
        Assert.Equal(HttpStatusCode.RequestEntityTooLarge, photo.StatusCode);
    }

    [Fact]
    public async Task The_gateway_answers_its_own_health_check_without_the_api()
    {
        var response = await _gateway.CreateClient().GetAsync("/gateway/health");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Empty(_api.Seen);
    }
}

public sealed class GatewayLimitsTests
{
    [Fact]
    public async Task Registration_tries_are_rate_limited()
    {
        await using var api = await FakeApi.StartAsync();
        await using var gateway = new GatewayFactory(api.BaseUrl, new() { ["Gateway:RegistrationsPerMinute"] = "2" });
        var client = gateway.CreateClient();
        var codes = new List<HttpStatusCode>();
        for (var i = 0; i < 3; i++) codes.Add((await client.PostAsync("/api/v1/devices/register", new StringContent("{}"))).StatusCode);
        Assert.Equal([HttpStatusCode.OK, HttpStatusCode.OK, HttpStatusCode.TooManyRequests], codes);
        // Other requests are not affected by the registration limit.
        Assert.Equal(HttpStatusCode.OK, (await client.GetAsync("/api/v1/me")).StatusCode);
    }

    [Fact]
    public async Task All_requests_together_are_rate_limited()
    {
        await using var api = await FakeApi.StartAsync();
        await using var gateway = new GatewayFactory(api.BaseUrl, new() { ["Gateway:RequestsPerMinute"] = "3" });
        var client = gateway.CreateClient();
        for (var i = 0; i < 3; i++) Assert.Equal(HttpStatusCode.OK, (await client.GetAsync("/api/v1/me")).StatusCode);
        var limited = await client.GetAsync("/api/v1/me");
        Assert.Equal(HttpStatusCode.TooManyRequests, limited.StatusCode);
        Assert.Equal("RATE_LIMITED", (await limited.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("code").GetString());
    }

    [Fact]
    public async Task When_the_api_cannot_be_reached_the_phone_is_told_to_try_later()
    {
        // A port nothing listens on.
        await using var gateway = new GatewayFactory("http://127.0.0.1:9/");
        var response = await gateway.CreateClient().GetAsync("/api/v1/me");
        Assert.Equal(HttpStatusCode.BadGateway, response.StatusCode); // 5xx: the app keeps readings and retries
        Assert.Equal("API_UNAVAILABLE", (await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("code").GetString());
    }
}

/// <summary>Mutual TLS: the internal API only talks to the gateway that shows the right certificate.</summary>
public sealed class GatewayCertificateTests : IAsyncLifetime
{
    private readonly string _dir = Directory.CreateTempSubdirectory("gw-certs").FullName;
    private X509Certificate2 _server = null!;
    private X509Certificate2 _client = null!;
    private X509Certificate2 _otherClient = null!;

    private static X509Certificate2 Make(string subject, bool server)
    {
        using var key = RSA.Create(2048);
        var request = new CertificateRequest($"CN={subject}", key, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(
            [new Oid(server ? "1.3.6.1.5.5.7.3.1" : "1.3.6.1.5.5.7.3.2")], false));
        if (server)
        {
            var san = new SubjectAlternativeNameBuilder();
            san.AddIpAddress(IPAddress.Loopback);
            san.AddDnsName("localhost");
            request.CertificateExtensions.Add(san.Build());
        }
        using var cert = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddDays(30));
        return X509CertificateLoader.LoadPkcs12(cert.Export(X509ContentType.Pfx, "pw"), "pw", X509KeyStorageFlags.Exportable);
    }

    public Task InitializeAsync()
    {
        _server = Make("meterreading-api.internal", server: true);
        _client = Make("meterreading-gateway", server: false);
        _otherClient = Make("someone-else", server: false);
        return Task.CompletedTask;
    }

    public Task DisposeAsync()
    {
        Directory.Delete(_dir, recursive: true);
        return Task.CompletedTask;
    }

    private string Pfx(X509Certificate2 cert)
    {
        var path = Path.Combine(_dir, Guid.NewGuid() + ".pfx");
        File.WriteAllBytes(path, cert.Export(X509ContentType.Pfx, "pw"));
        return path;
    }

    private GatewayFactory Gateway(string apiUrl, X509Certificate2 clientCert, string? pin) => new(apiUrl, new()
    {
        ["Gateway:ClientCertificate:Path"] = Pfx(clientCert),
        ["Gateway:ClientCertificate:Password"] = "pw",
        ["Gateway:ApiCertificateThumbprint"] = pin,
    });

    [Fact]
    public async Task The_gateway_shows_its_certificate_and_the_api_accepts_it()
    {
        await using var api = await FakeApi.StartAsync(_server, requiredClientThumbprint: _client.Thumbprint);
        await using var gateway = Gateway(api.BaseUrl, _client, pin: _server.Thumbprint);
        var response = await gateway.CreateClient().GetAsync("/api/v1/me");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.True(api.Seen.TryDequeue(out var seen));
        Assert.Equal(_client.Thumbprint, seen.ClientThumbprint);
    }

    [Fact]
    public async Task Another_certificate_is_refused_by_the_api()
    {
        await using var api = await FakeApi.StartAsync(_server, requiredClientThumbprint: _client.Thumbprint);
        await using var gateway = Gateway(api.BaseUrl, _otherClient, pin: _server.Thumbprint);
        var response = await gateway.CreateClient().GetAsync("/api/v1/me");
        Assert.Equal(HttpStatusCode.BadGateway, response.StatusCode);
        Assert.Empty(api.Seen);
    }

    [Fact]
    public async Task An_api_with_a_different_certificate_than_the_pinned_one_is_not_trusted()
    {
        await using var api = await FakeApi.StartAsync(_server, requiredClientThumbprint: _client.Thumbprint);
        await using var gateway = Gateway(api.BaseUrl, _client, pin: _otherClient.Thumbprint);
        var response = await gateway.CreateClient().GetAsync("/api/v1/me");
        Assert.Equal(HttpStatusCode.BadGateway, response.StatusCode);
        Assert.Empty(api.Seen);
    }
}

/// <summary>DMZ 3.5 and 3.7: security headers, limits per phone, and the audit log.</summary>
public sealed class GatewayHardeningTests
{
    private static HttpRequestMessage FromPhone(HttpMethod method, string path, string phone, string key = "secret-key-123")
    {
        var request = new HttpRequestMessage(method, path);
        request.Headers.Add("X-Device-Id", phone);
        request.Headers.Add("X-Device-Key", key);
        if (method == HttpMethod.Put) request.Content = new ByteArrayContent([1, 2, 3]);
        return request;
    }

    private const string Upload = "/api/v1/readings/6f1c2b4a-1111-4222-8333-444455556666/images/7f1c2b4a-1111-4222-8333-444455556666?role=DISPLAY";

    [Theory]
    [InlineData("/api/v1/me")]       // passed on to the API
    [InlineData("/api/v1/secrets")]  // answered by the gateway
    public async Task Every_answer_carries_the_security_headers(string path)
    {
        await using var api = await FakeApi.StartAsync();
        await using var gateway = new GatewayFactory(api.BaseUrl);
        var response = await gateway.CreateClient().GetAsync(path);
        Assert.Equal("max-age=31536000", response.Headers.GetValues("Strict-Transport-Security").Single());
        Assert.Equal("nosniff", response.Headers.GetValues("X-Content-Type-Options").Single());
        Assert.True(response.Headers.CacheControl?.NoStore);
    }

    [Fact]
    public async Task Each_phone_has_its_own_limit_and_photo_uploads_their_own()
    {
        await using var api = await FakeApi.StartAsync();
        await using var gateway = new GatewayFactory(api.BaseUrl, new()
        {
            ["Gateway:RequestsPerMinutePerPhone"] = "2",
            ["Gateway:UploadsPerMinutePerPhone"] = "3",
        });
        var client = gateway.CreateClient();
        var a = Guid.NewGuid().ToString();
        var b = Guid.NewGuid().ToString();
        async Task<HttpStatusCode> Send(HttpMethod m, string path, string phone) => (await client.SendAsync(FromPhone(m, path, phone))).StatusCode;

        Assert.Equal(HttpStatusCode.OK, await Send(HttpMethod.Get, "/api/v1/me", a));
        Assert.Equal(HttpStatusCode.OK, await Send(HttpMethod.Get, "/api/v1/me", a));
        Assert.Equal(HttpStatusCode.TooManyRequests, await Send(HttpMethod.Get, "/api/v1/me", a));
        // Another phone on the same address is not held back by the first.
        Assert.Equal(HttpStatusCode.OK, await Send(HttpMethod.Get, "/api/v1/me", b));
        // Photos waiting on phone a still go up: uploads count apart.
        for (var i = 0; i < 3; i++) Assert.Equal(HttpStatusCode.OK, await Send(HttpMethod.Put, Upload, a));
        Assert.Equal(HttpStatusCode.TooManyRequests, await Send(HttpMethod.Put, Upload, a));
    }

    [Fact]
    public async Task All_phones_together_have_a_ceiling()
    {
        await using var api = await FakeApi.StartAsync();
        await using var gateway = new GatewayFactory(api.BaseUrl, new() { ["Gateway:TotalRequestsPerMinute"] = "3" });
        var client = gateway.CreateClient();
        for (var i = 0; i < 3; i++)
            Assert.Equal(HttpStatusCode.OK, (await client.SendAsync(FromPhone(HttpMethod.Get, "/api/v1/me", Guid.NewGuid().ToString()))).StatusCode);
        Assert.Equal(HttpStatusCode.TooManyRequests, (await client.SendAsync(FromPhone(HttpMethod.Get, "/api/v1/me", Guid.NewGuid().ToString()))).StatusCode);
    }

    [Fact]
    public async Task Requests_and_refusals_are_audited_with_reason_codes_and_never_the_key()
    {
        var log = new LogCapture(GatewayAudit.Category);
        await using var api = await FakeApi.StartAsync();
        await using var gateway = new GatewayFactory(api.BaseUrl, new() { ["Gateway:RequestsPerMinutePerPhone"] = "1" })
            .WithWebHostBuilder(b => b.ConfigureLogging(l => l.AddProvider(log)));
        var client = gateway.CreateClient();
        var phone = Guid.NewGuid().ToString();

        await client.SendAsync(FromPhone(HttpMethod.Get, "/api/v1/me", phone, "phone-key-should-not-appear"));
        await client.SendAsync(FromPhone(HttpMethod.Get, "/api/v1/me", phone, "phone-key-should-not-appear"));
        await client.GetAsync("/web.config");
        await client.DeleteAsync("/api/v1/me");

        var lines = log.Lines.ToArray();
        Assert.Contains(lines, l => l.StartsWith("Information") && l.Contains("GET me 200") && l.Contains($"device={phone}"));
        Assert.Contains(lines, l => l.StartsWith("Warning") && l.Contains("429") && l.Contains("reason=RATE_LIMITED"));
        Assert.Contains(lines, l => l.Contains("/web.config 404") && l.Contains("reason=NOT_FOUND"));
        Assert.Contains(lines, l => l.Contains("405") && l.Contains("reason=METHOD_NOT_ALLOWED"));
        Assert.DoesNotContain(lines, l => l.Contains("phone-key-should-not-appear"));
    }
}

using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using MeterReading.Api.Auth;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Data.SqlClient;
using Microsoft.Extensions.Logging;

namespace MeterReading.Api.Tests;

/// <summary>The API in Device mode: registered phones with their own key (spec FR-002).</summary>
public sealed class DeviceApiFactory : WebApplicationFactory<Program>
{
    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.UseEnvironment("Development");
        builder.UseSetting("ConnectionStrings:MeterReading", ApiFactory.ConnectionString);
        builder.UseSetting("SourceViews:HasReadingHistory", "true"); // db/dev has the optional history view
        builder.UseSetting("Auth:Mode", "Device");
        builder.UseSetting("Devices:RegisterPerMinute", "1000");
    }
}

[Collection(DatabaseCollection.Name)]
public sealed class DeviceTests : IClassFixture<DeviceApiFactory>, IAsyncLifetime
{
    private const string Rashid = "rashid@dip.example";
    private readonly DeviceApiFactory _factory;
    private readonly string _label = "test-" + Guid.NewGuid().ToString("N")[..8];

    public DeviceTests(DeviceApiFactory factory) => _factory = factory;

    public Task InitializeAsync() => Task.CompletedTask;

    public async Task DisposeAsync()
    {
        await Sql("DELETE FROM mr.Device WHERE Label = @label; DELETE FROM mr.DeviceRegistrationCode WHERE Label = @label;");
    }

    private async Task<object?> Sql(string sql, Dictionary<string, object>? args = null)
    {
        await using var c = new SqlConnection(ApiFactory.ConnectionString);
        await c.OpenAsync();
        await using var cmd = new SqlCommand(sql, c);
        cmd.Parameters.AddWithValue("@label", _label);
        foreach (var (k, v) in args ?? []) cmd.Parameters.AddWithValue(k, v);
        return await cmd.ExecuteScalarAsync();
    }

    /// <summary>A code as db/ops/new_device_code.sql makes it (stored hashed by SQL itself).</summary>
    private async Task<string> NewCode(int validHours = 24)
    {
        var plain = string.Concat(Enumerable.Range(0, 12).Select(_ => "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[Random.Shared.Next(32)]));
        await Sql("INSERT mr.DeviceRegistrationCode (CodeHash, Label, ExpiresAtUtc) VALUES (CONVERT(char(64), HASHBYTES('SHA2_256', CAST(@plain AS varchar(12))), 2), @label, DATEADD(HOUR, @hours, SYSUTCDATETIME()))",
            new() { ["@plain"] = plain, ["@hours"] = validHours });
        return $"{plain[..4]}-{plain[4..8]}-{plain[8..]}";
    }

    private async Task<HttpResponseMessage> Register(string code) =>
        await _factory.CreateClient().PostAsJsonAsync("/api/v1/devices/register", new { code, model = "Galaxy A15", androidVersion = "14", appVersion = "0.3.0" });

    private async Task<(Guid Id, string Key)> Registered()
    {
        var response = await Register(await NewCode());
        Assert.Equal(HttpStatusCode.Created, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        return (body.GetProperty("deviceId").GetGuid(), body.GetProperty("deviceKey").GetString()!);
    }

    private HttpClient Phone(Guid id, string key, string reader = Rashid)
    {
        var client = _factory.CreateClient();
        client.DefaultRequestHeaders.Add(DeviceAuthHandler.IdHeader, id.ToString());
        client.DefaultRequestHeaders.Add(DeviceAuthHandler.KeyHeader, key);
        client.DefaultRequestHeaders.Add(DeviceAuthHandler.ReaderHeader, reader);
        return client;
    }

    [Fact]
    public async Task DMZ_3_7_refusals_are_audited_with_a_reason_code_and_never_the_key()
    {
        var log = new LogCapture(MeterReading.Api.Audit.Category);
        await using var audited = _factory.WithWebHostBuilder(b => b.ConfigureLogging(l => l.AddProvider(log)));
        var (id, key) = await Registered();

        HttpClient Client(Guid deviceId, string deviceKey)
        {
            var c = audited.CreateClient();
            c.DefaultRequestHeaders.Add(DeviceAuthHandler.IdHeader, deviceId.ToString());
            c.DefaultRequestHeaders.Add(DeviceAuthHandler.KeyHeader, deviceKey);
            c.DefaultRequestHeaders.Add(DeviceAuthHandler.ReaderHeader, Rashid);
            return c;
        }

        Assert.Equal(HttpStatusCode.OK, (await Client(id, key).GetAsync("/api/v1/me")).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await Client(id, key + "x").GetAsync("/api/v1/me")).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await Client(Guid.NewGuid(), key).GetAsync("/api/v1/me")).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await audited.CreateClient().GetAsync("/api/v1/me")).StatusCode);

        var lines = log.Lines.ToArray();
        Assert.Contains(lines, l => l.StartsWith("Information") && l.Contains("GET /api/v1/me 200") && l.Contains($"device={id}") && l.Contains($"reader={Rashid}"));
        Assert.Contains(lines, l => l.StartsWith("Warning") && l.Contains("403") && l.Contains("reason=DEVICE_KEY_WRONG"));
        Assert.Contains(lines, l => l.Contains("reason=DEVICE_UNKNOWN"));
        Assert.Contains(lines, l => l.Contains("reason=DEVICE_HEADERS_MISSING"));
        Assert.DoesNotContain(lines, l => l.Contains(key));
    }

    [Fact]
    public async Task DMZ_3_5_a_field_the_api_does_not_know_is_refused()
    {
        var (id, key) = await Registered();
        var body = new StringContent(
            """{"code":"ZZZZ-ZZZZ-ZZZZ","model":"Galaxy A15","colour":"red"}""", System.Text.Encoding.UTF8, "application/json");
        var response = await Phone(id, key).PostAsync("/api/v1/devices/register", body);
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        Assert.Equal("VALIDATION_FAILED", await Code(response));
        Assert.DoesNotContain("colour", await response.Content.ReadAsStringAsync()); // no parser detail
    }

    [Fact]
    public async Task The_fields_the_phone_sends_are_all_known_to_the_api()
    {
        // The phone's RegisterDeviceRequest (android api/ApiDtos.kt), every field set.
        var body = new StringContent(
            $$"""{"code":"{{await NewCode()}}","model":"Galaxy A15","androidVersion":"14","appVersion":"0.4.1"}""", System.Text.Encoding.UTF8, "application/json");
        var response = await _factory.CreateClient().PostAsync("/api/v1/devices/register", body);
        Assert.Equal(HttpStatusCode.Created, response.StatusCode);
    }

    private static async Task<string> Code(HttpResponseMessage r) =>
        (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("code").GetString()!;

    [Fact]
    public async Task FR002_a_phone_registers_with_a_one_time_code_and_its_key_opens_the_api()
    {
        var code = await NewCode();
        var response = await Register("  " + code.ToLowerInvariant().Replace("-", " ") + " "); // typed loosely
        Assert.Equal(HttpStatusCode.Created, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        var id = body.GetProperty("deviceId").GetGuid();
        var key = body.GetProperty("deviceKey").GetString()!;
        Assert.Equal(_label, body.GetProperty("label").GetString());
        Assert.True(key.Length >= 43);

        var me = await Phone(id, key).GetAsync("/api/v1/me");
        Assert.Equal(HttpStatusCode.OK, me.StatusCode);

        // Only the hash is stored; the phone and its last reader are recorded (FR-002.2).
        Assert.Equal(DeviceKeys.Hash(key), (string)(await Sql("SELECT KeyHash FROM mr.Device WHERE DeviceId = @id", new() { ["@id"] = id }))!);
        Assert.Equal(Rashid, (string)(await Sql("SELECT LastReaderId FROM mr.Device WHERE DeviceId = @id", new() { ["@id"] = id }))!);
        Assert.Equal("Galaxy A15", (string)(await Sql("SELECT Model FROM mr.Device WHERE DeviceId = @id", new() { ["@id"] = id }))!);
    }

    [Fact]
    public async Task A_code_works_only_once()
    {
        var code = await NewCode();
        Assert.Equal(HttpStatusCode.Created, (await Register(code)).StatusCode);
        var again = await Register(code);
        Assert.Equal(HttpStatusCode.UnprocessableEntity, again.StatusCode);
        Assert.Equal("REGISTRATION_CODE_INVALID", await Code(again));
    }

    [Fact]
    public async Task An_expired_or_unknown_code_is_refused()
    {
        Assert.Equal("REGISTRATION_CODE_INVALID", await Code(await Register(await NewCode(validHours: -1))));
        Assert.Equal("REGISTRATION_CODE_INVALID", await Code(await Register("ZZZZ-ZZZZ-ZZZZ")));
        Assert.Equal("REGISTRATION_CODE_INVALID", await Code(await Register("short")));
    }

    [Fact]
    public async Task A_wrong_key_or_no_key_is_refused()
    {
        var (id, _) = await Registered();
        var wrong = await Phone(id, DeviceKeys.NewKey()).GetAsync("/api/v1/me");
        Assert.Equal(HttpStatusCode.Forbidden, wrong.StatusCode);
        Assert.Equal("DEVICE_NOT_REGISTERED", await Code(wrong));

        // The development header is not enough in Device mode.
        var client = _factory.CreateClient();
        client.DefaultRequestHeaders.Add("X-Dev-User", Rashid);
        var none = await client.GetAsync("/api/v1/me");
        Assert.Equal(HttpStatusCode.Forbidden, none.StatusCode);
        Assert.Equal("DEVICE_NOT_REGISTERED", await Code(none));
    }

    [Fact]
    public async Task FR002_3_a_revoked_phone_is_refused_and_can_be_reinstated()
    {
        var (id, key) = await Registered();
        await Sql("UPDATE mr.Device SET Status = 'REVOKED', RevokedAtUtc = SYSUTCDATETIME() WHERE DeviceId = @id", new() { ["@id"] = id });
        var refused = await Phone(id, key).GetAsync("/api/v1/me");
        Assert.Equal(HttpStatusCode.Forbidden, refused.StatusCode);
        Assert.Equal("DEVICE_REVOKED", await Code(refused));

        await Sql("UPDATE mr.Device SET Status = 'ACTIVE', RevokedAtUtc = NULL WHERE DeviceId = @id", new() { ["@id"] = id });
        Assert.Equal(HttpStatusCode.OK, (await Phone(id, key).GetAsync("/api/v1/me")).StatusCode);
    }

    [Fact]
    public async Task The_reader_on_the_phone_must_still_be_an_active_reader()
    {
        var (id, key) = await Registered();
        var response = await Phone(id, key, reader: "nobody@dip.example").GetAsync("/api/v1/me");
        Assert.Equal("READER_NOT_FOUND", await Code(response));
    }

    [Fact]
    public async Task Code_hashes_match_SQL_Server()
    {
        Assert.Equal(DeviceKeys.Hash("X3XNBKCULPYT"), (string)(await Sql("SELECT CONVERT(char(64), HASHBYTES('SHA2_256', 'X3XNBKCULPYT'), 2)"))!);
    }

    [Fact]
    public async Task Readiness_reports_the_device_tables()
    {
        var ready = await _factory.CreateClient().GetAsync("/health/ready");
        Assert.Equal(HttpStatusCode.OK, ready.StatusCode);
    }
}

/// <summary>Registration codes cannot be guessed quickly: the endpoint is rate limited per address.</summary>
public sealed class DeviceRateLimitTests
{
    private sealed class TightFactory : WebApplicationFactory<Program>
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            builder.UseEnvironment("Development");
            builder.UseSetting("ConnectionStrings:MeterReading", ApiFactory.ConnectionString);
        builder.UseSetting("SourceViews:HasReadingHistory", "true"); // db/dev has the optional history view
            builder.UseSetting("Auth:Mode", "Device");
            builder.UseSetting("Devices:RegisterPerMinute", "2");
        }
    }

    [Fact]
    public async Task Too_many_tries_in_a_minute_are_refused()
    {
        await using var factory = new TightFactory();
        var client = factory.CreateClient();
        var codes = new List<HttpStatusCode>();
        for (var i = 0; i < 3; i++)
            codes.Add((await client.PostAsJsonAsync("/api/v1/devices/register", new { code = "ZZZZ-ZZZZ-ZZZZ" })).StatusCode);
        Assert.Equal([HttpStatusCode.UnprocessableEntity, HttpStatusCode.UnprocessableEntity, HttpStatusCode.TooManyRequests], codes);
    }
}

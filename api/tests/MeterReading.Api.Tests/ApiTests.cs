using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;

namespace MeterReading.Api.Tests;

/// <summary>
/// Runs the API against a real SQL Server prepared with db/002 and db/dev scripts
/// (see README). Connection string: MR_TEST_SQL, else the local development default.
/// </summary>
public sealed class ApiFactory : WebApplicationFactory<Program>
{
    public static string ConnectionString =>
        Environment.GetEnvironmentVariable("MR_TEST_SQL")
        ?? "Server=localhost,1433;Database=MeterReading;User Id=sa;Password=Dev_Passw0rd!;TrustServerCertificate=True;Encrypt=True";

    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.UseEnvironment("Development");
        builder.UseSetting("ConnectionStrings:MeterReading", ConnectionString);
        builder.UseSetting("Auth:Mode", "Development");
    }
}

public sealed class ApiTests(ApiFactory factory) : IClassFixture<ApiFactory>
{
    private const string Rashid = "rashid@dip.example";
    private const string Anil = "anil@dip.example";

    private HttpClient As(string? user)
    {
        var client = factory.CreateClient();
        if (user is not null) client.DefaultRequestHeaders.Add("X-Dev-User", user);
        return client;
    }

    private async Task<JsonElement> Get(string user, string url)
    {
        var response = await As(user).GetAsync(url);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        return await response.Content.ReadFromJsonAsync<JsonElement>();
    }

    [Fact]
    public async Task Ready_when_views_and_tables_exist()
    {
        var response = await As(null).GetAsync("/health/ready");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task Me_returns_reader_and_open_period()
    {
        var me = await Get(Rashid, "/api/v1/me");
        Assert.Equal("E1001", me.GetProperty("readerId").GetString());
        Assert.Equal("2026-10", me.GetProperty("openPeriod").GetProperty("code").GetString());
    }

    [Fact]
    public async Task Sync_returns_every_active_meter_in_route_order()
    {
        var meters = (await Get(Rashid, "/api/v1/sync/meters")).GetProperty("meters").EnumerateArray().ToList();
        Assert.Equal(18, meters.Count);
        Assert.Equal("1001-I", meters[0].GetProperty("number").GetString());
        Assert.DoesNotContain(meters, m => m.GetProperty("id").GetInt64() == 19); // INACTIVE
    }

    [Fact]
    public async Task Sync_zone_filter_narrows_the_list()
    {
        var meters = (await Get(Rashid, "/api/v1/sync/meters?zone=602")).GetProperty("meters").EnumerateArray();
        Assert.Equal([16L, 17L, 18L], meters.Select(m => m.GetProperty("id").GetInt64()));
    }

    [Fact]
    public async Task A_meter_read_by_another_reader_shows_as_done_for_everyone()
    {
        var meters = (await Get(Rashid, "/api/v1/sync/meters?zone=602")).GetProperty("meters").EnumerateArray()
            .ToDictionary(m => m.GetProperty("id").GetInt64());
        Assert.Equal("SENT", meters[16].GetProperty("state").GetString());     // read by Anil
        Assert.Equal("REVISIT", meters[17].GetProperty("state").GetString());  // Anil could not reach it
        Assert.Equal("PENDING", meters[18].GetProperty("state").GetString());
    }

    [Fact]
    public async Task Sync_maps_state_note_first_reading_and_history_average()
    {
        var meters = (await Get(Rashid, "/api/v1/sync/meters")).GetProperty("meters").EnumerateArray()
            .ToDictionary(m => m.GetProperty("id").GetInt64());
        Assert.Equal("READ_AGAIN", meters[8].GetProperty("state").GetString());
        Assert.Equal("Photo not clear", meters[8].GetProperty("supervisorNote").GetString());
        Assert.Equal("CHECKING", meters[13].GetProperty("state").GetString());
        Assert.True(meters[10].GetProperty("isFirstReading").GetBoolean());
        Assert.Equal(12m, meters[10].GetProperty("previousReading").GetDecimal());
        Assert.Equal(1050m, meters[6].GetProperty("averageConsumption").GetDecimal()); // from history, AVERAGE period skipped
        Assert.Equal(3150m, meters[6].GetProperty("expectedHigh").GetDecimal());
    }

    [Fact]
    public async Task Search_finds_buildings_by_partial_code()
    {
        var result = await Get(Rashid, "/api/v1/properties/search?q=149");
        var codes = result.GetProperty("properties").EnumerateArray().Select(p => p.GetProperty("property").GetProperty("code").GetString());
        Assert.Equal(["1499-W1", "1497"], codes);
    }

    [Fact]
    public async Task Summary_buckets_add_up()
    {
        var s = await Get(Rashid, "/api/v1/summary");
        Assert.Equal(18, s.GetProperty("meters").GetInt32());
        Assert.Equal(9, s.GetProperty("read").GetInt32());
        Assert.Equal(9, s.GetProperty("notRead").GetInt32());
        Assert.Equal(7, s.GetProperty("readByYou").GetInt32());
        Assert.EndsWith("Z", s.GetProperty("lastReceivedUtc").GetString());
    }

    [Fact]
    public async Task Readings_are_scoped_to_the_reader()
    {
        var mine = await Get(Anil, "/api/v1/readings/mine");
        Assert.Equal(2, mine.GetArrayLength());
        Assert.All(mine.EnumerateArray(), r => Assert.True(r.GetProperty("meterId").GetInt64() is 16 or 17));
    }

    [Fact]
    public async Task Summary_by_zone_counts_only_that_zone()
    {
        var s = await Get(Rashid, "/api/v1/summary?zone=602");
        Assert.Equal(3, s.GetProperty("meters").GetInt32());
        Assert.Equal(0, s.GetProperty("readByYou").GetInt32());
    }

    [Fact]
    public async Task Any_reader_can_open_any_active_meter()
    {
        var detail = await Get(Rashid, "/api/v1/meters/16");
        Assert.Equal("3010-I", detail.GetProperty("meter").GetProperty("number").GetString());
    }

    [Fact]
    public async Task Inactive_meter_is_not_found()
    {
        var response = await As(Rashid).GetAsync("/api/v1/meters/19");
        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
        Assert.Contains("METER_NOT_FOUND", await response.Content.ReadAsStringAsync());
    }

    [Fact]
    public async Task Inactive_reader_is_forbidden() =>
        Assert.Equal(HttpStatusCode.Forbidden, (await As("left.company@dip.example").GetAsync("/api/v1/me")).StatusCode);

    [Fact]
    public async Task Anonymous_is_unauthorized() =>
        Assert.Equal(HttpStatusCode.Unauthorized, (await As(null).GetAsync("/api/v1/me")).StatusCode);

    [Theory]
    [InlineData("/api/v1/summary?period=2026-13", HttpStatusCode.BadRequest)]
    [InlineData("/api/v1/summary?period=2020-01", HttpStatusCode.NotFound)]
    [InlineData("/api/v1/properties/search?done=maybe", HttpStatusCode.BadRequest)]
    [InlineData("/api/v1/sync/meters?zone=597;drop", HttpStatusCode.BadRequest)]
    public async Task Bad_input_gets_problem_details(string url, HttpStatusCode expected) =>
        Assert.Equal(expected, (await As(Rashid).GetAsync(url)).StatusCode);
}

public class StartupSafetyTests
{
    [Fact]
    public void Development_sign_in_is_refused_outside_development()
    {
        using var factory = new WebApplicationFactory<Program>().WithWebHostBuilder(b =>
        {
            b.UseEnvironment("Production");
            b.UseSetting("ConnectionStrings:MeterReading", ApiFactory.ConnectionString);
            b.UseSetting("Auth:Mode", "Development");
        });
        var error = Assert.ThrowsAny<Exception>(() => factory.CreateClient());
        Assert.Contains("refused outside the Development environment", error.ToString());
    }
}

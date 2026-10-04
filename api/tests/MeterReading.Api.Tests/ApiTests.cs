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
        builder.UseSetting("ImageStore:Kind", "Database");
        builder.UseSetting("ConnectionStrings:MeterReading", ConnectionString);
        builder.UseSetting("SourceViews:HasReadingHistory", "true"); // db/dev has the optional history view
        builder.UseSetting("Auth:Mode", "Development");
    }
}

/// <summary>The current tenant of each development meter's property (db/dev/000, vw_MR_Tenant).</summary>
public static class DevTenants
{
    /// <summary>Stands for "the meter's own tenant" in test helpers.</summary>
    public const string Own = "<own>";

    public static string? Of(string meterId) => int.Parse(meterId[2..]) switch
    {
        <= 2 => "T-0101",              // 1100
        <= 4 => "T-0102",              // 1101 (also T-0199)
        <= 11 => "T-0201",             // 1499-W1
        <= 13 => "T-0202",             // 1502
        <= 15 => "T-0203",             // 1497
        _ => null,                     // 3010: lease ended; 4001: no tenant
    };

    public static string? Resolve(string? tenant, string meterId) => tenant == Own ? Of(meterId) : tenant;
}

/// <summary>Tests that use the shared database run one class at a time.</summary>
[CollectionDefinition(Name)]
public sealed class DatabaseCollection : ICollectionFixture<ApiFactory>
{
    public const string Name = "Database";
}

[Collection(DatabaseCollection.Name)]
public sealed class ApiTests(ApiFactory factory)
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
        Assert.Equal(17, meters.Count); // plot 4001 has no tenant, so the property view leaves it out
        Assert.Equal("1001-I", meters[0].GetProperty("number").GetString());
        Assert.Equal("BC0001", meters[0].GetProperty("id").GetString());          // barcode, text
        Assert.Equal("IRRIGATION", meters[0].GetProperty("type").GetString());   // view says "Irrigation"
        Assert.DoesNotContain(meters, m => m.GetProperty("id").GetString() == "BC0019"); // Status 0
    }

    [Fact]
    public async Task Sync_zone_filter_narrows_the_list()
    {
        var meters = (await Get(Rashid, "/api/v1/sync/meters?zone=602")).GetProperty("meters").EnumerateArray();
        Assert.Equal(["BC0016", "BC0017"], meters.Select(m => m.GetProperty("id").GetString()));
    }

    [Fact]
    public async Task A_meter_read_by_another_reader_shows_as_done_for_everyone()
    {
        var meters = (await Get(Rashid, "/api/v1/sync/meters?zone=602")).GetProperty("meters").EnumerateArray()
            .ToDictionary(m => m.GetProperty("id").GetString()!);
        Assert.Equal("SENT", meters["BC0016"].GetProperty("state").GetString());     // read by Anil
        Assert.Equal("REVISIT", meters["BC0017"].GetProperty("state").GetString());  // Anil could not reach it

    }

    [Fact]
    public async Task Sync_maps_state_note_first_reading_and_history_average()
    {
        var meters = (await Get(Rashid, "/api/v1/sync/meters")).GetProperty("meters").EnumerateArray()
            .ToDictionary(m => m.GetProperty("id").GetString()!);
        Assert.Equal("READ_AGAIN", meters["BC0008"].GetProperty("state").GetString());
        Assert.Equal("Photo not clear", meters["BC0008"].GetProperty("supervisorNote").GetString());
        Assert.Equal("CHECKING", meters["BC0013"].GetProperty("state").GetString());
        Assert.True(meters["BC0010"].GetProperty("isFirstReading").GetBoolean());
        Assert.Equal(0m, meters["BC0010"].GetProperty("previousReading").GetDecimal());          // OpeningReading 0 = never read
        Assert.Equal(JsonValueKind.Null, meters["BC0010"].GetProperty("averageConsumption").ValueKind); // AvgConsumption 0 = unknown
        Assert.Equal(1000m, meters["BC0006"].GetProperty("averageConsumption").GetDecimal());   // the view's AvgConsumption
        Assert.Equal(3000m, meters["BC0006"].GetProperty("expectedHigh").GetDecimal());
        Assert.Equal(1200m, meters["BC0006"].GetProperty("lastConsumption").GetDecimal());
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
        Assert.Equal(17, s.GetProperty("meters").GetInt32());
        Assert.Equal(9, s.GetProperty("read").GetInt32());
        Assert.Equal(8, s.GetProperty("notRead").GetInt32());
        Assert.Equal(7, s.GetProperty("readByYou").GetInt32());
        Assert.EndsWith("Z", s.GetProperty("lastReceivedUtc").GetString());
    }

    [Fact]
    public async Task Readings_are_scoped_to_the_reader()
    {
        var mine = await Get(Anil, "/api/v1/readings/mine");
        Assert.Equal(2, mine.GetArrayLength());
        Assert.All(mine.EnumerateArray(), r => Assert.Contains(r.GetProperty("meterId").GetString(), new[] { "BC0016", "BC0017" }));
    }

    [Fact]
    public async Task Properties_carry_the_tenant_company_name()
    {
        var properties = (await Get(Rashid, "/api/v1/sync/meters")).GetProperty("properties").EnumerateArray()
            .ToDictionary(p => p.GetProperty("code").GetString()!);
        Assert.Equal("Sandline Logistics LLC", properties["1499-W1"].GetProperty("companyName").GetString());
        Assert.Equal("T-0201", properties["1499-W1"].GetProperty("tenantCode").GetString());
    }

    [Fact]
    public async Task A_property_with_two_tenant_rows_lists_its_meters_once()
    {
        var sync = await Get(Rashid, "/api/v1/sync/meters?zone=597");
        var ids = sync.GetProperty("meters").EnumerateArray().Select(m => m.GetProperty("id").GetString()).ToList();
        Assert.Equal(ids.Distinct().Count(), ids.Count);
        Assert.Single(sync.GetProperty("properties").EnumerateArray(), p => p.GetProperty("code").GetString() == "1101");
    }

    [Fact]
    public async Task FR006_12_sync_lists_each_propertys_current_tenants()
    {
        var properties = (await Get(Rashid, "/api/v1/sync/meters")).GetProperty("properties").EnumerateArray()
            .ToDictionary(p => p.GetProperty("code").GetString()!, p => p.GetProperty("tenants").EnumerateArray().Select(t => t.GetProperty("code").GetString()).Order().ToList());
        Assert.Equal(["T-0102", "T-0199"], properties["1101"]);
        Assert.Equal(["T-0201"], properties["1499-W1"]);
        Assert.Empty(properties["3010"]); // lease ended
    }

    [Fact]
    public async Task FR021_find_a_property_by_company_name()
    {
        var result = await Get(Rashid, "/api/v1/properties/search?q=sandline");
        Assert.Equal(["1499-W1"], result.GetProperty("properties").EnumerateArray().Select(p => p.GetProperty("property").GetProperty("code").GetString()));
    }

    [Fact]
    public async Task Summary_by_zone_counts_only_that_zone()
    {
        var s = await Get(Rashid, "/api/v1/summary?zone=602");
        Assert.Equal(2, s.GetProperty("meters").GetInt32());
        Assert.Equal(0, s.GetProperty("readByYou").GetInt32());
    }

    [Fact]
    public async Task Any_reader_can_open_any_active_meter()
    {
        var detail = await Get(Rashid, "/api/v1/meters/BC0016");
        Assert.Equal("3010-I", detail.GetProperty("meter").GetProperty("number").GetString());
    }

    [Fact]
    public async Task Inactive_meter_is_not_found()
    {
        var response = await As(Rashid).GetAsync("/api/v1/meters/BC0019");
        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
        Assert.Contains("METER_NOT_FOUND", await response.Content.ReadAsStringAsync());
    }

    [Fact]
    public async Task Period_code_comes_from_start_date_and_latest_open_month_wins()
    {
        // The view says "2026-9" and marks August, September and October all OPEN.
        var me = await Get(Rashid, "/api/v1/me");
        Assert.Equal("2026-10", me.GetProperty("openPeriod").GetProperty("code").GetString());
        var september = await Get(Rashid, "/api/v1/summary?period=2026-09");
        Assert.Equal("2026-09", september.GetProperty("periodCode").GetString());
    }

    [Fact]
    public async Task Shared_sign_in_name_is_refused_with_a_clear_code()
    {
        var response = await As("shared@dip.example").GetAsync("/api/v1/me");
        Assert.Equal(HttpStatusCode.Conflict, response.StatusCode);
        Assert.Contains("LOGIN_NOT_UNIQUE", await response.Content.ReadAsStringAsync());
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
            b.UseSetting("SourceViews:HasReadingHistory", "true");
            b.UseSetting("Auth:Mode", "Development");
        });
        var error = Assert.ThrowsAny<Exception>(() => factory.CreateClient());
        Assert.Contains("refused outside the Development environment", error.ToString());
    }
}

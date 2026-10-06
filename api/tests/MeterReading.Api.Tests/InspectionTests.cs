using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text.Json;
using MeterReading.Api.Contracts;
using MeterReading.Api.Data;
using MeterReading.Api.Domain;
using Microsoft.Data.SqlClient;

namespace MeterReading.Api.Tests;

/// <summary>Rules of Field Inspection that need no database (spec §21).</summary>
public class InspectionRuleTests
{
    private static UnitResultRequest Unit(UnitResult result, string? occupant = null, string[]? reasons = null, string? note = null, int photos = 0, string? unitId = "1001") =>
        new(Guid.NewGuid(), unitId, result, OccupantName: occupant, Reasons: reasons, Note: note, PhotoCount: photos);

    private static string? Code(params UnitResultRequest[] units) => InspectionService.Validate(units, 6)?.ErrorCode;

    [Fact] public void FR032_as_recorded_and_vacant_need_nothing_more() => Assert.Null(Code(Unit(UnitResult.AS_RECORDED), Unit(UnitResult.VACANT, unitId: "1002")));
    [Fact] public void FR032_subleased_needs_who_is_there() => Assert.Equal("MANDATORY_FIELD_MISSING", Code(Unit(UnitResult.SUBLEASED, photos: 1)));
    [Fact] public void FR033_subleased_needs_a_photo() => Assert.Equal("MANDATORY_FIELD_MISSING", Code(Unit(UnitResult.SUBLEASED, occupant: "Al Noor Auto")));
    [Fact] public void FR032_disputed_needs_a_reason() => Assert.Equal("MANDATORY_FIELD_MISSING", Code(Unit(UnitResult.DISPUTED, photos: 1)));
    [Fact] public void FR032_rejected_with_reason_and_photo_is_fine() => Assert.Null(Code(Unit(UnitResult.REJECTED, reasons: ["LABOUR_IN_WAREHOUSE"], photos: 1)));
    [Fact] public void FR032_pending_needs_a_reason_but_no_photo() => Assert.Null(Code(Unit(UnitResult.PENDING, note: "Locked")));
    [Fact] public void FR033_photo_limit() => Assert.Equal("VALIDATION_FAILED", Code(Unit(UnitResult.AS_RECORDED, photos: 7)));
    [Fact] public void Same_unit_twice_is_refused() => Assert.Equal("VALIDATION_FAILED", Code(Unit(UnitResult.AS_RECORDED), Unit(UnitResult.VACANT)));
    [Fact] public void FR035_unit_not_on_list_needs_its_number() => Assert.Equal("MANDATORY_FIELD_MISSING", Code(Unit(UnitResult.AS_RECORDED, unitId: null)));
    [Fact] public void Reason_codes_are_checked() => Assert.Equal("INVALID_LOV_CODE", Code(Unit(UnitResult.PENDING, reasons: ["no access!"])));

    [Fact]
    public void FR030_without_TenantCode_on_units_every_unit_of_the_property_belongs_to_each_tenant()
    {
        var noTenant = new InspectionUnitRow { UnitId = "1", PropertyCode = "P", TenantCode = "" };
        var withTenant = new InspectionUnitRow { UnitId = "2", PropertyCode = "P", TenantCode = "T1" };
        Assert.True(noTenant.BelongsTo("T1"));
        Assert.True(noTenant.BelongsTo("T2"));
        Assert.True(withTenant.BelongsTo("T1"));
        Assert.False(withTenant.BelongsTo("T2"));
    }

    [Theory]
    [InlineData("Commercial>Warehouse>Warehouse", "Warehouse")]
    [InlineData("Commercial> >", "Commercial")]
    [InlineData("Residential>Labor Camps>Room in labor camp", "Room in labor camp")]
    [InlineData(null, null)]
    public void Category_shows_its_last_level(string? category, string? expected) => Assert.Equal(expected, InspectionService.CategoryName(category));

    private static InspectionPlanRow Plan => new() { PeriodCode = "2026-10", PlanDate = new DateTime(2026, 10, 5), PropertyCode = "P", TenantCode = "T" };
    private static InspectionUnitRow U(string id, bool active = true) => new() { UnitId = id, PropertyCode = "P", TenantCode = "T", UnitCode = id, Active = active };
    private static LatestUnitResultRow R(string id, string result) => new() { PeriodCode = "2026-10", PropertyCode = "P", TenantCode = "T", UnitId = id, Result = result };

    [Fact]
    public void FR034_not_started_before_any_visit() =>
        Assert.Equal(InspectionState.NOT_STARTED, InspectionService.Progress(Plan, [U("1")], [], null).State);

    [Fact]
    public void FR034_done_when_every_active_unit_is_checked()
    {
        var p = InspectionService.Progress(Plan, [U("1"), U("2"), U("3", active: false)], [R("1", "AS_RECORDED"), R("2", "SUBLEASED")], DateTime.UtcNow);
        Assert.Equal(InspectionState.DONE, p.State);
        Assert.Equal(2, p.CheckedUnits);
        Assert.Equal(1, p.FlaggedUnits);
    }

    [Fact]
    public void FR034_pending_or_unchecked_unit_means_come_back()
    {
        Assert.Equal(InspectionState.COME_BACK, InspectionService.Progress(Plan, [U("1"), U("2")], [R("1", "AS_RECORDED"), R("2", "PENDING")], DateTime.UtcNow).State);
        Assert.Equal(InspectionState.COME_BACK, InspectionService.Progress(Plan, [U("1"), U("2")], [R("1", "AS_RECORDED")], DateTime.UtcNow).State);
    }
}

/// <summary>The inspection endpoints against the development views (db/dev/000) and db/010.</summary>
[Collection(DatabaseCollection.Name)]
public sealed class InspectionTests(ApiFactory factory) : IAsyncLifetime
{
    private const string Rashid = "rashid@dip.example";
    private const string Anil = "anil@dip.example";
    private const string Window = "from=2026-10-01&to=2026-11-30";

    /// <summary>The development database only: every test starts with no visits to the sample plan.</summary>
    private static async Task Clean()
    {
        await using var c = new SqlConnection(ApiFactory.ConnectionString);
        await c.OpenAsync();
        const string sql = """
            DELETE d FROM mr.ReadingImageData d WHERE d.BlobPath LIKE N'inspections/%';
            DELETE FROM mr.InspectionImage;
            DELETE FROM mr.InspectionUnitResult;
            DELETE FROM mr.InspectionVisit;
            """;
        await using var cmd = new SqlCommand(sql, c);
        await cmd.ExecuteNonQueryAsync();
    }

    public Task InitializeAsync() => Clean();
    public Task DisposeAsync() => Clean();

    private HttpClient As(string user)
    {
        var client = factory.CreateClient();
        client.DefaultRequestHeaders.Add("X-Dev-User", user);
        return client;
    }

    private async Task<JsonElement> Get(string url, string user = Rashid)
    {
        var response = await As(user).GetAsync(url);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        return await response.Content.ReadFromJsonAsync<JsonElement>();
    }

    private static string Code(JsonElement problem) => problem.GetProperty("code").GetString()!;

    private static object U(string? unitId, string result, string? occupant = null, string[]? reasons = null, string? note = null,
        int photos = 0, int? people = null, string? unitCode = null, Guid? resultId = null) =>
        new { resultId = resultId ?? Guid.NewGuid(), unitId, result, occupantName = occupant, reasons, note, photoCount = photos, peopleSeen = people, unitCode };

    private static object Visit(string property, string tenant, object[] units, Guid? id = null, DateTime? finished = null, string period = "2026-10",
        bool signature = false, string? personMet = null) => new
    {
        visitId = id ?? Guid.NewGuid(), periodCode = period, propertyCode = property, tenantCode = tenant,
        startedAtUtc = (finished ?? DateTime.UtcNow.AddMinutes(-1)).AddMinutes(-20), finishedAtUtc = finished ?? DateTime.UtcNow.AddMinutes(-1),
        units, hasSignature = signature, personMet, latitude = 25.0143m, longitude = 55.1529m,
    };

    private async Task<(HttpStatusCode Status, JsonElement Body)> Post(object body, string user = Rashid)
    {
        var response = await As(user).PostAsJsonAsync("/api/v1/inspections", body);
        return (response.StatusCode, await response.Content.ReadFromJsonAsync<JsonElement>());
    }

    private async Task<JsonElement> PlanRow(string property, string tenant) =>
        (await Get($"/api/v1/inspections/plan?{Window}")).GetProperty("plans").EnumerateArray()
            .Single(p => p.GetProperty("propertyCode").GetString() == property && p.GetProperty("tenantCode").GetString() == tenant);

    private static readonly string[] Elegant = ["1001", "1002", "1003", "1004", "1005"];

    [Fact]
    public async Task FR030_me_says_inspection_is_set_up() =>
        Assert.True((await Get("/api/v1/me")).GetProperty("canInspect").GetBoolean());

    [Fact]
    public async Task FR031_plan_lists_rows_with_units_and_state()
    {
        var plan = await Get($"/api/v1/inspections/plan?{Window}");
        var rows = plan.GetProperty("plans").EnumerateArray().ToList();
        Assert.Equal(6, rows.Count);
        var tech = rows.Single(p => p.GetProperty("propertyCode").GetString() == "598-1187");
        Assert.Equal(2, tech.GetProperty("activeUnits").GetInt32());
        Assert.Equal(3, tech.GetProperty("inactiveUnits").GetInt32());
        Assert.Equal("NOT_STARTED", tech.GetProperty("state").GetString());
        // Two tenants on one property: one row each.
        Assert.Equal(2, rows.Count(p => p.GetProperty("propertyCode").GetString() == "598-1625"));
        // Narrower window.
        Assert.Single((await Get("/api/v1/inspections/plan?from=2026-11-01&to=2026-11-30")).GetProperty("plans").EnumerateArray());
    }

    [Fact]
    public async Task FR031_units_are_per_property_and_tenant()
    {
        var a = await Get("/api/v1/inspections/units?period=2026-10&property=598-1625&tenant=T-1625A");
        var unit = Assert.Single(a.GetProperty("units").EnumerateArray());
        Assert.Equal("02", unit.GetProperty("unitCode").GetString());
        Assert.Equal("Commercial", unit.GetProperty("categoryName").GetString());

        var tech = (await Get("/api/v1/inspections/units?period=2026-10&property=598-1187&tenant=T-1187")).GetProperty("units").EnumerateArray().ToList();
        Assert.Equal(5, tech.Count);
        Assert.Equal(3, tech.Count(u => !u.GetProperty("active").GetBoolean()));
        Assert.True(tech[0].GetProperty("active").GetBoolean()); // active units first

        var missing = await As(Rashid).GetAsync("/api/v1/inspections/units?period=2026-10&property=598-1625&tenant=T-0559");
        Assert.Equal(HttpStatusCode.NotFound, missing.StatusCode);
    }

    [Fact]
    public async Task FR034_all_units_as_recorded_makes_the_plan_done()
    {
        var (status, body) = await Post(Visit("597-559", "T-0559", Elegant.Select(u => U(u, "AS_RECORDED", people: 3)).ToArray()));
        Assert.Equal(HttpStatusCode.Created, status);
        Assert.Equal("DONE", body.GetProperty("state").GetString());

        var row = await PlanRow("597-559", "T-0559");
        Assert.Equal("DONE", row.GetProperty("state").GetString());
        Assert.Equal(5, row.GetProperty("checkedUnits").GetInt32());
        Assert.Equal(0, row.GetProperty("flaggedUnits").GetInt32());
    }

    [Fact]
    public async Task Retrying_a_visit_is_safe_and_a_reused_id_is_refused()
    {
        var id = Guid.NewGuid();
        var finished = DateTime.UtcNow.AddMinutes(-3);
        var units = new[] { U("1001", "AS_RECORDED", resultId: Guid.NewGuid()) };
        Assert.Equal(HttpStatusCode.Created, (await Post(Visit("597-559", "T-0559", units, id, finished))).Status);
        Assert.Equal(HttpStatusCode.OK, (await Post(Visit("597-559", "T-0559", units, id, finished))).Status);

        var (status, body) = await Post(Visit("597-559", "T-0559", [U("1001", "VACANT")], id, finished));
        Assert.Equal(HttpStatusCode.Conflict, status);
        Assert.Equal("VISIT_ID_REUSED", Code(body));
    }

    [Fact]
    public async Task FR034_pending_unit_brings_the_property_back_until_checked()
    {
        var first = Elegant.Select(u => u == "1005" ? U(u, "PENDING", reasons: ["LOCKED"]) : U(u, "AS_RECORDED")).ToArray();
        var (_, body) = await Post(Visit("597-559", "T-0559", first, finished: DateTime.UtcNow.AddMinutes(-30)));
        Assert.Equal("COME_BACK", body.GetProperty("state").GetString());
        Assert.Equal(4, (await PlanRow("597-559", "T-0559")).GetProperty("checkedUnits").GetInt32());

        // Next visit checks only the unit that was locked.
        (_, body) = await Post(Visit("597-559", "T-0559", [U("1005", "VACANT")]));
        Assert.Equal("DONE", body.GetProperty("state").GetString());

        var units = await Get("/api/v1/inspections/units?period=2026-10&property=597-559&tenant=T-0559");
        var unit5 = units.GetProperty("units").EnumerateArray().Single(u => u.GetProperty("unitId").GetString() == "1005");
        Assert.Equal("VACANT", unit5.GetProperty("last").GetProperty("result").GetString());
    }

    [Fact]
    public async Task FR032_subleased_needs_occupant_and_photo_and_counts_as_flagged()
    {
        var (status, body) = await Post(Visit("597-972", "T-0972", [U("2001", "SUBLEASED", photos: 1)]));
        Assert.Equal(HttpStatusCode.UnprocessableEntity, status);
        Assert.Equal("MANDATORY_FIELD_MISSING", Code(body));

        (status, body) = await Post(Visit("597-972", "T-0972", [U("2001", "SUBLEASED", occupant: "Al Noor Auto Repair")]));
        Assert.Equal("MANDATORY_FIELD_MISSING", Code(body));

        (status, body) = await Post(Visit("597-972", "T-0972",
            [U("2001", "SUBLEASED", occupant: "Al Noor Auto Repair", reasons: ["OTHER_COMPANY_SIGN"], photos: 2, people: 6),
             U("2002", "DISPUTED", note: "Tenant says the room is theirs", photos: 1)]));
        Assert.Equal(HttpStatusCode.Created, status);
        Assert.Equal(2, body.GetProperty("flaggedUnits").GetInt32());
        Assert.Equal(3, body.GetProperty("photosExpected").GetInt32());
        Assert.Equal("COME_BACK", body.GetProperty("state").GetString()); // 2003 and 2004 not checked yet
        Assert.Equal(2, (await PlanRow("597-972", "T-0972")).GetProperty("flaggedUnits").GetInt32());
    }

    [Fact]
    public async Task Units_must_belong_to_the_plan_row()
    {
        // 5002 is the other tenant's unit on the same property.
        var (status, body) = await Post(Visit("598-1625", "T-1625A", [U("5002", "AS_RECORDED")]));
        Assert.Equal(HttpStatusCode.NotFound, status);
        Assert.Equal("UNIT_NOT_FOUND", Code(body));

        (status, body) = await Post(Visit("598-1625", "T-0000", [U("5001", "AS_RECORDED")]));
        Assert.Equal("PLAN_NOT_FOUND", Code(body));
    }

    [Fact]
    public async Task FR035_unit_not_on_the_list_is_kept_for_the_office()
    {
        var (status, _) = await Post(Visit("598-1625", "T-1625A",
            [U("5001", "AS_RECORDED"), U(null, "SUBLEASED", occupant: "Unknown workshop", photos: 1, unitCode: "02A")]));
        Assert.Equal(HttpStatusCode.Created, status);

        await using var c = new SqlConnection(ApiFactory.ConnectionString);
        await c.OpenAsync();
        await using var cmd = new SqlCommand("SELECT COUNT(*) FROM mr.vw_InspectionResult WHERE UnitId IS NULL AND UnitCode = N'02A' AND Result = 'SUBLEASED'", c);
        Assert.Equal(1, (int)(await cmd.ExecuteScalarAsync())!);
    }

    [Fact]
    public async Task Old_or_future_visits_are_refused()
    {
        var (_, body) = await Post(Visit("597-559", "T-0559", [U("1001", "AS_RECORDED")], finished: DateTime.UtcNow.AddDays(-8)));
        Assert.Equal("CAPTURE_TIME_INVALID", Code(body));
    }

    private static byte[] Jpeg(byte seed = 1) => [0xFF, 0xD8, 0xFF, 0xE0, .. Enumerable.Repeat(seed, 1_500), 0xFF, 0xD9];

    private async Task<HttpResponseMessage> Upload(Guid visit, Guid image, byte[] bytes, string query, string user = Rashid)
    {
        var content = new ByteArrayContent(bytes);
        content.Headers.ContentType = new MediaTypeHeaderValue("image/jpeg");
        var request = new HttpRequestMessage(HttpMethod.Put, $"/api/v1/inspections/{visit}/images/{image}?{query}") { Content = content };
        request.Headers.Add("X-Content-SHA256", Convert.ToHexString(SHA256.HashData(bytes)));
        return await As(user).SendAsync(request);
    }

    [Fact]
    public async Task FR033_photos_and_signature_are_stored_once_and_only_by_the_inspector()
    {
        var visit = Guid.NewGuid();
        var result = Guid.NewGuid();
        await Post(Visit("598-1187", "T-1187",
            [U("3001", "REJECTED", reasons: ["LABOUR_IN_WAREHOUSE"], photos: 1, resultId: result), U("3002", "AS_RECORDED")],
            visit, signature: true, personMet: "Mr. Imran"));

        var photo = Guid.NewGuid();
        Assert.Equal(HttpStatusCode.Created, (await Upload(visit, photo, Jpeg(), $"role=EVIDENCE&result={result}")).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await Upload(visit, photo, Jpeg(), $"role=EVIDENCE&result={result}")).StatusCode);

        Assert.Equal(HttpStatusCode.Created, (await Upload(visit, Guid.NewGuid(), Jpeg(2), "role=SIGNATURE")).StatusCode);
        var second = await Upload(visit, Guid.NewGuid(), Jpeg(3), "role=SIGNATURE");
        Assert.Equal("TOO_MANY_IMAGES", Code(await second.Content.ReadFromJsonAsync<JsonElement>()));

        Assert.Equal(HttpStatusCode.BadRequest, (await Upload(visit, Guid.NewGuid(), Jpeg(4), "role=EVIDENCE")).StatusCode);
        var other = await Upload(visit, Guid.NewGuid(), Jpeg(5), $"role=EVIDENCE&result={result}", Anil);
        Assert.Equal("VISIT_NOT_FOUND", Code(await other.Content.ReadFromJsonAsync<JsonElement>()));

        var download = await As(Rashid).GetAsync($"/api/v1/inspections/{visit}/images/{photo}");
        Assert.Equal(Jpeg(), await download.Content.ReadAsByteArrayAsync());
    }
}

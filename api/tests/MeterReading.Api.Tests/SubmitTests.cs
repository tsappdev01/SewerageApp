using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using MeterReading.Api.Domain;
using Microsoft.Data.SqlClient;

namespace MeterReading.Api.Tests;

public class ConsumptionTests
{
    [Fact] public void BR001_working_meter() => Assert.Equal(new Consumption.Result(Consumption.Kind.Ok, 2500m), Consumption.Check(50_000, 52_500, 5, 3_000));
    [Fact] public void BR008_above_expected_is_high() => Assert.Equal(Consumption.Kind.High, Consumption.Check(50_000, 51_000, 5, 900).Kind);
    [Fact] public void BR008_no_expected_range_means_no_high() => Assert.Equal(Consumption.Kind.Ok, Consumption.Check(50_000, 59_000, 5, null).Kind);
    [Fact] public void BR006_lower_far_from_max_is_lower() => Assert.Equal(Consumption.Kind.Lower, Consumption.Check(50_000, 49_900, 5, 900).Kind);
    [Fact] public void BR006_rollover_near_max() => Assert.Equal(new Consumption.Result(Consumption.Kind.Rollover, 170m), Consumption.Check(99_950, 120, 5, 900));
    [Fact] public void BR006_rollover_out_of_range() => Assert.Equal(Consumption.Kind.RolloverHigh, Consumption.Check(99_950, 9_000, 5, 900).Kind);
    [Fact] public void Decimals_count_only_whole_digits() => Assert.Equal(5, Consumption.WholeDigits(12345.6789m));
}

/// <summary>POST /api/v1/readings against the development database. Each test removes its own rows.</summary>
[Collection(DatabaseCollection.Name)]
public sealed class SubmitTests(ApiFactory factory) : IAsyncLifetime
{
    private const string Rashid = "rashid@dip.example";
    private const string Anil = "anil@dip.example";
    private readonly List<Guid> _created = [];

    public Task InitializeAsync() => Task.CompletedTask;

    public async Task DisposeAsync()
    {
        if (_created.Count == 0) return;
        await using var c = new SqlConnection(ApiFactory.ConnectionString);
        await c.OpenAsync();
        await using var cmd = new SqlCommand("DELETE FROM mr.ReadingTransaction WHERE TransactionId IN (SELECT CAST([value] AS uniqueidentifier) FROM OPENJSON(@ids))", c);
        cmd.Parameters.AddWithValue("@ids", JsonSerializer.Serialize(_created));
        await cmd.ExecuteNonQueryAsync();
    }

    private object Reading(string meterId, string condition = "WORKING", decimal? reading = null, string? reason = null, string? note = null,
        Guid? id = null, DateTime? capturedAt = null, bool confirmed = false, string? subTenant = null, string? tenant = DevTenants.Own)
    {
        var transactionId = id ?? Guid.NewGuid();
        _created.Add(transactionId);
        return new
        {
            transactionId, meterId, condition, reasonCode = reason, note, newReading = reading,
            readerConfirmedWarning = confirmed, capturedAtUtc = capturedAt ?? DateTime.UtcNow.AddMinutes(-2), subTenant,
            tenantCode = DevTenants.Resolve(tenant, meterId),
        };
    }

    private async Task<(HttpStatusCode Status, JsonElement Body)> Post(object body, string user = Rashid)
    {
        var client = factory.CreateClient();
        client.DefaultRequestHeaders.Add("X-Dev-User", user);
        var response = await client.PostAsJsonAsync("/api/v1/readings", body);
        return (response.StatusCode, await response.Content.ReadFromJsonAsync<JsonElement>());
    }

    private static string Code(JsonElement problem) => problem.GetProperty("code").GetString()!;

    [Fact]
    public async Task Normal_reading_is_accepted_and_shows_as_done()
    {
        var (status, body) = await Post(Reading("BC0003", reading: 30_500));
        Assert.Equal(HttpStatusCode.Created, status);
        Assert.Equal("ACCEPTED", body.GetProperty("status").GetString());
        Assert.Equal("SENT", body.GetProperty("state").GetString());
        Assert.Equal(390m, body.GetProperty("consumption").GetDecimal());

        var client = factory.CreateClient();
        client.DefaultRequestHeaders.Add("X-Dev-User", Anil);
        var meters = await client.GetFromJsonAsync<JsonElement>("/api/v1/sync/meters?zone=597");
        Assert.Equal("SENT", meters.GetProperty("meters").EnumerateArray().Single(m => m.GetProperty("id").GetString() == "BC0003").GetProperty("state").GetString());
    }

    [Fact]
    public async Task BR013_retry_with_same_id_returns_stored_result()
    {
        var id = Guid.NewGuid();
        var captured = DateTime.UtcNow.AddMinutes(-3);
        var first = await Post(Reading("BC0003", reading: 30_500, id: id, capturedAt: captured));
        var again = await Post(Reading("BC0003", reading: 30_500, id: id, capturedAt: captured));
        Assert.Equal(HttpStatusCode.Created, first.Status);
        Assert.Equal(HttpStatusCode.OK, again.Status);
        Assert.Equal(first.Body.GetProperty("transactionId").GetString(), again.Body.GetProperty("transactionId").GetString());
    }

    [Fact]
    public async Task BR013_same_id_with_different_reading_is_refused()
    {
        var id = Guid.NewGuid();
        var captured = DateTime.UtcNow.AddMinutes(-3);
        await Post(Reading("BC0003", reading: 30_500, id: id, capturedAt: captured));
        var (status, body) = await Post(Reading("BC0003", reading: 30_600, id: id, capturedAt: captured));
        Assert.Equal(HttpStatusCode.Conflict, status);
        Assert.Equal("TRANSACTION_ID_REUSED", Code(body));
    }

    [Fact]
    public async Task A_meter_cannot_be_read_twice()
    {
        await Post(Reading("BC0003", reading: 30_500));
        var (status, body) = await Post(Reading("BC0003", reading: 30_510), Anil);
        Assert.Equal(HttpStatusCode.Conflict, status);
        Assert.Equal("ALREADY_READ", Code(body));
    }

    [Fact]
    public async Task A_rejected_meter_can_be_read_again()
    {
        var (status, body) = await Post(Reading("BC0008", reading: 22_600));
        Assert.Equal(HttpStatusCode.Created, status);
        Assert.Equal("ACCEPTED", body.GetProperty("status").GetString());
    }

    [Theory]
    [InlineData("BC0009", 5_000, "LOWER_THAN_PREVIOUS")]   // previous 5,120
    [InlineData("BC0007", 20_000, "HIGH_CONSUMPTION")]     // average 300, so above 900
    public async Task Readings_that_break_a_rule_go_to_the_supervisor(string meter, int reading, string exception)
    {
        var (status, body) = await Post(Reading(meter, reading: reading, confirmed: true));
        Assert.Equal(HttpStatusCode.Created, status);
        Assert.Equal("EXCEPTION", body.GetProperty("status").GetString());
        Assert.Equal("CHECKING", body.GetProperty("state").GetString());
        Assert.Equal([exception], body.GetProperty("exceptions").EnumerateArray().Select(e => e.GetString()));
    }

    [Fact]
    public async Task BR006_rollover_is_accepted()
    {
        var (_, body) = await Post(Reading("BC0004", reading: 120)); // previous 99,950 on 5 digits
        Assert.Equal("ACCEPTED", body.GetProperty("status").GetString());
        Assert.Equal(170m, body.GetProperty("consumption").GetDecimal());
    }

    [Fact]
    public async Task BR002_damaged_meter_always_goes_to_the_supervisor()
    {
        var (_, body) = await Post(Reading("BC0011", "DAMAGED", reason: "GLASS_BROKEN", note: "Glass cracked"));
        Assert.Equal("EXCEPTION", body.GetProperty("status").GetString());
        Assert.Contains("DAMAGED_METER", body.GetProperty("exceptions").EnumerateArray().Select(e => e.GetString()));
    }

    [Fact]
    public async Task BR009_not_accessible_becomes_a_revisit()
    {
        var (_, body) = await Post(Reading("BC0012", "NOT_ACCESSIBLE", reason: "GATE_LOCKED", note: "Gate locked"));
        Assert.Equal("ACCEPTED", body.GetProperty("status").GetString());
        Assert.Equal("REVISIT", body.GetProperty("state").GetString());
    }

    [Theory]
    [InlineData("BC0003", "WORKING", null, null, null, 422, "MANDATORY_FIELD_MISSING")]
    [InlineData("BC0003", "NOT_ACCESSIBLE", null, "GATE_LOCKED", null, 422, "MANDATORY_FIELD_MISSING")]
    [InlineData("BC0003", "WORKING", 123456, null, null, 422, "READING_EXCEEDS_REGISTER")]
    [InlineData("BC0003", "FLOODED", 1, null, null, 422, "INVALID_LOV_CODE")]
    [InlineData("BC9999", "WORKING", 1, null, null, 404, "METER_NOT_FOUND")]
    [InlineData("BC0019", "WORKING", 1, null, null, 404, "METER_NOT_FOUND")] // inactive
    public async Task Bad_readings_are_rejected_and_not_stored(string meter, string condition, int? reading, string? reason, string? note, int expectedStatus, string code)
    {
        var (status, body) = await Post(Reading(meter, condition, reading, reason, note));
        Assert.Equal(expectedStatus, (int)status);
        Assert.Equal(code, Code(body));
    }

    [Fact]
    public async Task Capture_time_in_the_future_is_rejected()
    {
        var (status, body) = await Post(Reading("BC0003", reading: 30_500, capturedAt: DateTime.UtcNow.AddHours(1)));
        Assert.Equal(HttpStatusCode.UnprocessableEntity, status);
        Assert.Equal("CAPTURE_TIME_INVALID", Code(body));
    }

    [Fact]
    public async Task Readings_are_stored_against_the_signed_in_reader()
    {
        await Post(Reading("BC0003", reading: 30_500), Anil);
        var client = factory.CreateClient();
        client.DefaultRequestHeaders.Add("X-Dev-User", Anil);
        var mine = await client.GetFromJsonAsync<JsonElement>("/api/v1/readings/mine");
        Assert.Contains(mine.EnumerateArray(), r => r.GetProperty("meterId").GetString() == "BC0003");
    }

    [Fact]
    public async Task The_reading_keeps_tenant_property_meter_and_sub_tenant()
    {
        var (status, body) = await Post(Reading("BC0007", reading: 17_300, subTenant: "  Al Fajr Workshop  "));
        Assert.Equal(HttpStatusCode.Created, status);
        var id = body.GetProperty("transactionId").GetGuid();

        await using var c = new SqlConnection(ApiFactory.ConnectionString);
        await c.OpenAsync();
        await using var cmd = new SqlCommand("""
            SELECT RowId, PropertyId, PropertyCode, MeterNumber, TenantCode, [Type], SubTenant, Current_Reading, Previous_Reading,
                   MeterReader, Posted, Transferred, MeterStatus
            FROM mr.vw_MeterReading WHERE TransactionId = @id
            """, c);
        cmd.Parameters.AddWithValue("@id", id);
        await using var r = await cmd.ExecuteReaderAsync();
        Assert.True(await r.ReadAsync());
        Assert.True(r.GetInt64(0) > 0);                       // RowId, like the source table
        Assert.False(r.IsDBNull(1));                          // PropertyId from vw_MR_Property
        Assert.Equal("1499-W1", r.GetString(2));
        Assert.Equal("2002-2", r.GetString(3));
        Assert.Equal("T-0201", r.GetString(4));               // tenant at the time of reading
        Assert.Equal("Sewerage", r.GetString(5));             // Type as the source writes it
        Assert.Equal("Al Fajr Workshop", r.GetString(6));     // trimmed
        Assert.Equal(17_300m, r.GetDecimal(7));
        Assert.Equal(17_040m, r.GetDecimal(8));
        Assert.Equal("E1001", r.GetString(9));
        Assert.False(r.GetBoolean(10));
        Assert.False(r.GetBoolean(11));
        Assert.Equal("WORKING", r.GetString(12));
    }

    [Fact]
    public async Task My_readings_show_the_tenant_and_sub_tenant()
    {
        await Post(Reading("BC0007", reading: 17_300, subTenant: "Al Fajr Workshop"));
        var client = factory.CreateClient();
        client.DefaultRequestHeaders.Add("X-Dev-User", Rashid);
        var mine = await client.GetFromJsonAsync<JsonElement>("/api/v1/readings/mine");
        var row = mine.EnumerateArray().First(r => r.GetProperty("meterId").GetString() == "BC0007");
        Assert.Equal("T-0201", row.GetProperty("tenantCode").GetString());
        Assert.Equal("Al Fajr Workshop", row.GetProperty("subTenant").GetString());
    }

    [Fact]
    public async Task Sub_tenant_name_is_limited_to_100_characters()
    {
        var (status, body) = await Post(Reading("BC0007", reading: 17_300, subTenant: new string('x', 101)));
        Assert.Equal(HttpStatusCode.UnprocessableEntity, status);
        Assert.Equal("VALIDATION_FAILED", Code(body));
    }

    [Fact]
    public async Task FR006_12_a_reading_without_the_checked_tenant_is_refused()
    {
        var (status, body) = await Post(Reading("BC0007", reading: 17_300, tenant: null));
        Assert.Equal(HttpStatusCode.UnprocessableEntity, status);
        Assert.Equal("TENANT_NOT_CONFIRMED", Code(body));
    }

    [Fact]
    public async Task FR006_12_a_tenant_that_is_not_the_propertys_is_refused()
    {
        var (status, body) = await Post(Reading("BC0007", reading: 17_300, tenant: "T-0101")); // 1100's tenant
        Assert.Equal(HttpStatusCode.Conflict, status);
        Assert.Equal("TENANT_CHANGED", Code(body));
    }

    [Fact]
    public async Task FR006_12_a_property_with_no_current_tenant_cannot_be_read()
    {
        var (status, body) = await Post(Reading("BC0017", reading: 4_800, tenant: "T-0301")); // 3010: lease ended
        Assert.Equal(HttpStatusCode.UnprocessableEntity, status);
        Assert.Equal("NO_TENANT", Code(body));
    }

    [Fact]
    public async Task FR006_12_the_tenant_the_reader_picked_is_stored()
    {
        var (status, _) = await Post(Reading("BC0003", reading: 30_500, tenant: "t-0199")); // 1101's second tenant
        Assert.Equal(HttpStatusCode.Created, status);
        var client = factory.CreateClient();
        client.DefaultRequestHeaders.Add("X-Dev-User", Rashid);
        var mine = await client.GetFromJsonAsync<JsonElement>("/api/v1/readings/mine");
        Assert.Equal("T-0199", mine.EnumerateArray().First(r => r.GetProperty("meterId").GetString() == "BC0003").GetProperty("tenantCode").GetString());
    }
}

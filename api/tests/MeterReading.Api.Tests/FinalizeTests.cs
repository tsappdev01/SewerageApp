using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using MeterReading.Api.Domain;
using Microsoft.Data.SqlClient;

namespace MeterReading.Api.Tests;

/// <summary>FR-037.3: pairing a property's sewerage and irrigation meters by meter number.</summary>
public class FinalizePairingTests
{
    private static FinalizeMeter S(string number, decimal prev, decimal now) => new("S" + number, number, "SEWERAGE", prev, now, now - prev, Guid.NewGuid());
    private static FinalizeMeter I(string number, decimal prev, decimal now) => new("I" + number, number, "IRRIGATION", prev, now, now - prev, Guid.NewGuid());

    [Fact]
    public void FR037_3_same_number_shares_a_row_and_a_lone_meter_has_its_own()
    {
        // DIP's example of 2026-10-18 (property 598-1000, period 2026-09).
        var rows = FinalizeService.Pair([S("10001", 75456, 800000), S("10002", 50000, 60000), I("10001", 51200, 51300), I("1003", 1000, 2000)]);

        Assert.Equal(3, rows.Count);
        Assert.Equal(("10001", 724544m, "10001", 100m), (rows[0].Sewerage!.Number, rows[0].Sewerage!.Consumption, rows[0].Irrigation!.Number, rows[0].Irrigation!.Consumption));
        Assert.Equal(("10002", 10000m), (rows[1].Sewerage!.Number, rows[1].Sewerage!.Consumption));
        Assert.Null(rows[1].Irrigation);
        Assert.Null(rows[2].Sewerage);
        Assert.Equal(("1003", 1000m), (rows[2].Irrigation!.Number, rows[2].Irrigation!.Consumption));
    }

    [Fact]
    public void FR037_3_two_meters_of_one_type_with_one_number_take_a_row_each()
    {
        var rows = FinalizeService.Pair([S("7", 0, 1), new("S7b", "7", "SEWERAGE", 0, 2, 2, null), I("7", 0, 3)]);
        Assert.Equal(2, rows.Count);
        Assert.NotNull(rows[0].Irrigation);
        Assert.Null(rows[1].Irrigation);
    }

    [Theory]
    [InlineData(AssignmentState.SENT, true)]
    [InlineData(AssignmentState.REVISIT, true)]
    [InlineData(AssignmentState.PENDING, false)]
    [InlineData(AssignmentState.CHECKING, false)]
    [InlineData(AssignmentState.READ_AGAIN, false)]
    public void FR037_2_a_meter_counts_as_read_once_its_reading_is_stored(AssignmentState state, bool read) =>
        Assert.Equal(read, FinalizeService.IsRead(state));
}

/// <summary>POST /api/v1/properties/{code}/finalize against the development database (db/dev/010 readings).</summary>
[Collection(DatabaseCollection.Name)]
public sealed class FinalizeTests(ApiFactory factory) : IAsyncLifetime
{
    private const string Rashid = "rashid@dip.example";

    public Task InitializeAsync() => CleanAsync();
    public Task DisposeAsync() => CleanAsync();

    private static async Task CleanAsync()
    {
        await using var c = new SqlConnection(ApiFactory.ConnectionString);
        await c.OpenAsync();
        await using var cmd = new SqlCommand("DELETE FROM mr.MaintainFinalizedMeter WHERE PropertyCode IN ('1497', '3010', '1101')", c);
        await cmd.ExecuteNonQueryAsync();
    }

    private async Task<(HttpStatusCode Status, JsonElement Body)> Finalize(string property)
    {
        var client = factory.CreateClient();
        client.DefaultRequestHeaders.Add("X-Dev-User", Rashid);
        var response = await client.PostAsync($"/api/v1/properties/{Uri.EscapeDataString(property)}/finalize", null);
        return (response.StatusCode, await response.Content.ReadFromJsonAsync<JsonElement>());
    }

    private static async Task<List<(string S, decimal PrevS, decimal NowS, decimal UsedS, string I, decimal PrevI, decimal NowI, decimal UsedI, bool Current)>> Rows(string property)
    {
        await using var c = new SqlConnection(ApiFactory.ConnectionString);
        await c.OpenAsync();
        await using var cmd = new SqlCommand("""
            SELECT MeterNumberSewerage, PrevReadingSewerage, CurrentReadingSewerage, ConsumptionSewerage,
                   MeterNumberIrrigation, PrevReadingIrrigation, CurrentReadingIrrigation, ConsumptionIrrigation, IsCurrent
            FROM mr.MaintainFinalizedMeter WHERE PropertyCode = @p ORDER BY FinalizedMeterId
            """, c);
        cmd.Parameters.AddWithValue("@p", property);
        var list = new List<(string, decimal, decimal, decimal, string, decimal, decimal, decimal, bool)>();
        await using var r = await cmd.ExecuteReaderAsync();
        while (await r.ReadAsync())
            list.Add((r.GetString(0), r.GetDecimal(1), r.GetDecimal(2), r.GetDecimal(3), r.GetString(4), r.GetDecimal(5), r.GetDecimal(6), r.GetDecimal(7), r.GetBoolean(8)));
        return list;
    }

    [Fact]
    public async Task FR037_1_all_meters_read_writes_one_row_per_meter_number()
    {
        // 1497: 1497-I (7450 -> 8440) and 1497-S (2010 -> 2300), both accepted; different numbers, so a row each.
        var (status, body) = await Finalize("1497");

        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Equal("2026-10", body.GetProperty("periodCode").GetString());
        Assert.Equal(2, body.GetProperty("rows").GetArrayLength());
        Assert.Equal(
            [("0", 0m, 0m, 0m, "1497-I", 7450m, 8440m, 990m, true), ("1497-S", 2010m, 2300m, 290m, "0", 0m, 0m, 0m, true)],
            await Rows("1497"));
    }

    [Fact]
    public async Task FR037_2_a_property_with_meters_still_to_read_is_refused()
    {
        var (status, body) = await Finalize("1101");

        Assert.Equal(HttpStatusCode.Conflict, status);
        Assert.Equal("PROPERTY_NOT_COMPLETE", body.GetProperty("code").GetString());
        Assert.Contains("2 meters", body.GetProperty("title").GetString());
        Assert.Empty(await Rows("1101"));
    }

    [Fact]
    public async Task FR037_2_not_accessible_counts_as_read_with_no_current_reading()
    {
        // 3010: 3010-I accepted (15600 -> 16610), 3010-S not accessible (previous 4480, no reading).
        var (status, _) = await Finalize("3010");

        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Equal(
            [("0", 0m, 0m, 0m, "3010-I", 15600m, 16610m, 1010m, true), ("3010-S", 4480m, 0m, 0m, "0", 0m, 0m, 0m, true)],
            await Rows("3010"));
    }

    [Fact]
    public async Task FR037_4_finalizing_again_keeps_the_earlier_rows_as_not_current()
    {
        Assert.Equal(HttpStatusCode.OK, (await Finalize("1497")).Status);
        Assert.Equal(HttpStatusCode.OK, (await Finalize("1497")).Status);

        var rows = await Rows("1497");
        Assert.Equal(4, rows.Count);
        Assert.Equal([false, false, true, true], rows.Select(r => r.Current));
    }

    [Fact]
    public async Task Unknown_property_is_404()
    {
        var (status, body) = await Finalize("NO-SUCH");
        Assert.Equal(HttpStatusCode.NotFound, status);
        Assert.Equal("PROPERTY_NOT_FOUND", body.GetProperty("code").GetString());
    }
}

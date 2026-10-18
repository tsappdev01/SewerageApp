using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using MeterReading.Api.Data;
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
    [InlineData(157, 220, 1.5, 621.72)]      // UAT values: 220 and 1.50, so 3.96 per unit
    [InlineData(0, 220, 1.5, 0)]
    [InlineData(10, 2.5, 33.333, 10)]        // 9.99990 rounds to 10.00
    public void FR037_5_amount_is_consumption_times_float_times_1_2_times_rate_percent(decimal used, decimal floatValue, decimal rate, decimal amount) =>
        Assert.Equal(amount, FinalizeService.Amount(used, floatValue, rate));

    [Fact]
    public void FR037_6_vat_is_5_percent_of_each_amount_and_total_is_the_sum_of_all()
    {
        var row = FinalizeService.Pair([
            new("S1", "001", "SEWERAGE", 0, 157, 157, null, 220, 1.5m),     // 621.72, VAT 31.09 (31.086)
            new("I1", "001", "IRRIGATION", 0, 31, 31, null, 220, 1.5m),     // 122.76, VAT 6.14 (6.138)
        ]).Single();
        Assert.Equal((621.72m, 31.09m, 122.76m, 6.14m, 781.71m),
            (row.SewerageAmount, row.SewerageVat, row.IrrigationAmount, row.IrrigationVat, row.TotalAmount));
    }

    [Theory]
    [InlineData("2025-02-28", null, false)]   // DIP: 597-905, ReadingDate > 28 Feb 2025
    [InlineData("2025-03-01", null, true)]
    [InlineData("2026-10-04", null, true)]
    [InlineData("2026-10-04", "2026-09-30", false)]
    public void FR037_7_irrigation_is_not_charged_after_the_date(string reading, string? to, bool noCharge) =>
        Assert.Equal(noCharge, FinalizeService.IsNoCharge(DateOnly.Parse(reading),
            new IrrigationNoChargeRow { NoChargeAfter = new DateOnly(2025, 2, 28), ToDate = to is null ? null : DateOnly.Parse(to) }));

    [Fact]
    public void FR037_7_a_not_charged_irrigation_meter_has_no_amount_and_no_vat_but_keeps_its_consumption()
    {
        var row = FinalizeService.Pair([
            new("S1", "001", "SEWERAGE", 0, 157, 157, null, 220, 1.5m),
            new("I1", "001", "IRRIGATION", 0, 31, 31, null, 220, 1.5m, NoCharge: true),
        ]).Single();
        Assert.Equal((31m, 0m, 0m, 652.81m, true),
            (row.Irrigation!.Consumption, row.IrrigationAmount, row.IrrigationVat, row.TotalAmount, row.IrrigationNoCharge));
    }

    [Fact]
    public void FR037_5_no_float_value_or_rate_factor_means_no_amount()
    {
        Assert.Equal(0m, FinalizeService.Amount(100, null, 1.5m));
        Assert.Equal(0m, FinalizeService.Amount(100, 220, null));
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
        await using var cmd = new SqlCommand("DELETE FROM mr.MaintainFinalizedMeter WHERE PropertyCode IN ('1497', '3010', '1101'); DELETE FROM mr.IrrigationNoCharge WHERE PropertyCode = '3010'", c);
        await cmd.ExecuteNonQueryAsync();
    }

    private async Task<(HttpStatusCode Status, JsonElement Body)> FinalizeProperty(string property)
    {
        var client = factory.CreateClient();
        client.DefaultRequestHeaders.Add("X-Dev-User", Rashid);
        var response = await client.PostAsync($"/api/v1/properties/{Uri.EscapeDataString(property)}/finalize", null);
        return (response.StatusCode, await response.Content.ReadFromJsonAsync<JsonElement>());
    }

    private static async Task<List<(decimal SewerageAmount, decimal IrrigationAmount, decimal Total)>> Amounts(string property)
    {
        await using var c = new SqlConnection(ApiFactory.ConnectionString);
        await c.OpenAsync();
        await using var cmd = new SqlCommand(
            "SELECT SewerageAmount, IrrigationAmount, TotalAmount FROM mr.MaintainFinalizedMeter WHERE PropertyCode = @p AND IsCurrent = 1 ORDER BY FinalizedMeterId", c);
        cmd.Parameters.AddWithValue("@p", property);
        var list = new List<(decimal, decimal, decimal)>();
        await using var r = await cmd.ExecuteReaderAsync();
        while (await r.ReadAsync()) list.Add((r.GetDecimal(0), r.GetDecimal(1), r.GetDecimal(2)));
        return list;
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
        var (status, body) = await FinalizeProperty("1497");

        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Equal("2026-10", body.GetProperty("periodCode").GetString());
        Assert.Equal(2, body.GetProperty("rows").GetArrayLength());
        Assert.Equal(
            [("0", 0m, 0m, 0m, "1497-I", 7450m, 8440m, 990m, true), ("1497-S", 2010m, 2300m, 290m, "0", 0m, 0m, 0m, true)],
            await Rows("1497"));
        // FR-037.5/6: dev meters are priced like UAT (220, 1.50): 3.96 per unit; 990 -> 3920.40 (+196.02 VAT),
        // 290 -> 1148.40 (+57.42 VAT).
        Assert.Equal([(0m, 3920.40m, 4116.42m), (1148.40m, 0m, 1205.82m)], await Amounts("1497"));
        Assert.Equal(1148.40m, body.GetProperty("rows")[1].GetProperty("sewerageAmount").GetDecimal());
    }

    [Fact]
    public async Task FR037_2_a_property_with_meters_still_to_read_is_refused()
    {
        var (status, body) = await FinalizeProperty("1101");

        Assert.Equal(HttpStatusCode.Conflict, status);
        Assert.Equal("PROPERTY_NOT_COMPLETE", body.GetProperty("code").GetString());
        Assert.Contains("2 meters", body.GetProperty("title").GetString());
        Assert.Empty(await Rows("1101"));
    }

    [Fact]
    public async Task FR037_2_not_accessible_counts_as_read_with_no_current_reading()
    {
        // 3010: 3010-I accepted (15600 -> 16610), 3010-S not accessible (previous 4480, no reading).
        var (status, _) = await FinalizeProperty("3010");

        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Equal(
            [("0", 0m, 0m, 0m, "3010-I", 15600m, 16610m, 1010m, true), ("3010-S", 4480m, 0m, 0m, "0", 0m, 0m, 0m, true)],
            await Rows("3010"));
    }

    [Fact]
    public async Task FR037_7_irrigation_of_a_listed_property_is_not_charged_after_its_date()
    {
        await using (var c = new SqlConnection(ApiFactory.ConnectionString))
        {
            await c.OpenAsync();
            await using var cmd = new SqlCommand("INSERT mr.IrrigationNoCharge (PropertyCode, NoChargeAfter) VALUES ('3010', '2025-02-28')", c);
            await cmd.ExecuteNonQueryAsync();
        }

        // 3010-I was read on 4 Oct 2026 (db/dev/010): 1010 used, no charge. 3010-S was not accessible.
        Assert.Equal(HttpStatusCode.OK, (await FinalizeProperty("3010")).Status);
        Assert.Equal([(0m, 0m, 0m), (0m, 0m, 0m)], await Amounts("3010"));
        Assert.Equal(1010m, (await Rows("3010"))[0].UsedI);
    }

    [Fact]
    public async Task FR037_4_finalizing_again_keeps_the_earlier_rows_as_not_current()
    {
        Assert.Equal(HttpStatusCode.OK, (await FinalizeProperty("1497")).Status);
        Assert.Equal(HttpStatusCode.OK, (await FinalizeProperty("1497")).Status);

        var rows = await Rows("1497");
        Assert.Equal(4, rows.Count);
        Assert.Equal([false, false, true, true], rows.Select(r => r.Current));
    }

    [Fact]
    public async Task Unknown_property_is_404()
    {
        var (status, body) = await FinalizeProperty("NO-SUCH");
        Assert.Equal(HttpStatusCode.NotFound, status);
        Assert.Equal("PROPERTY_NOT_FOUND", body.GetProperty("code").GetString());
    }
}

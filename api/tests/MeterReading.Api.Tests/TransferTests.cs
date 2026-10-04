using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using MeterReading.Api.Domain;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Data.SqlClient;
using Microsoft.Extensions.DependencyInjection;

namespace MeterReading.Api.Tests;

/// <summary>
/// Copying readings into MaintainMeterReading (db/dev/020 stand-in). Each test removes the readings
/// it sent and the rows they produced, and resets any seed reading the batch run copied.
/// </summary>
[Collection(DatabaseCollection.Name)]
public sealed class TransferTests(ApiFactory factory) : IAsyncLifetime
{
    private const string Rashid = "rashid@dip.example";
    private readonly List<Guid> _readings = [];

    public Task InitializeAsync() => Task.CompletedTask;

    public async Task DisposeAsync()
    {
        await using var c = new SqlConnection(ApiFactory.ConnectionString);
        await c.OpenAsync();
        const string sql = """
            DELETE m FROM dbo.MaintainMeterReading m JOIN mr.ReadingTransaction t ON t.PmsRowId = m.RowId;
            UPDATE mr.ReadingTransaction SET PmsRowId = NULL, PmsCopiedAtUtc = NULL, PmsCopyAttempts = 0, PmsCopyError = NULL;
            DELETE FROM mr.ReadingTransaction WHERE TransactionId IN (SELECT CAST([value] AS uniqueidentifier) FROM OPENJSON(@ids));
            """;
        await using var cmd = new SqlCommand(sql, c);
        cmd.Parameters.AddWithValue("@ids", JsonSerializer.Serialize(_readings));
        await cmd.ExecuteNonQueryAsync();
    }

    private static readonly DateTime Captured = new(2026, 10, 4, 5, 15, 30, DateTimeKind.Utc); // 09:15:30 in the UAE

    private async Task<Guid> Send(string meterId, string condition = "WORKING", decimal? reading = null, string? reason = null,
        string? note = null, string? subTenant = null, bool confirmed = false)
    {
        var id = Guid.NewGuid();
        _readings.Add(id);
        var client = factory.CreateClient();
        client.DefaultRequestHeaders.Add("X-Dev-User", Rashid);
        var response = await client.PostAsJsonAsync("/api/v1/readings", new
        {
            transactionId = id, meterId, condition, newReading = reading, reasonCode = reason, note, subTenant,
            readerConfirmedWarning = confirmed, capturedAtUtc = Captured,
        });
        Assert.Equal(HttpStatusCode.Created, response.StatusCode);
        return id;
    }

    private static async Task<TransferResult> Transfer(WebApplicationFactory<Program> app, Guid id)
    {
        using var scope = app.Services.CreateScope();
        return await scope.ServiceProvider.GetRequiredService<PmsTransferService>().TransferOneAsync(id, CancellationToken.None);
    }

    private static async Task<Dictionary<string, object?>> PmsRow(long rowId)
    {
        await using var c = new SqlConnection(ApiFactory.ConnectionString);
        await c.OpenAsync();
        await using var cmd = new SqlCommand("SELECT * FROM dbo.MaintainMeterReading WHERE RowId = @id", c);
        cmd.Parameters.AddWithValue("@id", rowId);
        await using var r = await cmd.ExecuteReaderAsync();
        Assert.True(await r.ReadAsync());
        return Enumerable.Range(0, r.FieldCount).ToDictionary(r.GetName, i => r.IsDBNull(i) ? null : r.GetValue(i));
    }

    private static async Task<(long? RowId, int Attempts, string? Error)> Tracking(Guid id)
    {
        await using var c = new SqlConnection(ApiFactory.ConnectionString);
        await c.OpenAsync();
        await using var cmd = new SqlCommand("SELECT PmsRowId, PmsCopyAttempts, PmsCopyError FROM mr.ReadingTransaction WHERE TransactionId = @id", c);
        cmd.Parameters.AddWithValue("@id", id);
        await using var r = await cmd.ExecuteReaderAsync();
        Assert.True(await r.ReadAsync());
        return (r.IsDBNull(0) ? null : r.GetInt64(0), r.GetInt32(1), r.IsDBNull(2) ? null : r.GetString(2));
    }

    [Fact]
    public async Task An_accepted_reading_is_copied_in_the_tables_own_format()
    {
        var id = await Send("BC0003", reading: 30_500, subTenant: "Al Fajr Workshop");
        var result = await Transfer(factory, id);
        Assert.True(result.Copied);

        var row = await PmsRow(result.PmsRowId!.Value);
        Assert.Equal("1101", row["PropertyCode"]);
        Assert.Equal("1101-I", row["MeterNumber"]);
        Assert.Equal("T-0102", row["TenantCode"]);
        Assert.Equal("2026-10-04 09:15:30", row["ReadingDate"]);   // UAE time, as text
        Assert.Equal("WORKING", row["MeterStatus"]);
        Assert.Equal("30110", row["Previous_Reading"]);
        Assert.Equal("30500", row["Current_Reading"]);
        Assert.Equal("E1001", row["MeterReader"]);
        Assert.Equal("Irrigation", row["Type"]);                  // as the source writes it
        Assert.Equal("Al Fajr Workshop", row["SubTenant"]);
        Assert.Equal(390L, row["Consumption"]);                   // calculated by the table
        Assert.Equal("0", row["Posted"]);                         // table defaults
        Assert.Equal(false, row["Transferred"]);
        Assert.NotNull(row["UploadTime"]);
        Assert.Null(row["TransferredToBaan"]);

        Assert.Equal(result.PmsRowId, (await Tracking(id)).RowId);
    }

    [Fact]
    public async Task A_reading_is_copied_only_once()
    {
        var id = await Send("BC0003", reading: 30_500);
        var first = await Transfer(factory, id);
        var again = await Transfer(factory, id);
        Assert.True(first.Copied);
        Assert.False(again.Copied);
        Assert.Null(again.Error);
        Assert.Equal(first.PmsRowId, (await Tracking(id)).RowId);
    }

    [Fact]
    public async Task An_exception_waits_for_the_supervisor()
    {
        var id = await Send("BC0009", reading: 5_000, confirmed: true); // lower than last time
        Assert.False((await Transfer(factory, id)).Copied);
        Assert.Null((await Tracking(id)).RowId);
    }

    [Fact]
    public async Task A_meter_that_could_not_be_reached_is_not_copied()
    {
        var id = await Send("BC0012", "NOT_ACCESSIBLE", reason: "GATE_LOCKED", note: "Gate locked");
        Assert.False((await Transfer(factory, id)).Copied);
    }

    [Fact]
    public async Task A_failed_copy_is_rolled_back_and_recorded_for_retry()
    {
        // The stand-in's SubTenant holds 50 characters; the API allows 100.
        var id = await Send("BC0003", reading: 30_500, subTenant: new string('S', 60));
        var result = await Transfer(factory, id);
        Assert.False(result.Copied);
        Assert.NotNull(result.Error);
        var (rowId, attempts, error) = await Tracking(id);
        Assert.Null(rowId);
        Assert.Equal(1, attempts);
        Assert.Contains("truncated", error);
    }

    [Fact]
    public async Task Status_and_date_format_follow_the_settings()
    {
        using var custom = factory.WithWebHostBuilder(b =>
        {
            b.UseSetting("PmsTransfer:MeterStatusMap:WORKING", "Working");
            b.UseSetting("PmsTransfer:ReadingDateFormat", "dd/MM/yyyy HH:mm");
        });
        var id = await Send("BC0003", reading: 30_500);
        var result = await Transfer(custom, id);
        var row = await PmsRow(result.PmsRowId!.Value);
        Assert.Equal("Working", row["MeterStatus"]);
        Assert.Equal("04/10/2026 09:15", row["ReadingDate"]);
    }

    [Fact]
    public async Task The_batch_run_copies_waiting_readings()
    {
        var id = await Send("BC0003", reading: 30_500);
        using var scope = factory.Services.CreateScope();
        var results = await scope.ServiceProvider.GetRequiredService<PmsTransferService>().RunOnceAsync(CancellationToken.None);
        Assert.Contains(results, r => r.TransactionId == id && r.Copied);
        Assert.DoesNotContain(results, r => r.Error is not null);
    }

    [Theory]
    [InlineData("dbo.MaintainMeterReading", "[dbo].[MaintainMeterReading]")]
    [InlineData("PropertyManagementSystem.dbo.MaintainMeterReading", "[PropertyManagementSystem].[dbo].[MaintainMeterReading]")]
    [InlineData("[dbo].[MaintainMeterReading]", "[dbo].[MaintainMeterReading]")]
    public void Target_table_name_is_checked_and_quoted(string configured, string quoted) =>
        Assert.Equal(quoted, PmsTransferService.QuoteTable(configured));

    [Fact]
    public void A_target_table_name_with_sql_in_it_is_refused() =>
        Assert.Throws<InvalidOperationException>(() => PmsTransferService.QuoteTable("dbo.MaintainMeterReading; DROP TABLE x"));
}

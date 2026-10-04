using System.Text.Json;
using System.Text.RegularExpressions;
using Dapper;
using MeterReading.Api.Data;
using Microsoft.Data.SqlClient;
using Microsoft.Extensions.Options;

namespace MeterReading.Api.Domain;

public sealed record TransferResult(Guid TransactionId, bool Copied, long? PmsRowId, string? Error);

/// <summary>
/// Copies accepted readings into MaintainMeterReading so the existing posting and Baan transfer
/// pick them up. Each copy is one transaction: insert there, then record the new RowId here, so a
/// reading is copied exactly once even if the job stops halfway. This needs both tables on one
/// SQL Server (same database, or a three-part TargetTable).
///
/// Copied: readings ACCEPTED, or APPROVED by a supervisor, with a value. Not copied: exceptions
/// until approved, NOT_ACCESSIBLE (no value) and METER_REPLACED (changes which meter is fitted,
/// so someone enters it).
/// </summary>
public sealed partial class PmsTransferService(SqlConnectionFactory db, IOptions<PmsTransferOptions> options, ILogger<PmsTransferService> log)
{
    private readonly PmsTransferOptions _o = options.Value;
    private readonly string _target = QuoteTable(options.Value.TargetTable);

    private const string Eligible = """
        t.PmsCopiedAtUtc IS NULL
        AND t.Status IN ('ACCEPTED', 'APPROVED')
        AND t.MeterCondition IN ('WORKING', 'DAMAGED', 'SUBMERSED', 'REMOVED')
        AND t.NewReading IS NOT NULL
        """;

    /// <summary>Copies up to BatchSize waiting readings, oldest first.</summary>
    public async Task<IReadOnlyList<TransferResult>> RunOnceAsync(CancellationToken ct)
    {
        var sql = $"""
            SELECT TOP (@batch) t.TransactionId
            FROM mr.ReadingTransaction t
            WHERE {Eligible} AND t.PmsCopyAttempts < @maxAttempts
            ORDER BY t.ReceivedAtUtc
            """;
        List<Guid> ids;
        await using (var c = await db.OpenMeterReadingAsync(ct))
            ids = (await c.QueryAsync<Guid>(new CommandDefinition(sql, new { batch = _o.BatchSize, maxAttempts = _o.MaxAttempts }, cancellationToken: ct))).AsList();

        var results = new List<TransferResult>(ids.Count);
        foreach (var id in ids) results.Add(await TransferOneAsync(id, ct));
        var copied = results.Count(r => r.Copied);
        var failed = results.Count(r => r.Error is not null);
        if (copied > 0 || failed > 0) log.LogInformation("Copied {Copied} readings to {Target}; {Failed} failed", copied, _o.TargetTable, failed);
        return results;
    }

    /// <summary>Copies one reading if it is eligible and not copied yet.</summary>
    public async Task<TransferResult> TransferOneAsync(Guid transactionId, CancellationToken ct)
    {
        var sql = $"""
            SET XACT_ABORT ON;
            BEGIN TRANSACTION;
            IF NOT EXISTS (SELECT 1 FROM mr.ReadingTransaction t WITH (UPDLOCK, HOLDLOCK)
                           WHERE t.TransactionId = @id AND {Eligible})
            BEGIN
                ROLLBACK TRANSACTION;
                SELECT CAST(NULL AS bigint);
                RETURN;
            END
            DECLARE @row TABLE (RowId bigint);
            INSERT INTO {_target} (PropertyId, PropertyCode, MeterNumber, TenantCode, ReadingDate, MeterStatus,
                Latitude, Longitude, Previous_Reading, Current_Reading, MeterReader, [Type], SubTenant)
            OUTPUT inserted.RowId INTO @row
            SELECT t.PropertyId, t.PropertyCode, t.MeterNumber, t.TenantCode,
                   FORMAT(t.CapturedAtUtc AT TIME ZONE 'UTC' AT TIME ZONE @timeZone, @dateFormat, 'en-US'),
                   COALESCE({SqlList.Lookup("@statusMap", "t.MeterCondition", "nvarchar(50)")}, t.MeterCondition),
                   FORMAT(t.Latitude, '0.######', 'en-US'), FORMAT(t.Longitude, '0.######', 'en-US'),
                   FORMAT(t.PreviousReading, '0.####', 'en-US'), FORMAT(t.NewReading, '0.####', 'en-US'),
                   t.ReaderId, t.MeterType, t.SubTenant
            FROM mr.ReadingTransaction t
            WHERE t.TransactionId = @id;
            UPDATE mr.ReadingTransaction
            SET PmsRowId = (SELECT RowId FROM @row), PmsCopiedAtUtc = SYSUTCDATETIME(), PmsCopyError = NULL
            WHERE TransactionId = @id;
            COMMIT TRANSACTION;
            SELECT RowId FROM @row;
            """;
        try
        {
            await using var c = await db.OpenMeterReadingAsync(ct);
            // Read every result to the end: ExecuteScalar can drop an error raised after the first
            // result, and a copy that fails silently would never be retried or reported.
            await using var reader = await c.ExecuteReaderAsync(new CommandDefinition(sql, new
            {
                id = transactionId,
                timeZone = _o.TimeZone,
                dateFormat = _o.ReadingDateFormat,
                statusMap = SqlList.Map(_o.MeterStatusMap),
            }, cancellationToken: ct));
            long? rowId = null;
            do
            {
                while (await reader.ReadAsync(ct))
                    if (!await reader.IsDBNullAsync(0, ct)) rowId = reader.GetInt64(0);
            } while (await reader.NextResultAsync(ct));
            return new TransferResult(transactionId, rowId is not null, rowId, null);
        }
        catch (SqlException e)
        {
            log.LogWarning(e, "Could not copy reading {TransactionId} to {Target}", transactionId, _o.TargetTable);
            await RecordFailureAsync(transactionId, e.Message, ct);
            return new TransferResult(transactionId, false, null, e.Message);
        }
    }

    /// <summary>True when the target table exists and the mr tracking columns are there.</summary>
    public async Task<string?> FindProblemAsync(CancellationToken ct)
    {
        await using var c = await db.OpenMeterReadingAsync(ct);
        if (await c.ExecuteScalarAsync<int>(new CommandDefinition("SELECT CASE WHEN COL_LENGTH(N'mr.ReadingTransaction', N'PmsRowId') IS NULL THEN 0 ELSE 1 END", cancellationToken: ct)) == 0)
            return "mr.ReadingTransaction.PmsRowId (run db/006_pms_transfer.sql)";
        if (await c.ExecuteScalarAsync<int>(new CommandDefinition("SELECT CASE WHEN OBJECT_ID(@t) IS NULL THEN 0 ELSE 1 END", new { t = _target }, cancellationToken: ct)) == 0)
            return $"{_o.TargetTable} (PmsTransfer:TargetTable) not found or no permission";
        return null;
    }

    private async Task RecordFailureAsync(Guid id, string error, CancellationToken ct)
    {
        try
        {
            await using var c = await db.OpenMeterReadingAsync(ct);
            await c.ExecuteAsync(new CommandDefinition(
                "UPDATE mr.ReadingTransaction SET PmsCopyAttempts = PmsCopyAttempts + 1, PmsCopyError = LEFT(@error, 400) WHERE TransactionId = @id",
                new { id, error }, cancellationToken: ct));
        }
        catch (SqlException e)
        {
            log.LogError(e, "Could not record the failed copy of {TransactionId}", id);
        }
    }

    /// <summary>"db.schema.table" with each part checked and bracketed, safe to put in SQL text.</summary>
    public static string QuoteTable(string name)
    {
        var parts = name.Split('.');
        if (parts.Length is < 1 or > 3) throw new InvalidOperationException($"PmsTransfer:TargetTable '{name}' must be table, schema.table or database.schema.table.");
        return string.Join('.', parts.Select(p =>
        {
            var bare = p.Trim().TrimStart('[').TrimEnd(']');
            if (!Identifier().IsMatch(bare)) throw new InvalidOperationException($"PmsTransfer:TargetTable part '{p}' is not a plain SQL identifier.");
            return $"[{bare}]";
        }));
    }

    [GeneratedRegex("^[A-Za-z_][A-Za-z0-9_]{0,127}$")]
    private static partial Regex Identifier();
}

/// <summary>Runs the transfer every IntervalSeconds while PmsTransfer:Enabled is true.</summary>
public sealed class PmsTransferWorker(IServiceScopeFactory scopes, IOptions<PmsTransferOptions> options, ILogger<PmsTransferWorker> log) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        if (!options.Value.Enabled)
        {
            log.LogInformation("Transfer to {Target} is off (PmsTransfer:Enabled)", options.Value.TargetTable);
            return;
        }
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(Math.Max(10, options.Value.IntervalSeconds)));
        do
        {
            try
            {
                using var scope = scopes.CreateScope();
                await scope.ServiceProvider.GetRequiredService<PmsTransferService>().RunOnceAsync(stoppingToken);
            }
            catch (Exception e) when (!stoppingToken.IsCancellationRequested)
            {
                log.LogError(e, "Transfer to {Target} failed; trying again next round", options.Value.TargetTable);
            }
        }
        while (await timer.WaitForNextTickAsync(stoppingToken));
    }
}

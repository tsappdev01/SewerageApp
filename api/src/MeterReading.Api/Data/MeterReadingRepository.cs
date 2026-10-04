using System.Text.Json;
using Dapper;
using Microsoft.Extensions.Options;

namespace MeterReading.Api.Data;

/// <summary>
/// All SQL the reader endpoints run. Source views are read-only; mr.* tables are the API's own.
/// Lists of meter ids are passed as one JSON parameter and expanded with OPENJSON, which keeps
/// clear of SQL Server's 2,100-parameter limit for readers with large routes.
/// </summary>
public sealed class MeterReadingRepository(SqlConnectionFactory db, IOptions<SourceViewsOptions> options)
{
    private readonly SourceViewNames _v = db.Views;
    private readonly SourceViewsOptions _options = options.Value;

    private const int CommandTimeoutSeconds = 30;

    public async Task<ReaderRow?> FindReaderAsync(string loginEmail, CancellationToken ct)
    {
        var sql = $"""
            SELECT CAST(ReaderId AS varchar(50)) AS ReaderId, CAST(LoginEmail AS nvarchar(256)) AS LoginEmail,
                   CAST(DisplayName AS nvarchar(100)) AS DisplayName, CAST(TeamCode AS varchar(20)) AS TeamCode,
                   CAST(SupervisorEmail AS nvarchar(256)) AS SupervisorEmail
            FROM {_v.Reader}
            WHERE LoginEmail = @loginEmail AND IsActive = 1
            """;
        await using var c = await db.OpenSourceAsync(ct);
        return await c.QuerySingleOrDefaultAsync<ReaderRow>(Cmd(sql, new { loginEmail }, ct));
    }

    /// <summary>The OPEN period. If the source wrongly has several, the latest wins.</summary>
    public async Task<PeriodRow?> GetOpenPeriodAsync(CancellationToken ct)
    {
        var sql = $"""
            SELECT TOP (1) CAST(PeriodCode AS char(7)) AS PeriodCode, CAST(StartDate AS datetime2) AS StartDate,
                   CAST(EndDate AS datetime2) AS EndDate, CAST(Status AS varchar(10)) AS Status
            FROM {_v.ReadingPeriod}
            WHERE Status = 'OPEN'
            ORDER BY PeriodCode DESC
            """;
        await using var c = await db.OpenSourceAsync(ct);
        return await c.QuerySingleOrDefaultAsync<PeriodRow>(Cmd(sql, null, ct));
    }

    public async Task<PeriodRow?> GetPeriodAsync(string periodCode, CancellationToken ct)
    {
        var sql = $"""
            SELECT CAST(PeriodCode AS char(7)) AS PeriodCode, CAST(StartDate AS datetime2) AS StartDate,
                   CAST(EndDate AS datetime2) AS EndDate, CAST(Status AS varchar(10)) AS Status
            FROM {_v.ReadingPeriod}
            WHERE PeriodCode = @periodCode
            """;
        await using var c = await db.OpenSourceAsync(ct);
        return await c.QuerySingleOrDefaultAsync<PeriodRow>(Cmd(sql, new { periodCode }, ct));
    }

    /// <summary>Active meters assigned to the reader for the period, with property, zone and last reading.</summary>
    public async Task<IReadOnlyList<AssignedMeterRow>> GetAssignedMetersAsync(string readerId, string periodCode, CancellationToken ct)
    {
        var from = _options.AssignmentMode == AssignmentMode.Meter
            ? $"""
              FROM {_v.Assignment} a
              JOIN {_v.Meter} m ON m.MeterId = a.MeterId
              JOIN {_v.Property} p ON p.PropertyCode = m.PropertyCode
              """
            : $"""
              FROM {_v.ZoneReader} zr
              JOIN {_v.Property} p ON p.ZoneCode = zr.ZoneCode
              JOIN {_v.Meter} m ON m.PropertyCode = p.PropertyCode
              """;
        var where = _options.AssignmentMode == AssignmentMode.Meter
            ? "WHERE a.PeriodCode = @periodCode AND a.ReaderId = @readerId AND m.Status = 'ACTIVE'"
            : "WHERE zr.ReaderId = @readerId AND p.IsActive = 1 AND m.Status = 'ACTIVE'";
        var sql = $"""
            SELECT CAST(m.MeterId AS bigint) AS MeterId, CAST(m.MeterNumber AS varchar(30)) AS MeterNumber,
                   CAST(m.MeterType AS varchar(20)) AS MeterType,
                   CAST(p.PropertyCode AS varchar(30)) AS PropertyCode, CAST(p.PropertyName AS nvarchar(150)) AS PropertyName,
                   CAST(p.RouteSequence AS int) AS PropertyRoute,
                   CAST(p.Latitude AS decimal(9,6)) AS Latitude, CAST(p.Longitude AS decimal(9,6)) AS Longitude,
                   CAST(p.ZoneCode AS varchar(20)) AS ZoneCode, CAST(z.ZoneName AS nvarchar(100)) AS ZoneName,
                   CAST(m.RouteSequence AS int) AS MeterRoute,
                   CAST(m.RegisterDigits AS int) AS RegisterDigits, CAST(ISNULL(m.DecimalDigits, 0) AS int) AS DecimalDigits,
                   CAST(m.OpeningReading AS decimal(18,3)) AS OpeningReading,
                   CAST(lr.ReadingValue AS decimal(18,3)) AS LastReading, CAST(lr.ReadingDate AS datetime2) AS LastReadingDate,
                   CAST(lr.AverageConsumption AS decimal(18,3)) AS AverageConsumption
            {from}
            LEFT JOIN {_v.Zone} z ON z.ZoneCode = p.ZoneCode
            LEFT JOIN {_v.LastReading} lr ON lr.MeterId = m.MeterId
            {where}
            """;
        await using var c = await db.OpenSourceAsync(ct);
        var rows = await c.QueryAsync<AssignedMeterRow>(Cmd(sql, new { readerId, periodCode }, ct));
        return rows.AsList();
    }

    /// <summary>Average of the last N actual consumptions, for meters the source gave no average for (BR-007).</summary>
    public async Task<IReadOnlyDictionary<long, decimal>> GetHistoryAveragesAsync(IReadOnlyCollection<long> meterIds, int periods, CancellationToken ct)
    {
        if (!_options.HasReadingHistory || meterIds.Count == 0) return new Dictionary<long, decimal>();
        var sql = $"""
            SELECT x.MeterId, CAST(AVG(x.Consumption) AS decimal(18,3)) AS Average
            FROM (
                SELECT CAST(h.MeterId AS bigint) AS MeterId, CAST(h.Consumption AS decimal(18,3)) AS Consumption,
                       ROW_NUMBER() OVER (PARTITION BY h.MeterId ORDER BY h.PeriodCode DESC) AS rn
                FROM {_v.ReadingHistory} h
                WHERE h.ConsumptionBasis = 'ACTUAL' AND h.Consumption IS NOT NULL
                  AND h.MeterId IN (SELECT CAST([value] AS bigint) FROM OPENJSON(@ids))
            ) x
            WHERE x.rn <= @periods
            GROUP BY x.MeterId
            """;
        await using var c = await db.OpenSourceAsync(ct);
        var rows = await c.QueryAsync<AverageRow>(Cmd(sql, new { ids = Json(meterIds), periods }, ct));
        return rows.ToDictionary(r => r.MeterId, r => r.Average);
    }

    public async Task<IReadOnlyList<HistoryRow>> GetHistoryAsync(long meterId, int periods, CancellationToken ct)
    {
        if (!_options.HasReadingHistory) return [];
        var sql = $"""
            SELECT TOP (@periods) CAST(PeriodCode AS char(7)) AS PeriodCode, CAST(ReadingDate AS datetime2) AS ReadingDate,
                   CAST(ReadingValue AS decimal(18,3)) AS ReadingValue, CAST(Consumption AS decimal(18,3)) AS Consumption,
                   CAST(ConsumptionBasis AS varchar(10)) AS ConsumptionBasis
            FROM {_v.ReadingHistory}
            WHERE MeterId = @meterId
            ORDER BY PeriodCode DESC
            """;
        await using var c = await db.OpenSourceAsync(ct);
        return (await c.QueryAsync<HistoryRow>(Cmd(sql, new { meterId, periods }, ct))).AsList();
    }

    /// <summary>Latest live (not superseded) transaction per meter for the period.</summary>
    public async Task<IReadOnlyDictionary<long, LatestTransactionRow>> GetLatestTransactionsAsync(
        string periodCode, IReadOnlyCollection<long> meterIds, CancellationToken ct)
    {
        if (meterIds.Count == 0) return new Dictionary<long, LatestTransactionRow>();
        const string sql = """
            SELECT MeterId, TransactionId, Status, StatusNote, MeterCondition, ReceivedAtUtc
            FROM (
                SELECT t.MeterId, t.TransactionId, t.Status, t.StatusNote, t.MeterCondition, t.ReceivedAtUtc,
                       ROW_NUMBER() OVER (PARTITION BY t.MeterId ORDER BY t.ReceivedAtUtc DESC, t.CapturedAtUtc DESC) AS rn
                FROM mr.ReadingTransaction t
                WHERE t.PeriodCode = @periodCode AND t.Status <> 'SUPERSEDED'
                  AND t.MeterId IN (SELECT CAST([value] AS bigint) FROM OPENJSON(@ids))
            ) x
            WHERE x.rn = 1
            """;
        await using var c = await db.OpenMeterReadingAsync(ct);
        var rows = await c.QueryAsync<LatestTransactionRow>(Cmd(sql, new { periodCode, ids = Json(meterIds) }, ct));
        return rows.ToDictionary(r => r.MeterId);
    }

    public async Task<IReadOnlyList<TransactionRow>> GetReaderTransactionsAsync(string readerId, string periodCode, CancellationToken ct)
    {
        const string sql = """
            SELECT TransactionId, MeterId, MeterCondition, ReasonCode, NewReading, Consumption,
                   CapturedAtUtc, ReceivedAtUtc, Status, StatusNote
            FROM mr.ReadingTransaction
            WHERE ReaderId = @readerId AND PeriodCode = @periodCode AND Status <> 'SUPERSEDED'
            ORDER BY CapturedAtUtc DESC
            """;
        await using var c = await db.OpenMeterReadingAsync(ct);
        return (await c.QueryAsync<TransactionRow>(Cmd(sql, new { readerId, periodCode }, ct))).AsList();
    }

    public async Task<IReadOnlyList<TransactionRow>> GetMeterTransactionsAsync(long meterId, string periodCode, CancellationToken ct)
    {
        const string sql = """
            SELECT TransactionId, MeterId, MeterCondition, ReasonCode, NewReading, Consumption,
                   CapturedAtUtc, ReceivedAtUtc, Status, StatusNote
            FROM mr.ReadingTransaction
            WHERE MeterId = @meterId AND PeriodCode = @periodCode
            ORDER BY ReceivedAtUtc DESC
            """;
        await using var c = await db.OpenMeterReadingAsync(ct);
        return (await c.QueryAsync<TransactionRow>(Cmd(sql, new { meterId, periodCode }, ct))).AsList();
    }

    /// <summary>Names of required views that are missing, for the readiness check.</summary>
    public async Task<IReadOnlyList<string>> FindMissingViewsAsync(CancellationToken ct)
    {
        var required = SourceViewNames.Required
            .Append(_options.AssignmentMode == AssignmentMode.Meter ? "vw_MR_Assignment" : "vw_MR_ZoneReader")
            .Concat(_options.HasReadingHistory ? ["vw_MR_ReadingHistory"] : Array.Empty<string>())
            .ToArray();
        const string sql = """
            SELECT r.[value]
            FROM OPENJSON(@names) r
            WHERE OBJECT_ID(QUOTENAME(@schema) + N'.' + QUOTENAME(r.[value])) IS NULL
            """;
        await using var c = await db.OpenSourceAsync(ct);
        var missing = (await c.QueryAsync<string>(Cmd(sql, new { names = JsonSerializer.Serialize(required), schema = _v.Schema }, ct))).AsList();

        await using var mr = await db.OpenMeterReadingAsync(ct);
        if (await mr.ExecuteScalarAsync<int>(Cmd("SELECT CASE WHEN OBJECT_ID(N'mr.ReadingTransaction') IS NULL THEN 0 ELSE 1 END", null, ct)) == 0)
            missing.Add("mr.ReadingTransaction (run db/002_create_mr_schema.sql)");
        return missing;
    }

    private static string Json(IEnumerable<long> ids) => JsonSerializer.Serialize(ids);

    private static CommandDefinition Cmd(string sql, object? args, CancellationToken ct) =>
        new(sql, args, commandTimeout: CommandTimeoutSeconds, cancellationToken: ct);
}

using System.Text.Json;
using Dapper;
using Microsoft.Extensions.Options;

namespace MeterReading.Api.Data;

/// <summary>Narrows the meter list. Empty means all active meters.</summary>
public sealed record MeterFilter(IReadOnlyCollection<string>? ZoneCodes = null, IReadOnlyCollection<string>? MeterIds = null);

/// <summary>
/// All SQL the reader endpoints run. Source views are read-only; mr.* tables are the API's own.
/// Lists of ids are passed as one XML parameter (<see cref="SqlList"/>), which keeps clear of SQL
/// Server's 2,100-parameter limit and, unlike OPENJSON, works at any database compatibility level. View columns are CAST (or TRY_CAST where a bad value should
/// read as "unknown" rather than fail the request) to the row types.
/// </summary>
public sealed class MeterReadingRepository(SqlConnectionFactory db, IOptions<SourceViewsOptions> options, IOptions<ImageStoreOptions> images)
{
    private readonly SourceViewNames _v = db.Views;
    private readonly SourceViewsOptions _options = options.Value;

    // PMS's views can take 15-30 s for the full list; the list is kept in memory (SourceCache) after.
    private const int CommandTimeoutSeconds = 90;

    /// <summary>The period code is built from StartDate (yyyy-MM), whatever format the view's PeriodCode uses.</summary>
    private const string PeriodColumns = """
        CONVERT(char(7), CAST(StartDate AS date), 126) AS PeriodCode, CAST(StartDate AS datetime2) AS StartDate,
        CAST(EndDate AS datetime2) AS EndDate, UPPER(CAST(Status AS varchar(10))) AS Status
        """;

    /// <summary>Active readers with this sign-in name. More than one means the view is ambiguous.</summary>
    public async Task<IReadOnlyList<ReaderRow>> FindReadersAsync(string loginEmail, CancellationToken ct)
    {
        var sql = $"""
            SELECT TOP (2) CAST(UserId AS varchar(50)) AS ReaderId, CAST(LoginEmail AS nvarchar(256)) AS LoginEmail,
                   CAST(DisplayName AS nvarchar(100)) AS DisplayName, CAST(TeamCode AS varchar(20)) AS TeamCode,
                   CAST(SupervisorEmail AS nvarchar(256)) AS SupervisorEmail
            FROM {_v.Reader}
            WHERE CAST(LoginEmail AS nvarchar(256)) COLLATE DATABASE_DEFAULT = @loginEmail AND {Active("IsActive")}
            """;
        await using var c = await db.OpenSourceAsync(ct);
        return (await c.QueryAsync<ReaderRow>(Cmd(sql, new { loginEmail }, ct))).AsList();
    }

    /// <summary>The latest OPEN period. The source may mark several months OPEN; the newest wins.</summary>
    /// <summary>
    /// The period open in vw_MR_ReadingPeriod. Its Status reads CLOSED when mr.ReadingPeriodStatus (db/024)
    /// closed it after posting (FR-037.11): it is still returned, so phones see it, but takes no readings.
    /// </summary>
    public async Task<PeriodRow?> GetOpenPeriodAsync(CancellationToken ct)
    {
        var sql = $"""
            SELECT TOP (1) {PeriodColumns}
            FROM {_v.ReadingPeriod}
            WHERE Status = 'OPEN'
            ORDER BY StartDate DESC
            """;
        PeriodRow? period;
        await using (var c = await db.OpenSourceAsync(ct))
            period = await c.QuerySingleOrDefaultAsync<PeriodRow>(Cmd(sql, null, ct));
        if (period is null || !await IsPeriodClosedAsync(period.PeriodCode, ct)) return period;
        return new PeriodRow { PeriodCode = period.PeriodCode, StartDate = period.StartDate, EndDate = period.EndDate, Status = "CLOSED" };
    }

    /// <summary>FR-037.11: closed in mr.ReadingPeriodStatus. False when db/024 has not been run.</summary>
    public async Task<bool> IsPeriodClosedAsync(string periodCode, CancellationToken ct)
    {
        const string sql = """
            IF OBJECT_ID(N'mr.ReadingPeriodStatus', N'U') IS NULL SELECT 0;
            ELSE SELECT CASE WHEN EXISTS (SELECT 1 FROM mr.ReadingPeriodStatus WHERE PeriodCode = @periodCode AND Status = 'CLOSED') THEN 1 ELSE 0 END;
            """;
        await using var c = await db.OpenMeterReadingAsync(ct);
        return await c.ExecuteScalarAsync<int>(Cmd(sql, new { periodCode }, ct)) == 1;
    }

    /// <summary>FR-037.11: how many of these properties have a current finalized set for the period.</summary>
    public async Task<int> CountFinalizedPropertiesAsync(string periodCode, IReadOnlyCollection<string> propertyCodes, CancellationToken ct)
    {
        if (propertyCodes.Count == 0) return 0;
        var sql = $"""
            SELECT COUNT(DISTINCT f.PropertyCode) FROM mr.MaintainFinalizedMeter f
            WHERE f.PeriodCode = @periodCode AND f.IsCurrent = 1
              AND f.PropertyCode IN ({SqlList.Select("@codes", "varchar(50)")})
            """;
        await using var c = await db.OpenMeterReadingAsync(ct);
        return await c.ExecuteScalarAsync<int>(Cmd(sql, new { periodCode, codes = SqlList.Of(propertyCodes) }, ct));
    }

    /// <summary>FR-037.11: closes the period for the app (mr.ReadingPeriodStatus, db/024).</summary>
    public async Task ClosePeriodAsync(string periodCode, string changedBy, string note, CancellationToken ct)
    {
        const string sql = """
            MERGE mr.ReadingPeriodStatus WITH (HOLDLOCK) AS t
            USING (SELECT @periodCode AS PeriodCode) AS s ON t.PeriodCode = s.PeriodCode
            WHEN MATCHED THEN UPDATE SET Status = 'CLOSED', ChangedAtUtc = SYSUTCDATETIME(), ChangedBy = @changedBy, Note = @note
            WHEN NOT MATCHED THEN INSERT (PeriodCode, Status, ChangedBy, Note) VALUES (@periodCode, 'CLOSED', @changedBy, @note);
            """;
        await using var c = await db.OpenMeterReadingAsync(ct);
        await c.ExecuteAsync(Cmd(sql, new { periodCode, changedBy, note }, ct));
    }

    public async Task<PeriodRow?> GetPeriodAsync(string periodCode, CancellationToken ct)
    {
        var sql = $"""
            SELECT TOP (1) {PeriodColumns}
            FROM {_v.ReadingPeriod}
            WHERE CONVERT(char(7), CAST(StartDate AS date), 126) = @periodCode
            ORDER BY StartDate DESC
            """;
        await using var c = await db.OpenSourceAsync(ct);
        return await c.QuerySingleOrDefaultAsync<PeriodRow>(Cmd(sql, new { periodCode }, ct));
    }

    /// <summary>vw_MR_MeterStatus (PMS's meter statuses), or null when the view is not there.</summary>
    public async Task<IReadOnlyList<MeterStatusRow>?> GetMeterStatusesAsync(CancellationToken ct)
    {
        await using var c = await db.OpenSourceAsync(ct);
        var exists = await c.ExecuteScalarAsync<int?>(Cmd($"SELECT OBJECT_ID(N'{_v.MeterStatus.Replace("'", "''")}')", null, ct));
        if (exists is null) return null;
        var sql = $"""
            SELECT LTRIM(RTRIM(CAST(StatusCode AS varchar(20)))) AS StatusCode, CAST(StatusName AS nvarchar(100)) AS StatusName,
                   TRY_CAST(SequenceNo AS int) AS SequenceNo
            FROM {_v.MeterStatus}
            WHERE StatusCode IS NOT NULL
            """;
        return (await c.QueryAsync<MeterStatusRow>(Cmd(sql, null, ct))).ToList();
    }

    /// <summary>
    /// Active meters in active properties and zones. Work is not assigned to readers, so the only
    /// narrowing is by zone or by meter id. MeterType is read from its first letter (I or S) and
    /// Status accepts 1, True or ACTIVE, so the view can pass its own codes through.
    /// </summary>
    public async Task<IReadOnlyList<MeterRow>> GetMetersAsync(MeterFilter filter, CancellationToken ct)
    {
        var zoneClause = filter.ZoneCodes is { Count: > 0 }
            ? $"AND p.ZoneCode IN ({SqlList.Select("@zones", "varchar(20)")})"
            : "";
        var idClause = filter.MeterIds is { Count: > 0 }
            ? $"AND m.MeterId IN ({SqlList.Select("@ids", "varchar(50)")})"
            : "";
        var sql = $"""
            SELECT CAST(m.MeterId AS varchar(50)) AS MeterId, CAST(m.MeterNumber AS varchar(30)) AS MeterNumber,
                   CASE UPPER(LEFT(LTRIM(CAST(m.MeterType AS varchar(20))), 1))
                        WHEN 'I' THEN 'IRRIGATION' WHEN 'S' THEN 'SEWERAGE'
                        ELSE UPPER(CAST(m.MeterType AS varchar(20))) END AS MeterType,
                   CAST(p.PropertyCode AS varchar(30)) AS PropertyCode, CAST(p.PropertyName AS nvarchar(150)) AS PropertyName,
                   CAST(p.TenantCode AS varchar(30)) AS TenantCode, LTRIM(RTRIM(CAST(p.CompanyName AS nvarchar(200)))) AS CompanyName,
                   TRY_CAST(p.PropertyId AS bigint) AS PropertyId, CAST(m.MeterType AS varchar(20)) AS SourceMeterType,
                   TRY_CAST(p.RouteSequence AS int) AS PropertyRoute,
                   TRY_CAST(p.Latitude AS decimal(9,6)) AS Latitude, TRY_CAST(p.Longitude AS decimal(9,6)) AS Longitude,
                   CAST(p.ZoneCode AS varchar(20)) AS ZoneCode, LTRIM(RTRIM(CAST(z.ZoneName AS nvarchar(100)))) AS ZoneName,
                   TRY_CAST(m.RouteSequence AS int) AS MeterRoute,
                   CAST(m.RegisterDigits AS int) AS RegisterDigits, CAST(ISNULL(m.DecimalDigits, 0) AS int) AS DecimalDigits,
                   TRY_CAST(m.OpeningReading AS decimal(18,4)) AS LastReading,
                   TRY_CAST(m.LastConsumption AS decimal(18,4)) AS LastConsumption,
                   TRY_CAST(m.AvgConsumption AS decimal(18,4)) AS AverageConsumption,
                   TRY_CAST(m.FloatValue AS decimal(18,6)) AS FloatValue, TRY_CAST(m.RateFactor AS decimal(18,6)) AS RateFactor
            FROM {_v.Meter} m
            JOIN {_v.Property} p ON p.PropertyCode = m.PropertyCode
            LEFT JOIN {_v.Zone} z ON z.ZoneCode = p.ZoneCode
            WHERE {Active("m.Status")}
              AND {Active("p.IsActive")} AND (z.IsActive IS NULL OR {Active("z.IsActive")})
              {zoneClause}
              {idClause}
            """;
        var args = new
        {
            zones = SqlList.Of(filter.ZoneCodes ?? []),
            ids = SqlList.Of(filter.MeterIds ?? []),
        };
        await using var c = await db.OpenSourceAsync(ct);
        return OnePerBarcode(await c.QueryAsync<MeterRow>(ViewCmd(sql, args, ct)));
    }

    /// <summary>
    /// One row per barcode. A property with two tenant rows appears twice in vw_MR_Property, and so
    /// do its meters: same meter, so the first row is kept. A barcode on <em>different</em> meters
    /// (another meter number or property) is left out altogether, so a reading can never be stored
    /// against the wrong meter; db/001 lists them for fixing in PMS.
    /// </summary>
    public static List<MeterRow> OnePerBarcode(IEnumerable<MeterRow> rows) =>
        rows.GroupBy(r => r.MeterId, StringComparer.Ordinal)
            .Where(g => g.Select(r => (r.MeterNumber, r.PropertyCode)).Distinct().Count() == 1)
            .Select(g => g.First())
            .ToList();

    /// <summary>
    /// SQL that is true when a source flag means active. The views pass their own codes through:
    /// a bit, 1/0, True/False, Y/N or ACTIVE/INACTIVE, as number or text.
    /// </summary>
    internal static string Active(string column) =>
        $"UPPER(LTRIM(RTRIM(CAST({column} AS varchar(10))))) IN ('1', 'TRUE', 'T', 'Y', 'YES', 'ACTIVE')";

    /// <summary>Average of the last N actual consumptions, for meters the source gave no average for (BR-007).</summary>
    public async Task<IReadOnlyDictionary<string, decimal>> GetHistoryAveragesAsync(IReadOnlyCollection<string> meterIds, int periods, CancellationToken ct)
    {
        if (!_options.HasReadingHistory || meterIds.Count == 0) return new Dictionary<string, decimal>();
        var sql = $"""
            SELECT x.MeterId, CAST(AVG(x.Consumption) AS decimal(18,4)) AS Average
            FROM (
                SELECT CAST(h.MeterId AS varchar(50)) AS MeterId, CAST(h.Consumption AS decimal(18,4)) AS Consumption,
                       ROW_NUMBER() OVER (PARTITION BY h.MeterId ORDER BY h.PeriodCode DESC) AS rn
                FROM {_v.ReadingHistory} h
                WHERE h.ConsumptionBasis = 'ACTUAL' AND h.Consumption IS NOT NULL
                  AND h.MeterId IN ({SqlList.Select("@ids", "varchar(50)")})
            ) x
            WHERE x.rn <= @periods
            GROUP BY x.MeterId
            """;
        await using var c = await db.OpenSourceAsync(ct);
        var rows = await c.QueryAsync<AverageRow>(ViewCmd(sql, new { ids = SqlList.Of(meterIds), periods }, ct));
        return rows.ToDictionary(r => r.MeterId, r => r.Average, StringComparer.Ordinal);
    }

    public async Task<IReadOnlyList<HistoryRow>> GetHistoryAsync(string meterId, int periods, CancellationToken ct)
    {
        if (!_options.HasReadingHistory) return [];
        var sql = $"""
            SELECT TOP (@periods) CAST(PeriodCode AS char(7)) AS PeriodCode, CAST(ReadingDate AS datetime2) AS ReadingDate,
                   CAST(ReadingValue AS decimal(18,4)) AS ReadingValue, CAST(Consumption AS decimal(18,4)) AS Consumption,
                   CAST(ConsumptionBasis AS varchar(10)) AS ConsumptionBasis
            FROM {_v.ReadingHistory}
            WHERE MeterId = @meterId
            ORDER BY PeriodCode DESC
            """;
        await using var c = await db.OpenSourceAsync(ct);
        return (await c.QueryAsync<HistoryRow>(Cmd(sql, new { meterId, periods }, ct))).AsList();
    }

    /// <summary>Latest live (not superseded) transaction per meter for the period.</summary>
    public async Task<IReadOnlyDictionary<string, LatestTransactionRow>> GetLatestTransactionsAsync(
        string periodCode, IReadOnlyCollection<string> meterIds, CancellationToken ct)
    {
        if (meterIds.Count == 0) return new Dictionary<string, LatestTransactionRow>();
        // A long list of ids as one XML parameter makes SQL Server slow (UAT 2026-10-18, 2,358 meters): for
        // more than a few hundred, the period's latest readings are read whole and matched here.
        var many = meterIds.Count > 200;
        var idClause = many ? "" : $"AND t.MeterId IN ({SqlList.Select("@ids", "varchar(50)")})";
        var sql = $"""
            SELECT MeterId, TransactionId, Status, StatusNote, MeterCondition, ConditionKind, ReceivedAtUtc
            FROM (
                SELECT t.MeterId, t.TransactionId, t.Status, t.StatusNote, t.MeterCondition, ISNULL(t.ConditionKind, t.MeterCondition) AS ConditionKind, t.ReceivedAtUtc,
                       ROW_NUMBER() OVER (PARTITION BY t.MeterId ORDER BY t.ReceivedAtUtc DESC, t.CapturedAtUtc DESC) AS rn
                FROM mr.ReadingTransaction t
                WHERE t.PeriodCode = @periodCode AND t.Status <> 'SUPERSEDED'
                  {idClause}
            ) x
            WHERE x.rn = 1
            """;
        await using var c = await db.OpenMeterReadingAsync(ct);
        var rows = await c.QueryAsync<LatestTransactionRow>(Cmd(sql, new { periodCode, ids = many ? "" : SqlList.Of(meterIds) }, ct));
        var wanted = many ? meterIds.ToHashSet(StringComparer.Ordinal) : null;
        return rows.Where(r => wanted is null || wanted.Contains(r.MeterId)).ToDictionary(r => r.MeterId, StringComparer.Ordinal);
    }

    /// <summary>
    /// FR-037: the latest stored reading of each meter this period (not superseded, not sent back by a
    /// supervisor), with the numbers that go into mr.MaintainFinalizedMeter.
    /// </summary>
    public async Task<IReadOnlyDictionary<string, FinalizeReadingRow>> GetFinalizeReadingsAsync(
        string periodCode, IReadOnlyCollection<string> meterIds, CancellationToken ct)
    {
        if (meterIds.Count == 0) return new Dictionary<string, FinalizeReadingRow>();
        var sql = $"""
            SELECT MeterId, TransactionId, PropertyId, PreviousReading, CurrentReading, Consumption, ReadingDate, TranCode, BaanCode, TenantCode
            FROM (
                SELECT t.MeterId, t.TransactionId, t.PropertyId, t.PreviousReading, t.TranCode, t.BaanCode, t.TenantCode,
                       COALESCE(t.NewReading, t.NewCurrentReading) AS CurrentReading, t.Consumption,
                       CAST(CAST(t.CapturedAtUtc AT TIME ZONE 'UTC' AT TIME ZONE 'Arabian Standard Time' AS date) AS datetime2) AS ReadingDate,
                       ROW_NUMBER() OVER (PARTITION BY t.MeterId ORDER BY t.ReceivedAtUtc DESC, t.CapturedAtUtc DESC) AS rn
                FROM mr.ReadingTransaction t
                WHERE t.PeriodCode = @periodCode AND t.Status NOT IN ('SUPERSEDED', 'REJECTED_BY_SUPERVISOR')
                  AND t.MeterId IN ({SqlList.Select("@ids", "varchar(50)")})
            ) x
            WHERE x.rn = 1
            """;
        await using var c = await db.OpenMeterReadingAsync(ct);
        var rows = await c.QueryAsync<FinalizeReadingRow>(Cmd(sql, new { periodCode, ids = SqlList.Of(meterIds) }, ct));
        return rows.ToDictionary(r => r.MeterId, StringComparer.Ordinal);
    }

    /// <summary>FR-037.7: the property's row of mr.IrrigationNoCharge (db/020), or null when its irrigation is charged.</summary>
    public async Task<IrrigationNoChargeRow?> GetIrrigationNoChargeAsync(string propertyCode, CancellationToken ct)
    {
        const string sql = """
            SELECT CAST(NoChargeAfter AS datetime2) AS NoChargeAfter, CAST(ToDate AS datetime2) AS ToDate
            FROM mr.IrrigationNoCharge WHERE PropertyCode = @propertyCode
            """;
        await using var c = await db.OpenMeterReadingAsync(ct);
        var row = await c.QuerySingleOrDefaultAsync<NoChargeDates>(Cmd(sql, new { propertyCode }, ct));
        return row is null ? null : new IrrigationNoChargeRow
        {
            NoChargeAfter = DateOnly.FromDateTime(row.NoChargeAfter),
            ToDate = row.ToDate is { } d ? DateOnly.FromDateTime(d) : null,
        };
    }

    private sealed class NoChargeDates
    {
        public DateTime NoChargeAfter { get; init; }
        public DateTime? ToDate { get; init; }
    }

    /// <summary>
    /// FR-037.4: writes the property's rows (with their FR-037.5 amounts) to mr.MaintainFinalizedMeter in one transaction; an earlier set
    /// for the same period and property is kept with IsCurrent = 0. Returns the time written (UTC).
    /// </summary>
    public async Task<DateTime> InsertFinalizedAsync(
        string periodCode, string propertyCode, long? propertyId, IReadOnlyList<Domain.FinalizedPair> rows,
        string readerId, Guid? deviceId, DateOnly fromDate, DateOnly endDate, CancellationToken ct)
    {
        const string retire = """
            UPDATE f SET IsCurrent = 0
            FROM mr.MaintainFinalizedMeter f WITH (UPDLOCK, HOLDLOCK)
            WHERE f.PeriodCode = @periodCode AND f.PropertyCode = @propertyCode AND f.IsCurrent = 1;
            SELECT CAST(SYSUTCDATETIME() AS datetime2(0));
            """;
        const string insert = """
            INSERT mr.MaintainFinalizedMeter (PeriodCode, PropertyId, PropertyCode,
                MeterNumberSewerage, PrevReadingSewerage, CurrentReadingSewerage, ConsumptionSewerage,
                MeterNumberIrrigation, PrevReadingIrrigation, CurrentReadingIrrigation, ConsumptionIrrigation,
                SewerageTransactionId, IrrigationTransactionId, FinalizedByReaderId, DeviceId, FinalizedAtUtc, IsCurrent,
                SewerageAmount, IrrigationAmount, FloatValueSewerage, RateFactorSewerage, FloatValueIrrigation, RateFactorIrrigation,
                SewerageVat, IrrigationVat, TotalAmount, IrrigationNoCharge, TenPercentAmount, IrrigationAmountCalculated,
                TranCode, BaanCode, TenantCode, FromDate, EndDate, CompanyName, VATCode, ChargeType1, ChargeType2)
            VALUES (@PeriodCode, @PropertyId, @PropertyCode,
                @MeterNumberSewerage, @PrevReadingSewerage, @CurrentReadingSewerage, @ConsumptionSewerage,
                @MeterNumberIrrigation, @PrevReadingIrrigation, @CurrentReadingIrrigation, @ConsumptionIrrigation,
                @SewerageTransactionId, @IrrigationTransactionId, @ReaderId, @DeviceId, @At, 1,
                @SewerageAmount, @IrrigationAmount, @FloatValueSewerage, @RateFactorSewerage, @FloatValueIrrigation, @RateFactorIrrigation,
                @SewerageVat, @IrrigationVat, @TotalAmount, @IrrigationNoCharge, @TenPercentAmount, @IrrigationAmountCalculated,
                @TranCode, @BaanCode, @TenantCode, @FromDate, @EndDate, @CompanyName, @VatCode, @ChargeType1, @ChargeType2);
            """;
        await using var c = await db.OpenMeterReadingAsync(ct);
        await using var tx = await c.BeginTransactionAsync(ct);
        var at = await c.ExecuteScalarAsync<DateTime>(new CommandDefinition(retire, new { periodCode, propertyCode }, tx,
            CommandTimeoutSeconds, cancellationToken: ct));
        await c.ExecuteAsync(new CommandDefinition(insert, rows.Select(r => new
        {
            PeriodCode = periodCode, PropertyId = propertyId, PropertyCode = propertyCode,
            MeterNumberSewerage = r.Sewerage?.Number ?? "0", PrevReadingSewerage = r.Sewerage?.Previous ?? 0m,
            CurrentReadingSewerage = r.Sewerage?.Current ?? 0m, ConsumptionSewerage = r.Sewerage?.Consumption ?? 0m,
            MeterNumberIrrigation = r.Irrigation?.Number ?? "0", PrevReadingIrrigation = r.Irrigation?.Previous ?? 0m,
            CurrentReadingIrrigation = r.Irrigation?.Current ?? 0m, ConsumptionIrrigation = r.Irrigation?.Consumption ?? 0m,
            SewerageTransactionId = r.Sewerage?.TransactionId, IrrigationTransactionId = r.Irrigation?.TransactionId,
            ReaderId = readerId, DeviceId = deviceId, At = at,
            r.SewerageAmount, r.IrrigationAmount, r.SewerageVat, r.IrrigationVat, r.TotalAmount, r.IrrigationNoCharge, r.TenPercentAmount, r.IrrigationAmountCalculated, r.TranCode, r.BaanCode, r.TenantCode, r.CompanyName,
            VatCode = Domain.FinalizeService.VatCode, ChargeType1 = Domain.FinalizeService.ChargeTypeSewerage,
            ChargeType2 = Domain.FinalizeService.ChargeTypeIrrigation,
            FromDate = fromDate.ToDateTime(TimeOnly.MinValue),
            EndDate = endDate.ToDateTime(TimeOnly.MinValue),
            FloatValueSewerage = r.Sewerage?.FloatValue, RateFactorSewerage = r.Sewerage?.RateFactor,
            FloatValueIrrigation = r.Irrigation?.FloatValue, RateFactorIrrigation = r.Irrigation?.RateFactor,
        }).ToList(), tx, CommandTimeoutSeconds, cancellationToken: ct));
        await tx.CommitAsync(ct);
        return at;
    }

    public async Task<IReadOnlyList<TransactionRow>> GetReaderTransactionsAsync(string readerId, string periodCode, CancellationToken ct)
    {
        const string sql = """
            SELECT t.TransactionId, t.MeterId, t.MeterCondition, ISNULL(t.ConditionKind, t.MeterCondition) AS ConditionKind, t.ReasonCode, t.NewReading, t.Consumption,
                   t.CapturedAtUtc, t.ReceivedAtUtc, t.Status, t.StatusNote, CAST(t.ExpectedPhotos AS int) AS ExpectedPhotos,
                   (SELECT COUNT(*) FROM mr.ReadingImage i WHERE i.TransactionId = t.TransactionId) AS PhotosReceived,
                   t.TenantCode, t.SubTenant
            FROM mr.ReadingTransaction t
            WHERE t.ReaderId = @readerId AND t.PeriodCode = @periodCode AND t.Status <> 'SUPERSEDED'
            ORDER BY t.CapturedAtUtc DESC
            """;
        await using var c = await db.OpenMeterReadingAsync(ct);
        return (await c.QueryAsync<TransactionRow>(Cmd(sql, new { readerId, periodCode }, ct))).AsList();
    }

    public async Task<IReadOnlyList<TransactionRow>> GetMeterTransactionsAsync(string meterId, string periodCode, CancellationToken ct)
    {
        const string sql = """
            SELECT t.TransactionId, t.MeterId, t.MeterCondition, ISNULL(t.ConditionKind, t.MeterCondition) AS ConditionKind, t.ReasonCode, t.NewReading, t.Consumption,
                   t.CapturedAtUtc, t.ReceivedAtUtc, t.Status, t.StatusNote, CAST(t.ExpectedPhotos AS int) AS ExpectedPhotos,
                   (SELECT COUNT(*) FROM mr.ReadingImage i WHERE i.TransactionId = t.TransactionId) AS PhotosReceived,
                   t.TenantCode, t.SubTenant
            FROM mr.ReadingTransaction t
            WHERE t.MeterId = @meterId AND t.PeriodCode = @periodCode
            ORDER BY t.ReceivedAtUtc DESC
            """;
        await using var c = await db.OpenMeterReadingAsync(ct);
        return (await c.QueryAsync<TransactionRow>(Cmd(sql, new { meterId, periodCode }, ct))).AsList();
    }

    public async Task<TransactionRow?> GetTransactionAsync(Guid transactionId, CancellationToken ct)
    {
        const string sql = """
            SELECT t.TransactionId, t.MeterId, t.ReaderId, t.PeriodCode, t.MeterCondition, ISNULL(t.ConditionKind, t.MeterCondition) AS ConditionKind, t.ReasonCode, t.NewReading, t.Consumption,
                   t.CapturedAtUtc, t.ReceivedAtUtc, t.Status, t.StatusNote, t.PayloadHash, CAST(t.ExpectedPhotos AS int) AS ExpectedPhotos,
                   (SELECT COUNT(*) FROM mr.ReadingImage i WHERE i.TransactionId = t.TransactionId) AS PhotosReceived,
                   t.TenantCode, t.SubTenant
            FROM mr.ReadingTransaction t
            WHERE t.TransactionId = @transactionId
            """;
        await using var c = await db.OpenMeterReadingAsync(ct);
        return await c.QuerySingleOrDefaultAsync<TransactionRow>(Cmd(sql, new { transactionId }, ct));
    }

    /// <summary>
    /// Inserts the reading unless the meter already has a live reading this period that is done or
    /// being checked (ALREADY_READ). The check and insert run under a range lock on the meter's rows,
    /// so two phones submitting at once cannot both succeed. Returns false when already read.
    /// </summary>
    public async Task<bool> InsertTransactionUnlessReadAsync(NewTransactionRow row, CancellationToken ct)
    {
        const string sql = """
            SET XACT_ABORT ON;
            BEGIN TRANSACTION;
            IF EXISTS (
                SELECT 1 FROM (
                    SELECT TOP (1) t.Status, ISNULL(t.ConditionKind, t.MeterCondition) AS Kind
                    FROM mr.ReadingTransaction t WITH (UPDLOCK, HOLDLOCK)
                    WHERE t.PeriodCode = @PeriodCode AND t.MeterId = @MeterId AND t.Status <> 'SUPERSEDED'
                    ORDER BY t.ReceivedAtUtc DESC, t.CapturedAtUtc DESC
                ) latest
                WHERE latest.Status <> 'REJECTED_BY_SUPERVISOR' AND latest.Kind <> 'NOT_ACCESSIBLE')
            BEGIN
                ROLLBACK TRANSACTION;
                SELECT 0;
                RETURN;
            END
            INSERT mr.ReadingTransaction (TransactionId, PeriodCode, MeterId, ReaderId, DeviceId, MeterCondition, ConditionKind, ReasonCode, Remarks,
                NewReading, PreviousReading, Consumption, OldFinalReading, NewMeterNumber, NewOpeningReading, NewCurrentReading,
                Latitude, Longitude, GpsAccuracyM, CapturedAtUtc, Status, StatusNote, StatusChangedAtUtc, PayloadHash, ExpectedPhotos,
                PropertyId, PropertyCode, MeterNumber, MeterType, TenantCode, SubTenant, TranCode, BaanCode)
            VALUES (@TransactionId, @PeriodCode, @MeterId, @ReaderId, @DeviceId, @MeterCondition, @ConditionKind, @ReasonCode, @Remarks,
                @NewReading, @PreviousReading, @Consumption, @OldFinalReading, @NewMeterNumber, @NewOpeningReading, @NewCurrentReading,
                @Latitude, @Longitude, @GpsAccuracyM, @CapturedAtUtc, @Status, @StatusNote, SYSUTCDATETIME(), @PayloadHash, @ExpectedPhotos,
                @PropertyId, @PropertyCode, @MeterNumber, @MeterType, @TenantCode, @SubTenant, @TranCode, @BaanCode);
            COMMIT TRANSACTION;
            SELECT 1;
            """;
        await using var c = await db.OpenMeterReadingAsync(ct);
        return await c.ExecuteScalarAsync<int>(Cmd(sql, row, ct)) == 1;
    }

    public async Task<ImageRow?> GetImageAsync(Guid imageId, CancellationToken ct)
    {
        const string sql = """
            SELECT ImageId, TransactionId, ImageRole, BlobPath, Sha256, SizeBytes, CapturedAtUtc
            FROM mr.ReadingImage WHERE ImageId = @imageId
            """;
        await using var c = await db.OpenMeterReadingAsync(ct);
        return await c.QuerySingleOrDefaultAsync<ImageRow>(Cmd(sql, new { imageId }, ct));
    }

    /// <summary>
    /// Records an uploaded photo unless the reading already has <paramref name="maxImages"/> photos.
    /// Returns false when full. Counted under a lock so parallel uploads cannot pass the limit.
    /// </summary>
    public async Task<bool> InsertImageAsync(ImageRow image, int maxImages, CancellationToken ct)
    {
        const string sql = """
            SET XACT_ABORT ON;
            BEGIN TRANSACTION;
            IF (SELECT COUNT(*) FROM mr.ReadingImage WITH (UPDLOCK, HOLDLOCK) WHERE TransactionId = @TransactionId) >= @maxImages
            BEGIN
                ROLLBACK TRANSACTION;
                SELECT 0;
                RETURN;
            END
            INSERT mr.ReadingImage (ImageId, TransactionId, ImageRole, BlobPath, Sha256, SizeBytes, CapturedAtUtc)
            VALUES (@ImageId, @TransactionId, @ImageRole, @BlobPath, @Sha256, @SizeBytes, @CapturedAtUtc);
            COMMIT TRANSACTION;
            SELECT 1;
            """;
        await using var c = await db.OpenMeterReadingAsync(ct);
        return await c.ExecuteScalarAsync<int>(Cmd(sql, new
        {
            image.ImageId, image.TransactionId, image.ImageRole, image.BlobPath, image.Sha256, image.SizeBytes, image.CapturedAtUtc, maxImages,
        }, ct)) == 1;
    }

    /// <summary>
    /// The current tenants of the given properties (all properties when null), from vw_MR_Tenant.
    /// A property can have more than one; the reader picks the one on site (FR-006.12).
    /// </summary>
    /// <summary>
    /// vw_MR_Property.TranCode (db/021) of the property's row for this tenant; without one for the tenant, the
    /// property's first row. Null when the property has none.
    /// </summary>
    public async Task<string?> GetTranCodeAsync(string propertyCode, string? tenantCode, CancellationToken ct)
    {
        var sql = $"""
            SELECT TOP (1) NULLIF(LTRIM(RTRIM(CAST(p.TranCode AS varchar(30)))), '')
            FROM {_v.Property} p
            WHERE CAST(p.PropertyCode AS varchar(30)) = @propertyCode
            ORDER BY CASE WHEN LTRIM(RTRIM(CAST(p.TenantCode AS varchar(30)))) = @tenantCode THEN 0 ELSE 1 END
            """;
        await using var c = await db.OpenSourceAsync(ct);
        return await c.ExecuteScalarAsync<string?>(Cmd(sql, new { propertyCode, tenantCode }, ct));
    }

    public async Task<IReadOnlyList<TenantRow>> GetTenantsAsync(IReadOnlyCollection<string>? propertyCodes, CancellationToken ct)
    {
        var codeClause = propertyCodes is null
            ? ""
            : $"WHERE CAST(t.PropertyCode AS varchar(30)) IN ({SqlList.Select("@codes", "varchar(30)")})";
        var sql = $"""
            SELECT DISTINCT CAST(t.PropertyCode AS varchar(30)) AS PropertyCode, LTRIM(RTRIM(CAST(t.TenantCode AS varchar(30)))) AS TenantCode,
                   LTRIM(RTRIM(CAST(t.CompanyName AS nvarchar(200)))) AS CompanyName,
                   NULLIF(LTRIM(RTRIM(CAST(t.BaanCode AS varchar(30)))), '') AS BaanCode
            FROM {_v.Tenant} t
            {codeClause}
            """;
        await using var c = await db.OpenSourceAsync(ct);
        var rows = await c.QueryAsync<TenantRow>(ViewCmd(sql, new { codes = SqlList.Of(propertyCodes ?? []) }, ct));
        return rows.Where(r => r.TenantCode.Length > 0).DistinctBy(r => (r.PropertyCode, r.TenantCode)).ToList();
    }

    /// <summary>Names of required views and tables that are missing, for the readiness check.</summary>
    public async Task<IReadOnlyList<string>> FindMissingViewsAsync(CancellationToken ct)
    {
        var required = SourceViewNames.Required
            .Concat(_options.HasReadingHistory ? ["vw_MR_ReadingHistory"] : Array.Empty<string>())
            .ToArray();
        var sql = $"""
            SELECT r.name
            FROM ({SqlList.Select("@names", "sysname")}) AS r(name)
            WHERE OBJECT_ID(QUOTENAME(@schema) + N'.' + QUOTENAME(r.name)) IS NULL
            """;
        await using var c = await db.OpenSourceAsync(ct);
        var missing = (await c.QueryAsync<string>(Cmd(sql, new { names = SqlList.Of(required), schema = _v.Schema }, ct))).AsList();

        await using var mr = await db.OpenMeterReadingAsync(ct);
        if (await mr.ExecuteScalarAsync<int>(Cmd("SELECT CASE WHEN OBJECT_ID(N'mr.ReadingTransaction') IS NULL THEN 0 ELSE 1 END", null, ct)) == 0)
            missing.Add("mr.ReadingTransaction (run db/002_create_mr_schema.sql)");
        else if (await mr.ExecuteScalarAsync<int>(Cmd("SELECT CASE WHEN COL_LENGTH(N'mr.ReadingTransaction', N'ExpectedPhotos') IS NULL THEN 0 ELSE 1 END", null, ct)) == 0)
            missing.Add("mr.ReadingTransaction.ExpectedPhotos (run db/004_add_expected_photos.sql)");
        else if (await mr.ExecuteScalarAsync<int>(Cmd("SELECT CASE WHEN COL_LENGTH(N'mr.ReadingTransaction', N'TenantCode') IS NULL THEN 0 ELSE 1 END", null, ct)) == 0)
            missing.Add("mr.ReadingTransaction.TenantCode (run db/005_reading_tenant_and_export.sql)");
        if (string.Equals(images.Value.Kind, "Database", StringComparison.OrdinalIgnoreCase)
            && await mr.ExecuteScalarAsync<int>(Cmd("SELECT CASE WHEN OBJECT_ID(N'mr.ReadingImageData') IS NULL THEN 0 ELSE 1 END", null, ct)) == 0)
            missing.Add("mr.ReadingImageData (run db/007_reading_image_data.sql)");
        return missing;
    }

    private static CommandDefinition Cmd(string sql, object? args, CancellationToken ct) =>
        new(sql, args, commandTimeout: CommandTimeoutSeconds, cancellationToken: ct);

    /// <summary>
    /// The full-list reads of PMS's views (meters, tenants, averages): run in the background for SourceCache,
    /// so they may take longer than a phone waits (SourceViews:CommandTimeoutSeconds, default 300).
    /// </summary>
    private CommandDefinition ViewCmd(string sql, object? args, CancellationToken ct) =>
        new(sql, args, commandTimeout: Math.Max(CommandTimeoutSeconds, _options.CommandTimeoutSeconds), cancellationToken: ct);
}

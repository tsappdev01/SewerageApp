using System.Text.Json;
using Dapper;
using Microsoft.Extensions.Options;

namespace MeterReading.Api.Data;

/// <summary>Narrows the meter list. Empty means all active meters.</summary>
public sealed record MeterFilter(IReadOnlyCollection<string>? ZoneCodes = null, IReadOnlyCollection<string>? MeterIds = null);

/// <summary>
/// All SQL the reader endpoints run. Source views are read-only; mr.* tables are the API's own.
/// Lists of ids are passed as one JSON parameter and expanded with OPENJSON, which keeps clear of
/// SQL Server's 2,100-parameter limit. View columns are CAST (or TRY_CAST where a bad value should
/// read as "unknown" rather than fail the request) to the row types.
/// </summary>
public sealed class MeterReadingRepository(SqlConnectionFactory db, IOptions<SourceViewsOptions> options, IOptions<ImageStoreOptions> images)
{
    private readonly SourceViewNames _v = db.Views;
    private readonly SourceViewsOptions _options = options.Value;

    private const int CommandTimeoutSeconds = 30;

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
            WHERE LoginEmail = @loginEmail AND IsActive = 1
            """;
        await using var c = await db.OpenSourceAsync(ct);
        return (await c.QueryAsync<ReaderRow>(Cmd(sql, new { loginEmail }, ct))).AsList();
    }

    /// <summary>The latest OPEN period. The source may mark several months OPEN; the newest wins.</summary>
    public async Task<PeriodRow?> GetOpenPeriodAsync(CancellationToken ct)
    {
        var sql = $"""
            SELECT TOP (1) {PeriodColumns}
            FROM {_v.ReadingPeriod}
            WHERE Status = 'OPEN'
            ORDER BY StartDate DESC
            """;
        await using var c = await db.OpenSourceAsync(ct);
        return await c.QuerySingleOrDefaultAsync<PeriodRow>(Cmd(sql, null, ct));
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

    /// <summary>
    /// Active meters in active properties and zones. Work is not assigned to readers, so the only
    /// narrowing is by zone or by meter id. MeterType is read from its first letter (I or S) and
    /// Status accepts 1, True or ACTIVE, so the view can pass its own codes through.
    /// </summary>
    public async Task<IReadOnlyList<MeterRow>> GetMetersAsync(MeterFilter filter, CancellationToken ct)
    {
        var zoneClause = filter.ZoneCodes is { Count: > 0 }
            ? "AND p.ZoneCode IN (SELECT CAST([value] AS varchar(20)) FROM OPENJSON(@zones))"
            : "";
        var idClause = filter.MeterIds is { Count: > 0 }
            ? "AND m.MeterId IN (SELECT CAST([value] AS varchar(50)) FROM OPENJSON(@ids))"
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
                   TRY_CAST(m.AvgConsumption AS decimal(18,4)) AS AverageConsumption
            FROM {_v.Meter} m
            JOIN {_v.Property} p ON p.PropertyCode = m.PropertyCode
            LEFT JOIN {_v.Zone} z ON z.ZoneCode = p.ZoneCode
            WHERE UPPER(CAST(m.Status AS varchar(10))) IN ('1', 'TRUE', 'ACTIVE')
              AND p.IsActive = 1 AND ISNULL(z.IsActive, 1) = 1
              {zoneClause}
              {idClause}
            """;
        var args = new
        {
            zones = JsonSerializer.Serialize(filter.ZoneCodes ?? []),
            ids = JsonSerializer.Serialize(filter.MeterIds ?? []),
        };
        await using var c = await db.OpenSourceAsync(ct);
        // A property with two tenant rows appears twice in vw_MR_Property, and so would its meters:
        // keep the first row per meter (the view checker reports the duplicate property).
        return (await c.QueryAsync<MeterRow>(Cmd(sql, args, ct))).DistinctBy(r => r.MeterId, StringComparer.Ordinal).ToList();
    }

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
                  AND h.MeterId IN (SELECT CAST([value] AS varchar(50)) FROM OPENJSON(@ids))
            ) x
            WHERE x.rn <= @periods
            GROUP BY x.MeterId
            """;
        await using var c = await db.OpenSourceAsync(ct);
        var rows = await c.QueryAsync<AverageRow>(Cmd(sql, new { ids = JsonSerializer.Serialize(meterIds), periods }, ct));
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
        const string sql = """
            SELECT MeterId, TransactionId, Status, StatusNote, MeterCondition, ReceivedAtUtc
            FROM (
                SELECT t.MeterId, t.TransactionId, t.Status, t.StatusNote, t.MeterCondition, t.ReceivedAtUtc,
                       ROW_NUMBER() OVER (PARTITION BY t.MeterId ORDER BY t.ReceivedAtUtc DESC, t.CapturedAtUtc DESC) AS rn
                FROM mr.ReadingTransaction t
                WHERE t.PeriodCode = @periodCode AND t.Status <> 'SUPERSEDED'
                  AND t.MeterId IN (SELECT CAST([value] AS varchar(50)) FROM OPENJSON(@ids))
            ) x
            WHERE x.rn = 1
            """;
        await using var c = await db.OpenMeterReadingAsync(ct);
        var rows = await c.QueryAsync<LatestTransactionRow>(Cmd(sql, new { periodCode, ids = JsonSerializer.Serialize(meterIds) }, ct));
        return rows.ToDictionary(r => r.MeterId, StringComparer.Ordinal);
    }

    public async Task<IReadOnlyList<TransactionRow>> GetReaderTransactionsAsync(string readerId, string periodCode, CancellationToken ct)
    {
        const string sql = """
            SELECT t.TransactionId, t.MeterId, t.MeterCondition, t.ReasonCode, t.NewReading, t.Consumption,
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
            SELECT t.TransactionId, t.MeterId, t.MeterCondition, t.ReasonCode, t.NewReading, t.Consumption,
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
            SELECT t.TransactionId, t.MeterId, t.ReaderId, t.PeriodCode, t.MeterCondition, t.ReasonCode, t.NewReading, t.Consumption,
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
                    SELECT TOP (1) t.Status, t.MeterCondition
                    FROM mr.ReadingTransaction t WITH (UPDLOCK, HOLDLOCK)
                    WHERE t.PeriodCode = @PeriodCode AND t.MeterId = @MeterId AND t.Status <> 'SUPERSEDED'
                    ORDER BY t.ReceivedAtUtc DESC, t.CapturedAtUtc DESC
                ) latest
                WHERE latest.Status <> 'REJECTED_BY_SUPERVISOR' AND latest.MeterCondition <> 'NOT_ACCESSIBLE')
            BEGIN
                ROLLBACK TRANSACTION;
                SELECT 0;
                RETURN;
            END
            INSERT mr.ReadingTransaction (TransactionId, PeriodCode, MeterId, ReaderId, DeviceId, MeterCondition, ReasonCode, Remarks,
                NewReading, PreviousReading, Consumption, OldFinalReading, NewMeterNumber, NewOpeningReading, NewCurrentReading,
                Latitude, Longitude, GpsAccuracyM, CapturedAtUtc, Status, StatusNote, StatusChangedAtUtc, PayloadHash, ExpectedPhotos,
                PropertyId, PropertyCode, MeterNumber, MeterType, TenantCode, SubTenant)
            VALUES (@TransactionId, @PeriodCode, @MeterId, @ReaderId, @DeviceId, @MeterCondition, @ReasonCode, @Remarks,
                @NewReading, @PreviousReading, @Consumption, @OldFinalReading, @NewMeterNumber, @NewOpeningReading, @NewCurrentReading,
                @Latitude, @Longitude, @GpsAccuracyM, @CapturedAtUtc, @Status, @StatusNote, SYSUTCDATETIME(), @PayloadHash, @ExpectedPhotos,
                @PropertyId, @PropertyCode, @MeterNumber, @MeterType, @TenantCode, @SubTenant);
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
    public async Task<IReadOnlyList<TenantRow>> GetTenantsAsync(IReadOnlyCollection<string>? propertyCodes, CancellationToken ct)
    {
        var codeClause = propertyCodes is null
            ? ""
            : "WHERE CAST(t.PropertyCode AS varchar(30)) IN (SELECT CAST([value] AS varchar(30)) FROM OPENJSON(@codes))";
        var sql = $"""
            SELECT DISTINCT CAST(t.PropertyCode AS varchar(30)) AS PropertyCode, LTRIM(RTRIM(CAST(t.TenantCode AS varchar(30)))) AS TenantCode,
                   LTRIM(RTRIM(CAST(t.CompanyName AS nvarchar(200)))) AS CompanyName
            FROM {_v.Tenant} t
            {codeClause}
            """;
        await using var c = await db.OpenSourceAsync(ct);
        var rows = await c.QueryAsync<TenantRow>(Cmd(sql, new { codes = JsonSerializer.Serialize(propertyCodes ?? []) }, ct));
        return rows.Where(r => r.TenantCode.Length > 0).DistinctBy(r => (r.PropertyCode, r.TenantCode)).ToList();
    }

    /// <summary>Names of required views and tables that are missing, for the readiness check.</summary>
    public async Task<IReadOnlyList<string>> FindMissingViewsAsync(CancellationToken ct)
    {
        var required = SourceViewNames.Required
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
}

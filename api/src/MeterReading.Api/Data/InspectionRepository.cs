using Dapper;

namespace MeterReading.Api.Data;

public sealed class InspectionPlanRow
{
    public string PeriodCode { get; init; } = "";
    public DateTime PlanDate { get; init; }
    public string PropertyCode { get; init; } = "";
    public string TenantCode { get; init; } = "";
    public string? CompanyName { get; init; }
    public int ActiveUnits { get; init; }
    public int InactiveUnits { get; init; }
    public int TotalUnits { get; init; }
}

public sealed class InspectionUnitRow
{
    public string UnitId { get; init; } = "";
    public string PropertyCode { get; init; } = "";
    public string TenantCode { get; init; } = "";
    public string? BuildingName { get; init; }
    public string UnitCode { get; init; } = "";
    public string? Category { get; init; }
    public string? SubTenantName { get; init; }
    public bool Active { get; init; }

    /// <summary>
    /// FR-030: the unit belongs to the plan row of this tenant. The unit view has no TenantCode on UAT
    /// (the tenant is on the plan view), so then every unit of the property belongs to each of its plan rows.
    /// </summary>
    public bool BelongsTo(string tenantCode) => TenantCode.Length == 0 || TenantCode == tenantCode;
}

/// <summary>The latest result for a unit within a plan row (or for the unit anywhere, for "last inspection").</summary>
public sealed class LatestUnitResultRow
{
    public string PeriodCode { get; init; } = "";
    public string PropertyCode { get; init; } = "";
    public string TenantCode { get; init; } = "";
    public string UnitId { get; init; } = "";
    public string Result { get; init; } = "";
    public DateTime FinishedAtUtc { get; init; }
    public string? OccupantName { get; init; }
    public int? PeopleSeen { get; init; }
}

public sealed class VisitSummaryRow
{
    public string PeriodCode { get; init; } = "";
    public string PropertyCode { get; init; } = "";
    public string TenantCode { get; init; } = "";
    public DateTime LastFinishedAtUtc { get; init; }
}

public sealed class VisitRow
{
    public Guid VisitId { get; init; }
    public string PeriodCode { get; init; } = "";
    public string PropertyCode { get; init; } = "";
    public string TenantCode { get; init; } = "";
    public string InspectorId { get; init; } = "";
    public DateTime FinishedAtUtc { get; init; }
    public int ExpectedPhotos { get; init; }
    public string PayloadHash { get; init; } = "";
}

public sealed class NewVisitRow
{
    public Guid VisitId { get; init; }
    public string PeriodCode { get; init; } = "";
    public string PropertyCode { get; init; } = "";
    public string TenantCode { get; init; } = "";
    public string? CompanyName { get; init; }
    public DateTime? PlanDate { get; init; }
    public string InspectorId { get; init; } = "";
    public Guid? DeviceId { get; init; }
    public DateTime StartedAtUtc { get; init; }
    public DateTime FinishedAtUtc { get; init; }
    public decimal? Latitude { get; init; }
    public decimal? Longitude { get; init; }
    public decimal? GpsAccuracyM { get; init; }
    public string? PersonMet { get; init; }
    public int ExpectedPhotos { get; init; }
    public string PayloadHash { get; init; } = "";
}

public sealed class NewUnitResultRow
{
    public Guid ResultId { get; init; }
    public Guid VisitId { get; init; }
    public string? UnitId { get; init; }
    public string UnitCode { get; init; } = "";
    public string? BuildingName { get; init; }
    public string? Category { get; init; }
    public string? SubTenantOnRecord { get; init; }
    public bool? ActiveOnRecord { get; init; }
    public string Result { get; init; } = "";
    public int? PeopleSeen { get; init; }
    public string? OccupantName { get; init; }
    public string? Reasons { get; init; }
    public string? Note { get; init; }
    public int ExpectedPhotos { get; init; }
}

public sealed class InspectionImageRow
{
    public Guid ImageId { get; init; }
    public Guid VisitId { get; init; }
    public Guid? ResultId { get; init; }
    public string ImageRole { get; init; } = "";
    public string BlobPath { get; init; } = "";
    public string Sha256 { get; init; } = "";
    public int SizeBytes { get; init; }
    public DateTime CapturedAtUtc { get; init; }
}

/// <summary>
/// SQL for Field Inspection. The plan and units come from the two inspection views (read only); visits,
/// results and photos are the API's own mr.Inspection* tables (db/010). As with meters, view data and mr
/// data are read on separate connections and joined in memory, because they may be in different databases.
/// </summary>
public sealed class InspectionRepository(SqlConnectionFactory db)
{
    private readonly SourceViewNames _v = db.Views;
    private const int CommandTimeoutSeconds = 30;

    private const string PlanColumns = """
        LTRIM(RTRIM(CAST(p.PeriodCode AS varchar(10)))) AS PeriodCode, CAST(p.InspectionPlanDate AS date) AS PlanDate,
        LTRIM(RTRIM(CAST(p.PropertyCode AS varchar(30)))) AS PropertyCode, LTRIM(RTRIM(CAST(p.TenantCode AS varchar(30)))) AS TenantCode,
        LTRIM(RTRIM(CAST(p.CompanyName AS nvarchar(200)))) AS CompanyName,
        ISNULL(TRY_CAST(p.ActiveUnits AS int), 0) AS ActiveUnits, ISNULL(TRY_CAST(p.InactiveUnits AS int), 0) AS InactiveUnits,
        ISNULL(TRY_CAST(p.TotalUnits AS int), 0) AS TotalUnits
        """;

    /// <summary>True when both inspection views exist (the feature is on) and db/010 has been run.</summary>
    public async Task<(bool Views, bool Tables)> AvailableAsync(CancellationToken ct)
    {
        await using var c = await db.OpenSourceAsync(ct);
        var views = await c.ExecuteScalarAsync<int>(Cmd(
            "SELECT CASE WHEN OBJECT_ID(@plan) IS NOT NULL AND OBJECT_ID(@unit) IS NOT NULL THEN 1 ELSE 0 END",
            new { plan = _v.InspectionPlan, unit = _v.InspectionUnit }, ct)) == 1;
        await using var mr = await db.OpenMeterReadingAsync(ct);
        var tables = await mr.ExecuteScalarAsync<int>(Cmd(
            "SELECT CASE WHEN OBJECT_ID(N'mr.InspectionImage') IS NOT NULL THEN 1 ELSE 0 END", null, ct)) == 1;
        return (views, tables);
    }

    public async Task<IReadOnlyList<InspectionPlanRow>> GetPlanAsync(DateOnly from, DateOnly to, CancellationToken ct)
    {
        var sql = $"""
            SELECT {PlanColumns}
            FROM {_v.InspectionPlan} p
            WHERE CAST(p.InspectionPlanDate AS date) BETWEEN @from AND @to
            ORDER BY p.InspectionPlanDate
            """;
        await using var c = await db.OpenSourceAsync(ct);
        var rows = await c.QueryAsync<InspectionPlanRow>(Cmd(sql, new { from = from.ToDateTime(TimeOnly.MinValue), to = to.ToDateTime(TimeOnly.MinValue) }, ct));
        // A plan row listed twice keeps its earliest date, the same one GetPlanRowAsync uses.
        return rows.DistinctBy(r => (r.PeriodCode, r.PropertyCode, r.TenantCode)).ToList();
    }

    public async Task<InspectionPlanRow?> GetPlanRowAsync(string periodCode, string propertyCode, string tenantCode, CancellationToken ct)
    {
        var sql = $"""
            SELECT TOP (1) {PlanColumns}
            FROM {_v.InspectionPlan} p
            WHERE LTRIM(RTRIM(CAST(p.PeriodCode AS varchar(10)))) COLLATE DATABASE_DEFAULT = @periodCode
              AND LTRIM(RTRIM(CAST(p.PropertyCode AS varchar(30)))) COLLATE DATABASE_DEFAULT = @propertyCode
              AND LTRIM(RTRIM(CAST(p.TenantCode AS varchar(30)))) COLLATE DATABASE_DEFAULT = @tenantCode
            ORDER BY p.InspectionPlanDate
            """;
        await using var c = await db.OpenSourceAsync(ct);
        return await c.QuerySingleOrDefaultAsync<InspectionPlanRow>(Cmd(sql, new { periodCode, propertyCode, tenantCode }, ct));
    }

    /// <summary>Units of the given properties, all tenants; the caller matches property and tenant.</summary>
    public async Task<IReadOnlyList<InspectionUnitRow>> GetUnitsAsync(IReadOnlyCollection<string> propertyCodes, CancellationToken ct)
    {
        if (propertyCodes.Count == 0) return [];
        await using var c = await db.OpenSourceAsync(ct);
        // TenantCode on the unit view is optional: without it units are matched on the property alone.
        var hasTenant = await c.ExecuteScalarAsync<int>(Cmd(
            "SELECT CASE WHEN COL_LENGTH(@view, 'TenantCode') IS NULL THEN 0 ELSE 1 END", new { view = _v.InspectionUnit }, ct)) == 1;
        var tenant = hasTenant ? "ISNULL(LTRIM(RTRIM(CAST(u.TenantCode AS varchar(30)))), '')" : "''";
        var sql = $"""
            SELECT CAST(u.UnitId AS varchar(50)) AS UnitId, LTRIM(RTRIM(CAST(u.PropertyCode AS varchar(30)))) AS PropertyCode,
                   CAST({tenant} AS varchar(30)) AS TenantCode,
                   LTRIM(RTRIM(CAST(u.BuildingName AS nvarchar(150)))) AS BuildingName, LTRIM(RTRIM(CAST(u.UnitCode AS nvarchar(30)))) AS UnitCode,
                   LTRIM(RTRIM(CAST(u.Category AS nvarchar(200)))) AS Category, LTRIM(RTRIM(CAST(u.SubTenantName AS nvarchar(200)))) AS SubTenantName,
                   CAST(CASE WHEN {MeterReadingRepository.Active("u.Active")} THEN 1 ELSE 0 END AS bit) AS Active
            FROM {_v.InspectionUnit} u
            WHERE LTRIM(RTRIM(CAST(u.PropertyCode AS varchar(30)))) IN ({SqlList.Select("@codes", "varchar(30)")})
            """;
        var rows = await c.QueryAsync<InspectionUnitRow>(Cmd(sql, new { codes = SqlList.Of(propertyCodes) }, ct));
        // A unit listed twice in the view is kept once.
        return rows.Where(r => r.UnitId.Length > 0).DistinctBy(r => r.UnitId).ToList();
    }

    /// <summary>Last visit time per plan row, for the plan rows' properties.</summary>
    public async Task<IReadOnlyList<VisitSummaryRow>> GetVisitSummariesAsync(IReadOnlyCollection<string> propertyCodes, CancellationToken ct)
    {
        if (propertyCodes.Count == 0) return [];
        var sql = $"""
            SELECT PeriodCode, PropertyCode, TenantCode, MAX(FinishedAtUtc) AS LastFinishedAtUtc
            FROM mr.InspectionVisit
            WHERE PropertyCode IN ({SqlList.Select("@codes", "varchar(30)")})
            GROUP BY PeriodCode, PropertyCode, TenantCode
            """;
        await using var c = await db.OpenMeterReadingAsync(ct);
        return (await c.QueryAsync<VisitSummaryRow>(Cmd(sql, new { codes = SqlList.Of(propertyCodes) }, ct))).AsList();
    }

    /// <summary>The latest result of each listed unit per plan row (period, property, tenant), for the given properties.</summary>
    public async Task<IReadOnlyList<LatestUnitResultRow>> GetLatestResultsAsync(IReadOnlyCollection<string> propertyCodes, CancellationToken ct)
    {
        if (propertyCodes.Count == 0) return [];
        var sql = $"""
            SELECT PeriodCode, PropertyCode, TenantCode, UnitId, Result, FinishedAtUtc, OccupantName, PeopleSeen
            FROM (
                SELECT v.PeriodCode, v.PropertyCode, v.TenantCode, r.UnitId, r.Result, v.FinishedAtUtc, r.OccupantName, r.PeopleSeen,
                       ROW_NUMBER() OVER (PARTITION BY v.PeriodCode, v.PropertyCode, v.TenantCode, r.UnitId
                                          ORDER BY v.FinishedAtUtc DESC, v.ReceivedAtUtc DESC) AS rn
                FROM mr.InspectionVisit v
                JOIN mr.InspectionUnitResult r ON r.VisitId = v.VisitId
                WHERE r.UnitId IS NOT NULL AND v.PropertyCode IN ({SqlList.Select("@codes", "varchar(30)")})
            ) x
            WHERE x.rn = 1
            """;
        await using var c = await db.OpenMeterReadingAsync(ct);
        return (await c.QueryAsync<LatestUnitResultRow>(Cmd(sql, new { codes = SqlList.Of(propertyCodes) }, ct))).AsList();
    }

    public async Task<VisitRow?> GetVisitAsync(Guid visitId, CancellationToken ct)
    {
        const string sql = """
            SELECT VisitId, PeriodCode, PropertyCode, TenantCode, InspectorId, FinishedAtUtc, ExpectedPhotos, PayloadHash
            FROM mr.InspectionVisit WHERE VisitId = @visitId
            """;
        await using var c = await db.OpenMeterReadingAsync(ct);
        return await c.QuerySingleOrDefaultAsync<VisitRow>(Cmd(sql, new { visitId }, ct));
    }

    /// <summary>The result ids of a visit with the photos each expects.</summary>
    public async Task<IReadOnlyDictionary<Guid, int>> GetResultPhotoLimitsAsync(Guid visitId, CancellationToken ct)
    {
        await using var c = await db.OpenMeterReadingAsync(ct);
        var rows = await c.QueryAsync<(Guid ResultId, int ExpectedPhotos)>(Cmd(
            "SELECT ResultId, ExpectedPhotos FROM mr.InspectionUnitResult WHERE VisitId = @visitId", new { visitId }, ct));
        return rows.ToDictionary(r => r.ResultId, r => r.ExpectedPhotos);
    }

    /// <summary>Stores the visit and all its unit results in one transaction. False when the VisitId already exists.</summary>
    public async Task<bool> InsertVisitAsync(NewVisitRow visit, IReadOnlyList<NewUnitResultRow> results, CancellationToken ct)
    {
        const string visitSql = """
            INSERT mr.InspectionVisit (VisitId, PeriodCode, PropertyCode, TenantCode, CompanyName, PlanDate, InspectorId, DeviceId,
                StartedAtUtc, FinishedAtUtc, Latitude, Longitude, GpsAccuracyM, PersonMet, ExpectedPhotos, PayloadHash)
            SELECT @VisitId, @PeriodCode, @PropertyCode, @TenantCode, @CompanyName, @PlanDate, @InspectorId, @DeviceId,
                @StartedAtUtc, @FinishedAtUtc, @Latitude, @Longitude, @GpsAccuracyM, @PersonMet, @ExpectedPhotos, @PayloadHash
            WHERE NOT EXISTS (SELECT 1 FROM mr.InspectionVisit WITH (UPDLOCK, HOLDLOCK) WHERE VisitId = @VisitId);
            SELECT @@ROWCOUNT;
            """;
        const string resultSql = """
            INSERT mr.InspectionUnitResult (ResultId, VisitId, UnitId, UnitCode, BuildingName, Category, SubTenantOnRecord, ActiveOnRecord,
                Result, PeopleSeen, OccupantName, Reasons, Note, ExpectedPhotos)
            VALUES (@ResultId, @VisitId, @UnitId, @UnitCode, @BuildingName, @Category, @SubTenantOnRecord, @ActiveOnRecord,
                @Result, @PeopleSeen, @OccupantName, @Reasons, @Note, @ExpectedPhotos);
            """;
        await using var c = await db.OpenMeterReadingAsync(ct);
        await using var tx = await c.BeginTransactionAsync(ct);
        var inserted = await c.ExecuteScalarAsync<int>(new CommandDefinition(visitSql, visit, tx, CommandTimeoutSeconds, cancellationToken: ct));
        if (inserted == 0)
        {
            await tx.RollbackAsync(ct);
            return false;
        }
        await c.ExecuteAsync(new CommandDefinition(resultSql, results, tx, CommandTimeoutSeconds, cancellationToken: ct));
        await tx.CommitAsync(ct);
        return true;
    }

    public async Task<InspectionImageRow?> GetImageAsync(Guid imageId, CancellationToken ct)
    {
        const string sql = """
            SELECT ImageId, VisitId, ResultId, ImageRole, BlobPath, Sha256, SizeBytes, CapturedAtUtc
            FROM mr.InspectionImage WHERE ImageId = @imageId
            """;
        await using var c = await db.OpenMeterReadingAsync(ct);
        return await c.QuerySingleOrDefaultAsync<InspectionImageRow>(Cmd(sql, new { imageId }, ct));
    }

    /// <summary>
    /// Records a photo unless its unit already has <paramref name="max"/> photos (or the visit already has a
    /// signature, for role SIGNATURE). Counted under a lock so parallel uploads cannot pass the limit.
    /// </summary>
    public async Task<bool> InsertImageAsync(InspectionImageRow image, int max, CancellationToken ct)
    {
        const string sql = """
            SET XACT_ABORT ON;
            BEGIN TRANSACTION;
            IF (SELECT COUNT(*) FROM mr.InspectionImage WITH (UPDLOCK, HOLDLOCK)
                WHERE VisitId = @VisitId AND ImageRole = @ImageRole AND (ResultId = @ResultId OR (ResultId IS NULL AND @ResultId IS NULL))) >= @max
            BEGIN
                ROLLBACK TRANSACTION;
                SELECT 0;
                RETURN;
            END
            INSERT mr.InspectionImage (ImageId, VisitId, ResultId, ImageRole, BlobPath, Sha256, SizeBytes, CapturedAtUtc)
            VALUES (@ImageId, @VisitId, @ResultId, @ImageRole, @BlobPath, @Sha256, @SizeBytes, @CapturedAtUtc);
            COMMIT TRANSACTION;
            SELECT 1;
            """;
        await using var c = await db.OpenMeterReadingAsync(ct);
        return await c.ExecuteScalarAsync<int>(Cmd(sql, new
        {
            image.ImageId, image.VisitId, image.ResultId, image.ImageRole, image.BlobPath, image.Sha256, image.SizeBytes, image.CapturedAtUtc, max,
        }, ct)) == 1;
    }

    private static CommandDefinition Cmd(string sql, object? args, CancellationToken ct) =>
        new(sql, args, commandTimeout: CommandTimeoutSeconds, cancellationToken: ct);
}

using Dapper;

namespace MeterReading.Api.Data;

public sealed class DeviceRow
{
    public Guid DeviceId { get; init; }
    public string? KeyHash { get; init; }
    public string Status { get; init; } = "";
    public string? Label { get; init; }
    /// <summary>The reader this phone belongs to (FR-002.7); null until its first sign-in.</summary>
    public string? BoundReaderLogin { get; init; }
}

/// <summary>Registered phones, mr.Device and mr.DeviceRegistrationCode (db/009). The API's own tables.</summary>
public sealed class DeviceRepository(SqlConnectionFactory db)
{
    /// <summary>
    /// Uses the code and records the phone, in one transaction. Null when the code is unknown, used
    /// or expired. The code row is locked, so two phones cannot use the same code.
    /// </summary>
    public async Task<string?> RegisterAsync(string codeHash, Guid deviceId, string keyHash, string? model, string? androidVersion, string? appVersion, CancellationToken ct)
    {
        const string sql = """
            SET XACT_ABORT ON;
            BEGIN TRANSACTION;
            DECLARE @label nvarchar(100);
            UPDATE mr.DeviceRegistrationCode WITH (UPDLOCK, HOLDLOCK)
            SET UsedAtUtc = SYSUTCDATETIME(), DeviceId = @deviceId, @label = Label
            WHERE CodeHash = @codeHash AND UsedAtUtc IS NULL AND ExpiresAtUtc > SYSUTCDATETIME();
            IF @@ROWCOUNT = 0
            BEGIN
                ROLLBACK TRANSACTION;
                SELECT CAST(NULL AS nvarchar(100)) AS Label, CAST(0 AS bit) AS Ok;
                RETURN;
            END
            INSERT mr.Device (DeviceId, KeyHash, Label, Model, AndroidVersion, AppVersion, Status)
            VALUES (@deviceId, @keyHash, @label, @model, @androidVersion, @appVersion, 'ACTIVE');
            COMMIT TRANSACTION;
            SELECT @label AS Label, CAST(1 AS bit) AS Ok;
            """;
        await using var c = await db.OpenMeterReadingAsync(ct);
        var row = await c.QuerySingleAsync<(string? Label, bool Ok)>(new CommandDefinition(sql, new
        {
            codeHash, deviceId, keyHash,
            model = Trim(model, 100), androidVersion = Trim(androidVersion, 20), appVersion = Trim(appVersion, 20),
        }, cancellationToken: ct));
        return row.Ok ? row.Label ?? "" : null;
    }

    public async Task<DeviceRow?> FindAsync(Guid deviceId, CancellationToken ct)
    {
        await using var c = await db.OpenMeterReadingAsync(ct);
        return await c.QuerySingleOrDefaultAsync<DeviceRow>(new CommandDefinition(
            "SELECT DeviceId, KeyHash, Status, Label, BoundReaderLogin FROM mr.Device WHERE DeviceId = @deviceId", new { deviceId }, cancellationToken: ct));
    }

    /// <summary>
    /// Gives a phone that has no reader yet to <paramref name="readerLogin"/> (FR-002.7). Returns the phone's
    /// reader afterwards: this one, or the one that got there first.
    /// </summary>
    public async Task<string?> BindReaderAsync(Guid deviceId, string readerLogin, CancellationToken ct)
    {
        const string sql = """
            UPDATE mr.Device SET BoundReaderLogin = LEFT(@reader, 200), BoundAtUtc = SYSUTCDATETIME()
            WHERE DeviceId = @deviceId AND BoundReaderLogin IS NULL;
            SELECT BoundReaderLogin FROM mr.Device WHERE DeviceId = @deviceId;
            """;
        await using var c = await db.OpenMeterReadingAsync(ct);
        return await c.ExecuteScalarAsync<string?>(new CommandDefinition(sql, new { deviceId, reader = readerLogin }, cancellationToken: ct));
    }

    /// <summary>Last seen and by whom, at most every five minutes per phone (spec FR-002.2).</summary>
    public async Task TouchAsync(Guid deviceId, string? readerLogin, CancellationToken ct)
    {
        const string sql = """
            UPDATE mr.Device SET LastSyncAtUtc = SYSUTCDATETIME(), LastReaderId = LEFT(@reader, 50)
            WHERE DeviceId = @deviceId
              AND (LastSyncAtUtc IS NULL OR LastSyncAtUtc < DATEADD(MINUTE, -5, SYSUTCDATETIME()) OR ISNULL(LastReaderId, '') <> ISNULL(LEFT(@reader, 50), ''))
            """;
        await using var c = await db.OpenMeterReadingAsync(ct);
        await c.ExecuteAsync(new CommandDefinition(sql, new { deviceId, reader = readerLogin }, cancellationToken: ct));
    }

    /// <summary>For the readiness check in Device mode: db/009 and db/013 have been run.</summary>
    public async Task<bool> TablesExistAsync(CancellationToken ct)
    {
        await using var c = await db.OpenMeterReadingAsync(ct);
        return await c.ExecuteScalarAsync<int>(new CommandDefinition(
            "SELECT CASE WHEN OBJECT_ID(N'mr.DeviceRegistrationCode') IS NULL OR COL_LENGTH(N'mr.Device', N'KeyHash') IS NULL OR COL_LENGTH(N'mr.Device', N'BoundReaderLogin') IS NULL THEN 0 ELSE 1 END",
            cancellationToken: ct)) == 1;
    }

    private static string? Trim(string? s, int max) => string.IsNullOrWhiteSpace(s) ? null : s.Trim().Length > max ? s.Trim()[..max] : s.Trim();
}

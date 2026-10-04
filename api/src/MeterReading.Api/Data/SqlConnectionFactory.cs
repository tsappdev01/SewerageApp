using System.Data.Common;
using System.Text.RegularExpressions;
using Microsoft.Data.SqlClient;
using Microsoft.Extensions.Options;

namespace MeterReading.Api.Data;

/// <summary>
/// Opens connections and names the source views. The views may sit in another database than
/// the API's own <c>mr</c> tables, so the two are queried separately and joined in memory.
/// </summary>
public sealed partial class SqlConnectionFactory
{
    private readonly string _source;
    private readonly string _meterReading;

    public SqlConnectionFactory(IConfiguration configuration, IOptions<SourceViewsOptions> views)
    {
        _meterReading = configuration.GetConnectionString("MeterReading")
            ?? throw new InvalidOperationException("ConnectionStrings:MeterReading is not set.");
        _source = configuration.GetConnectionString("Source") is { Length: > 0 } source ? source : _meterReading;

        var schema = views.Value.Schema;
        if (!Identifier().IsMatch(schema))
            throw new InvalidOperationException($"SourceViews:Schema '{schema}' is not a plain SQL identifier.");
        Views = new SourceViewNames(schema);
    }

    public SourceViewNames Views { get; }

    /// <summary>Connection to the database holding the vw_MR_* views.</summary>
    public async Task<DbConnection> OpenSourceAsync(CancellationToken ct)
    {
        var connection = new SqlConnection(_source);
        await connection.OpenAsync(ct);
        return connection;
    }

    /// <summary>Connection to the database holding the API's own mr.* tables.</summary>
    public async Task<DbConnection> OpenMeterReadingAsync(CancellationToken ct)
    {
        var connection = new SqlConnection(_meterReading);
        await connection.OpenAsync(ct);
        return connection;
    }

    [GeneratedRegex("^[A-Za-z_][A-Za-z0-9_]{0,127}$")]
    private static partial Regex Identifier();
}

/// <summary>Bracketed, schema-qualified view names, safe to put in SQL text.</summary>
public sealed class SourceViewNames(string schema)
{
    private string Name(string view) => $"[{schema}].[{view}]";

    public string Schema => schema;
    public string Reader => Name("vw_MR_Reader");
    public string Zone => Name("vw_MR_Zone");
    public string Property => Name("vw_MR_Property");
    public string Meter => Name("vw_MR_Meter");
    public string ReadingPeriod => Name("vw_MR_ReadingPeriod");
    public string ReadingHistory => Name("vw_MR_ReadingHistory");
    public string Tenant => Name("vw_MR_Tenant");

    public static readonly string[] Required =
        ["vw_MR_Reader", "vw_MR_Zone", "vw_MR_Property", "vw_MR_Meter", "vw_MR_ReadingPeriod", "vw_MR_Tenant"];
}

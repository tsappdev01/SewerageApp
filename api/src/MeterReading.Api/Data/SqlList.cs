using System.Security;

namespace MeterReading.Api.Data;

/// <summary>
/// A list of values passed to SQL as one XML parameter: no 2,100-parameter limit, and unlike OPENJSON
/// it works at any database compatibility level (PropertyManagementSystem on UAT is below 130).
/// </summary>
public static class SqlList
{
    /// <summary>The parameter value: &lt;i&gt;a&lt;/i&gt;&lt;i&gt;b&lt;/i&gt;, each value escaped.</summary>
    public static string Of(IEnumerable<string> values) =>
        string.Concat(values.Select(v => "<i>" + SecurityElement.Escape(v) + "</i>"));

    /// <summary>A SELECT returning one row per value in <paramref name="parameter"/>, as <paramref name="sqlType"/>.</summary>
    public static string Select(string parameter, string sqlType) =>
        $"SELECT i.v.value('(./text())[1]', '{sqlType}') COLLATE DATABASE_DEFAULT FROM (SELECT CAST({parameter} AS xml) AS d) AS s CROSS APPLY s.d.nodes('/i') AS i(v)";

    /// <summary>Pairs as &lt;m k="key" v="value"/&gt;, for <see cref="Lookup"/>.</summary>
    public static string Map(IReadOnlyDictionary<string, string> pairs) =>
        string.Concat(pairs.Select(p => $"<m k=\"{SecurityElement.Escape(p.Key)}\" v=\"{SecurityElement.Escape(p.Value)}\"/>"));

    /// <summary>SQL for the value stored under <paramref name="key"/> in a <see cref="Map"/> parameter, or NULL.</summary>
    public static string Lookup(string parameter, string key, string sqlType) =>
        $"(SELECT TOP (1) m.p.value('@v', '{sqlType}') FROM (SELECT CAST({parameter} AS xml) AS d) AS s CROSS APPLY s.d.nodes('/m') AS m(p) WHERE m.p.value('@k', 'varchar(50)') COLLATE DATABASE_DEFAULT = {key})";
}

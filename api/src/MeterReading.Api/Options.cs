namespace MeterReading.Api;

/// <summary>Where the source views live (docs/source-views.md).</summary>
public sealed class SourceViewsOptions
{
    public const string Section = "SourceViews";

    /// <summary>Schema of the vw_MR_* views. Must be a plain SQL identifier.</summary>
    public string Schema { get; set; } = "dbo";

    /// <summary>False when the optional vw_MR_ReadingHistory view is not provided.</summary>
    public bool HasReadingHistory { get; set; } = true;
}

/// <summary>Thresholds from spec BR-007 and BR-008. Settings, not constants.</summary>
public sealed class ReadingRulesOptions
{
    public const string Section = "ReadingRules";

    public int AveragePeriods { get; set; } = 3;
    public decimal HighConsumptionFactor { get; set; } = 3.0m;
    public decimal HighConsumptionFloorIrrigation { get; set; } = 100m;
    public decimal HighConsumptionFloorSewerage { get; set; } = 100m;
    public decimal? DefaultAverageIrrigation { get; set; }
    public decimal? DefaultAverageSewerage { get; set; }
}

/// <summary>How callers are identified.</summary>
public sealed class AuthOptions
{
    public const string Section = "Auth";

    /// <summary><c>Entra</c> validates Microsoft Entra ID tokens. <c>Development</c> trusts the
    /// X-Dev-User header and is refused outside the Development environment.</summary>
    public string Mode { get; set; } = "Entra";

    /// <summary>Entra app role required to use the reader endpoints.</summary>
    public string ReaderRole { get; set; } = "MeterReader";
}

namespace MeterReading.Api;

/// <summary>Where the source views live (docs/source-views.md).</summary>
public sealed class SourceViewsOptions
{
    public const string Section = "SourceViews";

    /// <summary>Schema of the vw_MR_* views. Must be a plain SQL identifier.</summary>
    public string Schema { get; set; } = "dbo";

    /// <summary>True only when the optional vw_MR_ReadingHistory view is provided.</summary>
    public bool HasReadingHistory { get; set; }
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

    /// <summary><c>Device</c>: registered phones with their own key (FR-002; the phone app's mode).
    /// <c>Entra</c> validates Microsoft Entra ID tokens. <c>Development</c> trusts the X-Dev-User
    /// header and is refused outside the Development environment.</summary>
    public string Mode { get; set; } = "Entra";

    /// <summary>Entra app role required to use the reader endpoints.</summary>
    public string ReaderRole { get; set; } = "MeterReader";
}

/// <summary>Where reading photos are kept (spec FR-009.3) and the upload limits.</summary>
public sealed class ImageStoreOptions
{
    public const string Section = "ImageStore";

    /// <summary><c>Database</c> (table mr.ReadingImageData, the default), <c>FileSystem</c> or <c>AzureBlob</c>.</summary>
    public string Kind { get; set; } = "Database";

    /// <summary>FileSystem: folder that holds the photos. The API needs write access.</summary>
    public string Root { get; set; } = "images";

    /// <summary>AzureBlob: e.g. https://account.blob.core.windows.net. Uses the API's managed identity.</summary>
    public string? BlobServiceUri { get; set; }

    public string Container { get; set; } = "readings";

    /// <summary>The phone shrinks photos to about 500 KB (FR-008.5); this is the hard limit.</summary>
    public int MaxImageBytes { get; set; } = 2_000_000;

    /// <summary>FR-008.7.</summary>
    public int MaxImagesPerReading { get; set; } = 4;
}

/// <summary>Field Inspection (spec §21). On only when the two inspection views exist.</summary>
public sealed class InspectionOptions
{
    public const string Section = "Inspection";

    /// <summary>Evidence photos per unit (spec FR-033.3).</summary>
    public int MaxPhotosPerUnit { get; set; } = 6;

    /// <summary>The plan list covers plan dates from this many days ago (late visits)...</summary>
    public int PlanDaysBack { get; set; } = 60;

    /// <summary>...to this many days ahead.</summary>
    public int PlanDaysAhead { get; set; } = 14;

    /// <summary>Hours ahead of UTC for "today" (UAE: 4).</summary>
    public int UtcOffsetHours { get; set; } = 4;

    /// <summary>The DIP office, for the distance to each property (FR-031.2). Empty: no distances.</summary>
    public double? OfficeLatitude { get; set; }

    public double? OfficeLongitude { get; set; }
}

/// <summary>
/// Copying accepted readings into PropertyManagementSystem's MaintainMeterReading
/// (docs/readings-table.md). Off until the formats below are confirmed against existing rows.
/// </summary>
public sealed class PmsTransferOptions
{
    public const string Section = "PmsTransfer";

    public bool Enabled { get; set; }

    /// <summary>
    /// The table to insert into, as seen from the MeterReading connection: <c>dbo.MaintainMeterReading</c>
    /// when the mr schema is in PropertyManagementSystem, or <c>PropertyManagementSystem.dbo.MaintainMeterReading</c>
    /// from another database on the same server.
    /// </summary>
    public string TargetTable { get; set; } = "dbo.MaintainMeterReading";

    public int IntervalSeconds { get; set; } = 60;
    public int BatchSize { get; set; } = 100;

    /// <summary>After this many failed attempts a reading is left for someone to look at.</summary>
    public int MaxAttempts { get; set; } = 10;

    /// <summary>.NET format for the text ReadingDate column, in <see cref="TimeZone"/>.</summary>
    public string ReadingDateFormat { get; set; } = "yyyy-MM-dd HH:mm:ss";

    /// <summary>SQL Server time zone name for ReadingDate.</summary>
    public string TimeZone { get; set; } = "Arabian Standard Time";

    /// <summary>App condition code to the MeterStatus text the table uses, e.g. WORKING → Working. Unmapped codes are copied as they are.</summary>
    public Dictionary<string, string> MeterStatusMap { get; set; } = new(StringComparer.OrdinalIgnoreCase);
}

/// <summary>
/// The DMZ gateway in front of the API (gateway/ project, docs/deployment.md "DMZ gateway").
/// Section "Gateway" in appsettings.json or Gateway__* environment variables.
/// </summary>
public sealed class GatewayTrustOptions
{
    public const string Section = "Gateway";

    /// <summary>
    /// The gateway's IP addresses. Only requests from these may say, in X-Forwarded-For, which phone
    /// they come from; then rate limits count per phone, not per gateway. Empty: the header is ignored.
    /// </summary>
    public string[] KnownProxies { get; set; } = [];

    /// <summary>Refuse every request (except /health/*) that does not come with one of <see cref="ClientCertificateThumbprints"/>.</summary>
    public bool RequireClientCertificate { get; set; }

    /// <summary>Thumbprints of the gateway's client certificate(s); two during a certificate change.</summary>
    public string[] ClientCertificateThumbprints { get; set; } = [];
}

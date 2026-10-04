/*
  001_check_source_views.sql
  Checks the views the Meter Reading API reads (docs/source-views.md). Read-only; safe to run
  any time. Set @Schema if the views are not in dbo.

  Result 1: structure problems (missing view, missing column, wrong type).
  Result 2: data problems (duplicate keys, unknown codes, broken references) and notes, such as
            meters the API will treat as never read. Rows is how many are affected.
  Both empty = the API can use the views.
*/
SET NOCOUNT ON;
DECLARE @Schema sysname = N'dbo';

DECLARE @Expected TABLE (ViewName sysname, ColumnName sysname, Family varchar(10), ViewRequired bit);
INSERT @Expected (ViewName, ColumnName, Family, ViewRequired) VALUES
 (N'vw_MR_Reader', N'UserId', 'text', 1), (N'vw_MR_Reader', N'LoginEmail', 'text', 1),
 (N'vw_MR_Reader', N'DisplayName', 'text', 1), (N'vw_MR_Reader', N'TeamCode', 'text', 1),
 (N'vw_MR_Reader', N'SupervisorEmail', 'text', 1), (N'vw_MR_Reader', N'IsActive', 'flag', 1),
 (N'vw_MR_Zone', N'ZoneCode', 'text', 1), (N'vw_MR_Zone', N'ZoneName', 'text', 1), (N'vw_MR_Zone', N'IsActive', 'flag', 1),
 (N'vw_MR_Property', N'PropertyCode', 'text', 1), (N'vw_MR_Property', N'PropertyName', 'text', 1),
 (N'vw_MR_Property', N'ZoneCode', 'text', 1), (N'vw_MR_Property', N'RouteSequence', 'int', 1),
 (N'vw_MR_Property', N'Latitude', 'any', 1), (N'vw_MR_Property', N'Longitude', 'any', 1),
 (N'vw_MR_Property', N'IsActive', 'flag', 1),
 (N'vw_MR_Property', N'PropertyId', 'key', 1), (N'vw_MR_Property', N'TenantCode', 'text', 1), (N'vw_MR_Property', N'CompanyName', 'text', 1),
 (N'vw_MR_Meter', N'MeterId', 'key', 1), (N'vw_MR_Meter', N'MeterNumber', 'text', 1),
 (N'vw_MR_Meter', N'PropertyCode', 'text', 1), (N'vw_MR_Meter', N'MeterType', 'text', 1),
 (N'vw_MR_Meter', N'RegisterDigits', 'int', 1), (N'vw_MR_Meter', N'DecimalDigits', 'int', 1),
 (N'vw_MR_Meter', N'OpeningReading', 'decimal', 1), (N'vw_MR_Meter', N'LastConsumption', 'decimal', 1),
 (N'vw_MR_Meter', N'AvgConsumption', 'decimal', 1), (N'vw_MR_Meter', N'InstallDate', 'date', 1),
 (N'vw_MR_Meter', N'RouteSequence', 'int', 1), (N'vw_MR_Meter', N'SerialNumber', 'text', 1),
 (N'vw_MR_Meter', N'Status', 'flag', 1),
 (N'vw_MR_ReadingPeriod', N'StartDate', 'date', 1), (N'vw_MR_ReadingPeriod', N'EndDate', 'date', 1),
 (N'vw_MR_ReadingPeriod', N'Status', 'text', 1),
 (N'vw_MR_Tenant', N'PropertyCode', 'text', 1), (N'vw_MR_Tenant', N'TenantCode', 'text', 1),
 (N'vw_MR_Tenant', N'CompanyName', 'text', 1),
 (N'vw_MR_ReadingHistory', N'MeterId', 'key', 0), (N'vw_MR_ReadingHistory', N'PeriodCode', 'text', 0),
 (N'vw_MR_ReadingHistory', N'ReadingDate', 'date', 0), (N'vw_MR_ReadingHistory', N'ReadingValue', 'decimal', 0),
 (N'vw_MR_ReadingHistory', N'Consumption', 'decimal', 0), (N'vw_MR_ReadingHistory', N'ConsumptionBasis', 'text', 0);

DECLARE @Actual TABLE (ViewName sysname, ColumnName sysname, TypeName sysname);
INSERT @Actual
SELECT o.name, c.name, t.name
FROM sys.objects o
JOIN sys.schemas s ON s.schema_id = o.schema_id
JOIN sys.columns c ON c.object_id = o.object_id
JOIN sys.types t ON t.user_type_id = c.user_type_id
WHERE s.name = @Schema AND o.type IN ('V', 'U');

/* Result 1: structure */
SELECT e.ViewName, e.ColumnName, Problem =
    CASE
        WHEN NOT EXISTS (SELECT 1 FROM @Actual a WHERE a.ViewName = e.ViewName)
            THEN CASE WHEN e.ViewRequired = 1 THEN 'View is missing' ELSE 'Optional view not found (averages use defaults)' END
        WHEN a.ColumnName IS NULL THEN 'Column is missing'
        ELSE 'Type ' + a.TypeName + ' is not a ' + e.Family + ' type'
    END
FROM @Expected e
LEFT JOIN @Actual a ON a.ViewName = e.ViewName AND a.ColumnName = e.ColumnName
WHERE a.ColumnName IS NULL
   OR NOT (
        (e.Family = 'text' AND a.TypeName IN ('varchar', 'nvarchar', 'char', 'nchar'))
     OR (e.Family = 'int' AND a.TypeName IN ('tinyint', 'smallint', 'int', 'bigint'))
     OR (e.Family = 'decimal' AND a.TypeName IN ('decimal', 'numeric', 'tinyint', 'smallint', 'int', 'bigint', 'float', 'real', 'money'))
     OR (e.Family = 'date' AND a.TypeName IN ('date', 'datetime', 'datetime2', 'smalldatetime', 'datetimeoffset'))
     OR (e.Family = 'flag' AND a.TypeName IN ('bit', 'tinyint', 'smallint', 'int', 'char', 'varchar', 'nchar', 'nvarchar'))
     OR (e.Family = 'key' AND a.TypeName IN ('varchar', 'nvarchar', 'char', 'nchar', 'tinyint', 'smallint', 'int', 'bigint'))
     OR e.Family = 'any')
GROUP BY e.ViewName, e.ColumnName, e.ViewRequired, a.ColumnName, a.TypeName, e.Family
ORDER BY e.ViewName, e.ColumnName;

/* Result 2: data. Each check counts offending rows; it runs only when its views exist. */
DECLARE @Checks TABLE (CheckName nvarchar(200), View1 sysname, View2 sysname NULL, Query nvarchar(max));
INSERT @Checks VALUES
 (N'Duplicate UserId', N'vw_MR_Reader', NULL, N'SELECT @n = COUNT(*) FROM (SELECT UserId FROM {s}.vw_MR_Reader GROUP BY UserId HAVING COUNT(*) > 1) d'),
 (N'Active readers sharing a LoginEmail (they cannot sign in)', N'vw_MR_Reader', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Reader r WHERE r.IsActive = 1 AND (SELECT COUNT(*) FROM {s}.vw_MR_Reader r2 WHERE r2.IsActive = 1 AND r2.LoginEmail = r.LoginEmail) > 1'),
 (N'Reader without LoginEmail', N'vw_MR_Reader', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Reader WHERE IsActive = 1 AND (LoginEmail IS NULL OR LoginEmail NOT LIKE ''%_@_%'')'),
 (N'Duplicate ZoneCode', N'vw_MR_Zone', NULL, N'SELECT @n = COUNT(*) FROM (SELECT ZoneCode FROM {s}.vw_MR_Zone GROUP BY ZoneCode HAVING COUNT(*) > 1) d'),
 (N'Duplicate PropertyCode (e.g. two tenant rows; the API keeps the first)', N'vw_MR_Property', NULL, N'SELECT @n = COUNT(*) FROM (SELECT PropertyCode FROM {s}.vw_MR_Property GROUP BY PropertyCode HAVING COUNT(*) > 1) d'),
 (N'Property without CompanyName (the phone shows the code instead)', N'vw_MR_Property', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Property WHERE CompanyName IS NULL OR LTRIM(CompanyName) = '''''),
 (N'Property with unknown ZoneCode', N'vw_MR_Property', N'vw_MR_Zone', N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Property p WHERE NOT EXISTS (SELECT 1 FROM {s}.vw_MR_Zone z WHERE z.ZoneCode = p.ZoneCode)'),
 (N'Latitude or Longitude not a number', N'vw_MR_Property', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Property WHERE (Latitude IS NOT NULL AND TRY_CAST(Latitude AS decimal(9,6)) IS NULL) OR (Longitude IS NOT NULL AND TRY_CAST(Longitude AS decimal(9,6)) IS NULL)'),
 (N'Property with no current tenant in vw_MR_Tenant (its readings are refused: NO_TENANT)', N'vw_MR_Property', N'vw_MR_Tenant', N'SELECT @n = COUNT(*) FROM (SELECT DISTINCT p.PropertyCode FROM {s}.vw_MR_Property p WHERE p.IsActive = 1 AND NOT EXISTS (SELECT 1 FROM {s}.vw_MR_Tenant t WHERE t.PropertyCode = p.PropertyCode)) d'),
 (N'Properties with more than one current tenant (the reader picks one)', N'vw_MR_Tenant', NULL, N'SELECT @n = COUNT(*) FROM (SELECT PropertyCode FROM {s}.vw_MR_Tenant GROUP BY PropertyCode HAVING COUNT(DISTINCT TenantCode) > 1) d'),
 (N'Tenant row without TenantCode', N'vw_MR_Tenant', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Tenant WHERE TenantCode IS NULL OR LTRIM(TenantCode) = '''''),
 (N'Meter without MeterId (barcode)', N'vw_MR_Meter', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Meter WHERE MeterId IS NULL OR LTRIM(CAST(MeterId AS varchar(50))) = '''''),
 (N'Duplicate MeterId (barcode)', N'vw_MR_Meter', NULL, N'SELECT @n = COUNT(*) FROM (SELECT MeterId FROM {s}.vw_MR_Meter WHERE MeterId IS NOT NULL GROUP BY MeterId HAVING COUNT(*) > 1) d'),
 (N'MeterId longer than 50 characters', N'vw_MR_Meter', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Meter WHERE LEN(CAST(MeterId AS varchar(60))) > 50'),
 (N'MeterType not starting with I (irrigation) or S (sewerage)', N'vw_MR_Meter', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Meter WHERE UPPER(LEFT(LTRIM(CAST(MeterType AS varchar(20))), 1)) NOT IN (''I'', ''S'') OR MeterType IS NULL'),
 (N'Meter Status not 1/0, True/False or ACTIVE/INACTIVE', N'vw_MR_Meter', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Meter WHERE UPPER(CAST(Status AS varchar(10))) NOT IN (''1'', ''0'', ''TRUE'', ''FALSE'', ''ACTIVE'', ''INACTIVE'') OR Status IS NULL'),
 (N'RegisterDigits not between 1 and 10', N'vw_MR_Meter', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Meter WHERE RegisterDigits NOT BETWEEN 1 AND 10 OR RegisterDigits IS NULL'),
 (N'Meter with unknown PropertyCode', N'vw_MR_Meter', N'vw_MR_Property', N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Meter m WHERE NOT EXISTS (SELECT 1 FROM {s}.vw_MR_Property p WHERE p.PropertyCode = m.PropertyCode)'),
 (N'Negative OpeningReading', N'vw_MR_Meter', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Meter WHERE OpeningReading < 0'),
 (N'Meters with OpeningReading 0 (treated as never read)', N'vw_MR_Meter', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Meter WHERE OpeningReading = 0 OR OpeningReading IS NULL'),
 (N'Duplicate period (same month twice)', N'vw_MR_ReadingPeriod', NULL, N'SELECT @n = COUNT(*) FROM (SELECT CONVERT(char(7), CAST(StartDate AS date), 126) m FROM {s}.vw_MR_ReadingPeriod GROUP BY CONVERT(char(7), CAST(StartDate AS date), 126) HAVING COUNT(*) > 1) d'),
 (N'Period Status not PLANNED, OPEN or CLOSED', N'vw_MR_ReadingPeriod', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_ReadingPeriod WHERE UPPER(Status) NOT IN (''PLANNED'', ''OPEN'', ''CLOSED'') OR Status IS NULL'),
 (N'More than one OPEN period (the API uses the latest; count shown)', N'vw_MR_ReadingPeriod', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_ReadingPeriod WHERE Status = ''OPEN'' HAVING COUNT(*) > 1'),
 (N'No OPEN period', N'vw_MR_ReadingPeriod', NULL, N'SELECT @n = CASE WHEN EXISTS (SELECT 1 FROM {s}.vw_MR_ReadingPeriod WHERE Status = ''OPEN'') THEN 0 ELSE 1 END'),
 (N'ConsumptionBasis not ACTUAL or AVERAGE', N'vw_MR_ReadingHistory', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_ReadingHistory WHERE ConsumptionBasis NOT IN (''ACTUAL'', ''AVERAGE'') OR ConsumptionBasis IS NULL');

DECLARE @Problems TABLE (CheckName nvarchar(200), Rows int);
DECLARE @name nvarchar(200), @v1 sysname, @v2 sysname, @q nvarchar(max), @n int;
DECLARE c CURSOR LOCAL FAST_FORWARD FOR SELECT CheckName, View1, View2, Query FROM @Checks;
OPEN c;
FETCH NEXT FROM c INTO @name, @v1, @v2, @q;
WHILE @@FETCH_STATUS = 0
BEGIN
    IF OBJECT_ID(QUOTENAME(@Schema) + N'.' + QUOTENAME(@v1)) IS NOT NULL
       AND (@v2 IS NULL OR OBJECT_ID(QUOTENAME(@Schema) + N'.' + QUOTENAME(@v2)) IS NOT NULL)
    BEGIN
        SET @n = 0;
        SET @q = REPLACE(@q, N'{s}', QUOTENAME(@Schema));
        BEGIN TRY
            EXEC sp_executesql @q, N'@n int OUTPUT', @n = @n OUTPUT;
            IF ISNULL(@n, 0) > 0 INSERT @Problems VALUES (@name, @n);
        END TRY
        BEGIN CATCH
            INSERT @Problems VALUES (@name + N' (check failed: ' + ERROR_MESSAGE() + N')', NULL);
        END CATCH
    END
    FETCH NEXT FROM c INTO @name, @v1, @v2, @q;
END
CLOSE c;
DEALLOCATE c;

SELECT CheckName, Rows FROM @Problems ORDER BY CheckName;

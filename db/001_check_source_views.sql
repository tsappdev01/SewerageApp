/*
  001_check_source_views.sql
  Checks the views the Meter Reading API reads (docs/source-views.md). Read-only; safe to run
  any time. Set @Schema if the views are not in dbo.

  Result 1: structure problems (missing view, missing column, wrong type).
  Result 2: data problems (duplicate keys, unknown codes, broken references) and notes, such as
            meters the API will treat as never read. Rows is how many are affected. A check
            that could not run shows Rows NULL and the SQL error in Detail.
  Result 3: the rows behind the problems that must be fixed by hand (shared sign-in names,
            duplicate barcodes), so they can be found in PropertyManagementSystem.
  Results 1 and 2 empty = the API can use the views.
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
 (N'vw_MR_ReadingHistory', N'Consumption', 'decimal', 0), (N'vw_MR_ReadingHistory', N'ConsumptionBasis', 'text', 0),
 (N'vw_MR_InspectionPlan', N'PeriodCode', 'text', 0), (N'vw_MR_InspectionPlan', N'InspectionPlanDate', 'date', 0),
 (N'vw_MR_InspectionPlan', N'PropertyCode', 'text', 0), (N'vw_MR_InspectionPlan', N'TenantCode', 'text', 0),
 (N'vw_MR_InspectionPlan', N'CompanyName', 'text', 0), (N'vw_MR_InspectionPlan', N'ActiveUnits', 'int', 0),
 (N'vw_MR_InspectionPlan', N'InactiveUnits', 'int', 0), (N'vw_MR_InspectionPlan', N'TotalUnits', 'int', 0),
 (N'vw_MR_InspectionUnit', N'UnitId', 'key', 0), (N'vw_MR_InspectionUnit', N'PropertyCode', 'text', 0),
 (N'vw_MR_InspectionUnit', N'BuildingName', 'text', 0),
 (N'vw_MR_InspectionUnit', N'UnitCode', 'text', 0), (N'vw_MR_InspectionUnit', N'Category', 'text', 0),
 (N'vw_MR_InspectionUnit', N'SubTenantName', 'text', 0), (N'vw_MR_InspectionUnit', N'Active', 'flag', 0);

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
            THEN CASE WHEN e.ViewRequired = 1 THEN 'View is missing'
                      WHEN e.ViewName LIKE N'vw[_]MR[_]Inspection%' THEN 'Optional view not found (Field Inspection is off)'
                      ELSE 'Optional view not found (averages use defaults)' END
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
 (N'Active readers sharing a LoginEmail (they cannot sign in; listed in result 3)', N'vw_MR_Reader', NULL, N'SELECT @n = ISNULL(SUM(c), 0) FROM (SELECT COUNT(*) c FROM {s}.vw_MR_Reader WHERE UPPER(LTRIM(RTRIM(CAST(IsActive AS varchar(10))))) IN (''1'', ''TRUE'', ''T'', ''Y'', ''YES'', ''ACTIVE'') GROUP BY CAST(LoginEmail AS nvarchar(256)) COLLATE DATABASE_DEFAULT HAVING COUNT(*) > 1) d'),
 (N'Reader without LoginEmail', N'vw_MR_Reader', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Reader WHERE UPPER(LTRIM(RTRIM(CAST(IsActive AS varchar(10))))) IN (''1'', ''TRUE'', ''T'', ''Y'', ''YES'', ''ACTIVE'') AND (LoginEmail IS NULL OR LoginEmail NOT LIKE ''%_@_%'')'),
 (N'vw_MR_Reader.IsActive value not understood (read as inactive; use 1/0, Y/N, True/False)', N'vw_MR_Reader', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Reader WHERE UPPER(LTRIM(RTRIM(CAST(IsActive AS varchar(10))))) NOT IN (''1'', ''TRUE'', ''T'', ''Y'', ''YES'', ''ACTIVE'', ''0'', ''FALSE'', ''F'', ''N'', ''NO'', ''INACTIVE'') OR IsActive IS NULL'),
 (N'vw_MR_Zone.IsActive value not understood (read as inactive; use 1/0, Y/N, True/False)', N'vw_MR_Zone', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Zone WHERE UPPER(LTRIM(RTRIM(CAST(IsActive AS varchar(10))))) NOT IN (''1'', ''TRUE'', ''T'', ''Y'', ''YES'', ''ACTIVE'', ''0'', ''FALSE'', ''F'', ''N'', ''NO'', ''INACTIVE'') OR IsActive IS NULL'),
 (N'vw_MR_Property.IsActive value not understood (read as inactive; use 1/0, Y/N, True/False)', N'vw_MR_Property', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Property WHERE UPPER(LTRIM(RTRIM(CAST(IsActive AS varchar(10))))) NOT IN (''1'', ''TRUE'', ''T'', ''Y'', ''YES'', ''ACTIVE'', ''0'', ''FALSE'', ''F'', ''N'', ''NO'', ''INACTIVE'') OR IsActive IS NULL'),
 (N'Duplicate ZoneCode', N'vw_MR_Zone', NULL, N'SELECT @n = COUNT(*) FROM (SELECT ZoneCode FROM {s}.vw_MR_Zone GROUP BY ZoneCode HAVING COUNT(*) > 1) d'),
 (N'Duplicate PropertyCode (e.g. two tenant rows; the API keeps the first)', N'vw_MR_Property', NULL, N'SELECT @n = COUNT(*) FROM (SELECT PropertyCode FROM {s}.vw_MR_Property GROUP BY PropertyCode HAVING COUNT(*) > 1) d'),
 (N'Property without CompanyName (the phone shows the code instead)', N'vw_MR_Property', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Property WHERE CompanyName IS NULL OR LTRIM(CompanyName) = '''''),
 (N'Property with unknown ZoneCode', N'vw_MR_Property', N'vw_MR_Zone', N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Property p WHERE NOT EXISTS (SELECT 1 FROM {s}.vw_MR_Zone z WHERE z.ZoneCode = p.ZoneCode)'),
 (N'Latitude or Longitude not a number', N'vw_MR_Property', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Property WHERE (Latitude IS NOT NULL AND TRY_CAST(Latitude AS decimal(9,6)) IS NULL) OR (Longitude IS NOT NULL AND TRY_CAST(Longitude AS decimal(9,6)) IS NULL)'),
 (N'Property with no current tenant in vw_MR_Tenant (its readings are refused: NO_TENANT)', N'vw_MR_Property', N'vw_MR_Tenant', N'SELECT @n = COUNT(*) FROM (SELECT DISTINCT p.PropertyCode FROM {s}.vw_MR_Property p WHERE UPPER(LTRIM(RTRIM(CAST(p.IsActive AS varchar(10))))) IN (''1'', ''TRUE'', ''T'', ''Y'', ''YES'', ''ACTIVE'') AND NOT EXISTS (SELECT 1 FROM {s}.vw_MR_Tenant t WHERE t.PropertyCode = p.PropertyCode)) d'),
 (N'Properties with more than one current tenant (the reader picks one)', N'vw_MR_Tenant', NULL, N'SELECT @n = COUNT(*) FROM (SELECT PropertyCode FROM {s}.vw_MR_Tenant GROUP BY PropertyCode HAVING COUNT(DISTINCT TenantCode) > 1) d'),
 (N'Tenant row without TenantCode', N'vw_MR_Tenant', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Tenant WHERE TenantCode IS NULL OR LTRIM(TenantCode) = '''''),
 (N'Meter without MeterId (barcode)', N'vw_MR_Meter', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Meter WHERE MeterId IS NULL OR LTRIM(CAST(MeterId AS varchar(50))) = '''''),
 (N'Duplicate MeterId (barcode): on different meters the API leaves them out; listed in result 3', N'vw_MR_Meter', NULL, N'SELECT @n = COUNT(*) FROM (SELECT MeterId FROM {s}.vw_MR_Meter WHERE MeterId IS NOT NULL GROUP BY MeterId HAVING COUNT(*) > 1) d'),
 (N'MeterId longer than 50 characters', N'vw_MR_Meter', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Meter WHERE LEN(CAST(MeterId AS varchar(60))) > 50'),
 (N'MeterType not starting with I (irrigation) or S (sewerage)', N'vw_MR_Meter', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Meter WHERE UPPER(LEFT(LTRIM(CAST(MeterType AS varchar(20))), 1)) NOT IN (''I'', ''S'') OR MeterType IS NULL'),
 (N'Meter Status not 1/0, Y/N, True/False or ACTIVE/INACTIVE', N'vw_MR_Meter', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Meter WHERE UPPER(LTRIM(RTRIM(CAST(Status AS varchar(10))))) NOT IN (''1'', ''TRUE'', ''T'', ''Y'', ''YES'', ''ACTIVE'', ''0'', ''FALSE'', ''F'', ''N'', ''NO'', ''INACTIVE'') OR Status IS NULL'),
 (N'RegisterDigits not between 1 and 10', N'vw_MR_Meter', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Meter WHERE RegisterDigits NOT BETWEEN 1 AND 10 OR RegisterDigits IS NULL'),
 (N'Meter with unknown PropertyCode', N'vw_MR_Meter', N'vw_MR_Property', N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Meter m WHERE NOT EXISTS (SELECT 1 FROM {s}.vw_MR_Property p WHERE p.PropertyCode = m.PropertyCode)'),
 (N'Negative OpeningReading', N'vw_MR_Meter', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Meter WHERE OpeningReading < 0'),
 (N'Meters with OpeningReading 0 (treated as never read)', N'vw_MR_Meter', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_Meter WHERE OpeningReading = 0 OR OpeningReading IS NULL'),
 (N'Duplicate period (same month twice)', N'vw_MR_ReadingPeriod', NULL, N'SELECT @n = COUNT(*) FROM (SELECT CONVERT(char(7), CAST(StartDate AS date), 126) m FROM {s}.vw_MR_ReadingPeriod GROUP BY CONVERT(char(7), CAST(StartDate AS date), 126) HAVING COUNT(*) > 1) d'),
 (N'Period Status not PLANNED, OPEN or CLOSED', N'vw_MR_ReadingPeriod', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_ReadingPeriod WHERE UPPER(Status) NOT IN (''PLANNED'', ''OPEN'', ''CLOSED'') OR Status IS NULL'),
 (N'More than one OPEN period (the API uses the latest; count shown)', N'vw_MR_ReadingPeriod', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_ReadingPeriod WHERE Status = ''OPEN'' HAVING COUNT(*) > 1'),
 (N'No OPEN period', N'vw_MR_ReadingPeriod', NULL, N'SELECT @n = CASE WHEN EXISTS (SELECT 1 FROM {s}.vw_MR_ReadingPeriod WHERE Status = ''OPEN'') THEN 0 ELSE 1 END'),
 (N'ConsumptionBasis not ACTUAL or AVERAGE', N'vw_MR_ReadingHistory', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_ReadingHistory WHERE ConsumptionBasis NOT IN (''ACTUAL'', ''AVERAGE'') OR ConsumptionBasis IS NULL'),
 (N'vw_MR_InspectionUnit returns exactly 1,000 rows: remove TOP (1000) from the view', N'vw_MR_InspectionUnit', NULL, N'SELECT @n = CASE WHEN (SELECT COUNT(*) FROM {s}.vw_MR_InspectionUnit) = 1000 THEN 1 ELSE 0 END'),
 (N'Duplicate UnitId in vw_MR_InspectionUnit (the API keeps the first)', N'vw_MR_InspectionUnit', NULL, N'SELECT @n = COUNT(*) FROM (SELECT UnitId FROM {s}.vw_MR_InspectionUnit GROUP BY UnitId HAVING COUNT(*) > 1) d'),
 (N'Unit without UnitCode', N'vw_MR_InspectionUnit', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_InspectionUnit WHERE UnitCode IS NULL OR LTRIM(CAST(UnitCode AS nvarchar(30))) = '''''),
 (N'vw_MR_InspectionUnit.Active value not understood (read as inactive; use 1/0, Y/N, True/False)', N'vw_MR_InspectionUnit', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_InspectionUnit WHERE UPPER(LTRIM(RTRIM(CAST(Active AS varchar(10))))) NOT IN (''1'', ''TRUE'', ''T'', ''Y'', ''YES'', ''ACTIVE'', ''0'', ''FALSE'', ''F'', ''N'', ''NO'', ''INACTIVE'') OR Active IS NULL'),
 (N'Units whose PropertyCode is on no plan row (inspectors never see them; a note while the plan covers part of the estate)', N'vw_MR_InspectionUnit', N'vw_MR_InspectionPlan', N'SELECT @n = COUNT(*) FROM {s}.vw_MR_InspectionUnit u WHERE NOT EXISTS (SELECT 1 FROM {s}.vw_MR_InspectionPlan p WHERE CAST(p.PropertyCode AS varchar(30)) = CAST(u.PropertyCode AS varchar(30)))'),
 (N'Plan rows whose property has no units (the inspector sees an empty list)', N'vw_MR_InspectionPlan', N'vw_MR_InspectionUnit', N'SELECT @n = COUNT(*) FROM {s}.vw_MR_InspectionPlan p WHERE NOT EXISTS (SELECT 1 FROM {s}.vw_MR_InspectionUnit u WHERE CAST(p.PropertyCode AS varchar(30)) = CAST(u.PropertyCode AS varchar(30)))'),
 (N'Properties with plan rows for more than one tenant in a period (units have no TenantCode, so each tenant''s visit lists all units)', N'vw_MR_InspectionPlan', NULL, N'SELECT @n = COUNT(*) FROM (SELECT PeriodCode, PropertyCode FROM {s}.vw_MR_InspectionPlan GROUP BY PeriodCode, PropertyCode HAVING COUNT(DISTINCT TenantCode) > 1) d'),
 (N'Duplicate plan row (same period, property and tenant)', N'vw_MR_InspectionPlan', NULL, N'SELECT @n = COUNT(*) FROM (SELECT PeriodCode, PropertyCode, TenantCode FROM {s}.vw_MR_InspectionPlan GROUP BY PeriodCode, PropertyCode, TenantCode HAVING COUNT(*) > 1) d'),
 (N'Plan row without InspectionPlanDate', N'vw_MR_InspectionPlan', NULL, N'SELECT @n = COUNT(*) FROM {s}.vw_MR_InspectionPlan WHERE InspectionPlanDate IS NULL');

DECLARE @Problems TABLE (CheckName nvarchar(200), Rows int, Detail nvarchar(2000));
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
            IF ISNULL(@n, 0) > 0 INSERT @Problems VALUES (@name, @n, NULL);
        END TRY
        BEGIN CATCH
            INSERT @Problems VALUES (@name, NULL, N'Check could not run: ' + ERROR_MESSAGE());
        END CATCH
    END
    FETCH NEXT FROM c INTO @name, @v1, @v2, @q;
END
CLOSE c;
DEALLOCATE c;

SELECT CheckName, Rows, Detail FROM @Problems ORDER BY CASE WHEN Rows IS NULL THEN 0 ELSE 1 END, CheckName;

/* Result 3: rows to fix by hand. */
DECLARE @Rows TABLE (Problem nvarchar(100), [Key] nvarchar(256), Count int, Rows nvarchar(2000));
IF OBJECT_ID(QUOTENAME(@Schema) + N'.vw_MR_Reader') IS NOT NULL
BEGIN TRY
    SET @q = REPLACE(N'
        SELECT N''Shared LoginEmail'', CAST(LoginEmail AS nvarchar(256)) COLLATE DATABASE_DEFAULT, COUNT(*),
               STRING_AGG(CAST(CAST(UserId AS varchar(50)) + N'' '' + ISNULL(CAST(DisplayName AS nvarchar(100)), N'''') AS nvarchar(max)), N'', '')
        FROM {s}.vw_MR_Reader WHERE UPPER(LTRIM(RTRIM(CAST(IsActive AS varchar(10))))) IN (''1'', ''TRUE'', ''T'', ''Y'', ''YES'', ''ACTIVE'')
        GROUP BY CAST(LoginEmail AS nvarchar(256)) COLLATE DATABASE_DEFAULT HAVING COUNT(*) > 1', N'{s}', QUOTENAME(@Schema));
    INSERT @Rows EXEC sp_executesql @q;
END TRY
BEGIN CATCH
    INSERT @Rows VALUES (N'Shared LoginEmail: could not list', NULL, NULL, ERROR_MESSAGE());
END CATCH
IF OBJECT_ID(QUOTENAME(@Schema) + N'.vw_MR_Meter') IS NOT NULL
BEGIN TRY
    SET @q = REPLACE(N'
        SELECT N''Duplicate MeterId (barcode)'', CAST(MeterId AS varchar(50)), COUNT(*),
               STRING_AGG(CAST(N''meter '' + ISNULL(CAST(MeterNumber AS varchar(30)), N''?'') + N'' at '' + ISNULL(CAST(PropertyCode AS varchar(30)), N''?'')
                               + N'' (status '' + ISNULL(CAST(Status AS varchar(10)), N''?'') + N'')'' AS nvarchar(max)), N'', '')
        FROM {s}.vw_MR_Meter WHERE MeterId IS NOT NULL
        GROUP BY CAST(MeterId AS varchar(50)) HAVING COUNT(*) > 1', N'{s}', QUOTENAME(@Schema));
    INSERT @Rows EXEC sp_executesql @q;
END TRY
BEGIN CATCH
    INSERT @Rows VALUES (N'Duplicate MeterId: could not list', NULL, NULL, ERROR_MESSAGE());
END CATCH
IF OBJECT_ID(QUOTENAME(@Schema) + N'.vw_MR_InspectionPlan') IS NOT NULL
BEGIN TRY
    SET @q = REPLACE(N'
        SELECT N''Duplicate plan row (the API uses the earliest plan date)'',
               CAST(CAST(PeriodCode AS varchar(10)) + N'' / '' + CAST(PropertyCode AS varchar(30)) + N'' / '' + CAST(TenantCode AS varchar(30)) AS nvarchar(256)), COUNT(*),
               STRING_AGG(CAST(CONVERT(char(10), CAST(InspectionPlanDate AS date), 126) + N'' '' + ISNULL(CAST(CompanyName AS nvarchar(200)), N'''') AS nvarchar(max)), N'', '')
        FROM {s}.vw_MR_InspectionPlan
        GROUP BY PeriodCode, PropertyCode, TenantCode HAVING COUNT(*) > 1', N'{s}', QUOTENAME(@Schema));
    INSERT @Rows EXEC sp_executesql @q;
END TRY
BEGIN CATCH
    INSERT @Rows VALUES (N'Duplicate plan row: could not list', NULL, NULL, ERROR_MESSAGE());
END CATCH
SELECT Problem, [Key], Count, Rows FROM @Rows ORDER BY Problem, [Key];

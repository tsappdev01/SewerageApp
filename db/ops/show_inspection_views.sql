/*
  ops/show_inspection_views.sql
  Shows how the two Field Inspection views are built, in MRDB and in PropertyManagementSystem, to
  find why vw_MR_InspectionUnit fails with "XML parsing: line 1, character ..." (a name with & or <
  put into XML without escaping) Read-only; run in MRDB.
*/
SELECT N'MRDB' AS [Database], o.name AS [View], OBJECT_DEFINITION(o.object_id) AS Definition
FROM sys.views o WHERE o.name IN (N'vw_MR_InspectionPlan', N'vw_MR_InspectionUnit')
UNION ALL
SELECT N'PropertyManagementSystem', o.name, d.definition
FROM PropertyManagementSystem.sys.views o
JOIN PropertyManagementSystem.sys.sql_modules d ON d.object_id = o.object_id
WHERE o.name IN (N'vw_MR_InspectionPlan', N'vw_MR_InspectionUnit');

/* Where the XML error starts: the first rows that fail when read one property at a time. */
SET NOCOUNT ON;
DECLARE @p varchar(30), @n int;
DECLARE @bad TABLE (PropertyCode varchar(30), Error nvarchar(400));
DECLARE c CURSOR LOCAL FAST_FORWARD FOR
    SELECT DISTINCT CAST(PropertyCode AS varchar(30)) FROM dbo.vw_MR_InspectionPlan;
OPEN c;
FETCH NEXT FROM c INTO @p;
WHILE @@FETCH_STATUS = 0
BEGIN
    BEGIN TRY
        SET @n = (SELECT COUNT(*) FROM dbo.vw_MR_InspectionUnit WHERE PropertyCode = @p);
    END TRY
    BEGIN CATCH
        INSERT @bad VALUES (@p, ERROR_MESSAGE());
    END CATCH
    FETCH NEXT FROM c INTO @p;
END
CLOSE c;
DEALLOCATE c;
SELECT TOP (20) PropertyCode, Error FROM @bad ORDER BY PropertyCode;

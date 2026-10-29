/*
  029_copy_mrdb_to_pms.sql
  DIP 2026-10-29: copies the app's data from MRDB's schema mr into PropertyManagementSystem's schema mr
  (readings, photos, finalized billing rows, phones, period status, opening readings, ...), every table
  that exists in both, with the columns both have.

  Run in PropertyManagementSystem, after 028, with the API stopped (app_offline.htm, or the app pool
  stopped) so no reading arrives during the copy. Needs MRDB on the same server.
  Re-runnable: a row already in PMS (same primary key) is skipped; a table without a primary key is
  copied only while it is empty in PMS. Nothing in MRDB is changed or deleted; keep MRDB until the
  app has run well on PMS, then retire it.

  Prints, per table, the rows in MRDB, copied now, and in PMS afterwards. The two counts must match.
*/
SET NOCOUNT ON;
SET XACT_ABORT ON;
IF DB_NAME() <> N'PropertyManagementSystem' OR DB_ID(N'MRDB') IS NULL
BEGIN
    RAISERROR(N'Run this in PropertyManagementSystem, with MRDB on the same server.', 16, 1) WITH NOWAIT;
    SET NOEXEC ON;
END
GO

DECLARE @tables TABLE (Name sysname PRIMARY KEY);
INSERT @tables (Name)
SELECT t.name
FROM MRDB.sys.tables t JOIN MRDB.sys.schemas s ON s.schema_id = t.schema_id
WHERE s.name = N'mr' AND OBJECT_ID(N'mr.' + QUOTENAME(t.name), N'U') IS NOT NULL;

DECLARE @missing nvarchar(max) = (
    SELECT STRING_AGG(t.name, N', ')
    FROM MRDB.sys.tables t JOIN MRDB.sys.schemas s ON s.schema_id = t.schema_id
    WHERE s.name = N'mr' AND OBJECT_ID(N'mr.' + QUOTENAME(t.name), N'U') IS NULL);
IF @missing IS NOT NULL
    RAISERROR(N'Not in PMS yet, so not copied (run their scripts here first): %s', 10, 1, @missing) WITH NOWAIT;

-- Foreign keys are switched off for the copy (tables are copied in name order) and checked again after.
DECLARE @name sysname, @sql nvarchar(max);
DECLARE t CURSOR LOCAL FAST_FORWARD FOR SELECT Name FROM @tables ORDER BY Name;
OPEN t; FETCH NEXT FROM t INTO @name;
WHILE @@FETCH_STATUS = 0
BEGIN
    SET @sql = N'ALTER TABLE mr.' + QUOTENAME(@name) + N' NOCHECK CONSTRAINT ALL;';
    EXEC (@sql);
    FETCH NEXT FROM t INTO @name;
END
CLOSE t;

DECLARE @report TABLE (TableName sysname, InMrdb bigint, CopiedNow bigint, InPms bigint);
OPEN t; FETCH NEXT FROM t INTO @name;
WHILE @@FETCH_STATUS = 0
BEGIN
    DECLARE @cols nvarchar(max), @pk nvarchar(max), @hasIdentity bit, @copied bigint, @inMrdb bigint, @inPms bigint;

    -- Columns in both, that can be written (no computed, rowversion or graph columns).
    SELECT @cols = STRING_AGG(CAST(QUOTENAME(c.name) AS nvarchar(max)), N', ') WITHIN GROUP (ORDER BY c.column_id)
    FROM sys.columns c
    WHERE c.object_id = OBJECT_ID(N'mr.' + QUOTENAME(@name))
      AND c.is_computed = 0 AND TYPE_NAME(c.system_type_id) NOT IN (N'timestamp')
      AND EXISTS (SELECT 1 FROM MRDB.sys.columns m
                  JOIN MRDB.sys.tables mt ON mt.object_id = m.object_id
                  JOIN MRDB.sys.schemas ms ON ms.schema_id = mt.schema_id
                  WHERE ms.name = N'mr' AND mt.name = @name AND m.name = c.name);

    SELECT @pk = STRING_AGG(CAST(N'p.' + QUOTENAME(c.name) + N' = m.' + QUOTENAME(c.name) AS nvarchar(max)), N' AND ')
    FROM sys.indexes i
    JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
    JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
    WHERE i.object_id = OBJECT_ID(N'mr.' + QUOTENAME(@name)) AND i.is_primary_key = 1;

    SET @hasIdentity = CASE WHEN EXISTS (SELECT 1 FROM sys.identity_columns WHERE object_id = OBJECT_ID(N'mr.' + QUOTENAME(@name))) THEN 1 ELSE 0 END;

    SET @sql =
        CASE WHEN @hasIdentity = 1 THEN N'SET IDENTITY_INSERT mr.' + QUOTENAME(@name) + N' ON; ' ELSE N'' END +
        N'INSERT mr.' + QUOTENAME(@name) + N' (' + @cols + N') SELECT ' + @cols + N' FROM MRDB.mr.' + QUOTENAME(@name) + N' m ' +
        CASE WHEN @pk IS NOT NULL
             THEN N'WHERE NOT EXISTS (SELECT 1 FROM mr.' + QUOTENAME(@name) + N' p WHERE ' + @pk + N'); '
             ELSE N'WHERE NOT EXISTS (SELECT 1 FROM mr.' + QUOTENAME(@name) + N'); ' END +
        N'SET @copied = @@ROWCOUNT; ' +
        CASE WHEN @hasIdentity = 1 THEN N'SET IDENTITY_INSERT mr.' + QUOTENAME(@name) + N' OFF; ' ELSE N'' END +
        N'SELECT @inMrdb = COUNT_BIG(*) FROM MRDB.mr.' + QUOTENAME(@name) + N'; SELECT @inPms = COUNT_BIG(*) FROM mr.' + QUOTENAME(@name) + N';';
    EXEC sp_executesql @sql, N'@copied bigint OUTPUT, @inMrdb bigint OUTPUT, @inPms bigint OUTPUT',
        @copied = @copied OUTPUT, @inMrdb = @inMrdb OUTPUT, @inPms = @inPms OUTPUT;
    IF @hasIdentity = 1
    BEGIN
        SET @sql = N'DBCC CHECKIDENT (''mr.' + QUOTENAME(@name) + N''', RESEED) WITH NO_INFOMSGS;';
        EXEC (@sql);
    END
    INSERT @report VALUES (@name, @inMrdb, @copied, @inPms);
    FETCH NEXT FROM t INTO @name;
END
CLOSE t;

OPEN t; FETCH NEXT FROM t INTO @name;
WHILE @@FETCH_STATUS = 0
BEGIN
    SET @sql = N'ALTER TABLE mr.' + QUOTENAME(@name) + N' WITH CHECK CHECK CONSTRAINT ALL;';
    EXEC (@sql);
    FETCH NEXT FROM t INTO @name;
END
CLOSE t; DEALLOCATE t;

SELECT TableName, InMrdb, CopiedNow, InPms,
       CASE WHEN InMrdb = InPms THEN 'OK' WHEN InPms > InMrdb THEN 'PMS has more (rows written there already)' ELSE 'MISSING ROWS' END AS Check_
FROM @report ORDER BY TableName;
GO
SET NOEXEC OFF;
GO

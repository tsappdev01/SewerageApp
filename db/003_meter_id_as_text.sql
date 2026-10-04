/*
  003_meter_id_as_text.sql
  The source system identifies meters by barcode (text), and readings have up to 4 decimals.
  Brings an mr schema created by an earlier 002 (MeterId bigint, readings decimal(18,3)) in line.
  Re-runnable: each change runs only if the column still has the old type. Fresh installs of
  002 already have the new types, so this does nothing there.
*/
IF EXISTS (SELECT 1 FROM sys.columns c JOIN sys.types t ON t.user_type_id = c.user_type_id
           WHERE c.object_id = OBJECT_ID(N'mr.ReadingTransaction') AND c.name = N'MeterId' AND t.name = N'bigint')
   AND EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_ReadingTransaction_Period_Meter' AND object_id = OBJECT_ID(N'mr.ReadingTransaction'))
    DROP INDEX IX_ReadingTransaction_Period_Meter ON mr.ReadingTransaction;
GO

IF EXISTS (SELECT 1 FROM sys.columns c JOIN sys.types t ON t.user_type_id = c.user_type_id
           WHERE c.object_id = OBJECT_ID(N'mr.ReadingTransaction') AND c.name = N'MeterId' AND t.name = N'bigint')
    ALTER TABLE mr.ReadingTransaction ALTER COLUMN MeterId varchar(50) NOT NULL;
GO

DECLARE @column sysname, @sql nvarchar(400);
DECLARE c CURSOR LOCAL FAST_FORWARD FOR
    SELECT c.name
    FROM sys.columns c JOIN sys.types t ON t.user_type_id = c.user_type_id
    WHERE c.object_id = OBJECT_ID(N'mr.ReadingTransaction') AND t.name = N'decimal' AND c.precision = 18 AND c.scale = 3;
OPEN c;
FETCH NEXT FROM c INTO @column;
WHILE @@FETCH_STATUS = 0
BEGIN
    -- The index INCLUDEs NewReading, so it must be dropped first; the next batch recreates it.
    IF @column = N'NewReading' AND EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_ReadingTransaction_Period_Meter' AND object_id = OBJECT_ID(N'mr.ReadingTransaction'))
        DROP INDEX IX_ReadingTransaction_Period_Meter ON mr.ReadingTransaction;
    SET @sql = N'ALTER TABLE mr.ReadingTransaction ALTER COLUMN ' + QUOTENAME(@column) + N' decimal(18, 4) NULL;';
    EXEC sp_executesql @sql;
    FETCH NEXT FROM c INTO @column;
END
CLOSE c;
DEALLOCATE c;
GO

IF OBJECT_ID(N'mr.ReadingTransaction', N'U') IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_ReadingTransaction_Period_Meter' AND object_id = OBJECT_ID(N'mr.ReadingTransaction'))
CREATE INDEX IX_ReadingTransaction_Period_Meter ON mr.ReadingTransaction (PeriodCode, MeterId, ReceivedAtUtc DESC)
    INCLUDE (Status, StatusNote, MeterCondition, NewReading);
GO

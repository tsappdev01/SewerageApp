/*
  008_grant_api_login.sql
  Gives the API's SQL login exactly what it needs, in both databases. Run by a DBA in the database
  that holds schema mr (MRDB on UAT), after 002-007. Re-runnable.

  Set @Login to the API's login; create the login first if it does not exist, e.g.
      CREATE LOGIN [mr_api] WITH PASSWORD = '...';            -- SQL login
      CREATE LOGIN [DOMAIN\svc-meterreading] FROM WINDOWS;     -- or the app pool's Windows account

  Why two databases: MRDB's vw_MR_* views read PropertyManagementSystem's views, and ownership
  chaining across databases is off, so the login also needs SELECT on those views there.
  The API never needs DELETE, ALTER or CREATE, and never writes to PropertyManagementSystem
  except the optional copy into MaintainMeterReading (@AllowPmsCopy).
*/
SET NOCOUNT ON;
DECLARE @Login sysname = N'mr_api';
DECLARE @ViewsDb sysname = N'PropertyManagementSystem';  -- where the real vw_MR_* views live
DECLARE @AllowPmsCopy bit = 0;                            -- 1 only when PmsTransfer:Enabled is switched on

IF SUSER_ID(@Login) IS NULL
BEGIN
    RAISERROR(N'Login %s does not exist. Create it first (see the comment at the top).', 16, 1, @Login);
    RETURN;
END

DECLARE @user nvarchar(300) = QUOTENAME(@Login);
DECLARE @sql nvarchar(max);
DECLARE @views TABLE (name sysname);
INSERT @views VALUES (N'vw_MR_Reader'), (N'vw_MR_Zone'), (N'vw_MR_Property'), (N'vw_MR_Meter'), (N'vw_MR_ReadingPeriod'), (N'vw_MR_Tenant');

/* 1. This database (schema mr, and the wrapper views). */
IF DATABASE_PRINCIPAL_ID(@Login) IS NULL
BEGIN
    SET @sql = N'CREATE USER ' + @user + N' FOR LOGIN ' + @user + N';';
    EXEC (@sql);
END
SET @sql = N'GRANT SELECT, INSERT, UPDATE ON SCHEMA::mr TO ' + @user + N';';
EXEC (@sql);
SELECT @sql = STRING_AGG(CAST(N'GRANT SELECT ON dbo.' + QUOTENAME(name) + N' TO ' + @user + N';' AS nvarchar(max)), N' ')
FROM @views WHERE OBJECT_ID(N'dbo.' + QUOTENAME(name)) IS NOT NULL;
IF @sql IS NOT NULL EXEC (@sql);

/* 2. The views' database, when it is another one. */
IF @ViewsDb <> DB_NAME()
BEGIN
    IF DB_ID(@ViewsDb) IS NULL
    BEGIN
        RAISERROR(N'Database %s not found. Set @ViewsDb.', 16, 1, @ViewsDb);
        RETURN;
    END
    DECLARE @run nvarchar(400) = QUOTENAME(@ViewsDb) + N'.sys.sp_executesql';
    SET @sql = N'IF DATABASE_PRINCIPAL_ID(@login) IS NULL EXEC (N''CREATE USER ' + REPLACE(@user, N'''', N'''''') + N' FOR LOGIN ' + REPLACE(@user, N'''', N'''''') + N''');';
    EXEC @run @sql, N'@login sysname', @login = @Login;
    SELECT @sql = STRING_AGG(CAST(N'IF OBJECT_ID(N''dbo.' + QUOTENAME(name) + N''') IS NOT NULL GRANT SELECT ON dbo.' + QUOTENAME(name) + N' TO ' + @user + N';' AS nvarchar(max)), N' ')
    FROM @views;
    EXEC @run @sql;
    IF @AllowPmsCopy = 1
    BEGIN
        SET @sql = N'GRANT INSERT ON dbo.MaintainMeterReading TO ' + @user + N';';
        EXEC @run @sql;
    END
END
ELSE IF @AllowPmsCopy = 1 AND OBJECT_ID(N'dbo.MaintainMeterReading') IS NOT NULL
BEGIN
    SET @sql = N'GRANT INSERT ON dbo.MaintainMeterReading TO ' + @user + N';';
    EXEC (@sql);
END

PRINT N'Permissions granted to ' + @Login + N'.';

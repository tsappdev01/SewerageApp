/*
  008_create_api_login.sql
  Creates the API's own login and database user, mr_api, and gives it exactly what it needs.
  It is the only account the API uses for the database (ConnectionStrings:MeterReading).
  Run by a DBA (needs securityadmin to create the login) in MRDB, after 002-007. Re-runnable:
  an existing login keeps its password; only missing users and grants are added.

  BEFORE RUNNING: set @Password to a strong password (12+ characters, upper, lower, digit, symbol).
  Do not save the password in this file or in the repository. Put it only in the server's
  connection string (docs/deployment.md 2.3).

  If the server allows Windows logins only, set @WindowsLogin to the IIS app pool's account
  (e.g. N'DOMAIN\svc-meterreading') instead; @Password is then ignored.

  Grants: SELECT, INSERT, UPDATE on schema mr; SELECT on the vw_MR_* views here and in
  PropertyManagementSystem (MRDB's views read those, and ownership chaining between databases is
  off); and, with @AllowPmsCopy = 1, INSERT on PropertyManagementSystem.dbo.MaintainMeterReading.
  Never DELETE, ALTER or CREATE.
*/
SET NOCOUNT ON;
DECLARE @Password nvarchar(128) = N'<set a strong password>';
DECLARE @WindowsLogin sysname = NULL;                    -- e.g. N'DOMAIN\svc-meterreading'
DECLARE @ViewsDb sysname = N'PropertyManagementSystem';  -- where the real vw_MR_* views live
DECLARE @AllowPmsCopy bit = 0;                            -- 1 only when PmsTransfer:Enabled is switched on

DECLARE @Login sysname = ISNULL(@WindowsLogin, N'mr_api');

IF DB_NAME() <> N'MRDB'
    PRINT N'Note: running in ' + DB_NAME() + N', not MRDB. Run it in the database that holds schema mr.';

IF SUSER_ID(@Login) IS NULL
BEGIN
    DECLARE @create nvarchar(max);
    IF @WindowsLogin IS NOT NULL
        SET @create = N'CREATE LOGIN ' + QUOTENAME(@WindowsLogin) + N' FROM WINDOWS;';
    ELSE
    BEGIN
        IF @Password LIKE N'<%' OR LEN(@Password) < 12
        BEGIN
            RAISERROR(N'Set @Password (12 characters or more) before running.', 16, 1);
            RETURN;
        END
        -- No expiry: a service account must not stop working; the password policy still applies.
        SET @create = N'CREATE LOGIN ' + QUOTENAME(@Login)
            + N' WITH PASSWORD = ' + QUOTENAME(@Password, N'''')
            + N', DEFAULT_DATABASE = ' + QUOTENAME(DB_NAME())
            + N', CHECK_POLICY = ON, CHECK_EXPIRATION = OFF;';
    END
    EXEC (@create);
    PRINT N'Login ' + @Login + N' created.';
END
ELSE
    PRINT N'Login ' + @Login + N' already exists; its password is unchanged.';

DECLARE @user nvarchar(300) = QUOTENAME(@Login);
DECLARE @sql nvarchar(max);
DECLARE @views TABLE (name sysname);
-- vw_MR_ReadingHistory and the inspection views are optional; each is granted only where it exists.
INSERT @views VALUES (N'vw_MR_Reader'), (N'vw_MR_Zone'), (N'vw_MR_Property'), (N'vw_MR_Meter'), (N'vw_MR_ReadingPeriod'), (N'vw_MR_Tenant'), (N'vw_MR_ReadingHistory'),
    (N'vw_MR_InspectionPlan'), (N'vw_MR_InspectionUnit');

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

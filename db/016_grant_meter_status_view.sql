/*
  016_grant_meter_status_view.sql
  Lets the API's login read the meter statuses (vw_MR_MeterStatus, db/015, spec FR-006.2). db/008
  granted SELECT only on the views that existed then; without this the API cannot read the statuses.
  Grants in MRDB (the wrapper view) and in PropertyManagementSystem (the view it reads; ownership
  chaining between databases is off). Run by a DBA in MRDB. Re-runnable.
*/
SET NOCOUNT ON;
DECLARE @Login sysname = N'mr_api';                      -- or the IIS app pool's Windows login, as in db/008
DECLARE @ViewsDb sysname = N'PropertyManagementSystem';  -- where the real vw_MR_MeterStatus lives
DECLARE @user nvarchar(300) = QUOTENAME(@Login);
DECLARE @sql nvarchar(max);

IF DATABASE_PRINCIPAL_ID(@Login) IS NULL
BEGIN
    RAISERROR(N'User %s is not in this database. Run db/008 first.', 16, 1, @Login);
    RETURN;
END
IF OBJECT_ID(N'dbo.vw_MR_MeterStatus') IS NOT NULL
BEGIN
    SET @sql = N'GRANT SELECT ON dbo.vw_MR_MeterStatus TO ' + @user + N';';
    EXEC (@sql);
    PRINT N'SELECT on ' + DB_NAME() + N'.dbo.vw_MR_MeterStatus granted to ' + @Login + N'.';
END
ELSE
    PRINT N'dbo.vw_MR_MeterStatus is not in ' + DB_NAME() + N' (see db/015).';

IF @ViewsDb <> DB_NAME() AND DB_ID(@ViewsDb) IS NOT NULL
BEGIN
    DECLARE @run nvarchar(400) = QUOTENAME(@ViewsDb) + N'.sys.sp_executesql';
    SET @sql = N'IF DATABASE_PRINCIPAL_ID(@login) IS NULL EXEC (N''CREATE USER ' + REPLACE(@user, N'''', N'''''') + N' FOR LOGIN ' + REPLACE(@user, N'''', N'''''') + N''');
                 IF OBJECT_ID(N''dbo.vw_MR_MeterStatus'') IS NOT NULL GRANT SELECT ON dbo.vw_MR_MeterStatus TO ' + @user + N';';
    EXEC @run @sql, N'@login sysname', @login = @Login;
    PRINT N'SELECT on ' + @ViewsDb + N'.dbo.vw_MR_MeterStatus granted to ' + @Login + N'.';
END

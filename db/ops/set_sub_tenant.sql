/* ops/set_sub_tenant.sql — sets the sub-tenant name of properties (db/030). Run in PropertyManagementSystem.
   Add a line per property to the list below; a property already in the table gets the new name. The phone
   offers the name from its next download (within 5 minutes of the change). To stop offering a name, set
   IsActive = 0 (the last statement); nothing is deleted. */
SET NOCOUNT ON;
DECLARE @names TABLE (PropertyCode varchar(30) PRIMARY KEY, SubTenantName nvarchar(100) NOT NULL, Note nvarchar(200) NULL);
INSERT @names (PropertyCode, SubTenantName, Note) VALUES
    ('597-646', N'Example Sub Tenant LLC', NULL);          -- replace with the real property codes and names

-- Properties that are not in the meter list (mistyped code?): listed, not saved.
SELECT n.PropertyCode AS NotAProperty FROM @names n
WHERE NOT EXISTS (SELECT 1 FROM mr.vw_MR_Property p WHERE CAST(p.PropertyCode AS varchar(30)) = n.PropertyCode);

MERGE mr.PropertySubTenant AS t
USING (SELECT n.* FROM @names n
       WHERE EXISTS (SELECT 1 FROM mr.vw_MR_Property p WHERE CAST(p.PropertyCode AS varchar(30)) = n.PropertyCode)) AS s
   ON t.PropertyCode = s.PropertyCode
WHEN MATCHED THEN UPDATE SET SubTenantName = LTRIM(RTRIM(s.SubTenantName)), Note = s.Note, IsActive = 1,
                             UpdatedAtUtc = SYSUTCDATETIME(), UpdatedBy = SUSER_SNAME()
WHEN NOT MATCHED THEN INSERT (PropertyCode, SubTenantName, Note) VALUES (s.PropertyCode, LTRIM(RTRIM(s.SubTenantName)), s.Note);

-- To stop offering a name: UPDATE mr.PropertySubTenant SET IsActive = 0, UpdatedAtUtc = SYSUTCDATETIME(), UpdatedBy = SUSER_SNAME() WHERE PropertyCode = '...';
SELECT PropertyCode, SubTenantName, IsActive, Note, UpdatedAtUtc, UpdatedBy FROM mr.PropertySubTenant ORDER BY PropertyCode;

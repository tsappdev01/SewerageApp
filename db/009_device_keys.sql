/*
  009_device_keys.sql
  Registered phones (spec FR-002). Each phone registers once with a one-time code from IT and gets
  its own secret device key; every API call carries it. Only SHA-256 hashes of codes and keys are
  stored. Run in MRDB after 008. Re-runnable.

  IT's daily tasks are in db/ops/: new_device_code.sql, list_devices.sql, revoke_device.sql.
*/
IF COL_LENGTH(N'mr.Device', N'KeyHash') IS NULL
    ALTER TABLE mr.Device ADD KeyHash char(64) NULL;
GO
IF COL_LENGTH(N'mr.Device', N'Label') IS NULL
    ALTER TABLE mr.Device ADD Label nvarchar(100) NULL;
GO
IF COL_LENGTH(N'mr.Device', N'RevokedAtUtc') IS NULL
    ALTER TABLE mr.Device ADD RevokedAtUtc datetime2(0) NULL;
GO

IF OBJECT_ID(N'mr.DeviceRegistrationCode', N'U') IS NULL
CREATE TABLE mr.DeviceRegistrationCode (
    CodeHash      char(64)          NOT NULL CONSTRAINT PK_DeviceRegistrationCode PRIMARY KEY,
    Label         nvarchar(100)     NULL,
    CreatedAtUtc  datetime2(0)      NOT NULL CONSTRAINT DF_DeviceRegistrationCode_CreatedAtUtc DEFAULT SYSUTCDATETIME(),
    ExpiresAtUtc  datetime2(0)      NOT NULL,
    UsedAtUtc     datetime2(0)      NULL,
    DeviceId      uniqueidentifier  NULL
);
GO

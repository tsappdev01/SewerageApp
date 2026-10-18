/*
  014_device_enrolment.sql
  Simpler phone registration (spec FR-002.8, DIP 2026-10-18). On first start the app sends its
  ANDROID_ID (iOS: identifierForVendor) and a secret key it made itself; the server keeps the phone as
  PENDING with a short check code. The phone shows both; IT approves it with db/ops/approve_device.sql
  (Status = 'REGISTERED', and the reader it belongs to). No code to type on the phone.

  The ID alone never opens the API: it is not secret. The key, which never leaves the phone after
  enrolment, does. A reinstall makes a new key, so IT approves the same ID again.

  Phones registered with a one-time code (db/009) keep Status 'ACTIVE' and go on working.
  Run in MRDB after 013. Re-runnable.
*/
IF COL_LENGTH(N'mr.Device', N'AndroidId') IS NULL
    ALTER TABLE mr.Device ADD AndroidId varchar(64) NULL;
GO
IF COL_LENGTH(N'mr.Device', N'CheckCode') IS NULL
    ALTER TABLE mr.Device ADD CheckCode char(6) NULL;
GO
-- PENDING (waiting for IT) and REGISTERED (approved) join ACTIVE (registered with a code) and REVOKED.
IF EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = N'CK_Device_Status'
           AND parent_object_id = OBJECT_ID(N'mr.Device') AND definition NOT LIKE N'%PENDING%')
    ALTER TABLE mr.Device DROP CONSTRAINT CK_Device_Status;
GO
IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = N'CK_Device_Status' AND parent_object_id = OBJECT_ID(N'mr.Device'))
    ALTER TABLE mr.Device ADD CONSTRAINT CK_Device_Status CHECK (Status IN ('ACTIVE', 'PENDING', 'REGISTERED', 'REVOKED'));
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_Device_AndroidId' AND object_id = OBJECT_ID(N'mr.Device'))
    CREATE INDEX IX_Device_AndroidId ON mr.Device (AndroidId) WHERE AndroidId IS NOT NULL;
GO

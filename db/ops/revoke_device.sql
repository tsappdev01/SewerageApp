/*
  ops/revoke_device.sql — blocks a lost or retired phone at once (spec FR-002.3). Run in MRDB.
  The phone's next call is refused with DEVICE_REVOKED; its waiting readings stay on it. To let a
  found phone send them, set Status back to 'ACTIVE' (and RevokedAtUtc to NULL).
*/
DECLARE @Label nvarchar(100) = N'Phone 01';   -- or set @DeviceId instead
DECLARE @DeviceId uniqueidentifier = NULL;

UPDATE mr.Device
SET Status = 'REVOKED', RevokedAtUtc = SYSUTCDATETIME()
WHERE Status = 'ACTIVE' AND (DeviceId = @DeviceId OR (@DeviceId IS NULL AND Label = @Label));

SELECT @@ROWCOUNT AS PhonesRevoked;

/*
  ops/rebind_device.sql — gives a phone to another reader (spec FR-002.7). Run in MRDB.
  A phone works only for the reader it was first used by. With @Reader NULL the phone is freed and
  the next reader set on it in Settings becomes its reader; with @Reader set, only that reader can use it.
  Readings already sent stay with the reader who sent them.
*/
DECLARE @Label nvarchar(100) = N'Phone 01';   -- or set @DeviceId instead
DECLARE @DeviceId uniqueidentifier = NULL;
DECLARE @Reader nvarchar(200) = NULL;         -- e.g. N'anil@dipark.com', or NULL for "the next one"

UPDATE mr.Device
SET BoundReaderLogin = @Reader, BoundAtUtc = CASE WHEN @Reader IS NULL THEN NULL ELSE SYSUTCDATETIME() END
WHERE DeviceId = @DeviceId OR (@DeviceId IS NULL AND Label = @Label);

SELECT @@ROWCOUNT AS PhonesChanged;

/*
  ops/new_device_code.sql
  Makes a one-time code to register one phone (spec FR-002). Run in MRDB; give the code to the
  supervisor, who types it in the phone's Settings → "Register this phone". A code works once and
  only until it expires. Only its hash is stored, so a lost code cannot be read back: make a new one.
*/
SET NOCOUNT ON;
DECLARE @Label nvarchar(100) = N'Phone 01';   -- how IT will recognise the phone, e.g. its asset tag
DECLARE @ValidHours int = 24;

-- 12 characters from 32 easy-to-read ones (no I, O, 0, 1): about 60 bits, read as XXXX-XXXX-XXXX.
DECLARE @alphabet char(32) = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
DECLARE @bytes varbinary(12) = CRYPT_GEN_RANDOM(12);
DECLARE @plain varchar(12) = '', @i int = 1;
WHILE @i <= 12
BEGIN
    SET @plain += SUBSTRING(@alphabet, CAST(SUBSTRING(@bytes, @i, 1) AS int) % 32 + 1, 1);
    SET @i += 1;
END

INSERT mr.DeviceRegistrationCode (CodeHash, Label, ExpiresAtUtc)
VALUES (CONVERT(char(64), HASHBYTES('SHA2_256', @plain), 2), @Label, DATEADD(HOUR, @ValidHours, SYSUTCDATETIME()));

SELECT STUFF(STUFF(@plain, 9, 0, '-'), 5, 0, '-') AS RegistrationCode,
       @Label AS Label,
       FORMAT(DATEADD(HOUR, @ValidHours, SYSUTCDATETIME()) AT TIME ZONE 'UTC' AT TIME ZONE 'Arabian Standard Time', 'yyyy-MM-dd HH:mm') AS ValidUntilUaeTime;

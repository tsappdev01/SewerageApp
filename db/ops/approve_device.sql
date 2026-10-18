/*
  ops/approve_device.sql — registers a phone that is waiting for IT (spec FR-002.8). Run in MRDB.

  On the phone: open the app; it shows "Waiting for IT" with the Phone ID and a Check code (also in
  Settings). Put both here, with the reader the phone is for (their LoginEmail in vw_MR_Reader), and
  run. Then the reader closes and opens the app: it goes straight to Home.

  The check code makes sure the request is from that phone: the Phone ID is not secret, so anyone could
  ask to be registered with it, but only the real phone shows its own check code.
  After a reinstall the phone asks again with the same Phone ID and a new check code: run this again.
  The phone's older registrations are blocked at the same time.
*/
SET NOCOUNT ON;
DECLARE @AndroidId   varchar(64)   = 'PHONE_ID';            -- "Phone ID" on the phone
DECLARE @CheckCode   char(6)       = 'CHECK1';              -- "Check code" on the phone
DECLARE @ReaderLogin nvarchar(200) = N'reader@dipark.com';  -- the reader's LoginEmail
DECLARE @Label       nvarchar(100) = N'Phone 01';           -- how IT will recognise the phone

IF NOT EXISTS (SELECT 1 FROM mr.Device WHERE AndroidId = @AndroidId AND CheckCode = UPPER(@CheckCode) AND Status = 'PENDING')
BEGIN
    SELECT 'Not found' AS Result, N'No phone is waiting with this Phone ID and Check code. Check both on the phone (Settings).' AS Detail;
    SELECT DeviceId, AndroidId, CheckCode, Status, Model, RegisteredAtUtc FROM mr.Device WHERE AndroidId = @AndroidId ORDER BY RegisteredAtUtc DESC;
    RETURN;
END

BEGIN TRANSACTION;
-- Older registrations of this phone (before a reinstall) no longer have a key on it.
UPDATE mr.Device SET Status = 'REVOKED', RevokedAtUtc = SYSUTCDATETIME()
WHERE AndroidId = @AndroidId AND CheckCode <> UPPER(@CheckCode) AND Status IN ('ACTIVE', 'REGISTERED', 'PENDING');

UPDATE mr.Device
SET Status = 'REGISTERED', BoundReaderLogin = @ReaderLogin, BoundAtUtc = SYSUTCDATETIME(), Label = @Label
WHERE AndroidId = @AndroidId AND CheckCode = UPPER(@CheckCode) AND Status = 'PENDING';
COMMIT TRANSACTION;

SELECT 'Registered' AS Result, DeviceId, AndroidId, CheckCode, Label, BoundReaderLogin, Model
FROM mr.Device WHERE AndroidId = @AndroidId AND CheckCode = UPPER(@CheckCode);

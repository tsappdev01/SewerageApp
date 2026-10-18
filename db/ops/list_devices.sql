/* ops/list_devices.sql — phones, newest first (PENDING = waiting for IT: db/ops/approve_device.sql), and codes not used yet. Run in MRDB. */
SELECT DeviceId, AndroidId, CheckCode, Label, Status, BoundReaderLogin, LastReaderId, Model, AndroidVersion, AppVersion,
       RegisteredAtUtc, LastSyncAtUtc, RevokedAtUtc
FROM mr.Device
ORDER BY RegisteredAtUtc DESC;

SELECT Label, CreatedAtUtc, ExpiresAtUtc,
       CASE WHEN ExpiresAtUtc < SYSUTCDATETIME() THEN 'EXPIRED' ELSE 'WAITING' END AS State
FROM mr.DeviceRegistrationCode
WHERE UsedAtUtc IS NULL
ORDER BY CreatedAtUtc DESC;

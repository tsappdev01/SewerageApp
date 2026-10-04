/* ops/list_devices.sql — registered phones, newest first, and codes not used yet. Run in MRDB. */
SELECT DeviceId, Label, Status, LastReaderId, Model, AndroidVersion, AppVersion,
       RegisteredAtUtc, LastSyncAtUtc, RevokedAtUtc
FROM mr.Device
ORDER BY RegisteredAtUtc DESC;

SELECT Label, CreatedAtUtc, ExpiresAtUtc,
       CASE WHEN ExpiresAtUtc < SYSUTCDATETIME() THEN 'EXPIRED' ELSE 'WAITING' END AS State
FROM mr.DeviceRegistrationCode
WHERE UsedAtUtc IS NULL
ORDER BY CreatedAtUtc DESC;

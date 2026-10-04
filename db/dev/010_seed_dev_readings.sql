/*
  dev/010_seed_dev_readings.sql
  DEVELOPMENT AND TEST ONLY. Readings already received this period, matching the app's sample:
  six accepted, one exception being checked, one rejected by the supervisor ("Photo not clear").
  Run after 002/003. Re-runnable: the sample rows are replaced each time.
*/
DELETE FROM mr.ReadingTransaction WHERE CAST(TransactionId AS char(36)) LIKE '0b7c0a3e-00%';

INSERT mr.ReadingTransaction (TransactionId, PeriodCode, MeterId, ReaderId, MeterCondition, NewReading, PreviousReading,
    Consumption, CapturedAtUtc, ReceivedAtUtc, Status, StatusNote)
SELECT v.* FROM (VALUES
    ('0b7c0a3e-0001-4000-8000-000000000001', '2026-10', 'BC0001', 'E1001', 'WORKING', 50820, 49820, 1000, '2026-10-04T02:40:00', '2026-10-04T02:40:05', 'ACCEPTED', NULL),
    ('0b7c0a3e-0002-4000-8000-000000000002', '2026-10', 'BC0002', 'E1001', 'WORKING',  8450,  8150,  300, '2026-10-04T02:46:00', '2026-10-04T02:46:04', 'ACCEPTED', NULL),
    ('0b7c0a3e-0005-4000-8000-000000000005', '2026-10', 'BC0005', 'E1001', 'WORKING', 62230, 61200, 1030, '2026-10-04T03:05:00', '2026-10-04T03:05:03', 'ACCEPTED', NULL),
    ('0b7c0a3e-0014-4000-8000-000000000014', '2026-10', 'BC0014', 'E1001', 'WORKING',  8440,  7450,  990, '2026-10-04T03:20:00', '2026-10-04T03:20:02', 'ACCEPTED', NULL),
    ('0b7c0a3e-0015-4000-8000-000000000015', '2026-10', 'BC0015', 'E1001', 'WORKING',  2300,  2010,  290, '2026-10-04T03:24:00', '2026-10-04T03:24:02', 'ACCEPTED', NULL),
    ('0b7c0a3e-0013-4000-8000-000000000013', '2026-10', 'BC0013', 'E1001', 'WORKING',  5300,  3300, 2000, '2026-10-04T03:30:00', '2026-10-04T03:30:02', 'EXCEPTION', N'HIGH_CONSUMPTION'),
    ('0b7c0a3e-0008-4000-8000-000000000008', '2026-10', 'BC0008', 'E1001', 'WORKING', 22600, 22310,  290, '2026-10-04T03:12:00', '2026-10-04T03:12:02', 'REJECTED_BY_SUPERVISOR', N'Photo not clear'),
    ('0b7c0a3e-0016-4000-8000-000000000016', '2026-10', 'BC0016', 'E1002', 'WORKING', 16610, 15600, 1010, '2026-10-04T04:00:00', '2026-10-04T04:00:02', 'ACCEPTED', NULL),
    ('0b7c0a3e-0017-4000-8000-000000000017', '2026-10', 'BC0017', 'E1002', 'NOT_ACCESSIBLE', NULL, 4480, NULL, '2026-10-04T04:05:00', '2026-10-04T04:05:02', 'ACCEPTED', NULL)
) v (TransactionId, PeriodCode, MeterId, ReaderId, MeterCondition, NewReading, PreviousReading, Consumption, CapturedAtUtc, ReceivedAtUtc, Status, StatusNote)
WHERE NOT EXISTS (SELECT 1 FROM mr.ReadingTransaction t WHERE t.TransactionId = v.TransactionId);

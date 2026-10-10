/*
  013_device_reader_binding.sql
  A phone belongs to one reader (spec FR-002.7). The first active reader a registered phone signs in
  as is kept in mr.Device.BoundReaderLogin; the API then refuses any other reader on that phone with
  READER_NOT_ON_THIS_PHONE. Before this, the phone key was checked but the reader (X-Reader) was not,
  so a registered phone could act as any active reader. Run in MRDB after 009. Re-runnable.

  To give a phone to another reader: db/ops/rebind_device.sql.
*/
IF COL_LENGTH(N'mr.Device', N'BoundReaderLogin') IS NULL
    ALTER TABLE mr.Device ADD BoundReaderLogin nvarchar(200) NULL;
GO
IF COL_LENGTH(N'mr.Device', N'BoundAtUtc') IS NULL
    ALTER TABLE mr.Device ADD BoundAtUtc datetime2(0) NULL;
GO

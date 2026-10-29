/* ops/fill_meter_opening_reading.sql — reloads mr.MeterOpeningReading from PMS's reading history
   (db/026; last 2 years). Run in MRDB when PMS has new readings, e.g. monthly before the new month opens. */
EXEC mr.usp_FillMeterOpeningReading;

SELECT TOP 20 * FROM mr.MeterOpeningReading ORDER BY ReadingDate DESC, PropertyCode, Type, MeterNumber;

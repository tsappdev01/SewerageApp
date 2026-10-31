/* ops/fill_meter_opening_reading.sql — reloads mr.MeterOpeningReading from PMS's reading history
   (db/026, db/031: the latest reading per property and meter; AvgConsumption = |mean of the latest 3|, whole).
   Run in PropertyManagementSystem when PMS has new readings, e.g. monthly before the new month opens. The meter
   view takes the last finalized month first (db/031) and this history only for meters with none. */
EXEC mr.usp_FillMeterOpeningReading;

SELECT TOP 20 * FROM mr.MeterOpeningReading ORDER BY ReadingDate DESC, PropertyCode, Type, MeterNumber;

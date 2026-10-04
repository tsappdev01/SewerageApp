/*
  004_add_expected_photos.sql
  How many photos the phone took for a reading. Photos are uploaded one by one after the reading
  (PUT /api/v1/readings/{id}/images/{imageId}), so comparing this with the mr.ReadingImage rows
  shows readings whose photos have not arrived. Re-runnable.
*/
IF COL_LENGTH(N'mr.ReadingTransaction', N'ExpectedPhotos') IS NULL
    ALTER TABLE mr.ReadingTransaction ADD ExpectedPhotos tinyint NULL;
GO

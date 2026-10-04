/*
  007_reading_image_data.sql
  Holds the photos themselves when ImageStore:Kind is Database (the default). One row per photo,
  keyed by the same path as mr.ReadingImage.BlobPath, so:
      SELECT i.*, d.ImageBytes FROM mr.ReadingImage i JOIN mr.ReadingImageData d ON d.BlobPath = i.BlobPath
  The bytes are kept apart from mr.ReadingImage so lists of photos never read them.
  A photo is never overwritten: the API inserts only. Re-runnable.
*/
IF OBJECT_ID(N'mr.ReadingImageData', N'U') IS NULL
CREATE TABLE mr.ReadingImageData (
    BlobPath     nvarchar(400)  NOT NULL CONSTRAINT PK_ReadingImageData PRIMARY KEY,
    ImageBytes   varbinary(max) NOT NULL,
    SavedAtUtc   datetime2(0)   NOT NULL CONSTRAINT DF_ReadingImageData_SavedAtUtc DEFAULT SYSUTCDATETIME()
);
GO

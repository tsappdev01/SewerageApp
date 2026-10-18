/*
  020_irrigation_no_charge.sql
  Spec FR-037.7: properties whose irrigation is not charged from a date on (DIP 2026-10-18). When a
  property is finalized, an irrigation reading taken after NoChargeAfter (UAE date) gets
  IrrigationAmount 0 and IrrigationVat 0; its consumption is still recorded, and the row has
  IrrigationNoCharge = 1. Sewerage is charged as usual.

  The list is data: add a property with one more INSERT below (or an UPDATE of its dates) and run this
  again. ToDate NULL means "until further notice". Run in MRDB after 019. Re-runnable.
*/
IF OBJECT_ID(N'mr.IrrigationNoCharge', N'U') IS NULL
CREATE TABLE mr.IrrigationNoCharge (
    PropertyCode   varchar(50)   NOT NULL CONSTRAINT PK_IrrigationNoCharge PRIMARY KEY,
    NoChargeAfter  date          NOT NULL,   -- readings on later dates are not charged
    ToDate         date          NULL,       -- last date not charged; NULL: no end
    Reason         nvarchar(200) NULL,
    AddedAtUtc     datetime2(0)  NOT NULL CONSTRAINT DF_IrrigationNoCharge_AddedAtUtc DEFAULT SYSUTCDATETIME()
);
GO

IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'IrrigationNoCharge') IS NULL
    ALTER TABLE mr.MaintainFinalizedMeter ADD IrrigationNoCharge bit NOT NULL
        CONSTRAINT DF_MaintainFinalizedMeter_IrrigationNoCharge DEFAULT 0;
GO

-- The properties. ReadingDate > '2025-02-28' for 597-905, as DIP gave it.
INSERT mr.IrrigationNoCharge (PropertyCode, NoChargeAfter, Reason)
SELECT v.PropertyCode, v.NoChargeAfter, v.Reason
FROM (VALUES
    ('597-905', CAST('2025-02-28' AS date), N'DIP 2026-10-18: no irrigation charge after 28 Feb 2025')
) v (PropertyCode, NoChargeAfter, Reason)
WHERE NOT EXISTS (SELECT 1 FROM mr.IrrigationNoCharge n WHERE n.PropertyCode = v.PropertyCode);
GO

SELECT PropertyCode, NoChargeAfter, ToDate, Reason FROM mr.IrrigationNoCharge ORDER BY PropertyCode;
GO

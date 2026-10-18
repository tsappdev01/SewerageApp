/*
  019_finalized_amounts.sql
  Spec FR-037.5: the sewerage and irrigation amounts on mr.MaintainFinalizedMeter, and the FloatValue and
  RateFactor (from vw_MR_Meter, db/018) each was priced with:
      SewerageAmount   = ConsumptionSewerage   * FloatValue * 1.2 * (RateFactor / 100)
      IrrigationAmount = ConsumptionIrrigation * FloatValue * 1.2 * (RateFactor / 100)
  rounded to 2 decimals; 0 on an empty side or when the meter has no FloatValue or RateFactor.
  VAT is 5% of each amount (SewerageVat, IrrigationVat, rounded to 2 decimals) and TotalAmount is the
  sum of all four (DIP 2026-10-18). TenPercentAmount is 10% of SewerageAmount + IrrigationAmount (the
  formula above, both sides), rounded to 2 decimals; it is not part of TotalAmount.
  Run in MRDB after 017. Re-runnable.
*/
IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'SewerageAmount') IS NULL
    ALTER TABLE mr.MaintainFinalizedMeter ADD SewerageAmount decimal(18, 2) NOT NULL
        CONSTRAINT DF_MaintainFinalizedMeter_SewerageAmount DEFAULT 0;
GO
IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'IrrigationAmount') IS NULL
    ALTER TABLE mr.MaintainFinalizedMeter ADD IrrigationAmount decimal(18, 2) NOT NULL
        CONSTRAINT DF_MaintainFinalizedMeter_IrrigationAmount DEFAULT 0;
GO
IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'FloatValueSewerage') IS NULL
    ALTER TABLE mr.MaintainFinalizedMeter ADD FloatValueSewerage decimal(18, 6) NULL, RateFactorSewerage decimal(18, 6) NULL;
GO
IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'FloatValueIrrigation') IS NULL
    ALTER TABLE mr.MaintainFinalizedMeter ADD FloatValueIrrigation decimal(18, 6) NULL, RateFactorIrrigation decimal(18, 6) NULL;
GO
IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'SewerageVat') IS NULL
    ALTER TABLE mr.MaintainFinalizedMeter ADD SewerageVat decimal(18, 2) NOT NULL
        CONSTRAINT DF_MaintainFinalizedMeter_SewerageVat DEFAULT 0;
GO
IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'IrrigationVat') IS NULL
    ALTER TABLE mr.MaintainFinalizedMeter ADD IrrigationVat decimal(18, 2) NOT NULL
        CONSTRAINT DF_MaintainFinalizedMeter_IrrigationVat DEFAULT 0;
GO
IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'TotalAmount') IS NULL
    ALTER TABLE mr.MaintainFinalizedMeter ADD TotalAmount decimal(18, 2) NOT NULL
        CONSTRAINT DF_MaintainFinalizedMeter_TotalAmount DEFAULT 0;
GO
IF COL_LENGTH(N'mr.MaintainFinalizedMeter', N'TenPercentAmount') IS NULL
    ALTER TABLE mr.MaintainFinalizedMeter ADD TenPercentAmount decimal(18, 2) NOT NULL
        CONSTRAINT DF_MaintainFinalizedMeter_TenPercentAmount DEFAULT 0;
GO

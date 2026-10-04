namespace MeterReading.Api.Domain;

/// <summary>
/// Consumption and its checks (spec BR-001, BR-006, BR-008). The Android app has the same rules
/// in data/ReadingRules.kt to warn the reader; this copy decides.
/// </summary>
public static class Consumption
{
    /// <summary>BR-006: a lower reading is a rollover candidate only within this share of the register maximum.</summary>
    public const int RolloverProximityPercent = 10;

    public enum Kind { Ok, Rollover, High, RolloverHigh, Lower }

    public readonly record struct Result(Kind Kind, decimal? Value);

    public static decimal RegisterMax(int digits)
    {
        decimal max = 1;
        for (var i = 0; i < digits; i++) max *= 10;
        return max - 1;
    }

    /// <param name="expectedHigh">Top of the expected range; null means no high check.</param>
    public static Result Check(decimal previous, decimal current, int registerDigits, decimal? expectedHigh)
    {
        if (current >= previous)
        {
            var used = current - previous;
            return new(expectedHigh is { } high && used > high ? Kind.High : Kind.Ok, used);
        }
        var max = RegisterMax(registerDigits);
        if (previous < max - max * RolloverProximityPercent / 100) return new(Kind.Lower, null);
        var rolled = (max - previous + 1) + current;
        return new(expectedHigh is { } h && rolled > h ? Kind.RolloverHigh : Kind.Rollover, rolled);
    }

    /// <summary>Whole-number digits of a reading, to compare with RegisterDigits.</summary>
    public static int WholeDigits(decimal reading) =>
        decimal.Truncate(Math.Abs(reading)).ToString(System.Globalization.CultureInfo.InvariantCulture).Length;
}

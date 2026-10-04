namespace MeterReading.Api.Domain;

/// <summary>Assignment status as the reader sees it (spec §7.1 C). Same names as the Android app.</summary>
public enum AssignmentState { PENDING, SENT, CHECKING, READ_AGAIN, REVISIT }

public static class AssignmentStates
{
    private static readonly HashSet<string> Done = ["ACCEPTED", "APPROVED", "POSTED", "ACKNOWLEDGED", "BILLING_FAILED"];

    /// <summary>State from the latest live transaction for the meter this period; null means none yet.</summary>
    public static AssignmentState From(string? status, string? meterCondition) => status switch
    {
        null => AssignmentState.PENDING,
        "EXCEPTION" => AssignmentState.CHECKING,
        "REJECTED_BY_SUPERVISOR" => AssignmentState.READ_AGAIN,
        _ when Done.Contains(status) && meterCondition == "NOT_ACCESSIBLE" => AssignmentState.REVISIT, // BR-009
        _ when Done.Contains(status) => AssignmentState.SENT,
        _ => AssignmentState.PENDING,
    };

    public static bool CanCapture(this AssignmentState s) =>
        s is AssignmentState.PENDING or AssignmentState.READ_AGAIN or AssignmentState.REVISIT;
}

/// <summary>Spec BR-007 and BR-008: average consumption and the top of the expected range.</summary>
public static class ExpectedRange
{
    /// <summary>Source average, else history average, else the configured default for the type.</summary>
    public static decimal? Average(decimal? fromSource, decimal? fromHistory, string meterType, ReadingRulesOptions o) =>
        fromSource ?? fromHistory ?? (meterType == "IRRIGATION" ? o.DefaultAverageIrrigation : o.DefaultAverageSewerage);

    /// <summary>max(average × factor, floor). Null when there is no average: the app then gives no "much bigger" warning.</summary>
    public static decimal? High(decimal? average, string meterType, ReadingRulesOptions o)
    {
        if (average is null) return null;
        var floor = meterType == "IRRIGATION" ? o.HighConsumptionFloorIrrigation : o.HighConsumptionFloorSewerage;
        return Math.Max(Math.Round(average.Value * o.HighConsumptionFactor, 3), floor);
    }
}

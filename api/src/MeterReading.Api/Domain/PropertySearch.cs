namespace MeterReading.Api.Domain;

public enum DoneFilter { ALL, TO_READ, DONE }

/// <summary>
/// Find a Property (spec FR-021). Same matching as the Android app: case, spaces and dashes
/// are ignored and a match anywhere counts, so "1499w1" finds 1499-W1 and "149" finds 1497.
/// </summary>
public static class PropertySearch
{
    public static string Normalize(string? text) =>
        new string((text ?? "").Where(char.IsLetterOrDigit).ToArray()).ToUpperInvariant();

    public static bool Matches(string? candidate, string normalizedQuery) =>
        normalizedQuery.Length == 0 || Normalize(candidate).Contains(normalizedQuery, StringComparison.Ordinal);

    public static bool Keep(AssignmentState state, string meterType, DoneFilter done, string? type) =>
        (done switch
        {
            DoneFilter.TO_READ => state.CanCapture(),
            DoneFilter.DONE => !state.CanCapture(),
            _ => true,
        }) && (type is null || string.Equals(meterType, type, StringComparison.OrdinalIgnoreCase));
}

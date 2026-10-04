namespace MeterReading.Api.Endpoints;

/// <summary>RFC 9457 problem details with the machine-readable <c>code</c> the app switches on (spec Appendix A).</summary>
public static class Problems
{
    public static IResult Of(int status, string code, string title) =>
        Results.Problem(title: title, statusCode: status, extensions: new Dictionary<string, object?> { ["code"] = code });

    public static IResult ReaderNotFound() =>
        Of(StatusCodes.Status403Forbidden, "READER_NOT_FOUND", "Your account is not set up as an active meter reader. Ask your supervisor.");

    public static IResult NoOpenPeriod() =>
        Of(StatusCodes.Status409Conflict, "NO_OPEN_PERIOD", "There is no open reading period.");

    public static IResult PeriodNotFound(string code) =>
        Of(StatusCodes.Status404NotFound, "PERIOD_NOT_FOUND", $"Reading period {code} does not exist.");

    public static IResult Invalid(string title) =>
        Of(StatusCodes.Status400BadRequest, "VALIDATION_FAILED", title);
}

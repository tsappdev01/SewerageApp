using MeterReading.Api.Auth;
using MeterReading.Api.Contracts;
using MeterReading.Api.Data;
using MeterReading.Api.Domain;

namespace MeterReading.Api.Endpoints;

/// <summary>Submitting readings from the phone (spec §7, §14.1).</summary>
public static class ReadingEndpoints
{
    public static void MapReadingEndpoints(this IEndpointRouteBuilder app)
    {
        var api = app.MapGroup("/api/v1").RequireAuthorization(ReaderEndpoints.Policy).WithTags("Meter reader");
        api.MapPost("/readings", Submit)
            .WithSummary("Send one reading. 201 when stored, 200 when the same TransactionId was already stored (safe to retry).");
        api.MapPost("/properties/{propertyCode}/finalize", FinalizeProperty)
            .WithSummary("FR-037: once every meter of the property is read, write its readings to mr.MaintainFinalizedMeter. Again: a new set replaces it.");
        api.MapPost("/periods/current/close", ClosePeriod)
            .WithSummary("FR-037.13: Finalize month on the phone (behind the supervisor PIN): closes the open billing month for every reader. Safe to repeat.");
    }

    /// <summary>
    /// FR-037.13: closes the open period, as db/ops/close_reading_period.sql does, whatever is still unread or being
    /// checked (DIP 2026-10-28). No billing rows are written. Kept with the reader and the phone that closed it.
    /// </summary>
    private static async Task<IResult> ClosePeriod(CurrentReader current, MeterReadingRepository repo, ILoggerFactory loggers, CancellationToken ct)
    {
        var (reader, problem) = await current.ResolveAsync(ct);
        if (problem is not null) return problem;
        try
        {
            var period = await repo.GetOpenPeriodAsync(ct);
            if (period is null) return Problems.NoOpenPeriod();
            if (period.Status == "CLOSED") return Results.Ok(new ClosePeriodResponse(period.PeriodCode, AlreadyClosed: true));
            var by = current.DeviceId is { } device ? $"{reader!.ReaderId} (phone {device})" : reader!.ReaderId;
            await repo.ClosePeriodAsync(period.PeriodCode, by[..Math.Min(by.Length, 100)], "Finalize month from the app", ct);
            loggers.CreateLogger(Audit.Category).LogInformation("Period {PeriodCode} closed from the app by {ClosedBy}", period.PeriodCode, by);
            return Results.Ok(new ClosePeriodResponse(period.PeriodCode, AlreadyClosed: false));
        }
        catch (Microsoft.Data.SqlClient.SqlException e) when (e.Number == 208) // mr.ReadingPeriodStatus missing: db/024 not run
        {
            return Problems.Of(StatusCodes.Status503ServiceUnavailable, "FINALIZE_OFF", "Closing the month is not set up on the server yet (db/024). Tell IT.");
        }
    }

    private static async Task<IResult> FinalizeProperty(string propertyCode, CurrentReader current, FinalizeService service, CancellationToken ct)
    {
        if (propertyCode.Length is 0 or > 50) return Problems.Invalid("Property code must be 1 to 50 characters.");
        var (reader, problem) = await current.ResolveAsync(ct);
        if (problem is not null) return problem;
        try
        {
            var outcome = await service.FinalizeAsync(reader!, current.DeviceId, propertyCode, ct);
            return outcome.Result is { } result
                ? Results.Ok(result)
                : Problems.Of(outcome.ErrorStatus, outcome.ErrorCode!, outcome.ErrorTitle!);
        }
        catch (Microsoft.Data.SqlClient.SqlException e) when (e.Number == 208) // invalid object name: db/017 not run
        {
            return Problems.Of(StatusCodes.Status503ServiceUnavailable, "FINALIZE_OFF", "Finalizing is not set up on the server yet (db/017 to db/020). Tell IT.");
        }
    }

    private static async Task<IResult> Submit(SubmitReadingRequest request, CurrentReader current, SubmitService service, CancellationToken ct)
    {
        var (reader, problem) = await current.ResolveAsync(ct);
        if (problem is not null) return problem;

        // FR-002: the reading is kept with the phone that signed in, whatever the body says.
        if (current.DeviceId is { } device) request = request with { DeviceId = device };
        var outcome = await service.SubmitAsync(reader!, request, ct);
        if (outcome.Response is not { } response) return Problems.Of(outcome.ErrorStatus!.Value, outcome.ErrorCode!, outcome.ErrorTitle!);
        return outcome.Created
            ? Results.Created($"/api/v1/readings/{response.TransactionId}", response)
            : Results.Ok(response);
    }
}

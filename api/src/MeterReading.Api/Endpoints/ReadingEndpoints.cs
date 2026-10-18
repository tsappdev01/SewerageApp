using MeterReading.Api.Auth;
using MeterReading.Api.Contracts;
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

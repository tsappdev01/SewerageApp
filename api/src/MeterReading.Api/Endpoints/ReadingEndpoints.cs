using MeterReading.Api.Auth;
using MeterReading.Api.Contracts;
using MeterReading.Api.Domain;

namespace MeterReading.Api.Endpoints;

/// <summary>Submitting readings from the phone (spec §7, §14.1).</summary>
public static class ReadingEndpoints
{
    public static void MapReadingEndpoints(this IEndpointRouteBuilder app)
    {
        app.MapGroup("/api/v1").RequireAuthorization(ReaderEndpoints.Policy).WithTags("Meter reader")
            .MapPost("/readings", Submit)
            .WithSummary("Send one reading. 201 when stored, 200 when the same TransactionId was already stored (safe to retry).");
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

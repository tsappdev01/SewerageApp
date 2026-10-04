using MeterReading.Api.Auth;
using MeterReading.Api.Data;
using Microsoft.AspNetCore.RateLimiting;

namespace MeterReading.Api.Endpoints;

public sealed record RegisterDeviceRequest(string Code, string? Model = null, string? AndroidVersion = null, string? AppVersion = null);

public sealed record RegisterDeviceResponse(Guid DeviceId, string DeviceKey, string Label);

/// <summary>
/// POST /api/v1/devices/register (spec FR-002.1): a phone exchanges a one-time code from IT
/// (db/ops/new_device_code.sql) for its own id and secret key. No sign-in: the code is the proof.
/// The key is returned once and stored only as a hash.
/// </summary>
public static class DeviceEndpoints
{
    public const string RateLimitPolicy = "device-register";

    public static void MapDeviceEndpoints(this IEndpointRouteBuilder app)
    {
        app.MapPost("/api/v1/devices/register", Register)
            .AllowAnonymous()
            .RequireRateLimiting(RateLimitPolicy)
            .WithTags("Devices")
            .WithSummary("Register this phone with a one-time code from IT. Returns the phone's id and secret key.");
    }

    private static async Task<IResult> Register(RegisterDeviceRequest request, DeviceRepository devices, CancellationToken ct)
    {
        var code = DeviceKeys.NormalizeCode(request.Code ?? "");
        if (code.Length != 12) return Problems.Of(StatusCodes.Status422UnprocessableEntity, "REGISTRATION_CODE_INVALID", "The code has 12 letters and numbers, like ABCD-EFGH-JKLM.");
        var deviceId = Guid.NewGuid();
        var key = DeviceKeys.NewKey();
        var label = await devices.RegisterAsync(DeviceKeys.Hash(code), deviceId, DeviceKeys.Hash(key), request.Model, request.AndroidVersion, request.AppVersion, ct);
        return label is null
            ? Problems.Of(StatusCodes.Status422UnprocessableEntity, "REGISTRATION_CODE_INVALID", "This code is wrong, already used or out of date. Ask IT for a new one.")
            : Results.Created($"/api/v1/devices/{deviceId}", new RegisterDeviceResponse(deviceId, key, label));
    }
}

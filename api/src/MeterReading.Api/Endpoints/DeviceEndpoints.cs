using MeterReading.Api.Auth;
using MeterReading.Api.Data;
using Microsoft.AspNetCore.RateLimiting;

namespace MeterReading.Api.Endpoints;

public sealed record RegisterDeviceRequest(string Code, string? Model = null, string? AndroidVersion = null, string? AppVersion = null);

public sealed record RegisterDeviceResponse(Guid DeviceId, string DeviceKey, string Label);

public sealed record EnrolDeviceRequest(string AndroidId, string? Model = null, string? AndroidVersion = null, string? AppVersion = null);

public sealed record EnrolDeviceResponse(Guid DeviceId, string DeviceKey, string CheckCode, string Status);

/// <summary>What a phone learns about itself: PENDING, REGISTERED or REVOKED, and the reader IT gave it.</summary>
public sealed record DeviceStatusResponse(string Status, string? AndroidId, string? CheckCode, string? ReaderLogin, string? Label);

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
        app.MapPost("/api/v1/devices/enrol", Enrol)
            .AllowAnonymous()
            .RequireRateLimiting(RateLimitPolicy)
            .WithTags("Devices")
            .WithSummary("Ask IT to register this phone by its ANDROID_ID. Returns the phone's id, secret key and check code (PENDING).");
        app.MapGet("/api/v1/devices/status", Status)
            .AllowAnonymous()
            .WithTags("Devices")
            .WithSummary("This phone's registration (its X-Device-Id and X-Device-Key): PENDING, REGISTERED or REVOKED, and its reader.");
    }

    /// <summary>
    /// FR-002.8: the phone sends its ANDROID_ID; the server makes the phone's id, key and check code and keeps
    /// it PENDING. The ID is not secret, so it never opens the API by itself: the key does, once IT approves.
    /// </summary>
    private static async Task<IResult> Enrol(EnrolDeviceRequest request, DeviceRepository devices, CancellationToken ct)
    {
        if (!DeviceKeys.IsPhoneId(request.AndroidId))
            return Problems.Of(StatusCodes.Status422UnprocessableEntity, "VALIDATION_FAILED", "The phone's ID is missing or not valid.");
        var deviceId = Guid.NewGuid();
        var key = DeviceKeys.NewKey();
        var check = DeviceKeys.NewCheckCode();
        var stored = await devices.EnrolAsync(deviceId, request.AndroidId.Trim(), DeviceKeys.Hash(key), check, request.Model, request.AndroidVersion, request.AppVersion, ct);
        return stored
            ? Results.Created($"/api/v1/devices/{deviceId}", new EnrolDeviceResponse(deviceId, key, check, "PENDING"))
            : Problems.Of(StatusCodes.Status429TooManyRequests, "RATE_LIMITED", "This phone has asked too many times. Ask IT to look at it.");
    }

    /// <summary>Checked with the phone's own id and key, so a phone learns only about itself.</summary>
    private static async Task<IResult> Status(HttpContext http, DeviceRepository devices, CancellationToken ct)
    {
        var idText = http.Request.Headers[DeviceAuthHandler.IdHeader].ToString();
        var key = http.Request.Headers[DeviceAuthHandler.KeyHeader].ToString();
        var device = Guid.TryParse(idText, out var id) && key.Length > 0 ? await devices.FindAsync(id, ct) : null;
        if (device?.KeyHash is not { } hash || !DeviceKeys.Matches(key, hash))
            return Problems.Of(StatusCodes.Status403Forbidden, "DEVICE_NOT_REGISTERED", "This phone is not known. It asks again.");
        var status = device.IsRegistered ? "REGISTERED" : device.Status;
        return Results.Ok(new DeviceStatusResponse(status, device.AndroidId, device.CheckCode, device.IsRegistered ? device.BoundReaderLogin : null, device.Label));
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

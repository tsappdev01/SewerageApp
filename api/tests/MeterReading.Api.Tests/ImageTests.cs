using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text.Json;
using Microsoft.Data.SqlClient;

namespace MeterReading.Api.Tests;

/// <summary>Photo upload (spec FR-008, FR-009). Each test removes its own readings and photos.</summary>
[Collection(DatabaseCollection.Name)]
public sealed class ImageTests(ApiFactory factory) : IAsyncLifetime
{
    private const string Rashid = "rashid@dip.example";
    private const string Anil = "anil@dip.example";
    private readonly List<Guid> _readings = [];

    public Task InitializeAsync() => Task.CompletedTask;

    public async Task DisposeAsync()
    {
        if (_readings.Count == 0) return;
        await using var c = new SqlConnection(ApiFactory.ConnectionString);
        await c.OpenAsync();
        const string sql = """
            DECLARE @t TABLE (id uniqueidentifier);
            INSERT @t SELECT CAST([value] AS uniqueidentifier) FROM OPENJSON(@ids);
            DELETE d FROM mr.ReadingImageData d JOIN @t t ON d.BlobPath LIKE N'%/' + CAST(t.id AS nvarchar(36)) + N'/%';
            DELETE FROM mr.ReadingImage WHERE TransactionId IN (SELECT id FROM @t);
            DELETE FROM mr.ReadingTransaction WHERE TransactionId IN (SELECT id FROM @t);
            """;
        await using var cmd = new SqlCommand(sql, c);
        cmd.Parameters.AddWithValue("@ids", JsonSerializer.Serialize(_readings));
        await cmd.ExecuteNonQueryAsync();
    }

    private HttpClient As(string user)
    {
        var client = factory.CreateClient();
        client.DefaultRequestHeaders.Add("X-Dev-User", user);
        return client;
    }

    /// <summary>A small but real JPEG start (FF D8 FF) plus distinct bytes.</summary>
    private static byte[] Jpeg(byte seed = 1) => [0xFF, 0xD8, 0xFF, 0xE0, .. Enumerable.Repeat(seed, 2_000), 0xFF, 0xD9];

    private async Task<Guid> SendReading(string meterId, int photoCount = 1)
    {
        var id = Guid.NewGuid();
        _readings.Add(id);
        var response = await As(Rashid).PostAsJsonAsync("/api/v1/readings", new
        {
            transactionId = id, meterId, condition = "WORKING", newReading = 30_500, photoCount, tenantCode = DevTenants.Of(meterId),
            readerConfirmedWarning = false, capturedAtUtc = DateTime.UtcNow.AddMinutes(-2),
        });
        Assert.Equal(HttpStatusCode.Created, response.StatusCode);
        return id;
    }

    private Task<HttpResponseMessage> Upload(Guid transactionId, Guid imageId, byte[] bytes, string role = "DISPLAY", string? hash = null, string user = Rashid)
    {
        var content = new ByteArrayContent(bytes);
        content.Headers.ContentType = new MediaTypeHeaderValue("image/jpeg");
        var request = new HttpRequestMessage(HttpMethod.Put, $"/api/v1/readings/{transactionId}/images/{imageId}?role={role}&capturedAtUtc={DateTime.UtcNow.AddMinutes(-2):O}")
        {
            Content = content,
        };
        request.Headers.Add("X-Content-SHA256", hash ?? Convert.ToHexString(SHA256.HashData(bytes)));
        return As(user).SendAsync(request);
    }

    private static async Task<byte[]?> StoredBytes(string path)
    {
        await using var c = new SqlConnection(ApiFactory.ConnectionString);
        await c.OpenAsync();
        await using var cmd = new SqlCommand("SELECT ImageBytes FROM mr.ReadingImageData WHERE BlobPath = @path", c);
        cmd.Parameters.AddWithValue("@path", path);
        return (byte[]?)await cmd.ExecuteScalarAsync();
    }

    private static async Task<string> Code(HttpResponseMessage r) =>
        (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("code").GetString()!;

    [Fact]
    public async Task FR009_photo_is_stored_in_the_database_under_the_period_and_meter_and_can_be_read_back()
    {
        var reading = await SendReading("BC0003");
        var imageId = Guid.NewGuid();
        var bytes = Jpeg();

        var response = await Upload(reading, imageId, bytes);
        Assert.Equal(HttpStatusCode.Created, response.StatusCode);
        Assert.Equal(bytes, await StoredBytes($"readings/2026/10/BC0003/{reading}/{imageId}.jpg"));

        var back = await As(Rashid).GetByteArrayAsync($"/api/v1/readings/{reading}/images/{imageId}");
        Assert.Equal(bytes, back);
    }

    [Fact]
    public async Task Uploading_the_same_photo_again_is_safe()
    {
        var reading = await SendReading("BC0003");
        var imageId = Guid.NewGuid();
        Assert.Equal(HttpStatusCode.Created, (await Upload(reading, imageId, Jpeg())).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await Upload(reading, imageId, Jpeg())).StatusCode);
    }

    [Fact]
    public async Task A_different_photo_under_the_same_id_is_refused()
    {
        var reading = await SendReading("BC0003");
        var imageId = Guid.NewGuid();
        await Upload(reading, imageId, Jpeg(1));
        var response = await Upload(reading, imageId, Jpeg(2));
        Assert.Equal(HttpStatusCode.Conflict, response.StatusCode);
        Assert.Equal("IMAGE_ID_REUSED", await Code(response));
    }

    [Fact]
    public async Task FR009_damaged_photo_is_refused()
    {
        var reading = await SendReading("BC0003");
        var response = await Upload(reading, Guid.NewGuid(), Jpeg(), hash: new string('A', 64));
        Assert.Equal(HttpStatusCode.UnprocessableEntity, response.StatusCode);
        Assert.Equal("IMAGE_HASH_MISMATCH", await Code(response));
    }

    [Fact]
    public async Task Only_jpeg_is_accepted()
    {
        var reading = await SendReading("BC0003");
        var response = await Upload(reading, Guid.NewGuid(), "not a photo"u8.ToArray());
        Assert.Equal(HttpStatusCode.UnprocessableEntity, response.StatusCode);
        Assert.Equal("IMAGE_INVALID", await Code(response));
    }

    [Fact]
    public async Task Unknown_role_is_refused()
    {
        var reading = await SendReading("BC0003");
        var response = await Upload(reading, Guid.NewGuid(), Jpeg(), role: "SELFIE");
        Assert.Equal("INVALID_LOV_CODE", await Code(response));
    }

    [Fact]
    public async Task FR008_at_most_four_photos_per_reading()
    {
        var reading = await SendReading("BC0003", photoCount: 4);
        for (byte i = 1; i <= 4; i++) Assert.Equal(HttpStatusCode.Created, (await Upload(reading, Guid.NewGuid(), Jpeg(i))).StatusCode);
        var fifth = await Upload(reading, Guid.NewGuid(), Jpeg(9));
        Assert.Equal(HttpStatusCode.UnprocessableEntity, fifth.StatusCode);
        Assert.Equal("TOO_MANY_IMAGES", await Code(fifth));
    }

    [Fact]
    public async Task A_photo_for_someone_elses_reading_is_refused()
    {
        var reading = await SendReading("BC0003");
        var response = await Upload(reading, Guid.NewGuid(), Jpeg(), user: Anil);
        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
        Assert.Equal("READING_NOT_FOUND", await Code(response));
        Assert.Equal(HttpStatusCode.NotFound, (await As(Anil).GetAsync($"/api/v1/readings/{reading}/images/{Guid.NewGuid()}")).StatusCode);
    }

    [Fact]
    public async Task Too_large_a_photo_is_refused()
    {
        var reading = await SendReading("BC0003");
        var big = new byte[2_100_000];
        big[0] = 0xFF; big[1] = 0xD8; big[2] = 0xFF;
        var response = await Upload(reading, Guid.NewGuid(), big);
        Assert.Equal(HttpStatusCode.RequestEntityTooLarge, response.StatusCode);
    }

    [Fact]
    public async Task Readings_and_summary_show_photos_still_to_come()
    {
        var reading = await SendReading("BC0003", photoCount: 2);
        await Upload(reading, Guid.NewGuid(), Jpeg());

        var mine = await As(Rashid).GetFromJsonAsync<JsonElement>("/api/v1/readings/mine");
        var row = mine.EnumerateArray().Single(r => r.GetProperty("transactionId").GetGuid() == reading);
        Assert.Equal(2, row.GetProperty("photosExpected").GetInt32());
        Assert.Equal(1, row.GetProperty("photosReceived").GetInt32());

        var summary = await As(Rashid).GetFromJsonAsync<JsonElement>("/api/v1/summary");
        Assert.Equal(1, summary.GetProperty("photosWaiting").GetInt32());
    }

    [Fact]
    public async Task A_reading_cannot_promise_more_than_four_photos()
    {
        var id = Guid.NewGuid();
        _readings.Add(id);
        var response = await As(Rashid).PostAsJsonAsync("/api/v1/readings", new
        {
            transactionId = id, meterId = "BC0003", condition = "WORKING", newReading = 30_500, photoCount = 5, tenantCode = "T-0102",
            readerConfirmedWarning = false, capturedAtUtc = DateTime.UtcNow.AddMinutes(-2),
        });
        Assert.Equal(HttpStatusCode.UnprocessableEntity, response.StatusCode);
    }
}

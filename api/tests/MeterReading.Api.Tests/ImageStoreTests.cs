using MeterReading.Api.Data;
using Microsoft.Data.SqlClient;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Options;

namespace MeterReading.Api.Tests;

/// <summary>The photo stores themselves: a photo is never overwritten (FR-009.3).</summary>
[Collection(DatabaseCollection.Name)]
public sealed class ImageStoreTests : IAsyncLifetime
{
    private readonly string _prefix = $"test/{Guid.NewGuid():D}/";
    private readonly string _folder = Path.Combine(Path.GetTempPath(), "mr-images-" + Guid.NewGuid().ToString("N"));

    public Task InitializeAsync() => Task.CompletedTask;

    public async Task DisposeAsync()
    {
        await using var c = new SqlConnection(ApiFactory.ConnectionString);
        await c.OpenAsync();
        await using var cmd = new SqlCommand("DELETE FROM mr.ReadingImageData WHERE BlobPath LIKE @prefix + N'%'", c);
        cmd.Parameters.AddWithValue("@prefix", _prefix);
        await cmd.ExecuteNonQueryAsync();
        if (Directory.Exists(_folder)) Directory.Delete(_folder, recursive: true);
    }

    private static DatabaseImageStore Database()
    {
        var config = new ConfigurationBuilder()
            .AddInMemoryCollection(new Dictionary<string, string?> { ["ConnectionStrings:MeterReading"] = ApiFactory.ConnectionString })
            .Build();
        return new DatabaseImageStore(new SqlConnectionFactory(config, Options.Create(new SourceViewsOptions())));
    }

    private FileSystemImageStore Folder() => new(Options.Create(new ImageStoreOptions { Root = _folder }));

    private static async Task<byte[]?> Read(IImageStore store, string path)
    {
        await using var stream = await store.OpenAsync(path, CancellationToken.None);
        if (stream is null) return null;
        using var copy = new MemoryStream();
        await stream.CopyToAsync(copy);
        return copy.ToArray();
    }

    [Fact]
    public async Task FR009_database_store_keeps_the_first_copy()
    {
        var store = Database();
        var path = _prefix + "a.jpg";
        await store.SaveAsync(path, [1, 2, 3], CancellationToken.None);
        await store.SaveAsync(path, [9, 9, 9], CancellationToken.None);
        Assert.Equal([1, 2, 3], await Read(store, path));
    }

    [Fact]
    public async Task Database_store_saving_the_same_photo_at_once_is_safe()
    {
        var store = Database();
        var path = _prefix + "b.jpg";
        await Task.WhenAll(Enumerable.Range(0, 8).Select(_ => store.SaveAsync(path, [7, 7], CancellationToken.None)));
        Assert.Equal([7, 7], await Read(store, path));
    }

    [Fact]
    public async Task Database_store_has_nothing_for_an_unknown_path() =>
        Assert.Null(await Read(Database(), _prefix + "missing.jpg"));

    [Fact]
    public async Task FR009_folder_store_keeps_the_first_copy()
    {
        var store = Folder();
        await store.SaveAsync("readings/x/a.jpg", [1, 2, 3], CancellationToken.None);
        await store.SaveAsync("readings/x/a.jpg", [9, 9, 9], CancellationToken.None);
        Assert.Equal([1, 2, 3], await Read(store, "readings/x/a.jpg"));
    }

    [Fact]
    public async Task Folder_store_refuses_a_path_outside_its_folder() =>
        await Assert.ThrowsAsync<InvalidOperationException>(() => Folder().SaveAsync("../escape.jpg", [1], CancellationToken.None));
}

using Azure;
using Azure.Identity;
using Azure.Storage.Blobs;
using Azure.Storage.Blobs.Models;
using Microsoft.Extensions.Options;

namespace MeterReading.Api.Data;

/// <summary>
/// Keeps reading photos. Photos are never overwritten (FR-009.3): saving the same path again
/// keeps the first file, which is safe because the path holds the image id and the bytes were
/// checked against their SHA-256 first.
/// </summary>
public interface IImageStore
{
    Task SaveAsync(string path, byte[] bytes, CancellationToken ct);

    /// <summary>The photo's bytes, or null if the store does not have it.</summary>
    Task<Stream?> OpenAsync(string path, CancellationToken ct);
}

/// <summary>Photos under a folder, for on-premises servers and development.</summary>
public sealed class FileSystemImageStore(IOptions<ImageStoreOptions> options) : IImageStore
{
    private readonly string _root = Path.GetFullPath(options.Value.Root);

    public async Task SaveAsync(string path, byte[] bytes, CancellationToken ct)
    {
        var full = Resolve(path);
        if (File.Exists(full)) return;
        Directory.CreateDirectory(Path.GetDirectoryName(full)!);
        var temp = full + "." + Guid.NewGuid().ToString("N") + ".tmp";
        await File.WriteAllBytesAsync(temp, bytes, ct);
        try
        {
            File.Move(temp, full, overwrite: false);
        }
        catch (IOException) when (File.Exists(full))
        {
            File.Delete(temp); // another request saved it first
        }
    }

    public Task<Stream?> OpenAsync(string path, CancellationToken ct)
    {
        var full = Resolve(path);
        return Task.FromResult<Stream?>(File.Exists(full) ? File.OpenRead(full) : null);
    }

    /// <summary>Refuses any path that would leave the root folder.</summary>
    private string Resolve(string path)
    {
        var full = Path.GetFullPath(Path.Combine(_root, path));
        if (!full.StartsWith(_root + Path.DirectorySeparatorChar, StringComparison.Ordinal))
            throw new InvalidOperationException("Image path leaves the image folder.");
        return full;
    }
}

/// <summary>Photos in a private Azure Blob container, reached with the API's managed identity (SEC-003, SEC-005).</summary>
public sealed class AzureBlobImageStore : IImageStore
{
    private readonly BlobContainerClient _container;

    public AzureBlobImageStore(IOptions<ImageStoreOptions> options)
    {
        var o = options.Value;
        if (string.IsNullOrWhiteSpace(o.BlobServiceUri))
            throw new InvalidOperationException("ImageStore:BlobServiceUri is required when ImageStore:Kind is AzureBlob.");
        _container = new BlobServiceClient(new Uri(o.BlobServiceUri), new DefaultAzureCredential()).GetBlobContainerClient(o.Container);
    }

    public async Task SaveAsync(string path, byte[] bytes, CancellationToken ct)
    {
        try
        {
            await _container.GetBlobClient(path).UploadAsync(
                new BinaryData(bytes),
                new BlobUploadOptions
                {
                    HttpHeaders = new BlobHttpHeaders { ContentType = "image/jpeg" },
                    Conditions = new BlobRequestConditions { IfNoneMatch = ETag.All },
                },
                ct);
        }
        catch (RequestFailedException e) when (e.Status == 409 || e.ErrorCode == BlobErrorCode.BlobAlreadyExists)
        {
            // Already saved by an earlier attempt.
        }
    }

    public async Task<Stream?> OpenAsync(string path, CancellationToken ct)
    {
        try
        {
            return (await _container.GetBlobClient(path).DownloadStreamingAsync(cancellationToken: ct)).Value.Content;
        }
        catch (RequestFailedException e) when (e.Status == 404)
        {
            return null;
        }
    }
}

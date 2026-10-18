using MeterReading.Api.Data;
using Microsoft.Extensions.Caching.Memory;
using Microsoft.Extensions.Options;

namespace MeterReading.Api.Domain;

/// <summary>
/// Keeps what the meter list reads from PMS's views (meters with their properties and zones, tenants,
/// history averages) in memory for SourceViews:CacheSeconds (default 300). Reading those views can take
/// 15-30 s, longer than a phone waits; with this one read serves every phone. A load that a phone gave up
/// on still finishes and is kept, and SourceCacheWarmer refreshes the full list before it runs out.
/// Reading states never come from here: they are read live from mr.ReadingTransaction.
/// </summary>
public sealed class SourceCache(IMemoryCache cache, IOptions<SourceViewsOptions> options)
{
    private readonly SemaphoreSlim _gate = new(1, 1);

    public TimeSpan Lifetime => TimeSpan.FromSeconds(Math.Max(0, options.Value.CacheSeconds));

    public async Task<T> GetAsync<T>(string key, Func<CancellationToken, Task<T>> load, CancellationToken ct)
    {
        if (Lifetime == TimeSpan.Zero) return await load(ct);
        if (cache.TryGetValue(key, out T? hit)) return hit!;
        await _gate.WaitAsync(ct);
        try
        {
            if (cache.TryGetValue(key, out hit)) return hit!;
            // Not the caller's token: if the phone gives up, the load still finishes for the next one.
            var value = await load(CancellationToken.None);
            cache.Set(key, value, Lifetime);
            return value;
        }
        finally
        {
            _gate.Release();
        }
    }

    /// <summary>Loads again now and replaces what is kept (the warmer).</summary>
    public async Task RefreshAsync<T>(string key, Func<CancellationToken, Task<T>> load, CancellationToken ct)
    {
        if (Lifetime == TimeSpan.Zero) return;
        var value = await load(ct);
        cache.Set(key, value, Lifetime);
    }
}

/// <summary>Loads the full meter list from the views at start and again before the kept copy runs out.</summary>
public sealed class SourceCacheWarmer(IServiceScopeFactory scopes, SourceCache cache, ILogger<SourceCacheWarmer> log) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stop)
    {
        if (cache.Lifetime == TimeSpan.Zero) return;
        var every = TimeSpan.FromSeconds(Math.Max(30, cache.Lifetime.TotalSeconds * 0.8));
        while (!stop.IsCancellationRequested)
        {
            try
            {
                using var scope = scopes.CreateScope();
                var started = DateTime.UtcNow;
                await scope.ServiceProvider.GetRequiredService<ReaderService>().WarmAsync(stop);
                log.LogInformation("Meter list from the views kept for {Seconds} s (read in {Ms} ms).", cache.Lifetime.TotalSeconds, (int)(DateTime.UtcNow - started).TotalMilliseconds);
            }
            catch (OperationCanceledException) when (stop.IsCancellationRequested)
            {
                return;
            }
            catch (Exception e)
            {
                log.LogWarning("Could not read the meter list from the views ({Error}); phones read it directly.", e.Message);
            }
            try { await Task.Delay(every, stop); } catch (OperationCanceledException) { return; }
        }
    }
}

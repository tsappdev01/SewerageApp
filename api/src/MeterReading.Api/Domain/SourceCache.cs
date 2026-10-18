using MeterReading.Api.Data;
using Microsoft.Extensions.Caching.Memory;
using Microsoft.Extensions.Options;

namespace MeterReading.Api.Domain;

/// <summary>
/// Keeps what the meter list reads from PMS's views (meters with their properties and zones, tenants,
/// history averages) in memory, read again every SourceViews:CacheSeconds (default 300). Reading those views
/// can take over a minute, longer than a phone waits; with this one read serves every phone. A load that a
/// phone gave up on still finishes and is kept, SourceCacheWarmer reads the full list at start and keeps it
/// fresh, and an old copy is served while a new one is read.
/// Reading states never come from here: they are read live from mr.ReadingTransaction.
/// </summary>
public sealed class SourceCache(IMemoryCache cache, IOptions<SourceViewsOptions> options, ILogger<SourceCache> log)
{
    private readonly SemaphoreSlim _gate = new(1, 1);

    /// <summary>A kept copy and when it was read.</summary>
    private sealed record Entry(object Value, DateTime ReadAtUtc);

    public TimeSpan Lifetime => TimeSpan.FromSeconds(Math.Max(0, options.Value.CacheSeconds));

    /// <summary>
    /// The kept copy, at once, whatever its age: a copy older than <see cref="Lifetime"/> is read again in the
    /// background and replaced when that finishes, so no phone waits for the views once a first copy exists
    /// (UAT 2026-10-18: the views took over a minute and every phone gave up). Only the very first read waits.
    /// </summary>
    public async Task<T> GetAsync<T>(string key, Func<CancellationToken, Task<T>> load, CancellationToken ct)
    {
        if (Lifetime == TimeSpan.Zero) return await load(ct);
        if (cache.TryGetValue(key, out Entry? kept) && kept is not null)
        {
            if (DateTime.UtcNow - kept.ReadAtUtc > Lifetime) _ = RefreshInBackgroundAsync(key, load);
            return (T)kept.Value;
        }
        await _gate.WaitAsync(ct);
        try
        {
            if (cache.TryGetValue(key, out kept) && kept is not null) return (T)kept.Value;
            // Not the caller's token: if the phone gives up, the load still finishes for the next one.
            var value = await load(CancellationToken.None);
            Keep(key, value!);
            return value;
        }
        finally
        {
            _gate.Release();
        }
    }

    /// <summary>Loads again now and replaces what is kept (the warmer). On failure the old copy stays.</summary>
    public async Task RefreshAsync<T>(string key, Func<CancellationToken, Task<T>> load, CancellationToken ct)
    {
        if (Lifetime == TimeSpan.Zero) return;
        // Under the same gate as a first read, so a phone that comes while the warmer reads waits for that
        // read instead of starting a second one on the views.
        await _gate.WaitAsync(ct);
        try { Keep(key, (await load(ct))!); }
        finally { _gate.Release(); }
    }

    private int _refreshing;

    private async Task RefreshInBackgroundAsync<T>(string key, Func<CancellationToken, Task<T>> load)
    {
        if (Interlocked.Exchange(ref _refreshing, 1) == 1) return;
        try { Keep(key, (await load(CancellationToken.None))!); }
        catch (Exception e) { log.LogWarning("Could not read the meter list again ({Error}); the kept copy stays.", e.Message); }
        finally { Interlocked.Exchange(ref _refreshing, 0); }
    }

    // No expiry: an old copy is better than none; it is replaced, never dropped.
    private void Keep(string key, object value) =>
        cache.Set(key, new Entry(value, DateTime.UtcNow), new MemoryCacheEntryOptions { Priority = CacheItemPriority.NeverRemove });
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

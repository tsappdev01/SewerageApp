using System.Collections.Concurrent;
using Microsoft.Extensions.Logging;

namespace MeterReading.Api.Tests;

/// <summary>Keeps the lines logged under one category, for tests of what the API writes to its log.</summary>
public sealed class LogCapture(string category) : ILoggerProvider
{
    public ConcurrentQueue<string> Lines { get; } = new();

    public ILogger CreateLogger(string name) => name == category ? new Logger(Lines) : Microsoft.Extensions.Logging.Abstractions.NullLogger.Instance;

    public void Dispose() { }

    private sealed class Logger(ConcurrentQueue<string> lines) : ILogger
    {
        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;
        public bool IsEnabled(LogLevel level) => true;
        public void Log<TState>(LogLevel level, EventId id, TState state, Exception? e, Func<TState, Exception?, string> format) =>
            lines.Enqueue($"{level}: {format(state, e)}");
    }
}

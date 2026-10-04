using System.Security.Cryptography;
using System.Text;

namespace MeterReading.Api.Auth;

/// <summary>Registration codes and device keys (spec FR-002). Only their SHA-256 hashes are stored.</summary>
public static class DeviceKeys
{
    /// <summary>A code as typed: spaces and dashes removed, upper case (db/ops/new_device_code.sql hashes the same way).</summary>
    public static string NormalizeCode(string code) =>
        new string(code.Where(c => !char.IsWhiteSpace(c) && c != '-').ToArray()).ToUpperInvariant();

    /// <summary>Upper-case hex SHA-256 of the UTF-8 text, as SQL's CONVERT(char(64), HASHBYTES('SHA2_256', ...), 2).</summary>
    public static string Hash(string text) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(text)));

    /// <summary>A new secret device key: 32 random bytes, Base64Url.</summary>
    public static string NewKey() =>
        Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    /// <summary>Constant-time comparison of a presented key with the stored hash.</summary>
    public static bool Matches(string key, string storedHash) =>
        CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(Hash(key)), Encoding.ASCII.GetBytes(storedHash.Trim()));
}

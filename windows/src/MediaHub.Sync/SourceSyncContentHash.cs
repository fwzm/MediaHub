using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace MediaHub.Sync;

/// <summary>
/// Stable content hash of a <see cref="SourceRecord"/>, and the per-field split the delta digest needs.
/// </summary>
/// <remarks>
/// <para>
/// The hash is taken over a canonical JSON document, never over <c>GetHashCode()</c>. The runtime's string hash
/// is randomised per process, so a baseline written in one session would never match the next one and the
/// digest would report every source as changed on every start — exactly the bug that makes an incremental
/// protocol pointless.
/// </para>
/// <para>
/// Canonicalisation removes only the differences that are not changes:
/// </para>
/// <list type="bullet">
/// <item><description>field keys in alphabetical order;</description></item>
/// <item><description>the source type as its lowercase wire value;</description></item>
/// <item><description>endpoint URLs through <see cref="SyncUrlNormalizer"/>, so <c>HTTP://HOST:8096/emby/</c>
/// and <c>http://host:8096/emby</c> hash the same;</description></item>
/// <item><description>endpoints sorted by (sortOrder, url, isPrimary, enabled) instead of sender order;</description></item>
/// <item><description>an account of whitespace treated as absent, and compared case-insensitively because that
/// is how D5 compares it;</description></item>
/// </list>
/// <para>
/// A display name is hashed verbatim: renaming a source is a change the peer should see, even when only the
/// case moved.
/// </para>
/// </remarks>
public static class SourceSyncContentHash
{
    /// <summary>Whole-record hash: 64 lowercase hex characters.</summary>
    public static string Compute(SourceRecord record)
    {
        ArgumentNullException.ThrowIfNull(record);
        return Hash(Canonicalize(record));
    }

    /// <summary>One hash per <see cref="SourceSyncFields.All"/> entry, so the digest can name changed fields.</summary>
    public static IReadOnlyDictionary<string, string> ComputeFields(SourceRecord record)
    {
        ArgumentNullException.ThrowIfNull(record);

        var hashes = new Dictionary<string, string>(SourceSyncFields.All.Count, StringComparer.Ordinal);
        foreach (string field in SourceSyncFields.All)
        {
            hashes[field] = Hash(CanonicalField(field, record));
        }

        return hashes;
    }

    /// <summary>
    /// The canonical form the hash is taken over. Public because a failing round is far easier to debug by
    /// diffing two canonical strings than by comparing two hex digests.
    /// </summary>
    public static string Canonicalize(SourceRecord record)
    {
        ArgumentNullException.ThrowIfNull(record);

        using var buffer = new MemoryStream();
        using (var writer = new Utf8JsonWriter(buffer))
        {
            writer.WriteStartObject();
            foreach (string field in SourceSyncFields.All)
            {
                writer.WritePropertyName(field);
                WriteFieldValue(writer, field, record);
            }

            writer.WriteEndObject();
        }

        return Encoding.UTF8.GetString(buffer.ToArray());
    }

    private static string CanonicalField(string field, SourceRecord record)
    {
        using var buffer = new MemoryStream();
        using (var writer = new Utf8JsonWriter(buffer))
        {
            writer.WriteStartObject();
            writer.WritePropertyName(field);
            WriteFieldValue(writer, field, record);
            writer.WriteEndObject();
        }

        return Encoding.UTF8.GetString(buffer.ToArray());
    }

    private static void WriteFieldValue(Utf8JsonWriter writer, string field, SourceRecord record)
    {
        switch (field)
        {
            case SourceSyncFields.AccountScope:
                if (NormalizedScope(record.AccountScope) is { } scope)
                {
                    writer.WriteStringValue(scope);
                }
                else
                {
                    writer.WriteNullValue();
                }

                break;

            case SourceSyncFields.DisplayName:
                writer.WriteStringValue(record.DisplayName ?? string.Empty);
                break;

            case SourceSyncFields.Endpoints:
                writer.WriteStartArray();
                foreach (CanonicalEndpoint endpoint in CanonicalEndpoints(record))
                {
                    writer.WriteStartObject();
                    writer.WriteBoolean("enabled", endpoint.Enabled);
                    writer.WriteBoolean("isPrimary", endpoint.IsPrimary);
                    writer.WriteNumber("sortOrder", endpoint.SortOrder);
                    writer.WriteString("url", endpoint.Url);
                    writer.WriteEndObject();
                }

                writer.WriteEndArray();
                break;

            case SourceSyncFields.SourceType:
                // Wire, not Raw: two spellings of one type are one type (D3).
                writer.WriteStringValue(record.SourceType.Wire);
                break;

            default:
                writer.WriteNullValue();
                break;
        }
    }

    private static IEnumerable<CanonicalEndpoint> CanonicalEndpoints(SourceRecord record)
        => record.Endpoints
            .Where(endpoint => endpoint is not null)
            .Select(endpoint => new CanonicalEndpoint(
                endpoint.SortOrder,
                NormalizedUrl(endpoint.Url),
                endpoint.IsPrimary,
                endpoint.Enabled))
            .OrderBy(endpoint => endpoint.SortOrder)
            .ThenBy(endpoint => endpoint.Url, StringComparer.Ordinal)
            .ThenBy(endpoint => endpoint.IsPrimary ? 0 : 1)
            .ThenBy(endpoint => endpoint.Enabled ? 0 : 1);

    private static string? NormalizedScope(string? accountScope)
        => WireText.IsBlank(accountScope) ? null : WireText.LowercaseAscii(WireText.Trim(accountScope));

    private static string NormalizedUrl(string? url)
        => WireText.IsBlank(url) ? string.Empty : SyncUrlNormalizer.Normalize(url) ?? WireText.Trim(url);

    private static string Hash(string canonical)
        => Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(canonical)));

    private readonly record struct CanonicalEndpoint(int SortOrder, string Url, bool IsPrimary, bool Enabled);
}

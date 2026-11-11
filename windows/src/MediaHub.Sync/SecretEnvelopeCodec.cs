using System.Text;
using System.Text.Json;

namespace MediaHub.Sync;

/// <summary>
/// JSON form of <see cref="SecretEnvelope"/>: <c>{ scheme, scope, payloadBase64, protectedAtEpochMs }</c>.
/// </summary>
/// <remarks>
/// <para>
/// The codec validates on both directions. It is not a security boundary by itself (the bytes are
/// already sealed) but it is the boundary where a plaintext password would have to slip through, so
/// every rejection maps to a stable <see cref="SecretError"/> code.
/// </para>
/// </remarks>
public static class SecretEnvelopeCodec
{
    public const string SchemeField = "scheme";
    public const string ScopeField = "scope";
    public const string PayloadField = "payloadBase64";
    public const string ProtectedAtField = "protectedAtEpochMs";

    /// <summary>Serialises a valid envelope to compact JSON.</summary>
    public static string Serialize(SecretEnvelope envelope)
        => Encoding.UTF8.GetString(SerializeToBytes(envelope));

    /// <summary>Serialises a valid envelope to UTF-8 JSON bytes.</summary>
    public static byte[] SerializeToBytes(SecretEnvelope envelope)
    {
        ArgumentNullException.ThrowIfNull(envelope);
        envelope.Validate();

        using var buffer = new MemoryStream();
        using (var writer = new Utf8JsonWriter(buffer))
        {
            writer.WriteStartObject();
            writer.WriteString(SchemeField, envelope.Scheme);
            writer.WriteString(ScopeField, envelope.Scope);
            writer.WriteString(PayloadField, envelope.PayloadBase64);
            writer.WriteNumber(ProtectedAtField, envelope.ProtectedAtEpochMs);
            writer.WriteEndObject();
        }

        return buffer.ToArray();
    }

    /// <summary>Serialises a valid envelope into a detached <see cref="JsonElement"/> for <see cref="SourceRecord.Secrets"/>.</summary>
    public static JsonElement SerializeToElement(SecretEnvelope envelope)
    {
        using JsonDocument document = JsonDocument.Parse(SerializeToBytes(envelope));
        return document.RootElement.Clone();
    }

    /// <summary>Parses and validates JSON. Throws <see cref="SecretException"/> on any violation.</summary>
    public static SecretEnvelope Deserialize(string json)
    {
        if (!TryParse(json, out SecretEnvelope? envelope, out string? reason))
        {
            throw new SecretException(reason!, "The secret block is not a valid sealed envelope.");
        }

        return envelope!;
    }

    /// <summary>Parses and validates JSON without throwing.</summary>
    public static bool TryParse(string? json, out SecretEnvelope? envelope, out string? reason)
    {
        envelope = null;
        if (string.IsNullOrWhiteSpace(json))
        {
            reason = SecretError.MalformedJson;
            return false;
        }

        try
        {
            using JsonDocument document = JsonDocument.Parse(json);
            return TryParse(document.RootElement, out envelope, out reason);
        }
        catch (JsonException)
        {
            reason = SecretError.MalformedJson;
            return false;
        }
    }

    /// <summary>Parses and validates a detached element without throwing. Unknown fields are ignored.</summary>
    public static bool TryParse(JsonElement element, out SecretEnvelope? envelope, out string? reason)
    {
        envelope = null;
        if (element.ValueKind != JsonValueKind.Object)
        {
            reason = SecretError.MalformedJson;
            return false;
        }

        string? scheme = null;
        string? scope = null;
        string? payload = null;
        long protectedAt = 0;

        foreach (JsonProperty property in element.EnumerateObject())
        {
            switch (property.Name)
            {
                case SchemeField when property.Value.ValueKind == JsonValueKind.String:
                    scheme = property.Value.GetString();
                    break;
                case ScopeField when property.Value.ValueKind == JsonValueKind.String:
                    scope = property.Value.GetString();
                    break;
                case PayloadField when property.Value.ValueKind == JsonValueKind.String:
                    payload = property.Value.GetString();
                    break;
                case ProtectedAtField when property.Value.ValueKind == JsonValueKind.Number:
                    protectedAt = property.Value.GetInt64();
                    break;
            }
        }

        var candidate = new SecretEnvelope
        {
            Scheme = scheme ?? "",
            Scope = scope ?? "",
            PayloadBase64 = payload ?? "",
            ProtectedAtEpochMs = protectedAt,
        };

        try
        {
            envelope = candidate.Validate();
            reason = null;
            return true;
        }
        catch (SecretException error)
        {
            reason = error.Reason;
            return false;
        }
    }
}

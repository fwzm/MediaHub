using System.Globalization;
using System.Text;
using System.Text.Json;

namespace MediaHub.Sync;

/// <summary>
/// The stable, machine-readable reasons a pairing attempt can be refused (ADR-S002).
/// </summary>
/// <remarks>
/// <para>
/// These strings are contract, not prose: the UI translates them, the logs group by them, and the ADR table is
/// generated from them. Never reword one — add a new code instead. Two shapes live here: the seven
/// <see cref="All"/> codes that a pairing decision reports, and the field-level
/// <c>missing-field:</c>/<c>invalid-field:</c> reasons a decoder uses to say which field was wrong
/// (see <see cref="PairingPayloadCodec"/>).
/// </para>
/// </remarks>
public static class PairingReasons
{
    /// <summary>The payload announces a schema version this build does not implement.</summary>
    public const string UnsupportedVersion = "unsupported-version";

    /// <summary>The public-key fingerprint is not a SHA-256 digest in hex.</summary>
    public const string BadFingerprint = "bad-fingerprint";

    /// <summary>The challenge is not thirty-two bytes in canonical base64.</summary>
    public const string BadChallenge = "bad-challenge";

    /// <summary>The ephemeral address is not a plain http(s) URL, or tries to carry credentials in it.</summary>
    public const string BadUrl = "bad-url";

    /// <summary>The invitation was past its expiry when it was used.</summary>
    public const string Expired = "expired";

    /// <summary>The challenge had already been spent; an invitation is worth exactly one pairing.</summary>
    public const string ChallengeReplayed = "challenge-replayed";

    /// <summary>The peer answered a different challenge than the one this device issued.</summary>
    public const string ChallengeMismatch = "challenge-mismatch";

    /// <summary>The seven refusal codes a pairing decision reports, in the order the ADR documents them.</summary>
    public static IReadOnlyList<string> All { get; } =
    [
        UnsupportedVersion,
        BadFingerprint,
        BadChallenge,
        BadUrl,
        Expired,
        ChallengeReplayed,
        ChallengeMismatch,
    ];

    /// <summary>The document could not be parsed as JSON at all.</summary>
    public const string MalformedJson = "malformed-json";

    internal static SyncContractException MissingField(string field)
        => new("missing-field:" + field, "The required pairing field '" + field + "' is missing or empty.");

    internal static SyncContractException InvalidField(string field, string detail)
        => new("invalid-field:" + field, "The pairing field '" + field + "' " + detail);
}

/// <summary>
/// Everything one device puts in front of the other to start a pairing: the six fields of the QR payload
/// (ADR-S001 D6, ADR-S002).
/// </summary>
/// <remarks>
/// <para>
/// The field set is the security boundary, so it is deliberately the whole story: a schema version, a device
/// identity, a temporary address, a one-shot challenge, a public-key fingerprint, and an expiry. There is no
/// slot for a password, a token, or a long-lived key, and there never will be — a QR code is photographed,
/// pasted into chats, and left on screens, so anything that passes through it must be worthless by the time
/// anyone else can read it. Adding a field here is an ADR-level decision, not a code change.
/// </para>
/// <para>
/// The payload is pure data: no clock, no socket, no file. Expiry is compared against a timestamp the caller
/// supplies (<see cref="ValidateFor"/>), which is what makes every rule in this layer testable as a function
/// of its inputs.
/// </para>
/// <para>
/// <see cref="EphemeralUrl"/> may still be plain HTTP: TLS 1.3 with certificate pinning is W6-3b. Until then
/// the invitation is only offered on a user-permitted private interface, the challenge stays single-use, and
/// the fingerprint is shown on both screens for a human to compare.
/// </para>
/// </remarks>
public sealed record PairingPayload
{
    /// <summary>Schema version this build writes and the only version it reads.</summary>
    public const int CurrentSchemaVersion = 1;

    /// <summary>How long an invitation stays usable, in seconds (ADR-S001 D6: 120 s).</summary>
    public const int LifetimeSeconds = 120;

    /// <summary>How long an invitation stays usable, in milliseconds.</summary>
    public const long LifetimeMilliseconds = LifetimeSeconds * 1000L;

    /// <summary>Wire schema version of this payload.</summary>
    public int SchemaVersion { get; init; } = CurrentSchemaVersion;

    /// <summary>Identifier of the device that produced the payload. Not a secret; it is shown on both screens.</summary>
    public string DeviceId { get; init; } = "";

    /// <summary>Temporary address the peer should reach, advertised only for the pairing window.</summary>
    public string EphemeralUrl { get; init; } = "";

    /// <summary>Thirty-two random bytes, canonical base64 (see <see cref="PairingChallenge"/>).</summary>
    public string Challenge { get; init; } = "";

    /// <summary>SHA-256 of the device's public key, sixty-four lowercase hex characters (see <see cref="PairingFingerprint"/>).</summary>
    public string PublicKeyFingerprint { get; init; } = "";

    /// <summary>Wall-clock instant, in milliseconds since the Unix epoch, after which the invitation is dead.</summary>
    public long ExpiresAtEpochMs { get; init; }

    /// <summary>Builds a payload whose invitation expires <see cref="LifetimeMilliseconds"/> after <paramref name="nowEpochMs"/>.</summary>
    public static PairingPayload Create(
        string deviceId,
        string ephemeralUrl,
        PairingChallenge challenge,
        PairingFingerprint publicKeyFingerprint,
        long nowEpochMs)
    {
        var payload = new PairingPayload
        {
            SchemaVersion = CurrentSchemaVersion,
            DeviceId = deviceId,
            EphemeralUrl = ephemeralUrl,
            Challenge = challenge.Base64,
            PublicKeyFingerprint = publicKeyFingerprint.Hex,
            ExpiresAtEpochMs = nowEpochMs + LifetimeMilliseconds,
        };

        payload.ValidateShape();
        return payload;
    }

    /// <summary>The challenge as a value.</summary>
    /// <exception cref="SyncContractException">The field is not a challenge (<c>bad-challenge</c>).</exception>
    public PairingChallenge ParseChallenge() => PairingChallenge.Parse(Challenge);

    /// <summary>The fingerprint as a value.</summary>
    /// <exception cref="SyncContractException">The field is not a fingerprint (<c>bad-fingerprint</c>).</exception>
    public PairingFingerprint ParseFingerprint() => PairingFingerprint.Parse(PublicKeyFingerprint);

    /// <summary>Validates everything that does not depend on the current time.</summary>
    /// <exception cref="SyncContractException">The payload does not satisfy the contract.</exception>
    public void ValidateShape()
    {
        if (SchemaVersion != CurrentSchemaVersion)
        {
            // Refuse, do not guess: a shape we do not know could carry a field this build would silently drop.
            throw new SyncContractException(
                PairingReasons.UnsupportedVersion,
                "Pairing payload schema version " + SchemaVersion.ToString(CultureInfo.InvariantCulture)
                    + "; this build reads version " + CurrentSchemaVersion.ToString(CultureInfo.InvariantCulture) + ".");
        }

        if (string.IsNullOrWhiteSpace(DeviceId))
        {
            throw PairingReasons.MissingField("deviceId");
        }

        ValidateEphemeralUrl(EphemeralUrl);
        _ = PairingChallenge.Parse(Challenge);
        _ = PairingFingerprint.Parse(PublicKeyFingerprint);

        if (ExpiresAtEpochMs <= 0)
        {
            throw PairingReasons.InvalidField("expiresAtEpochMs", "must be a positive Unix epoch timestamp in milliseconds.");
        }
    }

    /// <summary>Validates the payload including its expiry against a caller-supplied clock.</summary>
    /// <exception cref="SyncContractException">The payload does not satisfy the contract, or has expired.</exception>
    public void ValidateFor(long nowEpochMs)
    {
        ValidateShape();
        if (ExpiresAtEpochMs <= nowEpochMs)
        {
            throw new SyncContractException(
                PairingReasons.Expired,
                "The pairing invitation expired at " + ExpiresAtEpochMs.ToString(CultureInfo.InvariantCulture)
                    + " ms; the current time is " + nowEpochMs.ToString(CultureInfo.InvariantCulture) + " ms.");
        }
    }

    /// <summary>How much of the invitation's lifetime is left, floored at zero.</summary>
    public long RemainingMilliseconds(long nowEpochMs) => Math.Max(0, ExpiresAtEpochMs - nowEpochMs);

    private static void ValidateEphemeralUrl(string? url)
    {
        if (string.IsNullOrWhiteSpace(url))
        {
            throw PairingReasons.MissingField("ephemeralUrl");
        }

        if (!Uri.TryCreate(url, UriKind.Absolute, out Uri? uri)
            || (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps)
            || string.IsNullOrEmpty(uri.Host))
        {
            throw new SyncContractException(
                PairingReasons.BadUrl,
                "The ephemeral address must be an absolute http or https URL with a host; '" + url + "' is not.");
        }

        if (!string.IsNullOrEmpty(uri.UserInfo))
        {
            throw new SyncContractException(
                PairingReasons.BadUrl,
                "The ephemeral address must not carry user information ('user:password@'); the pairing payload never transports credentials.");
        }

        if (!string.IsNullOrEmpty(uri.Query) || !string.IsNullOrEmpty(uri.Fragment))
        {
            throw new SyncContractException(
                PairingReasons.BadUrl,
                "The ephemeral address must not carry a query string or fragment; that is where a token would be smuggled past D6.");
        }
    }
}

/// <summary>
/// Reads and writes <see cref="PairingPayload"/> as compact JSON — the exact text that goes into the QR code.
/// </summary>
/// <remarks>
/// <para>
/// Writing emits the six contract fields and nothing else, in a fixed order, defaults included: the QR is read
/// by a different build on a different platform, so the output has to be stable and diffable rather than
/// minimal. Reading ignores unknown properties (a newer sender must not break an older reader) and refuses
/// anything it cannot interpret, because a half-built payload would later be compared against a real device.
/// </para>
/// <para>
/// There is no secret in this document, by construction: <see cref="Serialize"/> has no code path that could
/// write a field outside the six, so "no passwords, no tokens in the QR" is a property of the shape rather than
/// a rule someone has to remember.
/// </para>
/// </remarks>
public static class PairingPayloadCodec
{
    /// <summary>Serialises <paramref name="payload"/> to compact UTF-8 JSON.</summary>
    /// <exception cref="SyncContractException">The payload does not satisfy the contract.</exception>
    public static string Serialize(PairingPayload payload)
    {
        ArgumentNullException.ThrowIfNull(payload);
        payload.ValidateShape();

        using var buffer = new MemoryStream();
        using (var writer = new Utf8JsonWriter(buffer))
        {
            writer.WriteStartObject();
            writer.WriteNumber("schemaVersion", payload.SchemaVersion);
            writer.WriteString("deviceId", payload.DeviceId);
            writer.WriteString("ephemeralUrl", payload.EphemeralUrl);
            writer.WriteString("challenge", payload.Challenge);
            writer.WriteString("publicKeyFingerprint", payload.PublicKeyFingerprint);
            writer.WriteNumber("expiresAtEpochMs", payload.ExpiresAtEpochMs);
            writer.WriteEndObject();
        }

        return Encoding.UTF8.GetString(buffer.ToArray());
    }

    /// <summary>Parses a pairing payload, validating everything that does not depend on the clock.</summary>
    /// <exception cref="SyncContractException">The document is not a readable pairing payload.</exception>
    public static PairingPayload Deserialize(string json)
    {
        if (string.IsNullOrWhiteSpace(json))
        {
            throw new SyncContractException(PairingReasons.MalformedJson, "The pairing payload is empty.");
        }

        JsonDocument document;
        try
        {
            document = JsonDocument.Parse(json);
        }
        catch (JsonException ex)
        {
            throw new SyncContractException(PairingReasons.MalformedJson, "The pairing payload is not valid JSON: " + ex.Message);
        }

        using (document)
        {
            JsonElement root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object)
            {
                throw new SyncContractException(PairingReasons.MalformedJson, "The pairing payload must be a JSON object.");
            }

            // Version first: a document from a newer build should say so, not complain about a field rename.
            int schemaVersion = ReadRequiredInt32(root, "schemaVersion");
            if (schemaVersion != PairingPayload.CurrentSchemaVersion)
            {
                throw new SyncContractException(
                    PairingReasons.UnsupportedVersion,
                    "Pairing payload schema version " + schemaVersion.ToString(CultureInfo.InvariantCulture)
                        + "; this build reads version " + PairingPayload.CurrentSchemaVersion.ToString(CultureInfo.InvariantCulture) + ".");
            }

            var payload = new PairingPayload
            {
                SchemaVersion = schemaVersion,
                DeviceId = ReadRequiredString(root, "deviceId"),
                EphemeralUrl = ReadRequiredString(root, "ephemeralUrl"),
                Challenge = ReadRequiredString(root, "challenge"),
                PublicKeyFingerprint = ReadRequiredString(root, "publicKeyFingerprint"),
                ExpiresAtEpochMs = ReadRequiredInt64(root, "expiresAtEpochMs"),
            };

            payload.ValidateShape();

            // Canonicalise the two encoded values on the way in. Hex case and base64 spelling are details of the
            // text we received, not of the key; normalising here means one key has one representation, so a
            // re-serialised payload is byte-identical to the one we would have written and a fingerprint seen
            // twice cannot look like two different peers.
            return payload with
            {
                PublicKeyFingerprint = payload.ParseFingerprint().Hex,
                Challenge = payload.ParseChallenge().Base64,
            };
        }
    }

    /// <summary>Parses a pairing payload, returning false and the reason instead of throwing.</summary>
    public static bool TryDeserialize(string? json, out PairingPayload? payload, out string? error)
    {
        payload = null;
        error = null;
        try
        {
            payload = Deserialize(json!);
            return true;
        }
        catch (SyncContractException ex)
        {
            error = ex.Reason + ": " + ex.Message;
            return false;
        }
    }

    private static JsonElement ReadRequiredProperty(JsonElement owner, string name)
    {
        if (!owner.TryGetProperty(name, out JsonElement value) || value.ValueKind == JsonValueKind.Null)
        {
            throw PairingReasons.MissingField(name);
        }

        return value;
    }

    private static string ReadRequiredString(JsonElement owner, string name)
    {
        JsonElement value = ReadRequiredProperty(owner, name);
        if (value.ValueKind != JsonValueKind.String)
        {
            throw PairingReasons.InvalidField(name, "must be a string.");
        }

        string? text = value.GetString();
        if (string.IsNullOrWhiteSpace(text))
        {
            throw PairingReasons.MissingField(name);
        }

        return text;
    }

    private static int ReadRequiredInt32(JsonElement owner, string name)
    {
        JsonElement value = ReadRequiredProperty(owner, name);
        if (value.ValueKind != JsonValueKind.Number || !value.TryGetInt32(out int number))
        {
            throw PairingReasons.InvalidField(name, "must be a 32-bit integer.");
        }

        return number;
    }

    private static long ReadRequiredInt64(JsonElement owner, string name)
    {
        JsonElement value = ReadRequiredProperty(owner, name);
        if (value.ValueKind != JsonValueKind.Number || !value.TryGetInt64(out long number))
        {
            throw PairingReasons.InvalidField(name, "must be a 64-bit integer.");
        }

        return number;
    }
}

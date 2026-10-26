using System;
using System.Collections.Generic;
using System.Text;

namespace MediaHub.Sync;

/// <summary>
/// Stable, machine-readable reasons a manual pairing code can be refused (W6-3c).
/// </summary>
/// <remarks>
/// <para>
/// A manual code is what the QR code becomes on a device without a camera: the same six-field payload, typed or
/// pasted instead of photographed. Its failure modes are therefore the payload's failure modes plus the two ways
/// text can arrive broken — it is not base64url, or it is longer than any pairing code could be.
/// </para>
/// <para>
/// The set is deliberately split in two. The four <see cref="PairingCodeReasons"/> values declared here describe
/// the envelope (the text itself); the aliases below point at the payload codes that already exist, so a caller
/// can switch on one list without this file and <see cref="PairingReasons"/> drifting apart. Adding a code is
/// allowed; rewording one is a breaking change for the UI text table and the tests that pin it.
/// </para>
/// </remarks>
public static class PairingCodeReasons
{
    /// <summary>The box was empty, or held only whitespace.</summary>
    public const string Empty = "code-empty";

    /// <summary>The text is not base64url: it contains characters this alphabet cannot carry.</summary>
    public const string NotBase64Url = "not-base64url";

    /// <summary>The text is longer than <see cref="PairingCode.MaximumLength"/>; that is not a pairing code.</summary>
    public const string TooLong = "code-too-long";

    /// <summary>The decoded bytes are not a readable pairing payload (not UTF-8, not JSON, or missing fields).</summary>
    public const string PayloadInvalid = "payload-invalid";

    /// <summary>The payload announces a schema version this build does not implement (<see cref="PairingReasons.UnsupportedVersion"/>).</summary>
    public const string UnsupportedVersion = PairingReasons.UnsupportedVersion;

    /// <summary>The payload's challenge is not thirty-two bytes in canonical base64 (<see cref="PairingReasons.BadChallenge"/>).</summary>
    public const string BadChallenge = PairingReasons.BadChallenge;

    /// <summary>The payload's fingerprint is not a SHA-256 digest in hex (<see cref="PairingReasons.BadFingerprint"/>).</summary>
    public const string BadFingerprint = PairingReasons.BadFingerprint;

    /// <summary>The payload's ephemeral address is not a plain http(s) URL (<see cref="PairingReasons.BadUrl"/>).</summary>
    public const string BadUrl = PairingReasons.BadUrl;

    /// <summary>The invitation had already expired when the code was used (<see cref="PairingReasons.Expired"/>).</summary>
    public const string Expired = PairingReasons.Expired;

    /// <summary>Every reason a manual code can report, in the order this file declares them.</summary>
    /// <remarks>
    /// Tests assert against this list, so a new code cannot appear without the UI text table and the docs being
    /// updated in the same change.
    /// </remarks>
    public static IReadOnlyList<string> All { get; } =
    [
        Empty,
        NotBase64Url,
        TooLong,
        PayloadInvalid,
        UnsupportedVersion,
        BadChallenge,
        BadFingerprint,
        BadUrl,
        Expired,
    ];
}

/// <summary>
/// The outcome of decoding a manual pairing code: either the payload, or a stable reason and a sentence that is
/// safe to show.
/// </summary>
/// <remarks>
/// <see cref="Detail"/> never contains the code, the challenge, or any field value that came out of the code. A
/// pairing challenge is a one-shot nonce: a code that reaches a log line is a code that can be replayed inside its
/// two-minute window, so the reason string — not the input — is what travels.
/// </remarks>
public readonly record struct PairingCodeResult
{
    private PairingCodeResult(PairingPayload? payload, string? reason, string? detail)
    {
        Payload = payload;
        Reason = reason;
        Detail = detail;
    }

    /// <summary>The decoded payload, or null when the code was refused.</summary>
    public PairingPayload? Payload { get; }

    /// <summary>The stable reason code (one of <see cref="PairingCodeReasons.All"/>), or null on success.</summary>
    public string? Reason { get; }

    /// <summary>A sentence explaining the refusal in the user's words; empty on success.</summary>
    public string? Detail { get; }

    /// <summary>True when a payload came out of the code.</summary>
    public bool Succeeded => Payload is not null;

    /// <summary>A decoded payload.</summary>
    public static PairingCodeResult Success(PairingPayload payload)
    {
        ArgumentNullException.ThrowIfNull(payload);
        return new PairingCodeResult(payload, null, null);
    }

    /// <summary>A refusal carrying one of <see cref="PairingCodeReasons.All"/>.</summary>
    /// <exception cref="ArgumentException"><paramref name="reason"/> is empty; an unreasoned refusal is a bug.</exception>
    public static PairingCodeResult Failure(string reason, string detail)
    {
        if (string.IsNullOrWhiteSpace(reason))
        {
            throw new ArgumentException("A refused pairing code must carry a reason code.", nameof(reason));
        }

        return new PairingCodeResult(null, reason, detail);
    }

    /// <inheritdoc />
    public override string ToString() => Succeeded
        ? "pairing-code: decoded (" + Payload!.DeviceId + ")"
        : "pairing-code: refused (" + Reason + ")";
}

/// <summary>
/// Turns a <see cref="PairingPayload"/> into the short text a person can read out, paste into a chat, or type on the
/// other machine — and back (W6-3c).
/// </summary>
/// <remarks>
/// <para>
/// The code is the payload's canonical JSON, base64url-encoded without padding: one line, no characters that a
/// chat client, a URL field, or a phone keyboard would re-encode. Padding is not emitted and not accepted —
/// <c>'='</c> is exactly the character that grows a trailing space when it is copied by hand, and an encoder that
/// sometimes pads and sometimes does not produces two spellings of one invitation.
/// </para>
/// <para>
/// Decoding is strict on purpose: wrong alphabet, wrong length, wrong version, wrong shape, and an expired
/// invitation are all refusals with a stable code, never a repair. The one thing it tolerates is layout — spaces,
/// tabs and newlines are stripped first, because a code pasted out of a chat window arrives wrapped.
/// </para>
/// <para>
/// Expiry is compared against a caller-supplied <c>nowEpochMs</c>; this type reads no clock. That is what makes the
/// boundary ("one millisecond before expiry still works") a testable statement instead of a sleep.
/// </para>
/// <para>
/// No secret is added here and none is printed: the input never appears in a message, and the payload's own
/// <see cref="PairingPayload.ToString"/> already redacts the challenge.
/// </para>
/// </remarks>
public static class PairingCode
{
    /// <summary>The longest text accepted as a pairing code, after whitespace is removed.</summary>
    /// <remarks>
    /// A six-field payload is a few hundred bytes, so a few hundred characters of base64url. Four thousand is
    /// generous for a payload and hostile to anything else — pasting a document into the box must fail fast rather
    /// than be decoded.
    /// </remarks>
    public const int MaximumLength = 4096;

    private static readonly UTF8Encoding StrictUtf8 = new(encoderShouldEmitUTF8Identifier: false, throwOnInvalidBytes: true);

    /// <summary>Encodes <paramref name="payload"/> as a base64url code without padding.</summary>
    /// <exception cref="SyncContractException">The payload does not satisfy the contract.</exception>
    public static string Encode(PairingPayload payload)
    {
        ArgumentNullException.ThrowIfNull(payload);

        // Serialize validates the shape first, so a code is never produced from an invitation that could not be read.
        string json = PairingPayloadCodec.Serialize(payload);
        return ToBase64Url(Encoding.UTF8.GetBytes(json));
    }

    /// <summary>Strips the whitespace a paste adds, leaving the characters that matter.</summary>
    /// <remarks>
    /// Only spaces, tabs, carriage returns and newlines are removed. Anything else is left in place so
    /// <see cref="Decode"/> can refuse it with <see cref="PairingCodeReasons.NotBase64Url"/> instead of silently
    /// dropping part of a code a user is trying to read out.
    /// </remarks>
    public static string Normalize(string? code)
    {
        if (string.IsNullOrEmpty(code))
        {
            return "";
        }

        var builder = new StringBuilder(code.Length);
        foreach (char character in code)
        {
            if (character is ' ' or '\t' or '\r' or '\n')
            {
                continue;
            }

            builder.Append(character);
        }

        return builder.ToString();
    }

    /// <summary>Decodes a manual code, refusing everything that is not a live invitation.</summary>
    /// <param name="code">The text the user pasted; whitespace is tolerated.</param>
    /// <param name="nowEpochMs">The caller's clock, in milliseconds since the Unix epoch.</param>
    public static PairingCodeResult Decode(string? code, long nowEpochMs)
    {
        string normalized = Normalize(code);
        if (normalized.Length == 0)
        {
            return PairingCodeResult.Failure(PairingCodeReasons.Empty, "配对码是空的：请粘贴对方显示的配对码。");
        }

        if (normalized.Length > MaximumLength)
        {
            return PairingCodeResult.Failure(
                PairingCodeReasons.TooLong,
                "这段文本有 " + normalized.Length + " 个字符，超过配对码上限 " + MaximumLength + " 个字符，可能不是配对码。");
        }

        foreach (char character in normalized)
        {
            if (!char.IsAsciiLetterOrDigit(character) && character is not ('-' or '_'))
            {
                return PairingCodeResult.Failure(
                    PairingCodeReasons.NotBase64Url,
                    "配对码含有 base64url 之外的字符，复制时可能混入了其它内容。");
            }
        }

        if (!TryFromBase64Url(normalized, out byte[] bytes))
        {
            return PairingCodeResult.Failure(
                PairingCodeReasons.NotBase64Url,
                "配对码不是合法的 base64url 文本（长度不完整），请重新复制。");
        }

        string json;
        try
        {
            json = StrictUtf8.GetString(bytes);
        }
        catch (ArgumentException)
        {
            return PairingCodeResult.Failure(
                PairingCodeReasons.PayloadInvalid,
                "配对码解出的内容不是 UTF-8 文本，可能复制时缺少一段。");
        }

        PairingPayload payload;
        try
        {
            // Deserialize, not TryDeserialize: TryDeserialize folds the exception message into its error string,
            // and those messages quote field values (a bad challenge is echoed verbatim). Only the reason code
            // leaves this method.
            payload = PairingPayloadCodec.Deserialize(json);
        }
        catch (SyncContractException ex)
        {
            return PairingCodeResult.Failure(MapPayloadReason(ex.Reason), DetailFor(ex.Reason));
        }

        try
        {
            payload.ValidateFor(nowEpochMs);
        }
        catch (SyncContractException ex)
        {
            return PairingCodeResult.Failure(MapPayloadReason(ex.Reason), DetailFor(ex.Reason));
        }

        return PairingCodeResult.Success(payload);
    }

    /// <summary>Decodes a manual code without throwing; returns the reason code instead.</summary>
    public static bool TryDecode(string? code, long nowEpochMs, out PairingPayload? payload, out string? reason)
    {
        PairingCodeResult result = Decode(code, nowEpochMs);
        payload = result.Payload;
        reason = result.Reason;
        return result.Succeeded;
    }

    /// <summary>Maps a payload-level reason onto the code-level set a caller switches on.</summary>
    private static string MapPayloadReason(string? reason)
    {
        if (string.IsNullOrWhiteSpace(reason))
        {
            return PairingCodeReasons.PayloadInvalid;
        }

        if (string.Equals(reason, PairingReasons.UnsupportedVersion, StringComparison.Ordinal)
            || string.Equals(reason, PairingReasons.BadChallenge, StringComparison.Ordinal)
            || string.Equals(reason, PairingReasons.BadFingerprint, StringComparison.Ordinal)
            || string.Equals(reason, PairingReasons.BadUrl, StringComparison.Ordinal)
            || string.Equals(reason, PairingReasons.Expired, StringComparison.Ordinal))
        {
            return reason;
        }

        // malformed-json, missing-field:*, invalid-field:* and anything a future decoder adds are all "the text
        // did not carry a readable payload"; the field name is not worth a public code.
        return PairingCodeReasons.PayloadInvalid;
    }

    /// <summary>A refusal sentence that never quotes the input.</summary>
    private static string DetailFor(string? reason) => reason switch
    {
        PairingReasons.UnsupportedVersion => "配对码来自更新版本的 MediaHub，本机暂不支持。请升级后再试。",
        PairingReasons.BadChallenge => "配对码里的挑战值格式不正确，请让对方重新生成配对码。",
        PairingReasons.BadFingerprint => "配对码里的证书指纹格式不正确，请让对方重新生成配对码。",
        PairingReasons.BadUrl => "配对码里的对端地址不是可用的 http(s) 地址，请让对方重新生成配对码。",
        PairingReasons.Expired => "配对码已过期（有效期 " + PairingPayload.LifetimeSeconds + " 秒），请让对方重新生成。",
        _ => "配对码里的内容不是可读的配对信息，可能复制不完整。",
    };

    private static string ToBase64Url(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static bool TryFromBase64Url(string text, out byte[] bytes)
    {
        bytes = [];
        int remainder = text.Length % 4;
        if (remainder == 1)
        {
            // 1 leftover character is not a truncation that padding can fix; the text is not base64 at all.
            return false;
        }

        string standard = text.Replace('-', '+').Replace('_', '/');
        standard = remainder switch
        {
            2 => standard + "==",
            3 => standard + "=",
            _ => standard,
        };

        try
        {
            bytes = Convert.FromBase64String(standard);
            return true;
        }
        catch (FormatException)
        {
            return false;
        }
    }
}

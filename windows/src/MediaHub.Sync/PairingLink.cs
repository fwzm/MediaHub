namespace MediaHub.Sync;

/// <summary>Stable reasons a shareable pairing link can be refused before any code is decoded.</summary>
public static class PairingLinkReasons
{
    /// <summary>Nothing was pasted.</summary>
    public const string Empty = "pairing-link-empty";

    /// <summary>The text does not start with the pairing scheme.</summary>
    public const string NotALink = "not-a-pairing-link";

    /// <summary>The text is longer than a pairing link could ever be.</summary>
    public const string TooLong = "pairing-link-too-long";

    /// <summary>Every reason above, in a stable order. Code-level reasons come from <see cref="PairingCodeReasons"/>.</summary>
    public static IReadOnlyList<string> All { get; } = [Empty, NotALink, TooLong];
}

/// <summary>The outcome of reading a shareable pairing link.</summary>
public sealed record PairingLinkResult
{
    /// <summary>The invitation, when the link carried a valid one.</summary>
    public PairingPayload? Payload { get; init; }

    /// <summary>Stable reason code when it did not.</summary>
    public string? Reason { get; init; }

    /// <summary>Chinese sentence for a user; the raw code stays in the log.</summary>
    public string? Detail { get; init; }

    public bool Succeeded => Payload is not null;

    public static PairingLinkResult Success(PairingPayload payload) => new() { Payload = payload };

    public static PairingLinkResult Failure(string reason, string detail) => new() { Reason = reason, Detail = detail };
}

/// <summary>Turns an invitation into a text link a person can hand over, and back.</summary>
/// <remarks>
/// The link is only a container: the payload inside it is the same <see cref="PairingCode"/> this app already shows
/// as a code, so there is exactly one encoder and one validation path. Refusals replay the contract layer's stable
/// code and never the exception text, because that text quotes field values — and a challenge is a secret.
/// </remarks>
public static class PairingLink
{
    /// <summary>Scheme and path of a pairing link.</summary>
    public const string Prefix = "mediahub://pair?";

    /// <summary>Optional parameter name when the link is written as <c>?code=…</c>.</summary>
    public const string ParameterName = "code";

    /// <summary>Longest link this app will look at.</summary>
    public const int MaximumLength = 8192;

    /// <summary>Builds the shareable text for an invitation.</summary>
    public static string Create(PairingPayload payload)
    {
        ArgumentNullException.ThrowIfNull(payload);
        return Prefix + PairingCode.Encode(payload);
    }

    /// <summary>Extracts the code part of a link without decoding it; empty when the text is not a link.</summary>
    public static string ExtractCode(string? link)
    {
        string text = (link ?? string.Empty).Trim();
        if (text.Length == 0 || !text.StartsWith(Prefix, StringComparison.OrdinalIgnoreCase))
        {
            return string.Empty;
        }

        string code = text[Prefix.Length..].Trim();
        if (code.StartsWith(ParameterName + "=", StringComparison.OrdinalIgnoreCase))
        {
            code = code[(ParameterName.Length + 1)..].Trim();
        }

        return code;
    }

    /// <summary>Reads a pasted link back into an invitation, refusing anything that is not one.</summary>
    public static PairingLinkResult Parse(string? link, long nowEpochMs)
    {
        string text = (link ?? string.Empty).Trim();
        if (text.Length == 0)
        {
            return PairingLinkResult.Failure(PairingLinkReasons.Empty, "没有粘贴配对链接。");
        }

        if (!text.StartsWith(Prefix, StringComparison.OrdinalIgnoreCase))
        {
            return PairingLinkResult.Failure(PairingLinkReasons.NotALink, "这不是 MediaHub 配对链接。");
        }

        if (text.Length > MaximumLength)
        {
            return PairingLinkResult.Failure(PairingLinkReasons.TooLong, "配对链接过长，不像是本应用生成的。");
        }

        PairingCodeResult decoded = PairingCode.Decode(ExtractCode(text), nowEpochMs);
        return decoded.Succeeded && decoded.Payload is not null
            ? PairingLinkResult.Success(decoded.Payload)
            : PairingLinkResult.Failure(
                decoded.Reason ?? PairingCodeReasons.PayloadInvalid,
                PairingUiText.DescribeCodeRejection(decoded.Reason, decoded.Detail));
    }

    /// <summary>Convenience wrapper for callers that only need the verdict.</summary>
    public static bool TryParse(string? link, long nowEpochMs, out PairingPayload? payload, out string? reason)
    {
        PairingLinkResult result = Parse(link, nowEpochMs);
        payload = result.Payload;
        reason = result.Reason;
        return result.Succeeded;
    }
}

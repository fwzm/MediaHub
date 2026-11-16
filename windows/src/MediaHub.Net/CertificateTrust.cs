using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace MediaHub.Net;

/// <summary>
/// Per-server certificate pinning.
/// </summary>
/// <remarks>
/// <para>
/// A self-signed server cannot be reached through the platform chain validator: the chain does not
/// terminate in a trusted root, so every request fails during the TLS handshake. The option of last
/// resort — accepting any certificate — would disable validation for every source in the process and
/// make the credential in the request header interceptable.
/// </para>
/// <para>
/// What is offered instead is a <i>pin</i>: the SHA-256 digest of one specific leaf certificate, stored
/// per source. The pin is set on that source's handler only, and it is only consulted after the
/// platform validator has already rejected the chain. A certificate that the platform accepts is
/// accepted without the pin ever being read, which is what keeps a pinned source from silently
/// accepting a future certificate that happens to share its digest with nothing.
/// </para>
/// <para>
/// A pin match bypasses the host-name check as well as the chain check. That is deliberate: a
/// self-signed certificate for a LAN address routinely carries no matching SAN, and the pin already
/// names exactly one certificate — there is nothing left for the name to disambiguate.
/// </para>
/// </remarks>
public static class CertificateTrust
{
    /// <summary>
    /// Normalises a fingerprint to upper-case hex without separators, or null when it is not a SHA-256.
    /// </summary>
    /// <remarks>
    /// Windows shows thumbprints as upper-case hex with spaces (<c>AB CD …</c>), OpenSSL prints them as
    /// lower-case hex with colons (<c>ab:cd:…</c>), and users paste both. Accepting one spelling and
    /// silently failing on the other would make "I pasted the fingerprint" indistinguishable from
    /// "the certificate changed".
    /// </remarks>
    public static string? NormalizeFingerprint(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return null;

        Span<char> buffer = stackalloc char[64];
        int length = 0;
        foreach (char c in value)
        {
            if (c is ':' or '-' or '.' or ' ' or '\t' or '\r' or '\n') continue;
            if (!char.IsAsciiHexDigit(c)) return null;
            if (length == buffer.Length) return null; // longer than a SHA-256: never a valid pin
            buffer[length++] = char.ToUpperInvariant(c);
        }

        return length == 64 ? new string(buffer) : null;
    }

    /// <summary>SHA-256 of the certificate's DER bytes, as upper-case hex, or null when unavailable.</summary>
    public static string? ComputeSha256(X509Certificate? certificate)
    {
        if (certificate is null) return null;
        try
        {
            byte[] raw = certificate.GetRawCertData();
            return raw.Length == 0 ? null : Convert.ToHexString(SHA256.HashData(raw));
        }
        catch
        {
            return null;
        }
    }

    /// <summary>
    /// Builds the validation callback for a pinned source.
    /// </summary>
    /// <remarks>
    /// The callback never throws. An exception thrown from here aborts the handshake with an opaque
    /// message and is the classic way a TLS bug becomes a "cannot connect" report with no cause.
    /// </remarks>
    public static RemoteCertificateValidationCallback CreateValidationCallback(string pin)
    {
        string normalized = NormalizeFingerprint(pin)
                            ?? throw new ArgumentException("证书指纹必须是 64 位十六进制 SHA-256。", nameof(pin));

        return (_, certificate, _, errors) =>
        {
            // Everything the platform accepts stays accepted; the pin is the fall-back for the rest.
            if (errors == SslPolicyErrors.None && certificate is not null) return true;
            if (certificate is null) return false;

            string? actual = ComputeSha256(certificate);
            return actual is not null && string.Equals(actual, normalized, StringComparison.Ordinal);
        };
    }
}

/// <summary>
/// A certificate a server presented that the platform does not trust, as shown to the user before a
/// pin is stored.
/// </summary>
public sealed record PresentedCertificate(
    string Host,
    string Subject,
    string Issuer,
    string Sha256,
    DateTimeOffset NotBefore,
    DateTimeOffset NotAfter,
    SslPolicyErrors ChainErrors)
{
    /// <summary>The certificate names a different host than the one that was dialled.</summary>
    public bool NameMismatch => ChainErrors.HasFlag(SslPolicyErrors.RemoteCertificateNameMismatch);

    /// <summary>The chain does not terminate in a trusted root — the usual shape of a self-signed server.</summary>
    public bool UntrustedChain => ChainErrors.HasFlag(SslPolicyErrors.RemoteCertificateChainErrors);

    /// <summary>One line for a status hint, without any material that could be replayed.</summary>
    public string Summary =>
        "SHA-256 " + Sha256 + "\n颁发给 " + Subject + "\n颁发者 " + Issuer +
        "\n有效期 " + NotBefore.ToLocalTime().ToString("yyyy-MM-dd") + " 至 " + NotAfter.ToLocalTime().ToString("yyyy-MM-dd") +
        (NameMismatch ? "\n注意：证书中的主机名与地址不一致" : "");
}

/// <summary>Reads the certificate a server presents, without ever sending it a request.</summary>
public static class CertificateInspector
{
    /// <summary>
    /// Connects over TLS and returns the presented certificate when the platform rejects it, or null
    /// when there is nothing to ask about.
    /// </summary>
    /// <remarks>
    /// The handshake callback records the certificate and then answers <c>false</c>, so the handshake
    /// always ends in failure. That is the property the whole flow rests on: not one HTTP byte — and in
    /// particular not one credential header — is written before the user has confirmed the fingerprint.
    /// Null also means "the platform trusts this certificate", in which case no pin is needed.
    /// </remarks>
    public static async Task<PresentedCertificate?> InspectAsync(Uri url, TimeSpan timeout, CancellationToken ct = default)
    {
        if (url is null || !url.IsAbsoluteUri) return null;
        if (!string.Equals(url.Scheme, Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase)) return null;
        if (string.IsNullOrEmpty(url.Host)) return null;

        byte[]? raw = null;
        SslPolicyErrors errors = SslPolicyErrors.None;

        using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeoutCts.CancelAfter(timeout <= TimeSpan.Zero ? TimeSpan.FromSeconds(10) : timeout);

        try
        {
            using var client = new TcpClient();
            await client.ConnectAsync(url.Host, url.Port, timeoutCts.Token).ConfigureAwait(false);

            await using var ssl = new SslStream(client.GetStream(), leaveInnerStreamOpen: false,
                (_, certificate, _, sslErrors) =>
                {
                    errors = sslErrors;
                    if (certificate is not null) raw = certificate.GetRawCertData();
                    return false; // abort: no request is sent over an unvalidated channel
                });

            await ssl.AuthenticateAsClientAsync(
                new SslClientAuthenticationOptions { TargetHost = url.Host },
                timeoutCts.Token).ConfigureAwait(false);

            // Unreachable: the callback above refuses every certificate. Kept so a future change to the
            // callback cannot silently turn this into a request that succeeds.
            return null;
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            return null; // connect/handshake timeout: report "unreachable", not "bad certificate"
        }
        catch
        {
            // Handshake failure is the expected outcome; only the recorded certificate matters.
        }

        if (raw is null || raw.Length == 0) return null;

        X509Certificate2? leaf = null;
        try
        {
            leaf = X509CertificateLoader.LoadCertificate(raw);
        }
        catch
        {
            return null;
        }

        using (leaf)
        {
            string? sha256 = CertificateTrust.ComputeSha256(leaf);
            if (sha256 is null) return null;

            // The platform accepted this certificate; there is nothing for the user to confirm.
            if (errors == SslPolicyErrors.None) return null;

            return new PresentedCertificate(
                url.Host,
                leaf.Subject,
                leaf.Issuer,
                sha256,
                new DateTimeOffset(leaf.NotBefore.ToUniversalTime()),
                new DateTimeOffset(leaf.NotAfter.ToUniversalTime()),
                errors);
        }
    }
}

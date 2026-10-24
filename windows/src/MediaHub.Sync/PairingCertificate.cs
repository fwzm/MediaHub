using System;
using System.Net;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace MediaHub.Sync;

/// <summary>
/// Self-signed certificates for the temporary pairing listener, plus the fingerprint the invitation advertises.
/// </summary>
/// <remarks>
/// <para>
/// W6-3b proves the pinning design with a self-signed certificate that lives only for the pairing window: there
/// is no CA to trust and no long-lived key to manage, so the only identity signal is the SHA-256 fingerprint the
/// QR code carries (ADR-S001 D6, ADR-S003 D2). The certificate is therefore never validated against a chain —
/// it is compared, byte for byte, against the pinned fingerprint. That is not a relaxation: a chain would add
/// nothing here, because a CA-signed certificate whose key the attacker holds would still match its own
/// fingerprint, and a self-signed certificate whose fingerprint matches the QR code cannot be substituted.
/// </para>
/// <para>
/// The fingerprint covers the SubjectPublicKeyInfo, not the whole certificate: reissuing the same key (new
/// validity dates, new serial) must not invalidate a printed QR code, while any change of key must. This is the
/// same convention SSH and Android's pairing APIs use.
/// </para>
/// </remarks>
public static class PairingCertificate
{
    private const string ServerAuthenticationOid = "1.3.6.1.5.5.7.3.1";

    private static readonly TimeSpan Validity = TimeSpan.FromHours(2);

    private static readonly TimeSpan ClockSkew = TimeSpan.FromMinutes(5);

    /// <summary>
    /// Creates a throw-away RSA-2048 certificate for <paramref name="commonName"/>, valid from a few minutes ago
    /// (clock skew) to two hours from now.
    /// </summary>
    /// <remarks>
    /// The certificate is exported to PKCS#12 and re-imported before being returned. Windows' SChannel refuses
    /// to use a <see cref="CertificateRequest"/>-generated key that was never imported into a key container
    /// ("the credentials supplied to the package were not recognized"), so the round-trip is what makes the
    /// object usable as a server certificate on Windows. <see cref="X509KeyStorageFlags.Exportable"/> keeps the
    /// key disposable: it disappears when the certificate is disposed.
    /// </remarks>
    public static X509Certificate2 CreateEphemeral(string commonName)
    {
        if (string.IsNullOrWhiteSpace(commonName))
        {
            throw new ArgumentException("A certificate needs a common name.", nameof(commonName));
        }

        using RSA key = RSA.Create(2048);
        var request = new CertificateRequest("CN=" + commonName, key, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);

        var alternativeNames = new SubjectAlternativeNameBuilder();
        alternativeNames.AddDnsName("localhost");
        alternativeNames.AddIpAddress(IPAddress.Loopback);

        request.CertificateExtensions.Add(alternativeNames.Build());
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, critical: true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(
            X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment,
            critical: false));
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(
            new OidCollection { new Oid(ServerAuthenticationOid) },
            critical: false));

        DateTimeOffset now = DateTimeOffset.UtcNow;
        using X509Certificate2 generated = request.CreateSelfSigned(now - ClockSkew, now + Validity);
        byte[] pfx = generated.Export(X509ContentType.Pfx);
        return X509CertificateLoader.LoadPkcs12(pfx, password: null, X509KeyStorageFlags.Exportable);
    }

    /// <summary>Lowercase hex SHA-256 of the certificate's SubjectPublicKeyInfo — the value an invitation pins.</summary>
    /// <exception cref="SyncContractException">The certificate has no RSA or ECDSA public key.</exception>
    public static string ComputeFingerprint(X509Certificate2 certificate)
    {
        ArgumentNullException.ThrowIfNull(certificate);

        AsymmetricAlgorithm? publicKey = certificate.GetECDsaPublicKey();
        publicKey ??= certificate.GetRSAPublicKey();
        if (publicKey is null)
        {
            throw new SyncContractException(
                PairingReasons.BadFingerprint,
                "The certificate carries no RSA or ECDSA public key, so it cannot be pinned.");
        }

        byte[] spki;
        using (publicKey)
        {
            spki = publicKey.ExportSubjectPublicKeyInfo();
        }

        return PairingFingerprint.FromBytes(SHA256.HashData(spki)).Hex;
    }

    /// <summary>Lowercase hex SHA-256 of the SubjectPublicKeyInfo of any certificate, including ones SslStream hands back.</summary>
    /// <exception cref="SyncContractException">The certificate has no RSA or ECDSA public key.</exception>
    public static string ComputeFingerprint(X509Certificate certificate)
    {
        ArgumentNullException.ThrowIfNull(certificate);
        using X509Certificate2 typed = X509CertificateLoader.LoadCertificate(certificate.GetRawCertData());
        return ComputeFingerprint(typed);
    }
}

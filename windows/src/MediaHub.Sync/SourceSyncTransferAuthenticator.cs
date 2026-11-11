using System;
using System.Collections.Generic;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;

namespace MediaHub.Sync;

/// <summary>
/// The stable reason codes the optional authentication layer (W6-24) can put in
/// <see cref="SourceSyncTransferResult.Reason"/>.
/// </summary>
/// <remarks>
/// <para>
/// These codes sit <em>beside</em> <see cref="SourceSyncTransferReasons"/> rather than inside it: that set is a
/// closed ten-code contract with its own assertions and its own Android mirror, and adding to it would silently
/// widen a set other platforms already switch over. A caller that only knows the ten original codes sees
/// "rate this as a plain failure" behaviour, because <c>CliExitCode.ClassifyReason</c> parks an unknown code on
/// the input-problem exit code.
/// </para>
/// <para>
/// None of these five carry a secret, a nonce, or an envelope body: they exist to say *which* proof failed.
/// </para>
/// </remarks>
public static class SourceSyncTransferAuthReasons
{
    /// <summary>The sender asked for authentication but announced no challenge to bind the tag to.</summary>
    public const string AuthRequired = "auth-required";

    /// <summary>The envelope arrived complete, but the peer never proved it knows the secret.</summary>
    public const string AuthMissing = "auth-missing";

    /// <summary>The peer's tag does not match this secret and this envelope.</summary>
    public const string AuthMismatch = "auth-mismatch";

    /// <summary>This device already accepted an envelope under this nonce; the transfer is a replay.</summary>
    public const string AuthReplayed = "auth-replayed";

    /// <summary>Authentication is on but this process holds no secret to prove or check with.</summary>
    public const string AuthSecretUnavailable = "auth-secret-unavailable";

    /// <summary>Every code, in a fixed order, so a caller can enumerate or assert the closed set.</summary>
    public static IReadOnlyList<string> All { get; } =
    [
        AuthRequired,
        AuthMissing,
        AuthMismatch,
        AuthReplayed,
        AuthSecretUnavailable,
    ];
}

/// <summary>
/// Proves that the peer of a loopback transfer holds the pairing's shared secret (W6-24).
/// </summary>
/// <remarks>
/// <para>
/// The wire is plain TCP on 127.0.0.1, so any local process can connect to a serving port and hand over an
/// envelope. The pairing layer already pins a device fingerprint, but nothing on the transfer path used it.
/// This layer closes that gap with the smallest primitive that needs no new dependency and no new exchange:
/// HMAC-SHA256 over <c>nonce || envelope</c>, where the nonce is the challenge the invitation already carries.
/// </para>
/// <para>
/// The construction is deliberately "tag the bytes you actually read": the tag covers the reassembled envelope
/// and the announced challenge, so a peer cannot re-target a valid tag onto a different payload or a different
/// nonce. Verification is constant-time (<see cref="CryptographicOperations.FixedTimeEquals"/>), and the nonce
/// is only remembered <em>after</em> the tag checks out, so a forged envelope cannot burn a nonce.
/// </para>
/// <para>
/// This is authentication, not encryption: the envelope still travels in the clear, which is acceptable on a
/// loopback interface the caller already trusts for confidentiality. It is also not a full protocol: there is
/// no key agreement, no rotation, and no protection against a same-nonce replay across processes — see
/// <c>docs/cross-device/W6-24-TRANSFER-AUTH.md</c> for the threat model and the uncovered cases.
/// </para>
/// </remarks>
public static class SourceSyncTransferAuthenticator
{
    /// <summary>Frame kind for the optional tag frame, appended after the last chunk.</summary>
    /// <remarks>
    /// 0x03 follows the challenge (0x01) and chunk (0x02) kinds. A reader that has authentication switched off
    /// still rejects it as "Unknown frame kind 0x03.", which is exactly the backward-compatible behaviour: a
    /// peer that does not know this frame never accepts an authenticated transfer by accident.
    /// </remarks>
    public const byte TagFrameKind = 0x03;

    /// <summary>Bytes in an HMAC-SHA256 tag.</summary>
    public const int TagBytes = 32;

    /// <summary>Bytes in a tag frame: the kind byte plus the tag.</summary>
    public const int TagFrameBytes = 1 + TagBytes;

    /// <summary>Entropy bytes in a generated nonce (the wire carries them base64url-encoded).</summary>
    public const int NonceEntropyBytes = 16;

    /// <summary>
    /// Computes <c>HMAC-SHA256(secret, nonce || envelope)</c>.
    /// </summary>
    /// <param name="secret">The shared secret; must be non-empty.</param>
    /// <param name="nonce">The challenge the transfer announces, as UTF-8 text.</param>
    /// <param name="envelope">The exact envelope bytes the tag covers.</param>
    /// <returns>The 32-byte tag.</returns>
    /// <exception cref="ArgumentException">The secret is empty.</exception>
    /// <exception cref="ArgumentNullException"><paramref name="nonce"/> is null.</exception>
    public static byte[] CreateTag(ReadOnlySpan<byte> secret, string nonce, ReadOnlySpan<byte> envelope)
    {
        ArgumentNullException.ThrowIfNull(nonce);
        return CreateTag(secret, Encoding.UTF8.GetBytes(nonce), envelope);
    }

    /// <summary>Computes <c>HMAC-SHA256(secret, nonce || envelope)</c> from nonce bytes already in hand.</summary>
    /// <param name="secret">The shared secret; must be non-empty.</param>
    /// <param name="nonce">The nonce bytes, exactly as they appear on the wire.</param>
    /// <param name="envelope">The exact envelope bytes the tag covers.</param>
    /// <returns>The 32-byte tag.</returns>
    /// <exception cref="ArgumentException">The secret is empty.</exception>
    public static byte[] CreateTag(ReadOnlySpan<byte> secret, ReadOnlySpan<byte> nonce, ReadOnlySpan<byte> envelope)
    {
        if (secret.IsEmpty)
        {
            throw new ArgumentException("A tag needs a non-empty secret.", nameof(secret));
        }

        byte[] message = new byte[nonce.Length + envelope.Length];
        nonce.CopyTo(message);
        envelope.CopyTo(message.AsSpan(nonce.Length));
        return HMACSHA256.HashData(secret, message);
    }

    /// <summary>Checks a tag in constant time.</summary>
    /// <param name="secret">The shared secret; an empty secret verifies nothing and returns false.</param>
    /// <param name="nonce">The challenge the peer announced, as UTF-8 text.</param>
    /// <param name="envelope">The envelope the peer delivered.</param>
    /// <param name="tag">The tag the peer sent.</param>
    /// <returns>True only for a tag of the right length that matches this secret, nonce and envelope.</returns>
    /// <exception cref="ArgumentNullException"><paramref name="nonce"/> is null.</exception>
    public static bool VerifyTag(ReadOnlySpan<byte> secret, string nonce, ReadOnlySpan<byte> envelope, ReadOnlySpan<byte> tag)
    {
        ArgumentNullException.ThrowIfNull(nonce);
        return VerifyTag(secret, Encoding.UTF8.GetBytes(nonce), envelope, tag);
    }

    /// <summary>Checks a tag in constant time, from nonce bytes already in hand.</summary>
    /// <param name="secret">The shared secret; an empty secret verifies nothing and returns false.</param>
    /// <param name="nonce">The nonce bytes, exactly as they appeared on the wire.</param>
    /// <param name="envelope">The envelope the peer delivered.</param>
    /// <param name="tag">The tag the peer sent.</param>
    /// <returns>True only for a tag of the right length that matches this secret, nonce and envelope.</returns>
    public static bool VerifyTag(ReadOnlySpan<byte> secret, ReadOnlySpan<byte> nonce, ReadOnlySpan<byte> envelope, ReadOnlySpan<byte> tag)
    {
        // A wrong length is a public fact (the frame carries it), so returning early leaks nothing — and it keeps
        // FixedTimeEquals from comparing arrays of different lengths, which it refuses to do anyway.
        if (secret.IsEmpty || tag.Length != TagBytes)
        {
            return false;
        }

        byte[] expected = CreateTag(secret, nonce, envelope);
        return CryptographicOperations.FixedTimeEquals(expected, tag);
    }

    /// <summary>
    /// Makes a fresh nonce for a transfer this device is about to start: 16 random bytes, base64url-encoded so it
    /// survives the challenge frame's UTF-8 trim unchanged.
    /// </summary>
    /// <returns>22 characters from <c>A-Z a-z 0-9 - _</c>.</returns>
    public static string CreateNonce()
    {
        Span<byte> buffer = stackalloc byte[NonceEntropyBytes];
        RandomNumberGenerator.Fill(buffer);
        return Convert.ToBase64String(buffer).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    }
}

/// <summary>
/// Remembers the nonces this device has already accepted, so the same (device, nonce) pair cannot be replayed.
/// </summary>
/// <remarks>
/// <para>
/// The window is deliberately small and bounded: <see cref="DefaultCapacity"/> entries, oldest evicted first.
/// A replay is only interesting within the lifetime of a single serve invocation, and an unbounded table would
/// be a memory-growth hole a peer could drive. Nothing is written to disk — a restart forgets the window, which
/// is the documented a cross-process limitation.
/// </para>
/// <para>
/// Entries are partitioned by device id, so two devices that happen to pick the same nonce do not knock each
/// other out. The cache is thread-safe: one listener may serve connections from several threads.
/// </para>
/// </remarks>
public sealed class SourceSyncNonceCache
{
    /// <summary>How many nonces the cache keeps by default.</summary>
    public const int DefaultCapacity = 64;

    private readonly object _gate = new();
    private readonly Queue<string> _order = new();
    private readonly HashSet<string> _seen = new(StringComparer.Ordinal);

    /// <summary>Creates a cache that remembers at most <paramref name="capacity"/> nonces.</summary>
    /// <param name="capacity">How many nonces to keep; at least one.</param>
    /// <exception cref="ArgumentOutOfRangeException"><paramref name="capacity"/> is below one.</exception>
    public SourceSyncNonceCache(int capacity = DefaultCapacity)
    {
        if (capacity < 1)
        {
            throw new ArgumentOutOfRangeException(
                nameof(capacity),
                capacity,
                "The cache needs room for at least one nonce.");
        }

        Capacity = capacity;
    }

    /// <summary>How many nonces this cache keeps before evicting the oldest.</summary>
    public int Capacity { get; }

    /// <summary>How many nonces are remembered right now.</summary>
    public int Count
    {
        get
        {
            lock (_gate)
            {
                return _seen.Count;
            }
        }
    }

    /// <summary>
    /// Records a nonce, or reports that this (device, nonce) pair has been seen before.
    /// </summary>
    /// <param name="deviceId">The peer's device id, or null for an unnamed peer.</param>
    /// <param name="nonce">The nonce the peer announced; must be non-empty.</param>
    /// <returns>True the first time a pair is seen, false for every repeat.</returns>
    /// <exception cref="ArgumentException"><paramref name="nonce"/> is null or empty.</exception>
    public bool Accept(string? deviceId, string nonce)
    {
        if (string.IsNullOrEmpty(nonce))
        {
            throw new ArgumentException("A nonce is never empty; an empty challenge is rejected before this point.", nameof(nonce));
        }

        string key = (deviceId ?? "") + "\u001f" + nonce;
        lock (_gate)
        {
            if (!_seen.Add(key))
            {
                return false;
            }

            _order.Enqueue(key);
            while (_order.Count > Capacity)
            {
                _seen.Remove(_order.Dequeue());
            }

            return true;
        }
    }

    /// <summary>Forgets every nonce; the next sighting of any pair counts as a first sighting.</summary>
    public void Clear()
    {
        lock (_gate)
        {
            _seen.Clear();
            _order.Clear();
        }
    }
}

/// <summary>
/// The optional authentication settings for one transfer end (W6-24).
/// </summary>
/// <remarks>
/// <para>
/// This is a class rather than a record on purpose. The compiler-generated <c>ToString</c> of a record prints
/// every property, and these options are formatted into diagnostics and exception text; a record would put the
/// secret into a log line the first time anyone printed the object. <see cref="ToString"/> below is explicit and
/// redacts the secret while still saying whether one is present and how long it is.
/// </para>
/// <para>
/// <see cref="Off"/> (the default everywhere) must leave the wire byte-for-byte identical to W6-16: no tag frame,
/// no extra flush, no change to the frame count.
/// </para>
/// </remarks>
public sealed class SourceSyncTransferOptions
{
    private readonly Lazy<SourceSyncNonceCache> _fallbackNonces = new(() => new SourceSyncNonceCache());

    /// <summary>Turns the tag frame on for this end of the transfer. Off by default.</summary>
    public bool Authenticate { get; init; }

    /// <summary>The shared secret, read from the environment by the CLI and never from a command line.</summary>
    public byte[] Secret { get; init; } = [];

    /// <summary>The replay window to use; when null, this options object keeps a private one.</summary>
    public SourceSyncNonceCache? Nonces { get; init; }

    /// <summary>
    /// The device id to partition replay memory by. Only the receiving end uses it, and it is never sent anywhere.
    /// </summary>
    public string? DeviceId { get; init; }

    /// <summary>True only when authentication is on <em>and</em> a secret is actually in hand.</summary>
    public bool HasSecret => Authenticate && Secret.Length > 0;

    /// <summary>The replay window this options object will use, creating the private one on first use.</summary>
    public SourceSyncNonceCache ActiveNonces => Nonces ?? _fallbackNonces.Value;

    /// <summary>The default: authentication off, which must produce the W6-16 byte stream exactly.</summary>
    public static SourceSyncTransferOptions Off { get; } = new();

    /// <summary>Turns authentication on with a copy of <paramref name="secret"/>.</summary>
    /// <param name="secret">The shared secret, or null/empty to build options that will report
    /// <see cref="SourceSyncTransferAuthReasons.AuthSecretUnavailable"/> instead of authenticating.</param>
    /// <param name="nonces">An optional replay window to share between calls.</param>
    /// <param name="deviceId">This device's id, used to partition the replay window.</param>
    /// <returns>Options with <see cref="Authenticate"/> set to true.</returns>
    public static SourceSyncTransferOptions WithSecret(byte[]? secret, SourceSyncNonceCache? nonces = null, string? deviceId = null) => new()
    {
        Authenticate = true,
        Secret = secret is null ? [] : (byte[])secret.Clone(),
        Nonces = nonces,
        DeviceId = deviceId,
    };

    /// <summary>Formats the options without ever printing the secret.</summary>
    /// <returns>A diagnostic string such as <c>SourceSyncTransferOptions { Authenticate = true, Secret = &lt;redacted 32 bytes&gt;, DeviceId = 'dev-1' }</c>.</returns>
    public override string ToString() =>
        "SourceSyncTransferOptions { Authenticate = " + (Authenticate ? "true" : "false")
        + ", Secret = " + (Secret.Length == 0
            ? "<none>"
            : "<redacted " + Secret.Length.ToString(CultureInfo.InvariantCulture) + " bytes>")
        + ", DeviceId = " + (string.IsNullOrEmpty(DeviceId) ? "<none>" : "'" + DeviceId + "'")
        + " }";
}

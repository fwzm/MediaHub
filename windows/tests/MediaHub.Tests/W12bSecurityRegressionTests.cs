using System.Buffers.Binary;
using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using MediaHub.Domain;
using MediaHub.Sync;
using MediaHub.SyncCli;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Second adversarial pass over the W6/W13 surfaces: every case here is paired with the happy path
/// that proves the refusal is a decision and not an accident. The pairing is deliberate — a test
/// that only asserts "it did not crash" would pass against a component that silently accepted a
/// hostile input, which is exactly the failure mode this file exists to catch.
/// </summary>
/// <remarks>
/// All fixtures are built in memory or in a temp directory owned by the test; nothing here touches
/// %LOCALAPPDATA%, a real server or a fixed port.
/// </remarks>
public sealed class PairingLinkAdversarialTests
{
    private const long Now = 1_760_000_000_000L;
    private const string Address = "http://127.0.0.1:52000/";

    private static PairingChallenge Challenge() => PairingChallenge.FromBytes(Fill(0x11));

    private static PairingFingerprint Fingerprint() => PairingFingerprint.FromBytes(Fill(0x2A));

    private static byte[] Fill(byte value)
    {
        byte[] bytes = new byte[32];
        Array.Fill(bytes, value);
        return bytes;
    }

    private static PairingPayload Payload(string deviceId = "device-a", string address = Address, long now = Now) =>
        PairingPayload.Create(deviceId, address, Challenge(), Fingerprint(), now);

    private static JsonObject PayloadJson(string address = Address, string? challenge = null, string? fingerprint = null, long expiresAt = Now + 120_000L)
    {
        return new JsonObject
        {
            ["schemaVersion"] = 1,
            ["deviceId"] = "device-a",
            ["ephemeralUrl"] = address,
            ["challenge"] = challenge ?? Challenge().Base64,
            ["publicKeyFingerprint"] = fingerprint ?? Fingerprint().Hex,
            ["expiresAtEpochMs"] = expiresAt,
        };
    }

    private static string ToBase64Url(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static string LinkAroundCode(string code) => PairingLink.Prefix + "code=" + code;

    private static string LinkAroundJson(JsonObject payload) =>
        LinkAroundCode(ToBase64Url(Encoding.UTF8.GetBytes(payload.ToJsonString())));

    [Fact]
    public void A_valid_link_round_trips_the_whole_payload()
    {
        PairingPayload payload = Payload();

        PairingLinkResult result = PairingLink.Parse(PairingLink.Create(payload), Now);

        Assert.True(result.Succeeded);
        Assert.Null(result.Reason);
        Assert.Null(result.Detail);
        Assert.Equal(payload.DeviceId, result.Payload!.DeviceId);
        Assert.Equal(payload.EphemeralUrl, result.Payload.EphemeralUrl);
        Assert.Equal(payload.Challenge, result.Payload.Challenge);
        Assert.Equal(payload.PublicKeyFingerprint, result.Payload.PublicKeyFingerprint);
        Assert.Equal(payload.ExpiresAtEpochMs, result.Payload.ExpiresAtEpochMs);
    }

    [Theory]
    [InlineData("", "code-empty")]
    [InlineData("!!!!", "not-base64url")]
    [InlineData("A", "not-base64url")]
    [InlineData("AAAA", "payload-invalid")]
    public void A_malformed_code_is_refused_with_a_stable_reason(string code, string expectedReason)
    {
        PairingLinkResult result = PairingLink.Parse(LinkAroundCode(code), Now);

        Assert.False(result.Succeeded);
        Assert.Null(result.Payload);
        Assert.Equal(expectedReason, result.Reason);
        Assert.Contains(result.Reason, PairingCodeReasons.All);
    }

    [Fact]
    public void An_oversized_link_is_refused_before_any_decoding()
    {
        string filler = new('a', PairingLink.MaximumLength);
        PairingLinkResult result = PairingLink.Parse(LinkAroundCode(filler), Now);

        Assert.False(result.Succeeded);
        Assert.Equal(PairingLinkReasons.TooLong, result.Reason);
        Assert.Contains(result.Reason, PairingLinkReasons.All);
        Assert.DoesNotContain("aaaa", result.Detail ?? "", StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void A_link_with_the_wrong_scheme_is_refused()
    {
        PairingLinkResult result = PairingLink.Parse("https://example.test/pair?code=AAAA", Now);

        Assert.False(result.Succeeded);
        Assert.Equal(PairingLinkReasons.NotALink, result.Reason);
    }

    [Fact]
    public void A_payload_whose_address_carries_user_info_is_refused()
    {
        PairingLinkResult result = PairingLink.Parse(LinkAroundJson(PayloadJson("http://user:hunter2@127.0.0.1:52000/")), Now);

        Assert.False(result.Succeeded);
        Assert.Equal(PairingReasons.BadUrl, result.Reason);
        Assert.DoesNotContain("hunter2", result.Detail ?? "", StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("ftp://127.0.0.1:52000/")]
    [InlineData("ws://127.0.0.1:52000/pair")]
    [InlineData("127.0.0.1:52000")]
    [InlineData("http:///no-host")]
    [InlineData("http://127.0.0.1:52000/?token=abc")]
    [InlineData("http://127.0.0.1:52000/#frag")]
    public void A_payload_whose_address_is_not_a_bare_http_url_is_refused(string address)
    {
        PairingLinkResult result = PairingLink.Parse(LinkAroundJson(PayloadJson(address)), Now);

        Assert.False(result.Succeeded);
        Assert.Equal(PairingReasons.BadUrl, result.Reason);
        Assert.DoesNotContain("token=abc", result.Detail ?? "", StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("schemaVersion")]
    [InlineData("deviceId")]
    [InlineData("ephemeralUrl")]
    [InlineData("challenge")]
    [InlineData("publicKeyFingerprint")]
    [InlineData("expiresAtEpochMs")]
    public void A_payload_missing_a_required_field_is_refused(string removed)
    {
        JsonObject json = PayloadJson();
        Assert.True(json.Remove(removed));

        PairingLinkResult result = PairingLink.Parse(LinkAroundJson(json), Now);

        Assert.False(result.Succeeded);
        Assert.Equal(PairingCodeReasons.PayloadInvalid, result.Reason);
    }

    [Fact]
    public void The_same_document_with_every_field_present_is_accepted()
    {
        PairingLinkResult result = PairingLink.Parse(LinkAroundJson(PayloadJson()), Now);

        Assert.True(result.Succeeded);
        Assert.Equal("device-a", result.Payload!.DeviceId);
        Assert.Equal(Now + 120_000L, result.Payload.ExpiresAtEpochMs);
    }

    [Fact]
    public void Unknown_extra_fields_are_ignored_and_never_written_back()
    {
        JsonObject json = PayloadJson();
        json["password"] = "hunter2";
        json["token"] = "token-abc";
        json["expiresAtEpochMs"] = Now + 60_000L;

        PairingLinkResult parsed = PairingLink.Parse(LinkAroundJson(json), Now);

        Assert.True(parsed.Succeeded);
        string reencoded = PairingCode.Encode(parsed.Payload!);
        Assert.DoesNotContain("password", reencoded, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("hunter2", reencoded, StringComparison.Ordinal);
        Assert.DoesNotContain("token-abc", reencoded, StringComparison.Ordinal);

        using JsonDocument document = JsonDocument.Parse(
            Encoding.UTF8.GetString(Convert.FromBase64String(PadBase64(reencoded))));
        Assert.Equal(6, document.RootElement.EnumerateObject().Count());
    }

    [Fact]
    public void An_expired_link_is_refused_at_the_boundary_and_accepted_one_millisecond_earlier()
    {
        string link = PairingLink.Create(Payload());

        Assert.True(PairingLink.Parse(link, Now + PairingPayload.LifetimeMilliseconds - 1).Succeeded);

        PairingLinkResult expired = PairingLink.Parse(link, Now + PairingPayload.LifetimeMilliseconds);
        Assert.False(expired.Succeeded);
        Assert.Equal(PairingReasons.Expired, expired.Reason);
        Assert.Contains("120", expired.Detail ?? "", StringComparison.Ordinal);
    }

    [Fact]
    public void A_truncated_code_is_refused_rather_than_half_decoded()
    {
        string code = PairingCode.Encode(Payload());
        PairingLinkResult result = PairingLink.Parse(LinkAroundCode(code[..^4]), Now);

        Assert.False(result.Succeeded);
        Assert.Null(result.Payload);
        Assert.Contains(result.Reason, PairingCodeReasons.All);
    }

    private static string PadBase64(string text)
    {
        string padded = text.Replace('-', '+').Replace('_', '/');
        return padded + new string('=', (4 - (padded.Length % 4)) % 4);
    }
}

/// <summary>
/// The paired-device document is the only durable state the pairing surface owns, so every refusal
/// here is checked twice: the call fails, and the bytes on disk are the bytes that were there before.
/// </summary>
public sealed class PairedDeviceStoreAdversarialTests : IDisposable
{
    private const long Now = 1_760_000_000_000L;

    private readonly string _root;

    public PairedDeviceStoreAdversarialTests()
    {
        _root = Path.Combine(Path.GetTempPath(), "mh-paired-adversarial-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_root);
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_root))
            {
                foreach (string file in Directory.EnumerateFiles(_root, "*", SearchOption.AllDirectories))
                {
                    File.SetAttributes(file, FileAttributes.Normal);
                }

                Directory.Delete(_root, recursive: true);
            }
        }
        catch (IOException)
        {
        }
    }

    private string StorePath => Path.Combine(_root, FilePairedDeviceStore.FileName);

    private static string FingerprintHex => Convert.ToHexString(new byte[32]).ToLowerInvariant();

    private static PairedDevice Device(string id, string displayName = "Pixel", int sourceCount = 2) =>
        PairedDevice.Create(id, FingerprintHex, displayName, Now, sourceCount);

    [Fact]
    public void A_valid_document_round_trips_a_device()
    {
        FilePairedDeviceStore store = new(_root);
        store.Save(Device("peer-a"));

        FilePairedDeviceStore reopened = new(_root);

        Assert.Null(reopened.LoadError);
        PairedDevice device = Assert.Single(reopened.GetAll());
        Assert.Equal("peer-a", device.DeviceId);
        Assert.Equal("Pixel", device.DisplayName);
        Assert.Equal(FingerprintHex, device.Fingerprint);
    }

    [Fact]
    public void Corrupt_json_is_quarantined_and_never_silently_emptied()
    {
        byte[] original = Encoding.UTF8.GetBytes("{ this is not json");
        File.WriteAllBytes(StorePath, original);

        FilePairedDeviceStore store = new(_root);

        Assert.Empty(store.GetAll());
        Assert.Equal(PairedDeviceStoreReasons.MalformedJson, store.LoadError);
        Assert.False(File.Exists(StorePath));
        Assert.NotNull(store.LastQuarantinedPath);
        Assert.Equal(original, File.ReadAllBytes(store.LastQuarantinedPath!));
    }

    [Fact]
    public void A_top_level_array_is_refused_as_malformed()
    {
        byte[] original = Encoding.UTF8.GetBytes("[]");
        File.WriteAllBytes(StorePath, original);

        FilePairedDeviceStore store = new(_root);

        Assert.Empty(store.GetAll());
        Assert.Equal(PairedDeviceStoreReasons.MalformedJson, store.LoadError);
        Assert.Equal(original, File.ReadAllBytes(store.LastQuarantinedPath!));
    }

    [Fact]
    public void An_unsupported_version_is_refused_and_names_the_version_it_saw()
    {
        File.WriteAllText(StorePath, "{\"version\":2,\"devices\":[]}");

        FilePairedDeviceStore store = new(_root);

        Assert.Empty(store.GetAll());
        Assert.NotNull(store.LoadError);
        Assert.StartsWith(PairedDeviceStoreReasons.UnsupportedVersion, store.LoadError, StringComparison.Ordinal);
        Assert.Contains(":2", store.LoadError, StringComparison.Ordinal);
        Assert.NotNull(store.LastQuarantinedPath);

        store.Save(Device("peer-after-quarantine"));
        Assert.Single(new FilePairedDeviceStore(_root).GetAll());
    }

    [Fact]
    public void Exceeding_the_capacity_is_refused_and_the_document_is_unchanged()
    {
        FilePairedDeviceStore store = new(_root) { Capacity = 2 };
        store.Save(Device("peer-a"));
        store.Save(Device("peer-b"));
        byte[] before = File.ReadAllBytes(StorePath);

        IOException error = Assert.Throws<IOException>(() => store.Save(Device("peer-c")));

        Assert.Equal(PairedDeviceStoreReasons.LimitReached, error.Message);
        Assert.Equal(before, File.ReadAllBytes(StorePath));
        Assert.Equal(2, new FilePairedDeviceStore(_root).GetAll().Count);
    }

    [Fact]
    public void An_exclusively_locked_document_refuses_writes_and_survives_the_attempt()
    {
        FilePairedDeviceStore seed = new(_root);
        seed.Save(Device("peer-a"));
        byte[] before = File.ReadAllBytes(StorePath);

        using (new FileStream(StorePath, FileMode.Open, FileAccess.ReadWrite, FileShare.None))
        {
            FilePairedDeviceStore blocked = new(_root);

            Assert.Empty(blocked.GetAll());
            Assert.Equal(PairedDeviceStoreReasons.StoreUnreadable, blocked.LoadError);
            IOException error = Assert.Throws<IOException>(() => blocked.Save(Device("peer-b")));
            Assert.Equal(PairedDeviceStoreReasons.StoreUnreadable, error.Message);
        }

        Assert.Equal(before, File.ReadAllBytes(StorePath));
        PairedDevice survivor = Assert.Single(new FilePairedDeviceStore(_root).GetAll());
        Assert.Equal("peer-a", survivor.DeviceId);
    }

    [Fact]
    public void A_read_only_document_refuses_writes_without_losing_the_device()
    {
        FilePairedDeviceStore seed = new(_root);
        seed.Save(Device("peer-a"));
        byte[] before = File.ReadAllBytes(StorePath);

        File.SetAttributes(StorePath, FileAttributes.ReadOnly);
        try
        {
            // IPairedDeviceStore.Save documents IOException for a failed persist, but Windows surfaces a
            // read-only destination as UnauthorizedAccessException from File.Replace. The CLI call sites
            // catch both, so this pins the real type rather than the documented one.
            Assert.Throws<UnauthorizedAccessException>(() => new FilePairedDeviceStore(_root).Save(Device("peer-a", "Renamed")));
        }
        finally
        {
            File.SetAttributes(StorePath, FileAttributes.Normal);
        }

        Assert.Equal(before, File.ReadAllBytes(StorePath));
        Assert.Equal("Pixel", Assert.Single(new FilePairedDeviceStore(_root).GetAll()).DisplayName);
    }

    [Fact]
    public void A_missing_document_reads_as_empty_and_the_first_save_creates_it()
    {
        FilePairedDeviceStore store = new(_root);

        Assert.Empty(store.GetAll());
        Assert.Null(store.LoadError);
        Assert.False(File.Exists(StorePath));

        store.Save(Device("peer-a"));

        Assert.True(File.Exists(StorePath));
        Assert.Single(new FilePairedDeviceStore(_root).GetAll());
    }

    [Fact]
    public void A_single_corrupt_row_is_skipped_while_the_healthy_rows_survive()
    {
        string document = "{\"version\":1,\"devices\":["
            + "{\"deviceId\":\"peer-ok\",\"fingerprint\":\"" + FingerprintHex + "\",\"displayName\":\"Ok\",\"pairedAtEpochMs\":1,\"lastSeenEpochMs\":1,\"sourceCount\":1},"
            + "{\"displayName\":\"No id\"},"
            + "{\"deviceId\":\"peer-bad-fingerprint\",\"fingerprint\":\"zz\"}"
            + "]}";
        File.WriteAllText(StorePath, document);

        FilePairedDeviceStore store = new(_root);

        Assert.Null(store.LoadError);
        PairedDevice survivor = Assert.Single(store.GetAll());
        Assert.Equal("peer-ok", survivor.DeviceId);
    }
}

/// <summary>
/// The transfer layer is the one place where a peer decides how many bytes this process allocates,
/// so the refusals must happen while looking at the header — never after trusting it.
/// </summary>
public sealed class SourceSyncTransferAdversarialTests
{
    private const string Challenge = "challenge-1";

    private static byte[] Envelope(int bytes = 40, byte fill = 0x5A)
    {
        byte[] envelope = new byte[bytes];
        Array.Fill(envelope, fill);
        return envelope;
    }

    private static byte[] DeclaredFrame(int declaredLength, params byte[] body)
    {
        using MemoryStream frame = new();
        Span<byte> header = stackalloc byte[4];
        BinaryPrimitives.WriteInt32BigEndian(header, declaredLength);
        frame.Write(header);
        frame.Write(body);
        return frame.ToArray();
    }

    private static byte[] ChunkFrame(int total, int offset, params byte[] payload)
    {
        byte[] body = new byte[9 + payload.Length];
        body[0] = 0x02;
        BinaryPrimitives.WriteInt32BigEndian(body.AsSpan(1, 4), total);
        BinaryPrimitives.WriteInt32BigEndian(body.AsSpan(5, 4), offset);
        payload.CopyTo(body, 9);
        return DeclaredFrame(body.Length, body);
    }

    private static byte[] ChallengeFrame(string text)
    {
        byte[] payload = Encoding.UTF8.GetBytes(text);
        byte[] body = new byte[1 + payload.Length];
        body[0] = 0x01;
        payload.CopyTo(body, 1);
        return DeclaredFrame(body.Length, body);
    }

    private static MemoryStream Wire(params byte[][] parts)
    {
        MemoryStream stream = new();
        foreach (byte[] part in parts)
        {
            stream.Write(part);
        }

        stream.Position = 0;
        return stream;
    }

    [Fact]
    public async Task A_healthy_transfer_round_trips_the_envelope_and_its_challenge()
    {
        byte[] envelope = Envelope(4000);
        using MemoryStream sent = new();

        SourceSyncTransferResult sentResult = await SourceSyncTransfer.SendAsync(sent, envelope, Challenge);

        Assert.True(sentResult.Succeeded);
        Assert.Equal(envelope, sentResult.Envelope);

        using MemoryStream wire = new(sent.ToArray());
        SourceSyncTransferResult received = await SourceSyncTransfer.ReceiveAsync(wire, Challenge);

        Assert.True(received.Succeeded);
        Assert.Null(received.Reason);
        Assert.Equal(envelope, received.Envelope);
        Assert.Equal(Challenge, received.Challenge);
    }

    [Fact]
    public async Task An_empty_envelope_is_refused_before_a_single_byte_is_written()
    {
        using MemoryStream stream = new();

        SourceSyncTransferResult result = await SourceSyncTransfer.SendAsync(stream, ReadOnlyMemory<byte>.Empty);

        Assert.False(result.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.PayloadTooLarge, result.Reason);
        Assert.True(stream.Length == 0, "a refused envelope must not write a single byte");
    }

    [Fact]
    public async Task An_envelope_over_the_ceiling_is_refused_at_send_time()
    {
        byte[] envelope = new byte[SourceSyncTransfer.MaximumEnvelopeBytes + 1];
        using MemoryStream stream = new();

        SourceSyncTransferResult result = await SourceSyncTransfer.SendAsync(stream, envelope);

        Assert.False(result.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.PayloadTooLarge, result.Reason);
        Assert.True(stream.Length == 0, "a refused envelope must not write a single byte");
    }

    [Theory]
    [InlineData(0)]
    [InlineData(8193)]
    [InlineData(int.MaxValue)]
    [InlineData(-1)]
    public async Task A_frame_that_declares_an_unusable_length_is_refused_without_buffering(int declaredLength)
    {
        using MemoryStream wire = new(DeclaredFrame(declaredLength, 0x02, 0x00, 0x00, 0x00, 0x01));
        Stopwatch stopwatch = Stopwatch.StartNew();

        SourceSyncTransferResult result = await SourceSyncTransfer.ReceiveAsync(wire);

        stopwatch.Stop();
        Assert.False(result.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.FrameTruncated, result.Reason);
        Assert.Empty(result.Envelope);
        Assert.True(stopwatch.Elapsed < TimeSpan.FromSeconds(1), "a refused declaration must not be waited on");
    }

    [Fact]
    public async Task A_chunk_that_announces_zero_bytes_is_refused()
    {
        using MemoryStream wire = Wire(ChunkFrame(0, 0));

        SourceSyncTransferResult result = await SourceSyncTransfer.ReceiveAsync(wire);

        Assert.False(result.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.FrameInvalid, result.Reason);
    }

    [Fact]
    public async Task A_chunk_that_announces_more_than_the_ceiling_is_refused_before_allocating()
    {
        int declared = SourceSyncTransfer.MaximumEnvelopeBytes + 1;
        using MemoryStream wire = Wire(ChunkFrame(declared, 0, 1, 2, 3));
        Stopwatch stopwatch = Stopwatch.StartNew();

        SourceSyncTransferResult result = await SourceSyncTransfer.ReceiveAsync(wire);

        stopwatch.Stop();
        Assert.False(result.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.PayloadTooLarge, result.Reason);
        Assert.True(stopwatch.Elapsed < TimeSpan.FromSeconds(1), "the ceiling must be enforced from the header");
        Assert.Empty(result.Envelope);
    }

    [Fact]
    public async Task A_negative_offset_is_refused()
    {
        using MemoryStream wire = Wire(ChunkFrame(10, -1, 1, 2));

        SourceSyncTransferResult result = await SourceSyncTransfer.ReceiveAsync(wire);

        Assert.False(result.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.FrameInvalid, result.Reason);
    }

    [Fact]
    public async Task A_challenge_frame_that_arrives_second_is_refused()
    {
        // The chunk announces ten bytes and delivers five, so the receiver is still reading when the
        // challenge frame shows up second; a complete chunk would end the transfer before it is seen.
        using MemoryStream wire = Wire(ChunkFrame(10, 0, 1, 2, 3, 4, 5), ChallengeFrame(Challenge));

        SourceSyncTransferResult result = await SourceSyncTransfer.ReceiveAsync(wire);

        Assert.False(result.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.FrameInvalid, result.Reason);
        Assert.Contains("before the first chunk", result.Detail ?? "", StringComparison.Ordinal);
    }

    [Fact]
    public async Task A_chunk_that_skips_bytes_is_refused()
    {
        using MemoryStream wire = Wire(ChunkFrame(10, 0, 1, 2, 3, 4, 5), ChunkFrame(10, 6, 6, 7, 8, 9));

        SourceSyncTransferResult result = await SourceSyncTransfer.ReceiveAsync(wire);

        Assert.False(result.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.FrameInvalid, result.Reason);
        Assert.Contains("starts at 6", result.Detail ?? "", StringComparison.Ordinal);
    }

    [Fact]
    public async Task Chunks_that_disagree_about_the_total_are_refused()
    {
        using MemoryStream wire = Wire(ChunkFrame(10, 0, 1, 2, 3, 4, 5), ChunkFrame(11, 5, 6, 7, 8, 9, 10));

        SourceSyncTransferResult result = await SourceSyncTransfer.ReceiveAsync(wire);

        Assert.False(result.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.FrameInvalid, result.Reason);
        Assert.Contains("announced 11", result.Detail ?? "", StringComparison.Ordinal);
    }

    [Fact]
    public async Task A_chunk_that_runs_past_the_announced_total_is_refused()
    {
        using MemoryStream wire = Wire(ChunkFrame(4, 0, 1, 2, 3, 4, 5, 6));

        SourceSyncTransferResult result = await SourceSyncTransfer.ReceiveAsync(wire);

        Assert.False(result.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.FrameInvalid, result.Reason);
        Assert.Contains("runs past", result.Detail ?? "", StringComparison.Ordinal);
        Assert.Empty(result.Envelope);
    }

    [Fact]
    public async Task A_peer_that_closes_mid_envelope_is_reported_as_truncated()
    {
        using MemoryStream wire = Wire(ChallengeFrame(Challenge), ChunkFrame(40, 0, 1, 2, 3, 4, 5));

        SourceSyncTransferResult result = await SourceSyncTransfer.ReceiveAsync(wire, Challenge);

        Assert.False(result.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.FrameTruncated, result.Reason);
        Assert.Empty(result.Envelope);
    }

    [Fact]
    public async Task A_peer_that_skips_the_expected_challenge_is_refused()
    {
        using MemoryStream wire = Wire(ChunkFrame(4, 0, 1, 2, 3, 4));

        SourceSyncTransferResult result = await SourceSyncTransfer.ReceiveAsync(wire, Challenge);

        Assert.False(result.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.ChallengeMismatch, result.Reason);
        Assert.Empty(result.Envelope);
    }

    [Fact]
    public async Task A_peer_that_announces_the_wrong_challenge_is_refused()
    {
        using MemoryStream wire = Wire(ChallengeFrame("challenge-2"), ChunkFrame(4, 0, 1, 2, 3, 4));

        SourceSyncTransferResult result = await SourceSyncTransfer.ReceiveAsync(wire, Challenge);

        Assert.False(result.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.ChallengeMismatch, result.Reason);
        Assert.Empty(result.Envelope);
    }

    [Fact]
    public async Task An_unknown_frame_kind_is_refused_and_quotes_only_the_kind()
    {
        byte[] payload = Encoding.UTF8.GetBytes("PW-MARKER-7d21");
        byte[] body = new byte[1 + payload.Length];
        body[0] = 0x7F;
        payload.CopyTo(body, 1);
        using MemoryStream wire = new(DeclaredFrame(body.Length, body));

        SourceSyncTransferResult result = await SourceSyncTransfer.ReceiveAsync(wire);

        Assert.False(result.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.FrameInvalid, result.Reason);
        Assert.Contains("0x7f", result.Detail ?? "", StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("PW-MARKER-7d21", result.Detail ?? "", StringComparison.Ordinal);
    }

    [Fact]
    public void The_refusal_codes_are_the_documented_stable_set()
    {
        Assert.Equal(10, SourceSyncTransferReasons.All.Count);
        Assert.Equal(SourceSyncTransferReasons.All.Count, SourceSyncTransferReasons.All.Distinct(StringComparer.Ordinal).Count());

        foreach (string reason in SourceSyncTransferReasons.All)
        {
            Assert.Matches("^[a-z]+(-[a-z]+)*$", reason);
            SourceSyncTransferResult failed = SourceSyncTransferResult.Failed(reason, "detail");
            Assert.False(failed.Succeeded);
            Assert.Equal(reason, failed.Reason);
        }
    }
}

/// <summary>
/// The command line is the only Sync entry point a script can call, so it has to be boring about
/// paths: whatever it does with a hostile root must be visible, repeatable and never leave a
/// half-written document behind.
/// </summary>
public sealed class SyncCliRootAdversarialTests : IDisposable
{
    private static readonly DateTimeOffset FixedNow = DateTimeOffset.FromUnixTimeMilliseconds(1_760_000_000_000L);

    private readonly string _root;

    public SyncCliRootAdversarialTests()
    {
        _root = Path.Combine(Path.GetTempPath(), "mhcli-adversarial-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_root);
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_root))
            {
                Directory.Delete(_root, recursive: true);
            }
        }
        catch (IOException)
        {
        }
    }

    private static (int ExitCode, string Stdout, string Stderr) Run(params string[] args)
    {
        using StringWriter stdout = new();
        using StringWriter stderr = new();
        int exitCode = SyncCliProgram.Run(args, stdout, stderr, () => FixedNow);
        return (exitCode, stdout.ToString(), stderr.ToString());
    }

    private static JsonElement ParseSingleDocument(string stdout)
    {
        string trimmed = stdout.Trim();
        Assert.Single(trimmed.Split((char)10));
        using JsonDocument document = JsonDocument.Parse(trimmed);
        return document.RootElement.Clone();
    }

    private string SeededRoot()
    {
        string root = Path.Combine(_root, "seeded");
        FileLocalSourceStore store = new(root);
        store.Save(new LocalSourceRecord
        {
            ServerId = "srv-emby",
            Type = ServerType.Emby,
            Url = "http://nas-a:8096/emby/",
            AccountScope = "alice",
            DisplayName = "Living room",
            RecordRevision = 1,
        });
        return root;
    }

    private static Dictionary<string, string> Snapshot(string root)
    {
        Dictionary<string, string> snapshot = new(StringComparer.OrdinalIgnoreCase);
        if (!Directory.Exists(root))
        {
            return snapshot;
        }

        foreach (string file in Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories))
        {
            snapshot[file] = Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(file)));
        }

        return snapshot;
    }

    private static void AssertSnapshotUnchanged(Dictionary<string, string> before, Dictionary<string, string> after)
    {
        Assert.Equal(before.Count, after.Count);
        foreach (KeyValuePair<string, string> entry in before)
        {
            Assert.True(after.ContainsKey(entry.Key), "file disappeared: " + entry.Key);
            Assert.Equal(entry.Value, after[entry.Key]);
        }
    }

    [Fact]
    public void Export_creates_the_missing_destination_directory_and_reports_the_real_path()
    {
        string root = SeededRoot();
        string destination = Path.Combine(_root, "fresh", "nested", SyncCliRunner.DefaultEnvelopeFileName);

        (int exitCode, string stdout, string stderr) = Run("export", "--root", root, "--out", destination, "--json");

        Assert.True(exitCode == 0, "export failed: " + stderr);
        Assert.True(File.Exists(destination));
        JsonElement document = ParseSingleDocument(stdout);
        Assert.Equal("export", document.GetProperty("command").GetString());
        Assert.Equal(destination, document.GetProperty("path").GetString());
    }

    [Fact]
    public void A_root_that_is_a_file_is_tolerated_but_never_overwritten()
    {
        string fileRoot = Path.Combine(_root, "root-is-a-file");
        byte[] original = Encoding.UTF8.GetBytes("this file happens to sit where a device root was expected");
        File.WriteAllBytes(fileRoot, original);

        (int exitCode, string stdout, string stderr) = Run("pair", "list", "--root", fileRoot, "--json");

        Assert.True(exitCode == 0, "pair list failed: " + stderr);
        Assert.Equal(original, File.ReadAllBytes(fileRoot));
        JsonElement document = ParseSingleDocument(stdout);
        Assert.Equal(0, document.GetProperty("count").GetInt32());
    }

    [Fact]
    public void A_root_with_a_parent_traversal_is_resolved_by_the_operating_system()
    {
        string root = SeededRoot();
        Directory.CreateDirectory(Path.Combine(_root, "a"));
        string traversal = Path.Combine(_root, "a", "..", "escaped", SyncCliRunner.DefaultEnvelopeFileName);
        string resolved = Path.Combine(_root, "escaped", SyncCliRunner.DefaultEnvelopeFileName);

        (int exitCode, string stdout, string stderr) = Run("export", "--root", root, "--out", traversal, "--json");

        Assert.True(exitCode == 0, "export failed: " + stderr);
        Assert.True(File.Exists(resolved));
        // The runner does not canonicalise --out: the JSON echoes the argument exactly as given while
        // the operating system resolves ".." and the bytes land in the parent directory.
        Assert.Equal(traversal, ParseSingleDocument(stdout).GetProperty("path").GetString());
        Assert.Single(Directory.EnumerateFiles(Path.Combine(_root, "escaped")));
        Assert.Equal(
            Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(resolved))),
            Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(traversal))));
    }

    [Theory]
    [InlineData("pair", "list")]
    [InlineData("baseline", "show")]
    public void Every_read_command_is_happy_with_a_root_that_does_not_exist(string group, string subcommand)
    {
        string missing = Path.Combine(_root, "never-created");

        (int exitCode, string stdout, string stderr) = Run(group, subcommand, "--root", missing, "--json");

        Assert.True(exitCode == 0, group + " " + subcommand + " failed: " + stderr);
        Assert.False(Directory.Exists(missing));
        JsonElement document = ParseSingleDocument(stdout);
        Assert.Equal(group, document.GetProperty("command").GetString());
        Assert.Equal(missing, document.GetProperty("root").GetString());
    }

    [Fact]
    public void Json_mode_writes_exactly_one_document_to_stdout_for_success_and_failure()
    {
        string root = SeededRoot();
        string missingDevice = "ghost-device";

        (int okExit, string okOut, _) = Run("pair", "list", "--root", root, "--json");
        Assert.Equal(0, okExit);
        JsonElement ok = ParseSingleDocument(okOut);
        Assert.Equal(0, ok.GetProperty("count").GetInt32());

        (int failedExit, string failedOut, string failedErr) = Run("pair", "forget", "--root", root, "--device", missingDevice, "--json");
        Assert.Equal(CliExitCode.InputProblem, failedExit);
        JsonElement failure = ParseSingleDocument(failedOut);
        Assert.False(failure.GetProperty("ok").GetBoolean());
        Assert.Equal("device-unknown", failure.GetProperty("error").GetProperty("code").GetString());
        Assert.StartsWith("error device-unknown:", failedErr, StringComparison.Ordinal);
    }

    [Fact]
    public void Text_mode_keeps_stdout_empty_and_sends_the_failure_to_stderr()
    {
        string root = SeededRoot();

        (int exitCode, string stdout, string stderr) = Run("pair", "forget", "--root", root, "--device", "ghost-device");

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        Assert.Equal("", stdout.Trim());
        Assert.Contains("error device-unknown:", stderr, StringComparison.Ordinal);
        Assert.Contains("ghost-device", stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void A_plan_without_apply_leaves_every_byte_on_disk_alone()
    {
        string root = SeededRoot();
        string envelope = Path.Combine(_root, "envelope.json");
        (int exportExit, _, string exportErr) = Run("export", "--root", root, "--out", envelope, "--json");
        Assert.True(exportExit == 0, "export failed: " + exportErr);

        Dictionary<string, string> before = Snapshot(root);

        (int exitCode, string stdout, string stderr) = Run("import", "--root", root, "--in", envelope, "--json");

        Assert.True(exitCode == 0 || exitCode == CliExitCode.NeedsApproval, "unexpected exit " + exitCode + ": " + stderr);
        JsonElement document = ParseSingleDocument(stdout);
        Assert.True(document.GetProperty("dryRun").GetBoolean());
        AssertSnapshotUnchanged(before, Snapshot(root));
    }

    [Fact]
    public void Pair_forget_previews_without_writing_and_only_apply_removes_the_device()
    {
        string root = Path.Combine(_root, "paired");
        Directory.CreateDirectory(root);
        FilePairedDeviceStore store = new(root);
        store.Save(PairedDevice.Create("peer-a", Convert.ToHexString(new byte[32]).ToLowerInvariant(), "Pixel", 1_760_000_000_000L));
        string storePath = Path.Combine(root, FilePairedDeviceStore.FileName);
        byte[] before = File.ReadAllBytes(storePath);

        (int previewExit, string previewOut, _) = Run("pair", "forget", "--root", root, "--device", "peer-a", "--json");

        Assert.Equal(0, previewExit);
        JsonElement preview = ParseSingleDocument(previewOut);
        Assert.True(preview.GetProperty("dryRun").GetBoolean());
        Assert.False(preview.GetProperty("applied").GetBoolean());
        Assert.Equal(before, File.ReadAllBytes(storePath));
        Assert.Single(new FilePairedDeviceStore(root).GetAll());

        (int applyExit, string applyOut, _) = Run("pair", "forget", "--root", root, "--device", "peer-a", "--json", "--apply");

        Assert.Equal(0, applyExit);
        JsonElement applied = ParseSingleDocument(applyOut);
        Assert.False(applied.GetProperty("dryRun").GetBoolean());
        Assert.True(applied.GetProperty("applied").GetBoolean());
        Assert.Empty(new FilePairedDeviceStore(root).GetAll());
    }
}

/// <summary>
/// Every failure path a script can reach is also a place a passphrase or a payload could leak into
/// a log, a terminal or a JSON document. Each case here drives a real failure and then searches the
/// whole observable surface for the marker that was handed to the tool.
/// </summary>
public sealed class SyncCliSecretLeakAdversarialTests : IDisposable
{
    private static readonly DateTimeOffset FixedNow = DateTimeOffset.FromUnixTimeMilliseconds(1_760_000_000_000L);
    private const string Marker = "PW-MARKER-7d21";

    private readonly string _root;

    public SyncCliSecretLeakAdversarialTests()
    {
        _root = Path.Combine(Path.GetTempPath(), "mhcli-leak-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_root);
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_root))
            {
                Directory.Delete(_root, recursive: true);
            }
        }
        catch (IOException)
        {
        }
    }

    private static (int ExitCode, string Stdout, string Stderr) Run(params string[] args)
    {
        using StringWriter stdout = new();
        using StringWriter stderr = new();
        int exitCode = SyncCliProgram.Run(args, stdout, stderr, () => FixedNow);
        return (exitCode, stdout.ToString(), stderr.ToString());
    }

    private static void AssertNoMarker(string stdout, string stderr)
    {
        Assert.DoesNotContain(Marker, stdout, StringComparison.Ordinal);
        Assert.DoesNotContain(Marker, stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void A_missing_input_file_never_echoes_the_passphrase()
    {
        string root = Path.Combine(_root, "root");
        Directory.CreateDirectory(root);

        (int exitCode, string stdout, string stderr) = Run(
            "import", "--root", root, "--in", Path.Combine(_root, "absent.json"), "--passphrase", Marker, "--json");

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        Assert.Contains("input-file-missing", stdout, StringComparison.Ordinal);
        AssertNoMarker(stdout, stderr);
    }

    [Fact]
    public void A_damaged_envelope_never_echoes_the_passphrase()
    {
        string root = Path.Combine(_root, "root");
        Directory.CreateDirectory(root);
        string damaged = Path.Combine(_root, "damaged.json");
        File.WriteAllText(damaged, "{ not an envelope at all }");

        (int exitCode, string stdout, string stderr) = Run(
            "import", "--root", root, "--in", damaged, "--passphrase", Marker, "--json");

        Assert.NotEqual(0, exitCode);
        AssertNoMarker(stdout, stderr);
        Assert.DoesNotContain(Marker, File.ReadAllText(damaged), StringComparison.Ordinal);
    }

    [Fact]
    public void A_damaged_baseline_never_echoes_the_device_or_the_passphrase()
    {
        string root = Path.Combine(_root, "root");
        Directory.CreateDirectory(root);
        byte[] original = Encoding.UTF8.GetBytes("{ broken baseline }");
        File.WriteAllBytes(Path.Combine(root, FileSourceSyncBaselineStore.FileName), original);

        (int exitCode, string stdout, string stderr) = Run(
            "baseline", "show", "--root", root, "--json");

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        Assert.DoesNotContain(Marker, stdout + stderr, StringComparison.Ordinal);
        Assert.DoesNotContain("{ broken baseline }", stdout, StringComparison.Ordinal);

        string quarantine = Assert.Single(
            Directory.EnumerateFiles(root, FileSourceSyncBaselineStore.CorruptPrefix + "*"));
        Assert.Equal(original, File.ReadAllBytes(quarantine));
    }

    [Fact]
    public void A_rejected_pairing_link_never_echoes_its_code()
    {
        const string Payload = "SECRETPAYLOADZZZ";

        (int exitCode, string stdout, string stderr) = Run(
            "pair", "verify", "--link", PairingLink.Prefix + "code=" + Payload, "--json");

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        Assert.DoesNotContain(Payload, stdout, StringComparison.Ordinal);
        Assert.DoesNotContain(Payload, stderr, StringComparison.Ordinal);
        using JsonDocument document = JsonDocument.Parse(stdout.Trim());
        Assert.False(document.RootElement.GetProperty("ok").GetBoolean());
    }

    [Fact]
    public void An_oversized_pairing_link_is_refused_without_being_quoted()
    {
        string filler = new('z', PairingLink.MaximumLength + 10);

        (int exitCode, string stdout, string stderr) = Run(
            "pair", "verify", "--link", PairingLink.Prefix + "code=" + filler, "--json");

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        Assert.DoesNotContain(filler[..200], stdout, StringComparison.Ordinal);
        Assert.DoesNotContain(filler[..200], stderr, StringComparison.Ordinal);
        Assert.Contains(PairingLinkReasons.TooLong, stdout, StringComparison.Ordinal);
    }
}

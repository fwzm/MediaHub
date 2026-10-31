using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using MediaHub.Domain;
using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// W6-33: the sync page's transfer section has no UI and no store, so everything it promises is testable here.
/// </summary>
/// <remarks>
/// Every case runs against a real loopback socket, because the presenter's whole job is to sit between a socket and
/// a sentence. The two properties that must never regress are asserted structurally as well as behaviourally: a
/// received envelope leaves as JSON text and can never be written (the presenter's surface has no store), and
/// <c>delivery-unconfirmed</c> is reported as a failure rather than softened into a delivery.
/// </remarks>
public sealed class SyncTransferPresenterTests : IDisposable
{
    private const string LocalDeviceId = "presenter-local-1";
    private const string PeerDeviceId = "presenter-peer-1";
    private const string EmbyUrl = "http://192.168.5.20:8096/emby";
    private const string Secret = "correct horse battery staple";
    private static readonly string WrongPin = new('a', 64);

    private readonly List<SyncTransferPresenter> _presenters = [];
    private readonly List<SilentPeer> _peers = [];
    private readonly List<string> _directories = [];

    /// <summary>Accepting one envelope is a preview input, not a write.</summary>
    [Fact]
    public async Task Receive_accepts_one_envelope_and_hands_it_over_as_json()
    {
        SyncTransferPresenter presenter = NewPresenter(withEnvelope: false);
        SyncTransferOutcome prepared = presenter.PrepareReceive(new SyncTransferReceiveForm());
        Assert.True(prepared.Succeeded, prepared.Summary);
        int port = prepared.BoundPort ?? 0;
        Assert.True(port > 0, prepared.Summary);

        Task<SyncTransferOutcome> waiting = presenter.ReceiveOnceAsync(TimeSpan.FromSeconds(10));
        SourceSyncEnvelope expected = EnvelopeOf(PeerDeviceId, Source("sync-1", "Living Room Emby"));
        byte[] payload = Encoding.UTF8.GetBytes(SourceSyncCodec.Serialize(expected));
        SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync("127.0.0.1", port, payload);
        Assert.True(sent.Succeeded, sent.Reason);

        SyncTransferOutcome received = await waiting;
        Assert.True(received.Succeeded, received.Summary);
        Assert.Equal(payload.Length, received.Bytes);
        Assert.Equal(1, received.Frames);
        Assert.Equal(SourceSyncCodec.Serialize(expected), received.EnvelopeJson);
        Assert.Equal(expected.SnapshotId, received.Envelope?.SnapshotId);
        Assert.Contains("已接收 1 个信封", received.Summary);
        Assert.False(presenter.IsListening);
        Assert.Equal(SyncTransferReceiveState.Idle.StatusText, presenter.ReceiveState.StatusText);
    }

    /// <summary>The exact string the page hands to the apply preview must decode.</summary>
    [Fact]
    public async Task The_received_json_round_trips_through_the_apply_preview_codec()
    {
        SyncTransferPresenter presenter = NewPresenter(withEnvelope: false);
        SyncTransferOutcome prepared = presenter.PrepareReceive(new SyncTransferReceiveForm());
        int port = prepared.BoundPort ?? 0;
        Task<SyncTransferOutcome> waiting = presenter.ReceiveOnceAsync(TimeSpan.FromSeconds(10));
        SourceSyncEnvelope expected = EnvelopeOf(PeerDeviceId, Source("sync-1", "Living Room Emby"));
        await SourceSyncTransferClient.SendAsync(
            "127.0.0.1",
            port,
            Encoding.UTF8.GetBytes(SourceSyncCodec.Serialize(expected)));
        SyncTransferOutcome received = await waiting;

        Assert.True(received.Succeeded, received.Summary);
        Assert.True(
            SourceSyncCodec.TryDeserialize(received.EnvelopeJson, out SourceSyncEnvelope? decoded, out string? error),
            error);
        Assert.NotNull(decoded);
        Assert.Equal(expected.SnapshotId, decoded!.SnapshotId);
        Assert.Single(decoded.Sources);
        Assert.Equal("Living Room Emby", decoded.Sources[0].DisplayName);
    }

    /// <summary>The write-freedom is structural: this type cannot even be handed a store.</summary>
    [Fact]
    public void The_presenter_exposes_no_store_so_a_received_envelope_cannot_be_written()
    {
        Type type = typeof(SyncTransferPresenter);
        foreach (ConstructorInfo constructor in type.GetConstructors())
        {
            foreach (ParameterInfo parameter in constructor.GetParameters())
            {
                Assert.False(
                    typeof(ILocalSourceStore).IsAssignableFrom(parameter.ParameterType),
                    "the presenter must not accept an ILocalSourceStore: " + constructor);
            }
        }

        Assert.DoesNotContain(type.GetMethods(), method => method.Name.Contains("Apply", StringComparison.Ordinal));
        Assert.DoesNotContain(type.GetMethods(), method => method.Name.Contains("Import", StringComparison.Ordinal));
        Assert.DoesNotContain(type.GetMethods(), method => method.Name.Contains("Save", StringComparison.Ordinal));
        Assert.DoesNotContain(type.GetProperties(), property => property.Name.Contains("Store", StringComparison.Ordinal));
    }

    /// <summary>A real store next to the presenter stays byte-identical: receiving only produces text.</summary>
    [Fact]
    public async Task A_real_store_next_to_the_presenter_is_never_touched_by_a_receive()
    {
        string directory = NewDirectory();
        FileLocalSourceStore store = new(directory);
        store.Save(Source("sync-local", "Local Emby"));
        Assert.Single(store.GetAll());

        SyncTransferPresenter presenter = NewPresenter(withEnvelope: false);
        SyncTransferOutcome prepared = presenter.PrepareReceive(new SyncTransferReceiveForm());
        Task<SyncTransferOutcome> waiting = presenter.ReceiveOnceAsync(TimeSpan.FromSeconds(10));
        await SourceSyncTransferClient.SendAsync(
            "127.0.0.1",
            prepared.BoundPort ?? 0,
            Encoding.UTF8.GetBytes(SourceSyncCodec.Serialize(EnvelopeOf(PeerDeviceId, Source("sync-2", "Peer Emby")))));
        SyncTransferOutcome received = await waiting;

        Assert.True(received.Succeeded, received.Summary);
        Assert.Single(store.GetAll());
        Assert.Equal("sync-local", store.GetAll()[0].SyncSourceId);
        Assert.False(File.Exists(Path.Combine(directory, "sync-baseline.json")));
    }

    /// <summary>One press of "send" puts the local envelope on the wire.</summary>
    [Fact]
    public async Task Send_delivers_the_local_envelope_to_a_real_listener()
    {
        SourceSyncTransferStartResult started = SourceSyncTransferListener.Start();
        Assert.True(started.Started, started.Reason);
        await using SourceSyncTransferListener listener = started.Listener!;
        Task<SourceSyncTransferResult> receiving = listener.ReceiveOnceAsync(null, TimeSpan.FromSeconds(10));

        SyncTransferPresenter presenter = NewPresenter();
        SyncTransferOutcome sent = await presenter.SendAsync(SendForm(listener.Port));
        Assert.True(sent.Succeeded, sent.Summary);
        Assert.Equal(1, sent.Frames);
        Assert.Null(sent.DeliveryConfirmed);
        Assert.Contains("没有要求送达确认", sent.Summary);

        SourceSyncTransferResult received = await receiving;
        Assert.True(received.Succeeded, received.Reason);
        Assert.True(
            SourceSyncCodec.TryDeserialize(
                Encoding.UTF8.GetString(received.Envelope),
                out SourceSyncEnvelope? decoded,
                out string? error),
            error);
        Assert.NotNull(decoded);
        Assert.Equal(LocalDeviceId, decoded!.DeviceId);
        Assert.Single(decoded.Sources);
        Assert.Equal("Living Room Emby", decoded.Sources[0].DisplayName);
    }

    /// <summary>The peer's close is the only receipt this protocol has, and it is reported as one.</summary>
    [Fact]
    public async Task A_confirmed_send_reports_the_peer_close_as_delivery()
    {
        SourceSyncTransferStartResult started = SourceSyncTransferListener.Start();
        Assert.True(started.Started, started.Reason);
        await using SourceSyncTransferListener listener = started.Listener!;
        Task<SourceSyncTransferResult> receiving = listener.ReceiveOnceAsync(null, TimeSpan.FromSeconds(10));

        SyncTransferPresenter presenter = NewPresenter();
        SyncTransferSendForm form = SendForm(listener.Port) with { ConfirmDelivery = true };
        SyncTransferOutcome sent = await presenter.SendAsync(form);

        Assert.True(sent.Succeeded, sent.Summary);
        Assert.True(sent.DeliveryConfirmed);
        Assert.Contains("确认送达", sent.Summary);
        SourceSyncTransferResult received = await receiving;
        Assert.True(received.Succeeded, received.Reason);
    }

    /// <summary>TLS with the listener's own fingerprint reaches the far side.</summary>
    [Fact]
    public async Task A_tls_send_pinned_to_the_listeners_fingerprint_succeeds()
    {
        SyncTransferPresenter receiver = NewPresenter(withEnvelope: false);
        SyncTransferOutcome prepared = receiver.PrepareReceive(new SyncTransferReceiveForm { UseTls = true });
        Assert.True(prepared.Succeeded, prepared.Summary);
        string pin = prepared.CertificateFingerprint ?? string.Empty;
        Assert.Equal(64, pin.Length);

        Task<SyncTransferOutcome> waiting = receiver.ReceiveOnceAsync(TimeSpan.FromSeconds(10));
        SyncTransferPresenter sender = NewPresenter(deviceId: "presenter-sender-1");
        SyncTransferOutcome sent = await sender.SendAsync(
            SendForm(prepared.BoundPort ?? 0) with { UseTls = true, ExpectedFingerprint = pin });
        Assert.True(sent.Succeeded, sent.Summary);
        Assert.Equal(pin, sent.CertificateFingerprint);

        SyncTransferOutcome received = await waiting;
        Assert.True(received.Succeeded, received.Summary);
        Assert.Equal(1, received.Frames);
    }

    /// <summary>A pin that does not match is refused before an envelope byte is written.</summary>
    [Fact]
    public async Task A_tls_send_with_a_wrong_pin_fails_with_tls_fingerprint_mismatch()
    {
        SyncTransferPresenter receiver = NewPresenter(withEnvelope: false);
        SyncTransferOutcome prepared = receiver.PrepareReceive(new SyncTransferReceiveForm { UseTls = true });
        Task<SyncTransferOutcome> waiting = receiver.ReceiveOnceAsync(TimeSpan.FromSeconds(10));

        SyncTransferPresenter sender = NewPresenter();
        SyncTransferOutcome sent = await sender.SendAsync(
            SendForm(prepared.BoundPort ?? 0) with { UseTls = true, ExpectedFingerprint = WrongPin });

        Assert.False(sent.Succeeded);
        Assert.Equal(SourceSyncTlsReasons.FingerprintMismatch, sent.Reason);
        Assert.Contains("tls-fingerprint-mismatch", sent.Summary);

        SyncTransferOutcome received = await waiting;
        Assert.False(received.Succeeded);
        Assert.NotNull(received.Reason);
        Assert.Null(received.EnvelopeJson);
    }

    /// <summary>A plaintext peer cannot talk to a TLS listener, and the listener says so with a stable code.</summary>
    [Fact]
    public async Task A_plaintext_peer_on_a_tls_listener_is_reported_as_a_handshake_failure()
    {
        SyncTransferPresenter receiver = NewPresenter(withEnvelope: false);
        SyncTransferOutcome prepared = receiver.PrepareReceive(new SyncTransferReceiveForm { UseTls = true });
        Task<SyncTransferOutcome> waiting = receiver.ReceiveOnceAsync(TimeSpan.FromSeconds(10));

        await SourceSyncTransferClient.SendAsync(
            "127.0.0.1",
            prepared.BoundPort ?? 0,
            Encoding.UTF8.GetBytes(SourceSyncCodec.Serialize(EnvelopeOf(PeerDeviceId, Source("sync-1", "Living Room Emby")))));

        SyncTransferOutcome received = await waiting;
        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTlsReasons.HandshakeFailed, received.Reason);
        Assert.Contains("tls-handshake-failed", received.Summary);
    }

    /// <summary>A TLS peer cannot talk to a plaintext listener either.</summary>
    [Fact]
    public async Task A_tls_send_to_a_plaintext_listener_fails_with_a_stable_code()
    {
        SyncTransferPresenter receiver = NewPresenter(withEnvelope: false);
        SyncTransferOutcome prepared = receiver.PrepareReceive(new SyncTransferReceiveForm());
        Task<SyncTransferOutcome> waiting = receiver.ReceiveOnceAsync(TimeSpan.FromSeconds(10));

        SyncTransferPresenter sender = NewPresenter();
        SyncTransferOutcome sent = await sender.SendAsync(
            SendForm(prepared.BoundPort ?? 0) with { UseTls = true, ExpectedFingerprint = WrongPin });

        Assert.False(sent.Succeeded);
        Assert.Equal(SourceSyncTlsReasons.HandshakeFailed, sent.Reason);

        SyncTransferOutcome received = await waiting;
        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTlsReasons.NotEnabled, received.Reason);
        Assert.Contains("tls-not-enabled", received.Summary);
    }

    /// <summary>Two different secrets are reported as a tag mismatch, never as a success.</summary>
    [Fact]
    public async Task A_different_secret_is_reported_as_auth_mismatch()
    {
        SyncTransferPresenter receiver = NewPresenter(withEnvelope: false);
        SyncTransferOutcome prepared = receiver.PrepareReceive(new SyncTransferReceiveForm { Secret = "alpha" });
        Task<SyncTransferOutcome> waiting = receiver.ReceiveOnceAsync(TimeSpan.FromSeconds(10));

        SyncTransferPresenter sender = NewPresenter();
        await sender.SendAsync(SendForm(prepared.BoundPort ?? 0) with { Secret = "beta" });

        SyncTransferOutcome received = await waiting;
        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferAuthReasons.AuthMismatch, received.Reason);
        Assert.Contains("auth-mismatch", received.Summary);
        Assert.Null(received.EnvelopeJson);
    }

    /// <summary>An authenticated receiver refuses an unauthenticated sender instead of accepting it silently.</summary>
    [Fact]
    public async Task An_unauthenticated_send_is_reported_as_a_missing_tag()
    {
        SyncTransferPresenter receiver = NewPresenter(withEnvelope: false);
        SyncTransferOutcome prepared = receiver.PrepareReceive(new SyncTransferReceiveForm { Secret = Secret });
        Task<SyncTransferOutcome> waiting = receiver.ReceiveOnceAsync(TimeSpan.FromSeconds(10));

        SyncTransferPresenter sender = NewPresenter();
        SyncTransferOutcome sent = await sender.SendAsync(SendForm(prepared.BoundPort ?? 0));
        Assert.True(sent.Succeeded, sent.Summary);

        SyncTransferOutcome received = await waiting;
        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferAuthReasons.AuthMissing, received.Reason);
    }

    /// <summary>The same secret on both ends carries the tag frame and decodes.</summary>
    [Fact]
    public async Task The_same_secret_on_both_ends_delivers_three_frames()
    {
        SyncTransferPresenter receiver = NewPresenter(withEnvelope: false);
        SyncTransferOutcome prepared = receiver.PrepareReceive(new SyncTransferReceiveForm { Secret = Secret });
        Task<SyncTransferOutcome> waiting = receiver.ReceiveOnceAsync(TimeSpan.FromSeconds(10));

        SyncTransferPresenter sender = NewPresenter();
        SyncTransferOutcome sent = await sender.SendAsync(
            SendForm(prepared.BoundPort ?? 0) with { Secret = Secret });
        Assert.True(sent.Succeeded, sent.Summary);
        Assert.Equal(3, sent.Frames);

        SyncTransferOutcome received = await waiting;
        Assert.True(received.Succeeded, received.Summary);
        Assert.Equal(3, received.Frames);
    }

    /// <summary>A peer that reads and never closes is not a delivery, and the code says so.</summary>
    [Fact]
    public async Task A_peer_that_never_closes_is_reported_as_delivery_unconfirmed()
    {
        SilentPeer peer = NewPeer();
        SyncTransferPresenter presenter = NewPresenter();
        SyncTransferOutcome sent = await presenter.SendAsync(
            SendForm(peer.Port) with { ConfirmDelivery = true },
            TimeSpan.FromSeconds(10));

        Assert.False(sent.Succeeded);
        Assert.Equal(SourceSyncDeliveryReasons.DeliveryUnconfirmed, sent.Reason);
        Assert.Contains("delivery-unconfirmed", sent.Summary);
        Assert.Contains("不算送达", sent.Summary);
        Assert.True(peer.BytesRead > 0, "the frames must have reached the silent peer before the wait timed out");
    }

    /// <summary>An empty host is a form problem, not a connection attempt.</summary>
    [Fact]
    public async Task An_empty_host_is_rejected_before_connecting()
    {
        SyncTransferPresenter presenter = NewPresenter();
        SyncTransferOutcome sent = await presenter.SendAsync(new SyncTransferSendForm { Host = "  ", PortText = "1" });

        Assert.False(sent.Succeeded);
        Assert.Equal(SyncTransferFormIssue.HostEmpty, sent.FormIssue);
        Assert.Null(sent.Reason);
        Assert.Contains("请填写对端地址", sent.Summary);
    }

    /// <summary>A port outside 0..65535 is rejected on both sides of the section.</summary>
    [Fact]
    public async Task A_port_out_of_range_is_rejected_on_both_forms()
    {
        SyncTransferPresenter presenter = NewPresenter();
        SyncTransferOutcome prepared = presenter.PrepareReceive(new SyncTransferReceiveForm { PortText = "70000" });
        Assert.Equal(SyncTransferFormIssue.PortOutOfRange, prepared.FormIssue);
        Assert.False(presenter.IsListening);

        SyncTransferOutcome sent = await presenter.SendAsync(new SyncTransferSendForm { PortText = "70000" });
        Assert.Equal(SyncTransferFormIssue.PortOutOfRange, sent.FormIssue);
        Assert.Null(sent.Reason);

        // A signed value is not a port at all: the parse is NumberStyles.None, the same rule the CLI applies.
        SyncTransferOutcome negative = await presenter.SendAsync(new SyncTransferSendForm { PortText = "-1" });
        Assert.Equal(SyncTransferFormIssue.PortNotNumber, negative.FormIssue);

        Assert.False(SyncTransferPresenter.TryParsePort("70000", out _, out SyncTransferFormIssue issue));
        Assert.Equal(SyncTransferFormIssue.PortOutOfRange, issue);
    }

    /// <summary>Text that is not a number never reaches the socket layer.</summary>
    [Fact]
    public async Task A_port_that_is_not_a_number_is_rejected()
    {
        SyncTransferPresenter presenter = NewPresenter();
        SyncTransferOutcome sent = await presenter.SendAsync(new SyncTransferSendForm { PortText = "abc" });
        Assert.Equal(SyncTransferFormIssue.PortNotNumber, sent.FormIssue);
        Assert.Contains("端口必须是", sent.Summary);

        SyncTransferOutcome prepared = presenter.PrepareReceive(new SyncTransferReceiveForm { PortText = "" });
        Assert.Equal(SyncTransferFormIssue.PortNotNumber, prepared.FormIssue);
        Assert.False(presenter.IsListening);
    }

    /// <summary>An unusable pin is caught before a socket is opened.</summary>
    [Fact]
    public async Task A_fingerprint_that_is_not_sixty_four_hex_is_rejected_before_connecting()
    {
        SyncTransferPresenter presenter = NewPresenter();
        SyncTransferOutcome sent = await presenter.SendAsync(
            new SyncTransferSendForm { Host = "127.0.0.1", PortText = "1", UseTls = true, ExpectedFingerprint = "abc" });

        Assert.Equal(SyncTransferFormIssue.FingerprintNotSixtyFourHex, sent.FormIssue);
        Assert.Null(sent.Reason);
        Assert.Contains("64 个十六进制字符", sent.Summary);
        Assert.True(SyncTransferPresenter.IsFingerprintText(WrongPin));
        Assert.True(SyncTransferPresenter.IsFingerprintText(WrongPin.ToUpperInvariant()));
        Assert.False(SyncTransferPresenter.IsFingerprintText("abc"));
        Assert.False(SyncTransferPresenter.IsFingerprintText(null));
    }

    /// <summary>Stopping must hand the port back, or a second attempt would need a different one.</summary>
    [Fact]
    public async Task Stopping_the_receiver_releases_the_port()
    {
        SyncTransferPresenter presenter = NewPresenter(withEnvelope: false);
        SyncTransferOutcome prepared = presenter.PrepareReceive(new SyncTransferReceiveForm());
        int port = prepared.BoundPort ?? 0;
        Assert.True(port > 0);
        Assert.True(presenter.IsListening);

        SyncTransferOutcome stopped = await presenter.StopReceiveAsync();
        Assert.True(stopped.Succeeded);
        Assert.Contains("已停止接收", stopped.Summary);
        Assert.False(presenter.IsListening);

        using TcpListener again = new(IPAddress.Loopback, port);
        again.Start();
        again.Stop();
    }

    /// <summary>A second start reports the existing listener instead of binding a second port.</summary>
    [Fact]
    public async Task Starting_twice_does_not_bind_a_second_listener()
    {
        SyncTransferPresenter presenter = NewPresenter(withEnvelope: false);
        SyncTransferOutcome first = presenter.PrepareReceive(new SyncTransferReceiveForm());
        SyncTransferOutcome second = presenter.PrepareReceive(new SyncTransferReceiveForm());

        Assert.True(first.Succeeded, first.Summary);
        Assert.False(second.Succeeded);
        Assert.Equal(SyncTransferFormIssue.AlreadyListening, second.FormIssue);
        Assert.Equal(first.BoundPort, second.BoundPort);
        Assert.Equal(first.BoundPort, presenter.ReceiveState.Port);
        Assert.Contains("没有重复绑定", second.Summary);

        SyncTransferOutcome stopped = await presenter.StopReceiveAsync();
        Assert.True(stopped.Succeeded);
    }

    /// <summary>The secret never leaves memory, and a stop clears even that copy.</summary>
    [Fact]
    public async Task The_secret_never_reaches_ToString_and_is_cleared_on_stop()
    {
        SyncTransferPresenter presenter = NewPresenter(withEnvelope: true, deviceId: "presenter-secret-1");
        Assert.DoesNotContain(Secret, new SyncTransferReceiveForm { Secret = Secret }.ToString());
        Assert.DoesNotContain(Secret, new SyncTransferSendForm { Secret = Secret }.ToString());

        SyncTransferOutcome prepared = presenter.PrepareReceive(new SyncTransferReceiveForm { Secret = Secret });
        Assert.True(prepared.Succeeded, prepared.Summary);
        Assert.DoesNotContain(Secret, prepared.ToString());
        Assert.DoesNotContain(Secret, presenter.ReceiveState.ToString());
        Assert.DoesNotContain(Secret, SyncTransferCopy.ReceiveNote);
        Assert.True(presenter.HoldsSecretInMemory);

        SyncTransferOutcome stopped = await presenter.StopReceiveAsync();
        Assert.False(presenter.HoldsSecretInMemory);
        Assert.DoesNotContain(Secret, stopped.ToString());
    }

    /// <summary>A failed send touches nothing, including a store that exists beside it.</summary>
    [Fact]
    public async Task A_failed_send_leaves_the_store_untouched()
    {
        string directory = NewDirectory();
        FileLocalSourceStore store = new(directory);
        store.Save(Source("sync-local", "Local Emby"));

        int port;
        using (TcpListener probe = new(IPAddress.Loopback, 0))
        {
            probe.Start();
            port = ((IPEndPoint)probe.LocalEndpoint).Port;
        }

        SyncTransferPresenter presenter = NewPresenter();
        SyncTransferOutcome sent = await presenter.SendAsync(SendForm(port), TimeSpan.FromSeconds(5));

        Assert.False(sent.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.ConnectFailed, sent.Reason);
        Assert.Contains("connect-failed", sent.Summary);
        Assert.Single(store.GetAll());
        Assert.Single(Directory.GetFiles(directory, "sync-sources.json"));
    }

    /// <summary>Valid frames around a payload that is not an envelope are refused by the decode step.</summary>
    [Fact]
    public async Task Frames_that_carry_no_envelope_are_rejected_as_envelope_invalid()
    {
        SyncTransferPresenter presenter = NewPresenter(withEnvelope: false);
        SyncTransferOutcome prepared = presenter.PrepareReceive(new SyncTransferReceiveForm());
        Task<SyncTransferOutcome> waiting = presenter.ReceiveOnceAsync(TimeSpan.FromSeconds(10));

        SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync(
            "127.0.0.1",
            prepared.BoundPort ?? 0,
            Encoding.UTF8.GetBytes("this is not a sync envelope"));
        Assert.True(sent.Succeeded, sent.Reason);

        SyncTransferOutcome received = await waiting;
        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.EnvelopeInvalid, received.Reason);
        Assert.Contains("envelope-invalid", received.Summary);
        Assert.Null(received.Envelope);
    }

    /// <summary>The receive budget is the transport's timeout, reported with its own code.</summary>
    [Fact]
    public async Task A_receive_with_no_peer_runs_out_of_its_budget()
    {
        SyncTransferPresenter presenter = NewPresenter(withEnvelope: false);
        SyncTransferOutcome prepared = presenter.PrepareReceive(new SyncTransferReceiveForm());
        Assert.True(prepared.Succeeded, prepared.Summary);

        SyncTransferOutcome received = await presenter.ReceiveOnceAsync(TimeSpan.FromMilliseconds(300));
        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.TransferTimeout, received.Reason);
        Assert.False(presenter.IsListening);
    }

    /// <summary>A stop is a cancellation, not a fabricated failure code.</summary>
    [Fact]
    public async Task Stopping_while_waiting_reports_a_cancellation()
    {
        SyncTransferPresenter presenter = NewPresenter(withEnvelope: false);
        Assert.True(presenter.PrepareReceive(new SyncTransferReceiveForm()).Succeeded);
        Task<SyncTransferOutcome> waiting = presenter.ReceiveOnceAsync(TimeSpan.FromSeconds(30));
        await Task.Delay(100);

        SyncTransferOutcome stopped = await presenter.StopReceiveAsync();
        Assert.True(stopped.Succeeded, stopped.Summary);

        SyncTransferOutcome received = await waiting;
        Assert.False(received.Succeeded);
        Assert.True(received.Cancelled);
        Assert.Null(received.Reason);
        Assert.Contains("已取消", received.Summary);
        Assert.False(presenter.IsListening);
    }

    /// <summary>The page can show the fingerprint before any peer exists, which is when the user needs it.</summary>
    [Fact]
    public void A_tls_receive_publishes_its_fingerprint_before_a_peer_connects()
    {
        SyncTransferPresenter presenter = NewPresenter(withEnvelope: false);
        SyncTransferOutcome prepared = presenter.PrepareReceive(new SyncTransferReceiveForm { UseTls = true });

        Assert.True(prepared.Succeeded, prepared.Summary);
        string fingerprint = prepared.CertificateFingerprint ?? string.Empty;
        Assert.Equal(64, fingerprint.Length);
        Assert.Equal(fingerprint.ToLowerInvariant(), fingerprint);
        Assert.True(SyncTransferPresenter.IsFingerprintText(fingerprint));
        Assert.True(presenter.ReceiveState.UseTls);
        Assert.Equal(fingerprint, presenter.ReceiveState.CertificateFingerprint);
        Assert.Contains(fingerprint, presenter.ReceiveState.StatusText);
        Assert.Contains("TLS 1.3", presenter.ReceiveState.StatusText);
        Assert.Contains("正在等待对端连接", presenter.ReceiveState.StatusText);
    }

    /// <summary>Disposal is the unload path: it releases the listener and the secret without a throw.</summary>
    [Fact]
    public async Task Dispose_releases_the_listener_and_the_secret()
    {
        SyncTransferPresenter presenter = NewPresenter(withEnvelope: false, deviceId: "presenter-dispose-1");
        SyncTransferOutcome prepared = presenter.PrepareReceive(new SyncTransferReceiveForm { Secret = Secret });
        int port = prepared.BoundPort ?? 0;
        Assert.True(presenter.IsListening);
        Assert.True(presenter.HoldsSecretInMemory);

        presenter.Dispose();

        Assert.False(presenter.IsListening);
        Assert.False(presenter.HoldsSecretInMemory);
        Assert.Equal(SyncTransferReceiveState.Idle.StatusText, presenter.ReceiveState.StatusText);
        using TcpListener again = new(IPAddress.Loopback, port);
        again.Start();
        again.Stop();

        // A second dispose, and a stop after a dispose, must stay quiet.
        presenter.Dispose();
        SyncTransferOutcome stopped = await presenter.StopReceiveAsync();
        Assert.True(stopped.Succeeded);
    }

    /// <summary>An unrecognised code is echoed, never replaced with a guess.</summary>
    [Fact]
    public void An_unknown_reason_code_is_echoed_instead_of_invented()
    {
        string text = SyncTransferCopy.DescribeReason("made-up-code");
        Assert.Contains("made-up-code", text);
        Assert.Contains("未识别", text);
        Assert.Equal("未说明原因", SyncTransferCopy.DescribeReason(null));
        Assert.Equal("未说明原因", SyncTransferCopy.DescribeReason(""));
        Assert.Contains(SourceSyncDeliveryReasons.DeliveryUnconfirmed, SyncTransferCopy.DescribeReason(SourceSyncDeliveryReasons.DeliveryUnconfirmed));
    }

    /// <summary>Every fixed sentence keeps the two promises: no login, no automatic apply.</summary>
    [Fact]
    public void The_copy_never_promises_a_login_or_an_automatic_apply()
    {
        Assert.Contains("不写入任何内容", SyncTransferCopy.ReceiveNote);
        Assert.Contains("不会写入任何内容", SyncTransferCopy.AutoApplyNote);
        Assert.Contains("预览", SyncTransferCopy.AutoApplyNote);
        Assert.Contains("不代表已登录", SyncTransferCopy.NotALoginNote);
        Assert.DoesNotContain("已登录", SyncTransferCopy.ReceiveHeading);
        Assert.DoesNotContain("已同步", SyncTransferCopy.SendNote);
        Assert.Contains("清零", SyncTransferCopy.SecretNote);
    }

    /// <summary>A presenter that records the two log levels into one list each (W6-107).</summary>
    private SyncTransferPresenter NewLoggedPresenter(
        out List<string> info,
        out List<string> warn,
        bool withEnvelope = true,
        string deviceId = LocalDeviceId)
    {
        List<string> infoLines = [];
        List<string> warnLines = [];
        SyncTransferPresenter presenter = withEnvelope
            ? new SyncTransferPresenter(
                deviceId,
                () => EnvelopeOf(deviceId, Source("sync-1", "Living Room Emby")),
                infoLine: infoLines.Add,
                warnLine: warnLines.Add)
            : new SyncTransferPresenter(deviceId, null, infoLines.Add, warnLines.Add);
        _presenters.Add(presenter);
        info = infoLines;
        warn = warnLines;
        return presenter;
    }

    /// <summary>Opening a listener and closing it each leave one line; a second stop is a no-op and stays quiet.</summary>
    [Fact]
    public async Task A_prepared_listener_and_a_stop_each_leave_one_information_line()
    {
        SyncTransferPresenter presenter = NewLoggedPresenter(out List<string> info, out List<string> warn);
        SyncTransferOutcome prepared = presenter.PrepareReceive(new SyncTransferReceiveForm());
        Assert.True(prepared.Succeeded, prepared.Summary);
        int port = prepared.BoundPort ?? 0;

        SyncTransferOutcome stopped = await presenter.StopReceiveAsync();
        Assert.True(stopped.Succeeded, stopped.Summary);
        SyncTransferOutcome again = await presenter.StopReceiveAsync();
        Assert.True(again.Succeeded, again.Summary);

        Assert.Equal(
            new[] { SyncTransferLog.Listening(port, tls: false), SyncTransferLog.Stopped(port) },
            info);
        Assert.Empty(warn);
    }

    /// <summary>An arriving envelope leaves one line with the byte and frame counts, never the body.</summary>
    [Fact]
    public async Task A_received_envelope_leaves_one_line_with_the_byte_and_frame_counts()
    {
        SyncTransferPresenter presenter = NewLoggedPresenter(out List<string> info, out List<string> warn, withEnvelope: false);
        SyncTransferOutcome prepared = presenter.PrepareReceive(new SyncTransferReceiveForm());
        int port = prepared.BoundPort ?? 0;
        Task<SyncTransferOutcome> waiting = presenter.ReceiveOnceAsync(TimeSpan.FromSeconds(10));
        SourceSyncEnvelope expected = EnvelopeOf(PeerDeviceId, Source("sync-1", "Living Room Emby"));
        byte[] payload = Encoding.UTF8.GetBytes(SourceSyncCodec.Serialize(expected));
        SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync("127.0.0.1", port, payload);
        Assert.True(sent.Succeeded, sent.Reason);

        SyncTransferOutcome received = await waiting;
        Assert.True(received.Succeeded, received.Summary);

        Assert.Equal(
            new[] { SyncTransferLog.Listening(port, tls: false), SyncTransferLog.Received(payload.Length, 1) },
            info);
        Assert.Empty(warn);
    }

    /// <summary>A cancelled wait is not a failure: one line says so, and the warning level stays empty.</summary>
    [Fact]
    public async Task A_cancelled_wait_says_so_without_a_failure_line()
    {
        SyncTransferPresenter presenter = NewLoggedPresenter(out List<string> info, out List<string> warn, withEnvelope: false);
        SyncTransferOutcome prepared = presenter.PrepareReceive(new SyncTransferReceiveForm());
        int port = prepared.BoundPort ?? 0;
        using CancellationTokenSource cancellation = new();
        Task<SyncTransferOutcome> waiting = presenter.ReceiveOnceAsync(TimeSpan.FromSeconds(10), cancellation.Token);
        cancellation.Cancel();

        SyncTransferOutcome received = await waiting;

        Assert.True(received.Cancelled, received.Summary);
        Assert.Equal(
            new[] { SyncTransferLog.Listening(port, tls: false), SyncTransferLog.ReceiveCancelledText },
            info);
        Assert.Empty(warn);
    }

    /// <summary>A failed send carries the stable code on the warning line and leaves the information level empty.</summary>
    [Fact]
    public async Task A_failed_send_puts_the_code_on_the_warning_line_only()
    {
        SyncTransferPresenter presenter = NewLoggedPresenter(out List<string> info, out List<string> warn);
        SilentPeer peer = NewPeer();
        SyncTransferOutcome sent = await presenter.SendAsync(
            SendForm(peer.Port) with { ConfirmDelivery = true },
            TimeSpan.FromSeconds(2));

        Assert.False(sent.Succeeded, sent.Summary);
        string line = Assert.Single(warn);
        Assert.StartsWith(SyncTransferLog.SendFailedPrefix, line, StringComparison.Ordinal);
        Assert.Contains(sent.Reason ?? string.Empty, line, StringComparison.Ordinal);
        Assert.Empty(info);
    }

    /// <summary>A whole send and receive leave one line each, and neither line quotes the host or the endpoint.</summary>
    [Fact]
    public async Task A_successful_send_and_receive_leave_one_line_each_without_the_host()
    {
        SyncTransferPresenter receiver = NewLoggedPresenter(
            out List<string> receiverInfo,
            out List<string> receiverWarn,
            withEnvelope: false,
            deviceId: "logged-receiver-1");
        SyncTransferPresenter sender = NewLoggedPresenter(
            out List<string> senderInfo,
            out List<string> senderWarn,
            deviceId: "logged-sender-1");
        SyncTransferOutcome prepared = receiver.PrepareReceive(new SyncTransferReceiveForm());
        int port = prepared.BoundPort ?? 0;
        Task<SyncTransferOutcome> waiting = receiver.ReceiveOnceAsync(TimeSpan.FromSeconds(10));

        SyncTransferOutcome sent = await sender.SendAsync(SendForm(port), TimeSpan.FromSeconds(10));
        SyncTransferOutcome received = await waiting;

        Assert.True(sent.Succeeded, sent.Summary);
        Assert.True(received.Succeeded, received.Summary);
        Assert.Equal(
            new[] { SyncTransferLog.Listening(port, tls: false), SyncTransferLog.Received(received.Bytes, received.Frames) },
            receiverInfo);
        Assert.Equal(new[] { SyncTransferLog.Sent(sent.Bytes, sent.Frames) }, senderInfo);
        Assert.Empty(receiverWarn);
        Assert.Empty(senderWarn);
        foreach (string line in receiverInfo.Concat(senderInfo))
        {
            Assert.DoesNotContain(EmbyUrl, line, StringComparison.Ordinal);
            Assert.DoesNotContain("192.168.5.20", line, StringComparison.Ordinal);
        }
    }

    /// <summary>An authenticated transfer logs its two lines and neither quotes the shared secret.</summary>
    [Fact]
    public async Task An_authenticated_transfer_never_puts_the_secret_in_a_line()
    {
        SyncTransferPresenter receiver = NewLoggedPresenter(
            out List<string> receiverInfo,
            out List<string> receiverWarn,
            withEnvelope: false,
            deviceId: "logged-secret-receiver-1");
        SyncTransferPresenter sender = NewLoggedPresenter(
            out List<string> senderInfo,
            out List<string> senderWarn,
            deviceId: "logged-secret-sender-1");
        SyncTransferOutcome prepared = receiver.PrepareReceive(new SyncTransferReceiveForm { Secret = Secret });
        int port = prepared.BoundPort ?? 0;
        Task<SyncTransferOutcome> waiting = receiver.ReceiveOnceAsync(TimeSpan.FromSeconds(10));

        SyncTransferOutcome sent = await sender.SendAsync(SendForm(port) with { Secret = Secret }, TimeSpan.FromSeconds(10));
        SyncTransferOutcome received = await waiting;

        Assert.True(sent.Succeeded, sent.Summary);
        Assert.True(received.Succeeded, received.Summary);
        Assert.NotEmpty(receiverInfo);
        Assert.NotEmpty(senderInfo);
        foreach (string line in receiverInfo.Concat(senderInfo).Concat(receiverWarn).Concat(senderWarn))
        {
            Assert.DoesNotContain(Secret, line, StringComparison.Ordinal);
            Assert.DoesNotContain(WrongPin, line, StringComparison.Ordinal);
            Assert.DoesNotContain("192.168.5.20", line, StringComparison.Ordinal);
        }
    }

    /// <summary>Releases every presenter, silent peer, and temporary directory this test created.</summary>
    public void Dispose()
    {
        foreach (SyncTransferPresenter presenter in _presenters)
        {
            presenter.Dispose();
        }

        foreach (SilentPeer peer in _peers)
        {
            peer.Dispose();
        }

        foreach (string directory in _directories)
        {
            try
            {
                if (Directory.Exists(directory))
                {
                    Directory.Delete(directory, recursive: true);
                }
            }
            catch (IOException)
            {
                // A leftover temporary directory is not worth failing a green test over.
            }
            catch (UnauthorizedAccessException)
            {
                // Same: the next run uses a fresh directory anyway.
            }
        }
    }

    private static SyncTransferSendForm SendForm(int port) => new()
    {
        Host = "127.0.0.1",
        PortText = port.ToString(CultureInfo.InvariantCulture),
    };

    private static LocalSourceRecord Source(string syncSourceId, string displayName) => new()
    {
        ServerId = "srv-" + syncSourceId,
        SyncSourceId = syncSourceId,
        Type = ServerType.Emby,
        SourceTypeRaw = SyncSourceTypes.Emby,
        Url = EmbyUrl,
        DisplayName = displayName,
        RecordRevision = 1,
    };

    private static SourceSyncEnvelope EnvelopeOf(string deviceId, params LocalSourceRecord[] records)
    {
        InMemoryLocalSourceStore store = new();
        foreach (LocalSourceRecord record in records)
        {
            store.Save(record);
        }

        SourceSyncPeerRound round = new(deviceId, store);
        return round.Export();
    }

    private SyncTransferPresenter NewPresenter(bool withEnvelope = true, string deviceId = LocalDeviceId)
    {
        SyncTransferPresenter presenter = withEnvelope
            ? new SyncTransferPresenter(deviceId, () => EnvelopeOf(deviceId, Source("sync-1", "Living Room Emby")))
            : new SyncTransferPresenter(deviceId);
        _presenters.Add(presenter);
        return presenter;
    }

    private SilentPeer NewPeer()
    {
        SilentPeer peer = new();
        _peers.Add(peer);
        return peer;
    }

    private string NewDirectory()
    {
        string path = Path.Combine(Path.GetTempPath(), "mh-w6-33-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(path);
        _directories.Add(path);
        return path;
    }

    /// <summary>A peer that reads everything and then holds the connection open until it is disposed.</summary>
    private sealed class SilentPeer : IDisposable
    {
        private readonly TcpListener _listener = new(IPAddress.Loopback, 0);
        private readonly TaskCompletionSource<bool> _release = new(TaskCreationOptions.RunContinuationsAsynchronously);
        private readonly Task _accepted;
        private long _bytesRead;

        public SilentPeer()
        {
            _listener.Start();
            Port = ((IPEndPoint)_listener.LocalEndpoint).Port;
            _accepted = Task.Run(AcceptAsync);
        }

        public int Port { get; }

        public long BytesRead => Interlocked.Read(ref _bytesRead);

        public void Dispose()
        {
            _release.TrySetResult(true);
            try
            {
                _accepted.Wait(TimeSpan.FromSeconds(5));
            }
            catch (AggregateException)
            {
                // The peer's own failure is not this test's failure.
            }

            _listener.Stop();
        }

        private async Task AcceptAsync()
        {
            try
            {
                using TcpClient client = await _listener.AcceptTcpClientAsync();
                NetworkStream stream = client.GetStream();
                byte[] buffer = new byte[4096];
                while (true)
                {
                    int read = await stream.ReadAsync(buffer);
                    if (read == 0)
                    {
                        break;
                    }

                    Interlocked.Add(ref _bytesRead, read);
                }

                // The frames arrived; now refuse to close so the sender's wait must time out.
                await _release.Task;
            }
            catch (IOException)
            {
                // A peer that goes away mid-read is exactly what this helper exists to survive.
            }
            catch (ObjectDisposedException)
            {
                // Disposal during shutdown.
            }
            catch (SocketException)
            {
                // Same.
            }
        }
    }
}

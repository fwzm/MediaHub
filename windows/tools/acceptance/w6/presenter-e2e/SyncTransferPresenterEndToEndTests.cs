using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using MediaHub.Domain;
using MediaHub.Sync;
using Xunit;

namespace MediaHub.PresenterE2E;

/// <summary>
/// W6-35: end-to-end evidence that the sync page's transfer presenter (W6-33) really talks to the
/// shipped command line, not to an in-process stand-in.
/// </summary>
/// <remarks>
/// <para>Every peer in this file is a real <c>dotnet MediaHub.SyncCli.dll</c> child process, started with the
/// exact arguments the operator would type. The presenter under test is the same object the page uses: it is
/// never given a store, so "the receive wrote nothing" is proved by looking at the peer's directory on disk and
/// at a witness directory this test owns — never by asking the presenter about itself.</para>
/// <para>Run it through <c>windows/tools/acceptance/w6/presenter-e2e.ps1</c>: that script builds
/// MediaHub.SyncCli, exports <c>MH_PRESENTER_E2E_CLI</c> with the resulting DLL path, runs this project, and then
/// prints a residual-process self check.</para>
/// </remarks>
public sealed class SyncTransferPresenterEndToEndTests : IDisposable
{
    private const string LocalDeviceId = "presenter-e2e-local";
    private const string PeerDeviceA = "win-a";
    private const string PeerDeviceB = "win-b";
    private const string EmbyUrl = "http://192.168.5.20:8096/emby";
    private const string SecretEnvName = "MH_W635_SECRET";
    private const string Secret = "correct horse battery staple";
    private const string ListeningMarker = "listening 127.0.0.1:";
    private const string FingerprintMarker = "certificate fingerprint: ";

    /// <summary>A syntactically valid pin that no local certificate can match (64 'a' characters).</summary>
    private static readonly string WrongPin = new('a', 64);

    private static readonly TimeSpan CliBudget = TimeSpan.FromSeconds(60);
    private static readonly TimeSpan ServeReadyBudget = TimeSpan.FromSeconds(30);

    private static readonly string CliDll = ResolveCliDll();

    private readonly List<string> _directories = [];

    public SyncTransferPresenterEndToEndTests() => Directory.CreateDirectory(OutRoot);

    /// <summary>Temporary root every case writes under; deleted again in <see cref="Dispose"/>.</summary>
    private static string OutRoot { get; } = Path.Combine(Path.GetTempPath(), "mh-w6-35-" + Guid.NewGuid().ToString("N"));

    public void Dispose()
    {
        foreach (string directory in _directories)
        {
            for (int attempt = 0; attempt < 3; attempt++)
            {
                try
                {
                    if (Directory.Exists(directory))
                    {
                        Directory.Delete(directory, recursive: true);
                    }

                    break;
                }
                catch (IOException)
                {
                    Thread.Sleep(100);
                }
                catch (UnauthorizedAccessException)
                {
                    Thread.Sleep(100);
                }
            }
        }

        try
        {
            if (Directory.Exists(OutRoot))
            {
                Directory.Delete(OutRoot, recursive: true);
            }
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }

    // ---------------------------------------------------------------- R1

    /// <summary>
    /// R1: a real "transfer send" child process delivers one envelope into the presenter's plaintext listener,
    /// the presenter hands back the JSON the apply preview takes, and nothing is written on disk.
    /// </summary>
    [Fact]
    public async Task R1_plaintext_receive_accepts_a_real_cli_send_and_writes_nothing()
    {
        using SyncTransferPresenter presenter = NewPresenter();
        SyncTransferOutcome prepared = presenter.PrepareReceive(new SyncTransferReceiveForm { PortText = "0" });
        Assert.True(prepared.Succeeded, prepared.Summary);
        Assert.True(prepared.BoundPort is > 0, "the listener did not publish a real port");
        int port = prepared.BoundPort!.Value;

        string senderRoot = NewDirectory("r1/sender");
        string senderStoreFile = SeedSender(senderRoot);
        DateTime senderStampBefore = File.GetLastWriteTimeUtc(senderStoreFile);

        string witness = NewDirectory("r1/witness");
        Assert.Empty(Directory.GetFiles(witness));

        Task<SyncTransferOutcome> receiving = presenter.ReceiveOnceAsync(TimeSpan.FromSeconds(30));
        CliRun send = await RunCliAsync(
            environment: null,
            "transfer", "send", "--to", "127.0.0.1:" + port.ToString(CultureInfo.InvariantCulture),
            "--root", senderRoot, "--device", PeerDeviceB, "--json");

        SyncTransferOutcome received = await receiving;

        Assert.Equal(0, send.ExitCode);
        Assert.True(received.Succeeded, received.Summary + " | send stderr: " + send.StdErr);
        Assert.False(string.IsNullOrEmpty(received.EnvelopeJson));
        Assert.Equal(Encoding.UTF8.GetByteCount(received.EnvelopeJson!), received.Bytes);
        Assert.Equal(SourceSyncTransfer.ChunkCount(received.Bytes), received.Frames);

        SourceSyncEnvelope envelope = SourceSyncCodec.Deserialize(received.EnvelopeJson!);
        Assert.Equal(PeerDeviceB, envelope.DeviceId);
        Assert.Single(envelope.Sources);

        // External evidence: nothing under this test's witness directory, and the peer's own file untouched.
        Assert.Empty(Directory.GetFiles(witness));
        Assert.Equal(senderStampBefore, File.GetLastWriteTimeUtc(senderStoreFile));

        // The listener is released once one envelope arrived.
        Assert.False(presenter.ReceiveState.Listening);
    }

    // ---------------------------------------------------------------- R2

    /// <summary>
    /// R2: the presenter publishes its TLS fingerprint from ReceiveState, the child process pins exactly that
    /// value with --expect-fingerprint, and the envelope still arrives.
    /// </summary>
    [Fact]
    public async Task R2_tls_receive_hands_its_fingerprint_to_a_real_pinned_send()
    {
        using SyncTransferPresenter presenter = NewPresenter();
        SyncTransferOutcome prepared = presenter.PrepareReceive(new SyncTransferReceiveForm { PortText = "0", UseTls = true });
        Assert.True(prepared.Succeeded, prepared.Summary);
        int port = prepared.BoundPort!.Value;

        string? fingerprint = presenter.ReceiveState.CertificateFingerprint;
        Assert.NotNull(fingerprint);
        Assert.Equal(64, fingerprint!.Length);
        Assert.Matches("^[0-9a-f]{64}$", fingerprint);

        string senderRoot = NewDirectory("r2/sender");
        SeedSender(senderRoot);

        Task<SyncTransferOutcome> receiving = presenter.ReceiveOnceAsync(TimeSpan.FromSeconds(30));
        CliRun send = await RunCliAsync(
            environment: null,
            "transfer", "send", "--to", "127.0.0.1:" + port.ToString(CultureInfo.InvariantCulture),
            "--root", senderRoot, "--device", PeerDeviceB, "--json",
            "--tls", "--expect-fingerprint", fingerprint);

        SyncTransferOutcome received = await receiving;

        Assert.Equal(0, send.ExitCode);
        Assert.True(received.Succeeded, received.Summary + " | send stderr: " + send.StdErr);
        Assert.False(string.IsNullOrEmpty(received.EnvelopeJson));
        Assert.Equal(SourceSyncTransfer.ChunkCount(received.Bytes), received.Frames);
        Assert.Contains("\"tls\":true", send.StdOut, StringComparison.Ordinal);
    }

    // ---------------------------------------------------------------- R3

    /// <summary>
    /// R3: the presenter holds the shared secret, the child process gets it only through its environment, the
    /// tagged envelope is accepted, and the plaintext secret never appears in any output.
    /// </summary>
    [Fact]
    public async Task R3_authenticated_receive_takes_the_secret_from_the_child_environment_only()
    {
        using SyncTransferPresenter presenter = NewPresenter();
        SyncTransferOutcome prepared = presenter.PrepareReceive(
            new SyncTransferReceiveForm { PortText = "0", Secret = Secret });
        Assert.True(prepared.Succeeded, prepared.Summary);
        int port = prepared.BoundPort!.Value;

        string senderRoot = NewDirectory("r3/sender");
        SeedSender(senderRoot);

        Dictionary<string, string> environment = new(StringComparer.Ordinal) { [SecretEnvName] = Secret };

        Task<SyncTransferOutcome> receiving = presenter.ReceiveOnceAsync(TimeSpan.FromSeconds(30));
        CliRun send = await RunCliAsync(
            environment,
            "transfer", "send", "--to", "127.0.0.1:" + port.ToString(CultureInfo.InvariantCulture),
            "--root", senderRoot, "--device", PeerDeviceB, "--json",
            "--authenticate", "--secret-env", SecretEnvName);

        SyncTransferOutcome received = await receiving;

        Assert.Equal(0, send.ExitCode);
        Assert.True(received.Succeeded, received.Summary + " | send stderr: " + send.StdErr);
        Assert.False(string.IsNullOrEmpty(received.EnvelopeJson));

        // One challenge frame, the chunks, and one tag frame.
        Assert.Equal(SourceSyncTransfer.ChunkCount(received.Bytes) + 2, received.Frames);

        // The child read the secret from its environment and reported only the variable's name.
        Assert.Contains("\"authenticate\":true", send.StdOut, StringComparison.Ordinal);
        Assert.Contains("\"secretEnv\":\"" + SecretEnvName + "\"", send.StdOut, StringComparison.Ordinal);
        Assert.DoesNotContain(Secret, send.StdOut, StringComparison.Ordinal);
        Assert.DoesNotContain(Secret, send.StdErr, StringComparison.Ordinal);

        // Neither the presenter's own descriptions nor its state print it.
        Assert.DoesNotContain(Secret, presenter.ReceiveState.ToString(), StringComparison.Ordinal);
        Assert.DoesNotContain(Secret, prepared.ToString(), StringComparison.Ordinal);
        Assert.DoesNotContain(Secret, received.ToString(), StringComparison.Ordinal);
        Assert.DoesNotContain(Secret, presenter.LastReceive?.ToString() ?? string.Empty, StringComparison.Ordinal);
        Assert.DoesNotContain(Secret, new SyncTransferReceiveForm { Secret = Secret }.ToString(), StringComparison.Ordinal);
        Assert.DoesNotContain(Secret, new SyncTransferSendForm { Secret = Secret }.ToString(), StringComparison.Ordinal);
    }

    // ---------------------------------------------------------------- R4

    /// <summary>
    /// R4: a send that pins the wrong fingerprint fails with tls-fingerprint-mismatch, and the peer's root stays
    /// empty — the mismatch is refused before a single envelope byte is read.
    /// </summary>
    [Fact]
    public async Task R4_a_wrong_pin_is_refused_and_the_peer_writes_nothing()
    {
        string peerRoot = NewDirectory("r4/peer");
        using CliServe serve = StartServe(
            "transfer", "serve", "--root", peerRoot, "--device", PeerDeviceA, "--json", "--apply", "--tls");
        Assert.True(await serve.WaitForListeningAsync(ServeReadyBudget), "serve never announced a port: " + serve.StdErr);

        using SyncTransferPresenter presenter = NewPresenter();
        SyncTransferOutcome sent = await presenter.SendAsync(new SyncTransferSendForm
        {
            Host = SourceSyncTransferAddress.DefaultAddress,
            PortText = serve.Port.ToString(CultureInfo.InvariantCulture),
            UseTls = true,
            ExpectedFingerprint = WrongPin,
        });

        Assert.False(sent.Succeeded);
        Assert.Equal(SourceSyncTlsReasons.FingerprintMismatch, sent.Reason);

        int exitCode = await serve.WaitForExitAsync(CliBudget);
        Assert.NotEqual(0, exitCode);

        // External evidence: the refusing side was never able to write.
        Assert.False(File.Exists(Path.Combine(peerRoot, "sync-sources.json")), "the TLS listener wrote a store after a refused pin");
        Assert.False(File.Exists(Path.Combine(peerRoot, "sync-baseline.json")), "the TLS listener wrote a baseline after a refused pin");
    }

    // ---------------------------------------------------------------- R5

    /// <summary>
    /// R5: the presenter sends its local envelope to a real "transfer serve --apply" child process, and the two
    /// sources land in that process's own store file.
    /// </summary>
    [Fact]
    public async Task R5_a_send_to_a_real_serve_applies_both_sources()
    {
        string peerRoot = NewDirectory("r5/peer");
        using CliServe serve = StartServe(
            "transfer", "serve", "--root", peerRoot, "--device", PeerDeviceA, "--json", "--apply");
        Assert.True(await serve.WaitForListeningAsync(ServeReadyBudget), "serve never announced a port: " + serve.StdErr);

        using SyncTransferPresenter presenter = NewPresenter();
        SyncTransferOutcome sent = await presenter.SendAsync(SendForm(serve.Port));

        Assert.True(sent.Succeeded, sent.Summary);
        Assert.True(sent.Frames >= 1);
        Assert.Null(sent.DeliveryConfirmed);

        int exitCode = await serve.WaitForExitAsync(CliBudget);
        Assert.Equal(0, exitCode);

        // External evidence: read the file the child process wrote, through the same store type it used.
        FileLocalSourceStore applied = new(peerRoot);
        IReadOnlyList<LocalSourceRecord> records = applied.GetAll();
        Assert.Equal(2, records.Count);
        Assert.Contains(records, record => record.DisplayName == "Living Room Emby");
        Assert.Contains(records, record => record.DisplayName == "Study Emby");
        Assert.True(File.Exists(Path.Combine(peerRoot, "sync-baseline.json")), "the serve side did not record a baseline");
        Assert.Contains("\"applied\":2", serve.StdOut, StringComparison.Ordinal);
    }

    // ---------------------------------------------------------------- R6

    /// <summary>
    /// R6a: with ConfirmDelivery on, a real serve closes after it applied, so the presenter reports the close as
    /// a confirmation.
    /// </summary>
    [Fact]
    public async Task R6a_confirm_delivery_against_a_real_serve_reports_the_close()
    {
        string peerRoot = NewDirectory("r6a/peer");
        using CliServe serve = StartServe(
            "transfer", "serve", "--root", peerRoot, "--device", PeerDeviceA, "--json", "--apply");
        Assert.True(await serve.WaitForListeningAsync(ServeReadyBudget), "serve never announced a port: " + serve.StdErr);

        using SyncTransferPresenter presenter = NewPresenter();
        SyncTransferSendForm form = SendForm(serve.Port) with { ConfirmDelivery = true };
        SyncTransferOutcome sent = await presenter.SendAsync(form);

        Assert.True(sent.Succeeded, sent.Summary);
        Assert.True(sent.DeliveryConfirmed is true, "the peer's close was not reported as a confirmation: " + sent.Summary);

        Assert.Equal(0, await serve.WaitForExitAsync(CliBudget));
        Assert.Equal(2, new FileLocalSourceStore(peerRoot).GetAll().Count);
    }

    /// <summary>
    /// R6b: a peer that accepts the connection and then neither reads nor closes is exactly the case
    /// delivery-unconfirmed exists for — the presenter must report it as a failure, not as success.
    /// </summary>
    [Fact]
    public async Task R6b_confirm_delivery_against_a_silent_peer_is_delivery_unconfirmed()
    {
        TcpListener silent = new(IPAddress.Loopback, 0);
        silent.Start();
        try
        {
            int port = ((IPEndPoint)silent.LocalEndpoint).Port;
            using SyncTransferPresenter presenter = NewPresenter();
            Task<SyncTransferOutcome> sending = presenter.SendAsync(SendForm(port) with { ConfirmDelivery = true });

            using TcpClient held = await silent.AcceptTcpClientAsync().WaitAsync(TimeSpan.FromSeconds(10));
            Assert.True(held.Connected);

            SyncTransferOutcome sent = await sending;

            Assert.False(sent.Succeeded);
            Assert.Equal(SourceSyncDeliveryReasons.DeliveryUnconfirmed, sent.Reason);
            Assert.NotEqual(true, sent.DeliveryConfirmed);
            Assert.Contains("这不算送达", sent.Summary, StringComparison.Ordinal);
        }
        finally
        {
            silent.Stop();
        }
    }

    // ---------------------------------------------------------------- R7

    /// <summary>
    /// R7a: StopReceiveAsync gives the port back to the operating system — proved by binding it again from this
    /// test, not by reading the presenter's own state.
    /// </summary>
    [Fact]
    public async Task R7a_stopping_the_receiver_releases_the_port_for_a_rebind()
    {
        using SyncTransferPresenter presenter = NewPresenter();
        SyncTransferOutcome prepared = presenter.PrepareReceive(new SyncTransferReceiveForm { PortText = "0" });
        Assert.True(prepared.Succeeded, prepared.Summary);
        int port = prepared.BoundPort!.Value;

        SyncTransferOutcome stopped = await presenter.StopReceiveAsync();

        Assert.True(stopped.Succeeded, stopped.Summary);
        Assert.False(presenter.ReceiveState.Listening);
        Assert.Equal(SyncTransferCopy.IdleStatus, presenter.ReceiveState.StatusText);

        // External evidence: the port is free again.
        TcpListener rebind = new(IPAddress.Loopback, port);
        rebind.Start();
        try
        {
            Assert.Equal(port, ((IPEndPoint)rebind.LocalEndpoint).Port);
        }
        finally
        {
            rebind.Stop();
        }

        // A second stop has nothing to do, and Dispose after it must stay quiet.
        SyncTransferOutcome again = await presenter.StopReceiveAsync();
        Assert.True(again.Succeeded, again.Summary);
    }

    /// <summary>
    /// R7b: cancelling a receive that is already waiting reports a cancellation (not a failure code) and leaves
    /// the disk alone.
    /// </summary>
    [Fact]
    public async Task R7b_cancelling_a_waiting_receive_reports_cancelled_and_writes_nothing()
    {
        using SyncTransferPresenter presenter = NewPresenter();
        SyncTransferOutcome prepared = presenter.PrepareReceive(new SyncTransferReceiveForm { PortText = "0" });
        Assert.True(prepared.Succeeded, prepared.Summary);

        string witness = NewDirectory("r7b/witness");
        Assert.Empty(Directory.GetFiles(witness));

        using CancellationTokenSource cancel = new();
        Task<SyncTransferOutcome> waiting = presenter.ReceiveOnceAsync(TimeSpan.FromSeconds(30), cancel.Token);
        await Task.Delay(200);
        cancel.Cancel();

        SyncTransferOutcome outcome = await waiting;

        Assert.True(outcome.Cancelled);
        Assert.Null(outcome.Reason);
        Assert.Equal(0, outcome.Bytes);
        Assert.Equal(0, outcome.Frames);
        Assert.Null(outcome.EnvelopeJson);
        Assert.Contains("已取消", outcome.Summary, StringComparison.Ordinal);
        Assert.Empty(Directory.GetFiles(witness));
    }

    // ---------------------------------------------------------------- R8

    /// <summary>
    /// R8 (W6-39): the presenter is told to write a receipt, a real "transfer send --await-receipt" child process
    /// reads that byte back, and both sides report it — the presenter from its outcome, the CLI from its own JSON
    /// document. Nothing is written on disk.
    /// </summary>
    [Fact]
    public async Task R8_a_receipt_written_by_the_presenter_is_read_back_by_a_real_cli_send()
    {
        using SyncTransferPresenter presenter = NewPresenter();
        SyncTransferOutcome prepared = presenter.PrepareReceive(new SyncTransferReceiveForm { PortText = "0", WriteReceipt = true });
        Assert.True(prepared.Succeeded, prepared.Summary);
        Assert.True(prepared.BoundPort is > 0, "the listener did not publish a real port");
        Assert.True(presenter.ReceiveState.WritesReceipt, "the prepared receive did not announce a receipt");
        int port = prepared.BoundPort!.Value;

        string senderRoot = NewDirectory("r8/sender");
        string senderStoreFile = SeedSender(senderRoot);
        DateTime senderStampBefore = File.GetLastWriteTimeUtc(senderStoreFile);

        string witness = NewDirectory("r8/witness");
        Assert.Empty(Directory.GetFiles(witness));

        Task<SyncTransferOutcome> receiving = presenter.ReceiveOnceAsync(TimeSpan.FromSeconds(30));
        CliRun send = await RunCliAsync(
            environment: null,
            "transfer", "send", "--to", "127.0.0.1:" + port.ToString(CultureInfo.InvariantCulture),
            "--root", senderRoot, "--device", PeerDeviceB, "--json", "--await-receipt");

        SyncTransferOutcome received = await receiving;

        Assert.Equal(0, send.ExitCode);
        Assert.Contains("\"receiptConfirmed\":true", send.StdOut, StringComparison.Ordinal);
        Assert.True(received.Succeeded, received.Summary + " | send stderr: " + send.StdErr);
        Assert.True(received.ReceiptWritten is true, "the presenter did not report the receipt it wrote: " + received.Summary);

        // The receipt is one bare byte after the last frame: it adds no frame and no envelope byte.
        Assert.Equal(Encoding.UTF8.GetByteCount(received.EnvelopeJson!), received.Bytes);
        Assert.Equal(SourceSyncTransfer.ChunkCount(received.Bytes), received.Frames);

        Assert.Empty(Directory.GetFiles(witness));
        Assert.Equal(senderStampBefore, File.GetLastWriteTimeUtc(senderStoreFile));
        Assert.False(presenter.ReceiveState.Listening);
    }

    // ---------------------------------------------------------------- R9

    /// <summary>
    /// R9 (W6-39): the presenter sends with AwaitReceipt on to a real "transfer serve --send-receipt --apply"
    /// child process; the receipt comes back as evidence the peer decoded the envelope, and the peer's own store
    /// file is the external proof that it really applied it.
    /// </summary>
    [Fact]
    public async Task R9_a_presenter_send_that_awaits_a_receipt_gets_one_from_a_real_serve()
    {
        string peerRoot = NewDirectory("r9/peer");
        using CliServe serve = StartServe(
            "transfer", "serve", "--root", peerRoot, "--device", PeerDeviceA, "--json", "--apply", "--send-receipt");
        Assert.True(await serve.WaitForListeningAsync(ServeReadyBudget), "serve never announced a port: " + serve.StdErr);

        using SyncTransferPresenter presenter = NewPresenter();
        SyncTransferOutcome sent = await presenter.SendAsync(SendForm(serve.Port) with { AwaitReceipt = true });

        Assert.True(sent.Succeeded, sent.Summary);
        Assert.True(sent.ReceiptConfirmed is true, "the presenter did not report the peer's receipt: " + sent.Summary);
        Assert.Contains(SyncTransferCopy.ReceiptConfirmedSuffix, sent.Summary, StringComparison.Ordinal);
        Assert.Null(sent.DeliveryConfirmed);

        int exitCode = await serve.WaitForExitAsync(CliBudget);
        Assert.Equal(0, exitCode);
        Assert.Contains("\"receiptWritten\":true", serve.StdOut, StringComparison.Ordinal);

        FileLocalSourceStore applied = new(peerRoot);
        Assert.Equal(2, applied.GetAll().Count);
    }

    // ---------------------------------------------------------------- R10

    /// <summary>
    /// R10 (W6-39): a real serve that decodes the envelope but was not told to acknowledge it writes nothing and
    /// sends no receipt, so the presenter's awaiting send fails with receipt-missing. The peer exiting 0 and the
    /// empty directory are the evidence that "no receipt" is not the same thing as "the peer failed".
    /// </summary>
    [Fact]
    public async Task R10_a_real_serve_that_does_not_acknowledge_leaves_the_presenter_with_receipt_missing()
    {
        string peerRoot = NewDirectory("r10/peer");

        // No --send-receipt and no --apply: the peer decodes the envelope, writes nothing, closes silently.
        using CliServe serve = StartServe(
            "transfer", "serve", "--root", peerRoot, "--device", PeerDeviceA, "--json");
        Assert.True(await serve.WaitForListeningAsync(ServeReadyBudget), "serve never announced a port: " + serve.StdErr);

        using SyncTransferPresenter presenter = NewPresenter();
        SyncTransferOutcome sent = await presenter.SendAsync(SendForm(serve.Port) with { AwaitReceipt = true });

        Assert.False(sent.Succeeded);
        Assert.Equal(SourceSyncReceiptReasons.ReceiptMissing, sent.Reason);
        Assert.Null(sent.ReceiptConfirmed);

        int exitCode = await serve.WaitForExitAsync(CliBudget);
        Assert.Equal(0, exitCode);
        Assert.Contains("\"receiptWritten\":null", serve.StdOut, StringComparison.Ordinal);
        Assert.False(File.Exists(Path.Combine(peerRoot, "sync-sources.json")), "the peer wrote a store although --apply was not given");
        Assert.False(File.Exists(Path.Combine(peerRoot, "sync-baseline.json")), "the peer wrote a baseline although --apply was not given");
    }

    // ---------------------------------------------------------------- R11

    /// <summary>
    /// R11 (W6-45): TLS and the receipt switched on at the same time. The presenter publishes its fingerprint and
    /// promises the 0x04, the child pins exactly that fingerprint and awaits the byte, and the receipt comes back
    /// inside the TLS stream the certificate protected - so neither switch may displace the other.
    /// </summary>
    [Fact]
    public async Task R11_tls_receive_that_promises_a_receipt_acknowledges_a_pinned_send()
    {
        using SyncTransferPresenter presenter = NewPresenter();
        SyncTransferOutcome prepared = presenter.PrepareReceive(
            new SyncTransferReceiveForm { PortText = "0", UseTls = true, WriteReceipt = true });
        Assert.True(prepared.Succeeded, prepared.Summary);
        int port = prepared.BoundPort!.Value;
        Assert.True(presenter.ReceiveState.WritesReceipt, "the receipt promise never reached the presenter's state");

        string? fingerprint = presenter.ReceiveState.CertificateFingerprint;
        Assert.NotNull(fingerprint);
        Assert.Matches("^[0-9a-f]{64}$", fingerprint!);

        string senderRoot = NewDirectory("r11/sender");
        SeedSender(senderRoot);
        string witness = NewDirectory("r11/witness");

        Task<SyncTransferOutcome> receiving = presenter.ReceiveOnceAsync(TimeSpan.FromSeconds(30));
        CliRun send = await RunCliAsync(
            environment: null,
            "transfer", "send", "--to", "127.0.0.1:" + port.ToString(CultureInfo.InvariantCulture),
            "--root", senderRoot, "--device", PeerDeviceB, "--json",
            "--tls", "--expect-fingerprint", fingerprint!, "--await-receipt");

        SyncTransferOutcome received = await receiving;

        Assert.Equal(0, send.ExitCode);
        Assert.True(received.Succeeded, received.Summary + " | send stderr: " + send.StdErr);
        Assert.True(received.ReceiptWritten is true, "the presenter never reported writing the receipt: " + received.Summary);
        Assert.Contains("\"tls\":true", send.StdOut, StringComparison.Ordinal);
        Assert.Contains("\"receiptConfirmed\":true", send.StdOut, StringComparison.Ordinal);

        // The receipt is one bare byte outside the frame budget: the counted stream is still the envelope.
        Assert.Equal(Encoding.UTF8.GetByteCount(received.EnvelopeJson!), received.Bytes);
        Assert.Equal(SourceSyncTransfer.ChunkCount(received.Bytes), received.Frames);

        // External evidence: the receiver writes nothing, and the listener was released after one envelope.
        Assert.Empty(Directory.GetFiles(witness));
        Assert.False(presenter.ReceiveState.Listening);
    }

    // ---------------------------------------------------------------- R12

    /// <summary>
    /// R12 (W6-45): all three switches on the send side at once. The presenter pins the fingerprint the child
    /// published, signs its envelope with a secret that only reaches the child through its environment, and awaits
    /// a receipt - which arrives over the same TLS channel the tag protected. The second half keeps TLS and the tag
    /// but takes the acknowledgement away: the peer still applies both sources, and the presenter must still fail
    /// with receipt-missing rather than reporting a successful send.
    /// </summary>
    [Fact]
    public async Task R12_tls_authenticated_send_that_awaits_a_receipt_needs_the_acknowledgement()
    {
        Dictionary<string, string> environment = new(StringComparer.Ordinal) { [SecretEnvName] = Secret };

        string acknowledgedRoot = NewDirectory("r12/acknowledged");
        using (CliServe serve = StartServe(
            environment,
            "transfer", "serve", "--root", acknowledgedRoot, "--device", PeerDeviceA, "--json", "--apply",
            "--tls", "--authenticate", "--secret-env", SecretEnvName, "--send-receipt"))
        {
            Assert.True(await serve.WaitForListeningAsync(ServeReadyBudget), "serve never announced a port: " + serve.StdErr);
            string? fingerprint = serve.Fingerprint;
            Assert.NotNull(fingerprint);
            Assert.Matches("^[0-9a-f]{64}$", fingerprint!);

            using SyncTransferPresenter presenter = NewPresenter();
            SyncTransferOutcome sent = await presenter.SendAsync(new SyncTransferSendForm
            {
                Host = SourceSyncTransferAddress.DefaultAddress,
                PortText = serve.Port.ToString(CultureInfo.InvariantCulture),
                UseTls = true,
                ExpectedFingerprint = fingerprint,
                Secret = Secret,
                AwaitReceipt = true,
            });

            Assert.True(sent.Succeeded, sent.Summary);
            Assert.True(sent.ReceiptConfirmed is true, "the authenticated send never saw a receipt: " + sent.Summary);
            Assert.Contains(SyncTransferCopy.ReceiptConfirmedSuffix, sent.Summary, StringComparison.Ordinal);
            Assert.Null(sent.DeliveryConfirmed);
            Assert.DoesNotContain(Secret, sent.Summary, StringComparison.Ordinal);

            Assert.Equal(0, await serve.WaitForExitAsync(CliBudget));
            Assert.Contains("\"receiptWritten\":true", serve.StdOut, StringComparison.Ordinal);
            Assert.Contains("\"authenticate\":true", serve.StdOut, StringComparison.Ordinal);
            Assert.DoesNotContain(Secret, serve.StdOut, StringComparison.Ordinal);
            Assert.DoesNotContain(Secret, serve.StdErr, StringComparison.Ordinal);
            Assert.Equal(2, new FileLocalSourceStore(acknowledgedRoot).GetAll().Count);
        }

        // Same three switches minus the acknowledgement: the peer authenticates, applies, and closes silently.
        string silentRoot = NewDirectory("r12/silent");
        using (CliServe serve = StartServe(
            environment,
            "transfer", "serve", "--root", silentRoot, "--device", PeerDeviceA, "--json", "--apply",
            "--tls", "--authenticate", "--secret-env", SecretEnvName))
        {
            Assert.True(await serve.WaitForListeningAsync(ServeReadyBudget), "serve never announced a port: " + serve.StdErr);
            string? fingerprint = serve.Fingerprint;
            Assert.NotNull(fingerprint);

            using SyncTransferPresenter presenter = NewPresenter();
            SyncTransferOutcome sent = await presenter.SendAsync(new SyncTransferSendForm
            {
                Host = SourceSyncTransferAddress.DefaultAddress,
                PortText = serve.Port.ToString(CultureInfo.InvariantCulture),
                UseTls = true,
                ExpectedFingerprint = fingerprint,
                Secret = Secret,
                AwaitReceipt = true,
            });

            Assert.False(sent.Succeeded);
            Assert.Equal(SourceSyncReceiptReasons.ReceiptMissing, sent.Reason);
            Assert.Null(sent.ReceiptConfirmed);
            Assert.DoesNotContain(SyncTransferCopy.ReceiptConfirmedSuffix, sent.Summary, StringComparison.Ordinal);

            // External evidence that only the acknowledgement was missing: the tag was accepted (exit 0) and
            // both sources really landed.
            Assert.Equal(0, await serve.WaitForExitAsync(CliBudget));
            Assert.Equal(2, new FileLocalSourceStore(silentRoot).GetAll().Count);
            Assert.Contains("\"receiptWritten\":null", serve.StdOut, StringComparison.Ordinal);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static string ResolveCliDll()
    {
        string? path = Environment.GetEnvironmentVariable("MH_PRESENTER_E2E_CLI");
        if (string.IsNullOrWhiteSpace(path))
        {
            throw new InvalidOperationException(
                "MH_PRESENTER_E2E_CLI is not set. Run windows/tools/acceptance/w6/presenter-e2e.ps1, which builds "
                + "MediaHub.SyncCli and points this variable at MediaHub.SyncCli.dll.");
        }

        if (!File.Exists(path))
        {
            throw new InvalidOperationException("MH_PRESENTER_E2E_CLI points at '" + path + "', which does not exist.");
        }

        return path;
    }

    private static SyncTransferSendForm SendForm(int port) => new()
    {
        Host = SourceSyncTransferAddress.DefaultAddress,
        PortText = port.ToString(CultureInfo.InvariantCulture),
    };

    private static SyncTransferPresenter NewPresenter() => new(LocalDeviceId, LocalEnvelope);

    private static SourceSyncEnvelope LocalEnvelope()
    {
        InMemoryLocalSourceStore store = new();
        store.Save(Source("sync-1", "Living Room Emby"));
        store.Save(Source("sync-2", "Study Emby"));
        return new SourceSyncPeerRound(LocalDeviceId, store).Export();
    }

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

    /// <summary>Writes the sender's own store file and returns its path, so a case can prove it was not touched.</summary>
    private string SeedSender(string directory)
    {
        FileLocalSourceStore store = new(directory);
        store.Save(Source("sync-1", "Living Room Emby"));
        string path = Path.Combine(directory, "sync-sources.json");
        Assert.True(File.Exists(path), "the sender store file was not written");
        return path;
    }

    private string NewDirectory(string label)
    {
        string directory = Path.Combine(OutRoot, label.Replace('/', Path.DirectorySeparatorChar));
        Directory.CreateDirectory(directory);
        _directories.Add(directory);
        return directory;
    }

    private static CliRun AwaitKilled(Process process, Stopwatch watch, Task<string> stdout, Task<string> stderr, bool timedOut)
    {
        int exitCode = -1;
        if (!timedOut)
        {
            try
            {
                exitCode = process.ExitCode;
            }
            catch (InvalidOperationException)
            {
                exitCode = -1;
            }
        }

        return new CliRun(exitCode, Collect(stdout), Collect(stderr), timedOut, watch.Elapsed);
    }

    private static string Collect(Task<string> text)
    {
        try
        {
            return text.Wait(TimeSpan.FromSeconds(10)) ? text.Result : string.Empty;
        }
        catch (AggregateException)
        {
            return string.Empty;
        }
    }

    private static async Task<CliRun> RunCliAsync(IReadOnlyDictionary<string, string>? environment, params string[] args)
    {
        ProcessStartInfo info = NewStartInfo(environment, args);
        using Process process = new() { StartInfo = info };
        Stopwatch watch = Stopwatch.StartNew();
        if (!process.Start())
        {
            throw new InvalidOperationException("the CLI process could not be started");
        }

        Task<string> stdout = process.StandardOutput.ReadToEndAsync();
        Task<string> stderr = process.StandardError.ReadToEndAsync();

        bool exited;
        using (CancellationTokenSource budget = new(CliBudget))
        {
            try
            {
                await process.WaitForExitAsync(budget.Token);
                exited = true;
            }
            catch (OperationCanceledException)
            {
                exited = false;
            }
        }

        if (!exited)
        {
            TryKill(process);
        }

        CliRun run = AwaitKilled(process, watch, stdout, stderr, timedOut: !exited);
        if (run.TimedOut)
        {
            Assert.Fail("the CLI process did not exit within " + CliBudget.TotalSeconds.ToString(CultureInfo.InvariantCulture) + " s");
        }

        return run;
    }

    private static ProcessStartInfo NewStartInfo(IReadOnlyDictionary<string, string>? environment, string[] args)
    {
        ProcessStartInfo info = new("dotnet")
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        info.ArgumentList.Add(CliDll);
        foreach (string argument in args)
        {
            info.ArgumentList.Add(argument);
        }

        if (environment is not null)
        {
            foreach (KeyValuePair<string, string> pair in environment)
            {
                info.Environment[pair.Key] = pair.Value;
            }
        }

        return info;
    }

    private static CliServe StartServe(params string[] args) => StartServe(environment: null, args);

    private static CliServe StartServe(IReadOnlyDictionary<string, string>? environment, params string[] args)
    {
        ProcessStartInfo info = NewStartInfo(environment, args);
        CliServe serve = CliServe.Start(info);
        return serve;
    }

    private static void TryKill(Process process)
    {
        try
        {
            if (!process.HasExited)
            {
                process.Kill(entireProcessTree: true);
                process.WaitForExit(5000);
            }
        }
        catch (InvalidOperationException)
        {
        }
        catch (System.ComponentModel.Win32Exception)
        {
        }
    }

    private sealed record CliRun(int ExitCode, string StdOut, string StdErr, bool TimedOut, TimeSpan Elapsed);

    /// <summary>A running "transfer serve" child process whose stderr is watched for its port and fingerprint.</summary>
    private sealed class CliServe : IDisposable
    {
        private readonly Process _process;
        private readonly StringBuilder _stdout = new();
        private readonly StringBuilder _stderr = new();
        private readonly object _gate = new();

        private CliServe(Process process) => _process = process;

        public int Port { get; private set; }

        public string? Fingerprint { get; private set; }

        public string StdOut
        {
            get { lock (_gate) { return _stdout.ToString(); } }
        }

        public string StdErr
        {
            get { lock (_gate) { return _stderr.ToString(); } }
        }

        public static CliServe Start(ProcessStartInfo info)
        {
            Process process = new() { StartInfo = info };
            CliServe serve = new(process);
            process.OutputDataReceived += serve.OnStdout;
            process.ErrorDataReceived += serve.OnStderr;
            if (!process.Start())
            {
                throw new InvalidOperationException("the serve process could not be started");
            }

            process.BeginOutputReadLine();
            process.BeginErrorReadLine();
            return serve;
        }

        /// <summary>Waits until the child printed its port; the TLS fingerprint, when any, is read in the same pass.</summary>
        public async Task<bool> WaitForListeningAsync(TimeSpan timeout)
        {
            Stopwatch watch = Stopwatch.StartNew();
            while (watch.Elapsed < timeout)
            {
                Parse();
                if (Port > 0)
                {
                    return true;
                }

                if (_process.HasExited)
                {
                    Parse();
                    return Port > 0;
                }

                await Task.Delay(25);
            }

            Parse();
            return Port > 0;
        }

        public async Task<int> WaitForExitAsync(TimeSpan timeout)
        {
            try
            {
                using CancellationTokenSource budget = new(timeout);
                await _process.WaitForExitAsync(budget.Token);
            }
            catch (OperationCanceledException)
            {
                TryKill(_process);
            }

            return _process.HasExited ? _process.ExitCode : -1;
        }

        public void Dispose()
        {
            try
            {
                TryKill(_process);
            }
            finally
            {
                _process.Dispose();
            }
        }

        private void OnStdout(object sender, DataReceivedEventArgs e)
        {
            if (e.Data is not null)
            {
                lock (_gate) { _stdout.AppendLine(e.Data); }
            }
        }

        private void OnStderr(object sender, DataReceivedEventArgs e)
        {
            if (e.Data is not null)
            {
                lock (_gate) { _stderr.AppendLine(e.Data); }
            }
        }

        private void Parse()
        {
            string text = StdErr;

            int listening = text.IndexOf(ListeningMarker, StringComparison.Ordinal);
            if (listening >= 0)
            {
                int start = listening + ListeningMarker.Length;
                int end = start;
                while (end < text.Length && char.IsAsciiDigit(text[end]))
                {
                    end++;
                }

                if (end > start && int.TryParse(text.AsSpan(start, end - start), NumberStyles.None, CultureInfo.InvariantCulture, out int port))
                {
                    Port = port;
                }
            }

            int marker = text.IndexOf(FingerprintMarker, StringComparison.Ordinal);
            if (marker >= 0)
            {
                int start = marker + FingerprintMarker.Length;
                int end = start;
                while (end < text.Length && Uri.IsHexDigit(text[end]))
                {
                    end++;
                }

                if (end - start == 64)
                {
                    Fingerprint = text.Substring(start, 64);
                }
            }
        }
    }
}

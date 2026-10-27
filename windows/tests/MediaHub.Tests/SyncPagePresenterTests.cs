using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.NetworkInformation;
using System.Text.Json;
using System.Threading.Tasks;
using MediaHub.Domain;
using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// The sync page's decidable logic: the apply gate's rows and sentences, and the pairing/paired-device states.
/// </summary>
/// <remarks>
/// These tests exist because a window cannot be driven here. Everything the page would show is computed by
/// <see cref="SyncPagePresenter"/> and its two sections, so every sentence a user reads — and every decision that
/// decides whether a write happens — is asserted without a UI. The one thing deliberately not tested is WinUI
/// binding itself; that is covered by the App build and by the acceptance scripts.
/// </remarks>
public sealed class SyncPagePresenterTests : IDisposable
{
    private const string EmbyUrl = "http://192.168.5.20:8096/emby";
    private const string SenderId = "dev-a";
    private const string ReceiverId = "dev-b";
    private const long NowMs = 1_760_000_000_000L;

    private readonly List<string> _roots = [];

    public void Dispose()
    {
        foreach (string root in _roots)
        {
            try
            {
                if (Directory.Exists(root))
                {
                    Directory.Delete(root, recursive: true);
                }
            }
            catch (IOException)
            {
                // A leftover temp directory is not a test failure.
            }
            catch (UnauthorizedAccessException)
            {
                // Same.
            }
        }
    }

    // ---- apply: rows, stateText and the sentences ---------------------------------

    [Fact]
    public void View_ListsOneRowPerEntry()
    {
        SourceSyncApplyPreview preview = PreviewOf(Envelope(SenderId, Record("src-1"), Record("src-2")));
        var presenter = new SyncApplyPresenter();

        Assert.True(presenter.Load(preview));

        SyncApplyView view = presenter.View();
        Assert.True(view.HasPreview);
        AssertCount(2, view.Rows);
        Assert.Equal("src-1", view.Rows[0].SyncSourceId);
        Assert.True(view.Rows[0].Allowable);
        Assert.False(view.Rows[0].IsBlocked);
    }

    [Fact]
    public void View_SummaryUsesTheCountsSentence()
    {
        SourceSyncApplyPreview preview = PreviewOf(Envelope(SenderId, Record("src-1"), Record("src-2")));
        var presenter = new SyncApplyPresenter();
        presenter.Load(preview);

        string summary = presenter.View().SummaryText;

        Assert.Contains("共 2 项", summary, StringComparison.Ordinal);
        Assert.Contains("新建 2", summary, StringComparison.Ordinal);
    }

    [Fact]
    public void View_IsEmptyBeforeAnyPreview()
    {
        var presenter = new SyncApplyPresenter();

        SyncApplyView view = presenter.View();

        Assert.False(view.HasPreview);
        Assert.Empty(view.Rows);
        Assert.False(view.CanApply);
        Assert.Equal(SyncPageCopy.ApplyDisclaimer, view.Disclaimer);
    }

    [Fact]
    public void UndecidedApplicableEntry_ReadsAsAllowed()
    {
        var presenter = new SyncApplyPresenter();
        presenter.Load(PreviewOf(Envelope(SenderId, Record("src-1"))));

        SyncApplyRow row = Assert.Single(presenter.View().Rows);

        Assert.Equal(SyncPageCopy.AllowedState, row.StateLabel);
        Assert.Equal(SyncPageCopy.AllowedState, row.StateText);
        Assert.True(row.Allowed);
        Assert.Empty(row.BlockReason);
    }

    [Fact]
    public void ExplicitSkip_ReadsAsSkipped()
    {
        var presenter = new SyncApplyPresenter();
        presenter.Load(PreviewOf(Envelope(SenderId, Record("src-1"))));

        Assert.True(presenter.Skip("src-1"));

        SyncApplyRow row = Assert.Single(presenter.View().Rows);
        Assert.Equal(SyncPageCopy.SkippedState, row.StateLabel);
        Assert.False(row.Allowed);
        Assert.False(presenter.Gate().CanApply);
    }

    [Fact]
    public void ExplicitAllowAfterSkip_ReadsAsAllowedAgain()
    {
        var presenter = new SyncApplyPresenter();
        presenter.Load(PreviewOf(Envelope(SenderId, Record("src-1"))));

        presenter.Skip("src-1");
        presenter.Allow("src-1");

        Assert.Equal(SyncPageCopy.AllowedState, Assert.Single(presenter.View().Rows).StateLabel);
        Assert.True(presenter.Gate().CanApply);
    }

    [Fact]
    public void ApprovalGatedEntry_ReadsAsAllowRisk()
    {
        // A record without any address cannot be created without the user's consent (no-usable-endpoint).
        SourceSyncApplyPreview preview = PreviewOf(Envelope(SenderId, Record("src-1", endpoints: 0)));
        var presenter = new SyncApplyPresenter();
        presenter.Load(preview);

        SyncApplyRow row = Assert.Single(presenter.View().Rows);

        Assert.True(row.RequiresApproval);
        Assert.True(row.Allowable);
        Assert.Equal(SyncPageCopy.AllowRisk, row.StateLabel);
        Assert.Equal("需用户确认", row.ConfirmLabel);
        Assert.False(row.Allowed, "an approval-gated entry must not be written before the user allows it");
    }

    [Fact]
    public void SecretsEntry_IsBlockedWithItsStableCode()
    {
        SourceRecord record = Record("src-1") with
        {
            Secrets = JsonDocument.Parse("{}").RootElement.Clone(),
        };
        var presenter = new SyncApplyPresenter();
        presenter.Load(PreviewOf(Envelope(SenderId, record)));

        SyncApplyRow row = Assert.Single(presenter.View().Rows);

        Assert.False(row.Allowable);
        Assert.True(row.IsBlocked);
        Assert.Contains("secrets-require-approval", row.BlockReason, StringComparison.Ordinal);
        Assert.Equal(string.Empty, row.StateLabel);
    }

    [Fact]
    public void UnknownSourceType_IsBlockedWithItsStableCode()
    {
        var presenter = new SyncApplyPresenter();
        presenter.Load(PreviewOf(Envelope(SenderId, Record("src-1", type: "mystery-nas"))));

        SyncApplyRow row = Assert.Single(presenter.View().Rows);

        Assert.False(row.Allowable);
        Assert.StartsWith(SyncPageCopy.NeverAllowUnsupported, row.BlockReason, StringComparison.Ordinal);
        Assert.Contains("unknown-source-type", row.BlockReason, StringComparison.Ordinal);
    }

    [Fact]
    public void UnsupportedType_IsBlockedWithItsStableCode()
    {
        var presenter = new SyncApplyPresenter();
        presenter.Load(PreviewOf(Envelope(SenderId, Record("src-1", type: "fn-nas"))));

        SyncApplyRow row = Assert.Single(presenter.View().Rows);

        Assert.False(row.Allowable);
        Assert.Contains("not-supported-on-windows", row.BlockReason, StringComparison.Ordinal);
    }

    [Fact]
    public void BlockedEntry_StaysWithheldEvenWhenAllowed()
    {
        var presenter = new SyncApplyPresenter();
        presenter.Load(PreviewOf(Envelope(SenderId, Record("src-1", type: "mystery-nas"))));

        presenter.Allow("src-1");

        Assert.True(presenter.Gate().IsWithheld("src-1"));
        Assert.Empty(presenter.AllowedIds());
        Assert.False(Assert.Single(presenter.View().Rows).Allowed);
    }

    [Fact]
    public void ZeroAllowed_DisablesApplyAndSaysWhy()
    {
        SourceSyncApplyPreview preview = PreviewOf(Envelope(SenderId, Record("src-1", endpoints: 0)));
        var presenter = new SyncApplyPresenter();
        presenter.Load(preview);
        presenter.Skip("src-1");

        SyncApplyView view = presenter.View();

        Assert.False(view.CanApply);
        Assert.False(view.IsBlocked);
        Assert.Equal(SyncPageCopy.NothingAllowed, view.BlockedText);
        Assert.Null(presenter.Confirm());
    }

    [Fact]
    public void AllowedIds_FollowTheGate()
    {
        var presenter = new SyncApplyPresenter();
        presenter.Load(PreviewOf(Envelope(SenderId, Record("src-1"), Record("src-2"))));

        presenter.Skip("src-2");
        IReadOnlyList<string> allowed = presenter.AllowedIds();

        Assert.Single(allowed);
        Assert.Equal("src-1", allowed[0]);
    }

    [Fact]
    public void Confirm_CountsWhatWillBeWritten()
    {
        var presenter = new SyncApplyPresenter();
        presenter.Load(PreviewOf(Envelope(SenderId, Record("src-1"), Record("src-2"))));

        SyncApplyConfirm confirm = Assert.IsType<SyncApplyConfirm>(presenter.Confirm());

        Assert.Contains("将写入本机 2 条来源", confirm.CountsText, StringComparison.Ordinal);
        Assert.False(confirm.HasRisk);
        Assert.Equal(SyncPageCopy.ApplyDisclaimer, confirm.Disclaimer);
        Assert.Equal(SyncPageCopy.ConfirmTitle, confirm.Title);
    }

    [Fact]
    public void Confirm_ShowsTheRiskNoteOnlyForARiskyAllow()
    {
        // Same revision, different name: the planner calls it same-revision-different-content, which is allow-able
        // but overwrites what the local device has, so the gate must acknowledge the risk.
        var local = new InMemoryLocalSourceStore();
        local.Save(Local("src-1", "旧名字"));
        SourceSyncApplyPreview preview = PreviewOf(
            Envelope(SenderId, Record("src-1", name: "新名字")),
            local);

        var presenter = new SyncApplyPresenter();
        presenter.Load(preview);
        presenter.Allow("src-1");

        SyncApplyConfirm confirm = Assert.IsType<SyncApplyConfirm>(presenter.Confirm());
        Assert.True(presenter.Gate().AcknowledgedRisk);
        Assert.True(confirm.HasRisk);
        Assert.Equal(SyncPageCopy.ConfirmRiskNote, confirm.RiskText);
    }

    [Fact]
    public void Clear_DropsRowsAndDecisions()
    {
        var presenter = new SyncApplyPresenter();
        presenter.Load(PreviewOf(Envelope(SenderId, Record("src-1"))));
        presenter.Allow("src-1");

        presenter.Clear();

        Assert.False(presenter.HasPreview);
        Assert.Equal(0, presenter.DecisionCount);
        Assert.False(presenter.View().HasPreview);
        Assert.Empty(presenter.View().Rows);
    }

    [Fact]
    public void Load_ReplacesThePreviousPreviewAndItsDecisions()
    {
        var presenter = new SyncApplyPresenter();
        presenter.Load(PreviewOf(Envelope(SenderId, Record("src-1"))));
        presenter.Allow("src-1");

        presenter.Load(PreviewOf(Envelope(SenderId, Record("src-9"))));

        Assert.Equal(0, presenter.DecisionCount);
        Assert.Equal("src-9", Assert.Single(presenter.View().Rows).SyncSourceId);
    }

    [Fact]
    public void NotImportedEndpoints_AreCountedAndNamed()
    {
        var presenter = new SyncApplyPresenter();
        presenter.Load(PreviewOf(Envelope(SenderId, Record("src-1", endpoints: 2))));

        SyncApplyView view = presenter.View();

        AssertContainsSuffix(view.NotImportedCountText, "未导入的次要地址");
        string line = Assert.Single(view.NotImportedRows);
        Assert.Contains("secondary-endpoint-not-imported", line, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Apply_ThenReportCarriesCountsBaselineAndDisclaimer()
    {
        var local = new InMemoryLocalSourceStore();
        var baselines = new InMemorySourceSyncBaselineStore();
        var round = new SourceSyncImportRound(local, baselines);
        SourceSyncApplyPreview preview = round.Preview(Envelope(SenderId, Record("src-1")));

        var presenter = new SyncApplyPresenter();
        presenter.Load(preview);
        presenter.Allow("src-1");

        SourceSyncApplyReport report = await round.ApplyAsync(preview, presenter.AllowedIds(), Writer(local));
        SyncApplyResult result = presenter.DescribeResult(report);

        Assert.Equal(1, report.Applied);
        Assert.Single(local.GetAll());
        Assert.True(report.BaselineUpdated);
        Assert.Equal("已写入远端变更", result.InfoTitle);
        Assert.True(result.WroteChanges);
        Assert.Contains(report.Summary, result.Message, StringComparison.Ordinal);
        Assert.Contains(report.BaselineLabel, result.Message, StringComparison.Ordinal);
        Assert.EndsWith(SyncPageCopy.ApplyDisclaimer, result.Message, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Apply_SkipThePageRecordedIsHonoured()
    {
        // A create needs no approval, so its default verdict is "apply". If only the allow list travelled, a row the
        // page showed as skipped would be written anyway — the skip list is what makes the row's verdict real.
        var local = new InMemoryLocalSourceStore();
        var baselines = new InMemorySourceSyncBaselineStore();
        var round = new SourceSyncImportRound(local, baselines);
        SourceSyncApplyPreview preview = round.Preview(Envelope(SenderId, Record("src-1")));

        var presenter = new SyncApplyPresenter();
        presenter.Load(preview);
        presenter.Skip("src-1");

        Assert.Empty(presenter.AllowedIds());
        Assert.Equal("src-1", Assert.Single(presenter.SkippedIds()));
        Assert.False(presenter.Gate().CanApply);
        Assert.Null(presenter.Confirm());

        var attempted = new List<string>();
        SourceSyncApplyReport report = await round.ApplyAsync(
            preview,
            presenter.AllowedIds(),
            (item, _) =>
            {
                attempted.Add(item.SyncSourceId);
                return Task.FromResult(SourceSyncWriteOutcome.Written("written"));
            },
            presenter.SkippedIds());

        Assert.Empty(attempted);
        Assert.Equal(0, report.Applied);
        Assert.Equal(1, report.Skipped);
        Assert.Equal(0, report.Withheld);
        Assert.False(report.BaselineUpdated);
        Assert.Empty(local.GetAll());
    }

    [Fact]
    public void SkippedIds_ListsOnlyWhatTheUserTickedOff()
    {
        var presenter = new SyncApplyPresenter();
        presenter.Load(PreviewOf(Envelope(SenderId, Record("src-1"), Record("src-2"))));

        presenter.Skip("src-2");

        // An untouched row keeps the default verdict (apply, no approval needed), so it lands in the allow list and
        // must not also be reported as a skip — a skip list that named every row would just be a second allow list.
        Assert.Equal("src-2", Assert.Single(presenter.SkippedIds()));
        Assert.Equal("src-1", Assert.Single(presenter.AllowedIds()));
    }

    [Fact]
    public void SkippedIds_NeverNamesAWithheldEntry()
    {
        // Withheld entries are refused before the decision table is consulted; listing one as a skip would suggest a
        // decision could release it.
        SourceSyncApplyPreview preview = PreviewOf(Envelope(
            SenderId,
            Record("src-1"),
            Record("src-secret") with { Secrets = JsonDocument.Parse("{}").RootElement.Clone() }));
        var presenter = new SyncApplyPresenter();
        presenter.Load(preview);
        presenter.Skip("src-secret");

        Assert.DoesNotContain("src-secret", presenter.SkippedIds());
        Assert.Contains(
            SyncRoundReasons.SecretsRequireApproval,
            presenter.Gate().BlockingReasons,
            StringComparer.Ordinal);
    }

    // ---- pairing: an offline link, three states, and forgetting -------------------

    [Fact]
    public void CreateInvitation_IsAnOfflineLinkThatParses()
    {
        var presenter = new SyncPairingPresenter("dev-local", NewRegistry());

        SyncPairingInvitation invitation = presenter.CreateInvitation(NowMs);
        PairingLinkResult parsed = PairingLink.Parse(invitation.Link, NowMs);

        Assert.StartsWith(PairingLink.Prefix, invitation.Link, StringComparison.Ordinal);
        Assert.False(invitation.Listens);
        Assert.True(parsed.Succeeded);
        Assert.Equal("dev-local", parsed.Payload!.DeviceId);
        Assert.Equal(NowMs + PairingPayload.LifetimeMilliseconds, invitation.ExpiresAtEpochMs);
        Assert.Contains("不会为它监听任何非回环地址", invitation.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void Addresses_AreListedButNothingListens()
    {
        var presenter = new SyncPairingPresenter("dev-local", NewRegistry());
        presenter.SetAddresses(PairingAddressCandidates.Describe(
        [
            new PairingAddressInput
            {
                InterfaceName = "Wi-Fi",
                Type = NetworkInterfaceType.Wireless80211,
                Addresses = ["192.168.5.21"],
            },
            new PairingAddressInput
            {
                InterfaceName = "Ethernet",
                Type = NetworkInterfaceType.Ethernet,
                Addresses = ["192.168.5.20"],
            },
        ]));

        IReadOnlyList<string> lines = presenter.AddressLines();
        SyncPairingInvitation invitation = presenter.CreateInvitation(NowMs);

        AssertCount(2, lines);
        Assert.StartsWith("192.168.5.20", lines[0], StringComparison.Ordinal);
        Assert.Contains("有线", lines[0], StringComparison.Ordinal);
        Assert.Contains("无线", lines[1], StringComparison.Ordinal);
        Assert.False(invitation.Listens, "showing an address must never open a socket");
        AssertCount(2, invitation.AddressLines);
        Assert.Equal(SyncPageCopy.AddressNote, invitation.AddressNote);
    }

    [Fact]
    public void EmptyAddresses_ExplainThemselves()
    {
        var presenter = new SyncPairingPresenter("dev-local", NewRegistry());
        presenter.SetAddresses(new PairingAddressReport { Reason = PairingAddressReasons.NoUsableAddress });

        string line = Assert.Single(presenter.AddressLines());

        Assert.StartsWith(SyncPageCopy.AddressEmpty, line, StringComparison.Ordinal);
        Assert.Contains(PairingAddressReasons.NoUsableAddress, line, StringComparison.Ordinal);
    }

    [Fact]
    public void VerifyReply_NewDeviceIsNotPinnedUnlessAsked()
    {
        PairedDeviceRegistry registry = NewRegistry();
        var presenter = new SyncPairingPresenter("dev-local", registry);
        string link = LinkFor("peer-1", Fingerprint(7));

        SyncPairingReply first = presenter.VerifyReply(link, NowMs);

        Assert.Equal(SyncPairingReplyState.New, first.State);
        Assert.False(first.Registered);
        Assert.True(first.CanRegister);
        Assert.Equal(0, registry.Count);

        SyncPairingReply pinned = presenter.VerifyReply(link, NowMs, register: true);

        Assert.Equal(SyncPairingReplyState.New, pinned.State);
        Assert.True(pinned.CanRegister);
        Assert.True(pinned.Registered);
        Assert.Equal(PairedDeviceReasons.Registered, pinned.RegistrationReason);
        Assert.Equal(1, registry.Count);
    }

    [Fact]
    public void VerifyReply_SecondLinkMatchesThePinnedFingerprint()
    {
        PairedDeviceRegistry registry = NewRegistry();
        var presenter = new SyncPairingPresenter("dev-local", registry);
        string fingerprint = Fingerprint(11);
        presenter.VerifyReply(LinkFor("peer-1", fingerprint), NowMs, register: true);

        SyncPairingReply reply = presenter.VerifyReply(LinkFor("peer-1", fingerprint), NowMs + 1000, register: true);

        Assert.Equal(SyncPairingReplyState.Match, reply.State);
        Assert.Equal(PairedDeviceReasons.FingerprintMatch, reply.Reason);
        Assert.Contains("就是上次那台", reply.Message, StringComparison.Ordinal);
        Assert.Equal(1, registry.Count);
    }

    [Fact]
    public void VerifyReply_MismatchNeverOverwritesThePin()
    {
        PairedDeviceRegistry registry = NewRegistry();
        var presenter = new SyncPairingPresenter("dev-local", registry);
        string pinned = Fingerprint(21);
        presenter.VerifyReply(LinkFor("peer-1", pinned), NowMs, register: true);

        SyncPairingReply reply = presenter.VerifyReply(LinkFor("peer-1", Fingerprint(99)), NowMs + 2000, register: true);

        Assert.Equal(SyncPairingReplyState.Mismatch, reply.State);
        Assert.Equal(PairedDeviceReasons.FingerprintMismatch, reply.Reason);
        Assert.False(reply.CanRegister);
        Assert.False(reply.Registered, "a mismatch must never be registered, even when asked to");
        Assert.Equal(pinned, registry.Find("peer-1")!.Fingerprint);
        Assert.Contains("不会覆盖", reply.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void VerifyReply_RejectsALinkThatIsNotAPairingLink()
    {
        var presenter = new SyncPairingPresenter("dev-local", NewRegistry());

        SyncPairingReply link = presenter.VerifyReply("https://example.com/pair?code=abc", NowMs);
        SyncPairingReply blank = presenter.VerifyReply("", NowMs);

        Assert.Equal(SyncPairingReplyState.Invalid, link.State);
        Assert.Equal(PairingLinkReasons.NotALink, link.Reason);
        Assert.Contains(link.Reason ?? string.Empty, link.Message, StringComparison.Ordinal);
        Assert.Equal(PairingLinkReasons.Empty, blank.Reason);
    }

    [Fact]
    public void VerifyReply_WithoutARegistryIsRefused()
    {
        var presenter = new SyncPairingPresenter("dev-local");

        SyncPairingReply reply = presenter.VerifyReply(LinkFor("peer-1", Fingerprint(3)), NowMs);

        Assert.Equal(SyncPairingReplyState.Invalid, reply.State);
        Assert.Equal("registry-unavailable", reply.Reason);
        Assert.False(reply.Registered);
    }

    [Fact]
    public void AskForget_ConfirmDeletesOnlyThatDevice()
    {
        PairedDeviceRegistry registry = NewRegistry();
        InMemorySourceSyncBaselineStore baselines = SeededBaselines("peer-1", "peer-2");
        var presenter = new SyncPairingPresenter("dev-local", registry, baselines);
        presenter.VerifyReply(LinkFor("peer-1", Fingerprint(31)), NowMs, register: true);
        presenter.VerifyReply(LinkFor("peer-2", Fingerprint(41)), NowMs, register: true);
        PairedDeviceRow row = presenter.BuildPairedDevices(NowMs).Rows.Single(r => r.DeviceId == "peer-1");

        SyncForgetPrompt prompt = Assert.IsType<SyncForgetPrompt>(presenter.AskForget(row));
        Assert.True(presenter.IsForgetPending);
        Assert.Contains(SyncPageCopy.ForgetKeepsNote, prompt.Text, StringComparison.Ordinal);
        Assert.Equal("peer-1", prompt.DeviceId);

        SyncForgetOutcome outcome = presenter.ConfirmForget();

        Assert.True(outcome.Forgotten);
        Assert.True(outcome.BaselineForgotten);
        Assert.Equal(SyncPageCopy.ForgetDoneTitle, outcome.Title);
        Assert.Equal(SyncPageCopy.ForgetDoneMessage, outcome.Message);
        Assert.False(presenter.IsForgetPending);
        Assert.Equal(1, registry.Count);
        Assert.Null(registry.Find("peer-1"));
        Assert.NotNull(registry.Find("peer-2"));
        // The forgotten peer keeps no knowledge; the one still paired keeps its own baseline untouched.
        Assert.DoesNotContain("peer-1", baselines.KnownPeerDeviceIds);
        Assert.Contains("peer-2", baselines.KnownPeerDeviceIds);
        Assert.Empty(baselines.Load("peer-1").Entries);
        Assert.Single(baselines.Load("peer-2").Entries);
    }

    /// <summary>A page with no baseline store still forgets the device, and says so instead of pretending.</summary>
    [Fact]
    public void AskForget_WithoutABaselineStoreSaysTheBaselineStayed()
    {
        PairedDeviceRegistry registry = NewRegistry();
        var presenter = new SyncPairingPresenter("dev-local", registry);
        presenter.VerifyReply(LinkFor("peer-1", Fingerprint(71)), NowMs, register: true);
        presenter.AskForget(presenter.BuildPairedDevices(NowMs).Rows[0]);

        SyncForgetOutcome outcome = presenter.ConfirmForget();

        Assert.True(outcome.Forgotten);
        Assert.False(outcome.BaselineForgotten);
        Assert.Equal(SyncPageCopy.ForgetDoneMessageWithoutBaseline, outcome.Message);
        Assert.Null(registry.Find("peer-1"));
    }

    /// <summary>A store whose medium is unreadable costs the baseline, never the forget itself.</summary>
    [Fact]
    public void AskForget_WithAThrowingBaselineStoreStillForgetsTheDevice()
    {
        PairedDeviceRegistry registry = NewRegistry();
        var baselines = new ThrowingBaselineStore();
        var presenter = new SyncPairingPresenter("dev-local", registry, baselines);
        presenter.VerifyReply(LinkFor("peer-1", Fingerprint(81)), NowMs, register: true);
        presenter.AskForget(presenter.BuildPairedDevices(NowMs).Rows[0]);

        SyncForgetOutcome outcome = presenter.ConfirmForget();

        Assert.True(outcome.Forgotten);
        Assert.False(outcome.BaselineForgotten);
        Assert.Equal(SyncPageCopy.ForgetDoneMessageWithoutBaseline, outcome.Message);
        Assert.Equal(1, baselines.ForgetCalls);
        Assert.Null(registry.Find("peer-1"));
    }

    /// <summary>Registration first, baseline second: a forget that deletes nothing must not clear knowledge.</summary>
    [Fact]
    public void AskForget_UnknownDeviceLeavesItsBaselineAlone()
    {
        PairedDeviceRegistry registry = NewRegistry();
        InMemorySourceSyncBaselineStore baselines = SeededBaselines("peer-1");
        var presenter = new SyncPairingPresenter("dev-local", registry, baselines);
        presenter.VerifyReply(LinkFor("peer-1", Fingerprint(91)), NowMs, register: true);
        PairedDeviceRow row = presenter.BuildPairedDevices(NowMs).Rows[0];
        Assert.True(registry.Forget("peer-1"));
        int writesBefore = baselines.WriteCount;
        presenter.AskForget(row);

        SyncForgetOutcome outcome = presenter.ConfirmForget();

        Assert.False(outcome.Forgotten);
        Assert.False(outcome.BaselineForgotten);
        Assert.Equal(PairedDeviceReasons.UnknownDevice, outcome.Reason);
        Assert.Equal(writesBefore, baselines.WriteCount);
        Assert.Contains("peer-1", baselines.KnownPeerDeviceIds);
        Assert.Single(baselines.Load("peer-1").Entries);
    }

    [Fact]
    public void AskForget_CancelDeletesNothing()
    {
        PairedDeviceRegistry registry = NewRegistry();
        var presenter = new SyncPairingPresenter("dev-local", registry);
        presenter.VerifyReply(LinkFor("peer-1", Fingerprint(51)), NowMs, register: true);
        presenter.AskForget(presenter.BuildPairedDevices(NowMs).Rows[0]);

        SyncForgetOutcome outcome = presenter.CancelForget();

        Assert.False(outcome.Forgotten);
        Assert.Equal(SyncPageCopy.ForgetCancelled, outcome.Message);
        Assert.False(presenter.IsForgetPending);
        Assert.Equal(1, registry.Count);
    }

    [Fact]
    public void ConfirmForget_WithoutAPendingRowDeletesNothing()
    {
        PairedDeviceRegistry registry = NewRegistry();
        var presenter = new SyncPairingPresenter("dev-local", registry);
        presenter.VerifyReply(LinkFor("peer-1", Fingerprint(61)), NowMs, register: true);

        SyncForgetOutcome outcome = presenter.ConfirmForget();

        Assert.False(outcome.Forgotten);
        Assert.Equal(SyncPageCopy.ForgetNothingPending, outcome.Message);
        Assert.Equal(1, registry.Count);
    }

    [Fact]
    public void AskForget_ReportsAnUnknownDeviceInsteadOfPretending()
    {
        PairedDeviceRegistry registry = NewRegistry();
        var presenter = new SyncPairingPresenter("dev-local", registry);
        presenter.VerifyReply(LinkFor("peer-1", Fingerprint(71)), NowMs, register: true);
        presenter.AskForget(presenter.BuildPairedDevices(NowMs).Rows[0]);

        // Another actor removes the row first, which is exactly the race a confirmation dialog creates.
        registry.Forget("peer-1");
        SyncForgetOutcome outcome = presenter.ConfirmForget();

        Assert.False(outcome.Forgotten);
        Assert.Equal(SyncPageCopy.ForgetUnknownTitle, outcome.Title);
        Assert.Equal(PairedDeviceReasons.UnknownDevice, outcome.Reason);
    }

    [Fact]
    public void BuildPairedDevices_ShowsCountsTofuAndRows()
    {
        PairedDeviceRegistry registry = NewRegistry();
        var presenter = new SyncPairingPresenter("dev-local", registry);
        presenter.VerifyReply(LinkFor("peer-1", Fingerprint(81)), NowMs, register: true);
        presenter.VerifyReply(LinkFor("peer-2", Fingerprint(91)), NowMs, register: true);

        PairedDeviceListView view = presenter.BuildPairedDevices(NowMs + 60_000);

        Assert.False(view.IsEmpty);
        AssertCount(2, view.Rows);
        Assert.Equal(PairedDeviceListPresenter.CountText(2), view.CountLabel);
        Assert.Equal(PairedDeviceListPresenter.TofuText, view.TofuLabel);
        Assert.Contains("指纹", view.Rows[0].SummaryLine, StringComparison.Ordinal);
        Assert.Contains("不代表对方可信", view.TofuLabel, StringComparison.Ordinal);
        Assert.Equal(PairedDeviceListPresenter.TofuText, view.TofuLabel);
    }

    [Fact]
    public void BuildPairedDevices_EmptyRegistrySaysSo()
    {
        var presenter = new SyncPairingPresenter("dev-local", NewRegistry());

        PairedDeviceListView view = presenter.BuildPairedDevices(NowMs);

        Assert.True(view.IsEmpty);
        Assert.Equal(PairedDeviceListPresenter.EmptyText, view.EmptyLabel);
        Assert.Equal(PairedDeviceListPresenter.CountText(0), view.CountLabel);
    }

    [Fact]
    public void SyncPagePresenter_SharesOneRegistryBetweenBothSections()
    {
        PairedDeviceRegistry registry = NewRegistry();
        var page = new SyncPagePresenter("dev-local", registry);

        page.Pairing.VerifyReply(LinkFor("peer-1", Fingerprint(101)), NowMs, register: true);

        Assert.Same(registry, page.Pairing.Registry);
        Assert.Equal("dev-local", page.DeviceId);
        AssertCount(1, page.BuildPairedDevices(NowMs).Rows);
        Assert.False(page.Apply.HasPreview);
    }

    // ---- helpers -----------------------------------------------------------------

    private static SourceRecord Record(
        string syncSourceId,
        string name = "客厅 Emby",
        long revision = 1,
        string? type = null,
        int endpoints = 1)
    {
        var addresses = new List<SourceEndpoint>(endpoints);
        for (int index = 0; index < endpoints; index++)
        {
            addresses.Add(new SourceEndpoint
            {
                Url = index == 0 ? EmbyUrl : EmbyUrl + "/alt" + index,
                IsPrimary = index == 0,
                SortOrder = index,
            });
        }

        return new SourceRecord
        {
            SyncSourceId = syncSourceId,
            SourceType = SyncSourceTypes.Parse(type ?? SyncSourceTypes.Emby),
            DisplayName = name,
            RecordRevision = revision,
            FieldVersion = 1,
            Endpoints = addresses,
        };
    }

    private static LocalSourceRecord Local(string syncSourceId, string name, long revision = 1) => new()
    {
        ServerId = "srv-" + syncSourceId,
        SyncSourceId = syncSourceId,
        Type = ServerType.Emby,
        SourceTypeRaw = SyncSourceTypes.Emby,
        Url = EmbyUrl,
        DisplayName = name,
        RecordRevision = revision,
    };

    private static SourceSyncEnvelope Envelope(string deviceId, params SourceRecord[] sources) => new()
    {
        SchemaVersion = SourceSyncEnvelope.CurrentSchemaVersion,
        MinimumReaderVersion = 1,
        DeviceId = deviceId,
        SnapshotId = "snap-" + Guid.NewGuid().ToString("N"),
        CreatedAtEpochMs = NowMs,
        Sources = sources,
    };

    private static SourceSyncApplyPreview PreviewOf(SourceSyncEnvelope envelope, ILocalSourceStore? local = null) =>
        new SourceSyncImportRound(local ?? new InMemoryLocalSourceStore()).Preview(envelope);

    private static SourceSyncRecordWriter Writer(ILocalSourceStore store) => (item, record) =>
    {
        if (record is null)
        {
            return Task.FromResult(store.Delete(item.SyncSourceId)
                ? SourceSyncWriteOutcome.Written("removed")
                : SourceSyncWriteOutcome.Refused("nothing matched"));
        }

        store.Save(new LocalSourceRecord
        {
            ServerId = string.IsNullOrWhiteSpace(item.ServerId) ? "srv-" + item.SyncSourceId : item.ServerId.Trim(),
            SyncSourceId = item.SyncSourceId,
            Type = WindowsSourceTypeMap.ToServerType(record.SourceType.Wire),
            SourceTypeRaw = record.SourceType.Raw,
            Url = record.Endpoints.FirstOrDefault(endpoint => endpoint.IsPrimary)?.Url
                ?? record.Endpoints.FirstOrDefault()?.Url,
            AccountScope = record.AccountScope,
            DisplayName = record.DisplayName,
            RecordRevision = record.RecordRevision,
        });
        return Task.FromResult(SourceSyncWriteOutcome.Written("written"));
    };

    private static PairedDeviceRegistry NewRegistry() => new(new InMemoryPairedDeviceStore());

    private static InMemorySourceSyncBaselineStore SeededBaselines(params string[] peers)
    {
        var store = new InMemorySourceSyncBaselineStore();
        foreach (string peer in peers)
        {
            Assert.True(store.Save(peer, Baseline("sync-1", 3)).Saved);
        }

        return store;
    }

    private static SourceSyncBaseline Baseline(string id, long revision) => new()
    {
        DeviceId = "dev-local",
        SnapshotId = "snapshot-1",
        Entries =
        [
            new SourceSyncBaselineEntry
            {
                SyncSourceId = id,
                RecordRevision = revision,
                ContentHash = "hash",
                FieldHashes = new Dictionary<string, string>(StringComparer.Ordinal) { ["displayName"] = "h-hash" },
            },
        ],
    };

    /// <summary>A baseline store whose medium is gone: every write throws, which the presenter must survive.</summary>
    private sealed class ThrowingBaselineStore : ISourceSyncBaselineStore
    {
        public int ForgetCalls { get; private set; }

        public SourceSyncBaseline Load(string peerDeviceId) => SourceSyncBaseline.Empty;

        public SourceSyncBaselineSaveResult Save(string peerDeviceId, SourceSyncBaseline baseline) =>
            throw new IOException("baseline medium is gone");

        public bool Forget(string peerDeviceId)
        {
            ForgetCalls++;
            throw new IOException("baseline medium is gone");
        }
    }

    private static string Fingerprint(int seed)
    {
        var bytes = new byte[32];
        for (int index = 0; index < bytes.Length; index++)
        {
            bytes[index] = (byte)(seed + index);
        }

        return Convert.ToHexString(bytes).ToLowerInvariant();
    }

    private static string LinkFor(string deviceId, string fingerprintHex) => PairingLink.Create(
        PairingPayload.Create(
            deviceId,
            "http://127.0.0.1:9/",
            PairingChallenge.Create(),
            PairingFingerprint.Parse(fingerprintHex),
            NowMs));

    private static void AssertCount<T>(int expected, IReadOnlyCollection<T> items) =>
        Assert.Equal(expected, items.Count);

    private static void AssertContainsSuffix(string text, string expected) =>
        Assert.Contains(expected, text, StringComparison.Ordinal);
}

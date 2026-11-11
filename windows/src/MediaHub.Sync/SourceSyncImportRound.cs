using System.Globalization;

namespace MediaHub.Sync;

/// <summary>What one approved entry became on this device.</summary>
/// <remarks>
/// The writer is supplied by the App layer, because turning a contract record into a real source means
/// touching the provider list and the credential vault — things this layer must not know about. The round
/// still owns the ordering, the gate and the baseline, so both the App and its tests exercise one implementation.
/// </remarks>
public sealed record SourceSyncWriteOutcome
{
    /// <summary>True when the record really landed locally.</summary>
    public bool Applied { get; init; }

    /// <summary>One sentence describing what happened, safe to log (never a credential).</summary>
    public string Message { get; init; } = "";

    /// <summary>Local server id the write produced or matched, when the App layer knew it; null otherwise.</summary>
    public string? ServerId { get; init; }

    /// <summary>The record was written.</summary>
    public static SourceSyncWriteOutcome Written(string message, string? serverId = null)
        => new() { Applied = true, Message = message, ServerId = serverId };

    /// <summary>The record was deliberately not written; the round reports it as 保留未应用.</summary>
    public static SourceSyncWriteOutcome Refused(string message) => new() { Applied = false, Message = message };
}

/// <summary>Writes one approved entry; <paramref name="record"/> is null when the peer stated a deletion.</summary>
public delegate Task<SourceSyncWriteOutcome> SourceSyncRecordWriter(
    SourceSyncApprovalItem item,
    SourceRecord? record);

/// <summary>One peer envelope, its approval items, the gate's default verdict, and what will be left behind.</summary>
public sealed record SourceSyncApplyPreview
{
    /// <summary>The envelope that was read.</summary>
    public SourceSyncEnvelope Envelope { get; init; } = new();

    /// <summary>One row per planned entry, already carrying its reason and whether it needs approval.</summary>
    public IReadOnlyList<SourceSyncApprovalItem> Items { get; init; } = [];

    /// <summary>The gate's verdict with no user decision applied (every approval entry is a skip).</summary>
    public SourceSyncApprovalResult Gate { get; init; } = SourceSyncApprovalResult.None;

    /// <summary>Planner counts, for the preview sentence.</summary>
    public SourceSyncPreviewSummary Summary { get; init; } = new();

    /// <summary>Entries whose record carried a sealed credential block; they are never written here.</summary>
    public int SecretCount { get; init; }

    /// <summary>Every address of the envelope this single-endpoint client will not import.</summary>
    public IReadOnlyList<SourceSyncNotImportedEndpoint> NotImportedEndpoints { get; init; } = [];

    /// <summary>The endpoint summary behind <see cref="NotImportedEndpoints"/>, for the fixed sentence.</summary>
    public SourceSyncEndpointSummary EndpointSummary { get; init; } = new();

    /// <summary>True when a stored baseline for this peer was found and could be read.</summary>
    public bool BaselineKnown { get; init; }

    /// <summary>How many entries the stored baseline held for this peer.</summary>
    public int BaselineEntryCount { get; init; }

    /// <summary>
    /// Stable code explaining why the stored baseline could not be used, or null when it was usable.
    /// A damaged baseline degrades to a full plan; it is reported instead of silently pretending nothing is new.
    /// </summary>
    public string? BaselineError { get; init; }

    /// <summary>True when applying would write at least one source with the default decisions.</summary>
    public bool CanApply => Gate.CanApply;

    /// <summary>Entry count, for the "selected file" line.</summary>
    public int Total => Items.Count;

    /// <summary>True when the round had to plan against an empty baseline.</summary>
    public bool IsFullSnapshot => !BaselineKnown;
}

/// <summary>What one apply did, including whether the stored baseline advanced.</summary>
public sealed record SourceSyncApplyReport
{
    /// <summary>Entries really written to this device.</summary>
    public int Applied { get; init; }

    /// <summary>Entries the gate skipped (no change, stale, unsupported).</summary>
    public int Skipped { get; init; }

    /// <summary>Entries kept as proposals: withheld by the gate, or refused by the writer.</summary>
    public int Withheld { get; init; }

    /// <summary>Everything that did not take effect: skipped plus withheld. The task's 保留未应用.</summary>
    public int NotApplied { get; init; }

    /// <summary>The entries that really landed, in write order, each with the identity the peer knows it by.</summary>
    /// <remarks>
    /// Counts alone cannot be checked: this list is what turns 「已写入 2 条」 into two rows a user can compare
    /// against the preview. A refused write never appears here, because it never happened.
    /// </remarks>
    public IReadOnlyList<SourceSyncWrittenSource> Written { get; init; } = [];
    /// <summary>The entries the writer kept back, in write order, each with the reason it gave.</summary>
    /// <remarks>
    /// A refused write counts as 保留未应用, and this list is what turns that count into rows a user can act on.
    /// It is disjoint from <see cref="Written"/> by construction: an entry is in exactly one of the two.
    /// </remarks>
    public IReadOnlyList<SourceSyncRefusedSource> Refused { get; init; } = [];

    /// <summary>The entries the gate will never write, in gate order, each with the code it was held for.</summary>
    /// <remarks>
    /// <see cref="Withheld"/> counts gate-withheld entries and writer refusals together; this list is the first half
    /// of that count and is disjoint from <see cref="Refused"/>, so the two lists add up to the number a user reads.
    /// </remarks>
    public IReadOnlyList<SourceSyncWithheldSource> WithheldEntries { get; init; } = [];

    /// <summary>Stable reason codes the gate refuses to apply, ever.</summary>
    public IReadOnlyList<string> BlockingReasons { get; init; } = [];

    /// <summary>True when the user allowed at least one change the gate flagged as risky.</summary>
    public bool AcknowledgedRisk { get; init; }

    /// <summary>True when the stored baseline for the peer was rewritten by this round.</summary>
    public bool BaselineUpdated { get; init; }

    /// <summary>Entries the stored baseline holds for the peer after the round.</summary>
    public int BaselineEntryCount { get; init; }

    /// <summary>Non-fatal problems, in stable codes; never contains a credential.</summary>
    public IReadOnlyList<string> Warnings { get; init; } = [];

    /// <summary>The three counts the task asks for, in one line.</summary>
    public string Summary => "已写入本地 " + Applied.ToString(CultureInfo.InvariantCulture)
        + " 条 / 跳过 " + Skipped.ToString(CultureInfo.InvariantCulture)
        + " 条 / 保留未应用 " + NotApplied.ToString(CultureInfo.InvariantCulture) + " 条";

    /// <summary>One line the page appends to <see cref="Summary"/>: did the baseline move?</summary>
    public string BaselineLabel => BaselineUpdated
        ? "本机基线已更新（共 " + BaselineEntryCount.ToString(CultureInfo.InvariantCulture) + " 条；再次导入同一信封会全部显示为已是最新）。"
        : "本机基线未变化（共 " + BaselineEntryCount.ToString(CultureInfo.InvariantCulture) + " 条）。";

    /// <summary>True when the user allowed at least one change the gate flagged as risky.</summary>
    public bool TouchedRiskyChange => AcknowledgedRisk;
}

/// <summary>Wording for the endpoints an envelope offers but this client cannot store.</summary>
/// <remarks>
/// Windows keeps one address per source while the contract allows several. The sentence and the row text live
/// here, not in the page, so the phrase a user reads is the one a test asserts — including the stable reason code.
/// </remarks>
public static class SourceSyncNotImportedText
{
    /// <summary>The fixed sentence above the endpoint list.</summary>
    public static string Summarize(int sourceCount, SourceSyncEndpointSummary? endpoints)
    {
        int notImported = endpoints?.NotImportedCount ?? 0;
        string count = sourceCount.ToString(CultureInfo.InvariantCulture);
        return notImported == 0
            ? count + " 个来源已导入，没有次要地址被拒绝。"
            : count + " 个来源已导入，" + notImported.ToString(CultureInfo.InvariantCulture)
                + " 个次要地址未导入（" + SourceSyncEndpointPolicy.SecondaryNotImported + "）。";
    }

    /// <summary>How many addresses will not be imported, as its own line.</summary>
    public static string DescribeCount(SourceSyncEndpointSummary? endpoints)
    {
        int notImported = endpoints?.NotImportedCount ?? 0;
        return notImported == 0
            ? "没有未导入的次要地址。"
            : "未导入的次要地址：" + notImported.ToString(CultureInfo.InvariantCulture) + " 个。";
    }

    /// <summary>One row: which source, which address, and the stable reason it is not imported.</summary>
    public static string Describe(SourceSyncNotImportedEndpoint endpoint)
    {
        ArgumentNullException.ThrowIfNull(endpoint);

        string name = string.IsNullOrWhiteSpace(endpoint.DisplayName) ? endpoint.SyncSourceId : endpoint.DisplayName.Trim();
        string address = string.IsNullOrWhiteSpace(endpoint.NormalizedUrl) ? endpoint.Url : endpoint.NormalizedUrl!;
        var parts = new List<string>(3);
        if (!string.IsNullOrWhiteSpace(name))
        {
            parts.Add(name.Trim());
        }

        if (!string.IsNullOrWhiteSpace(address))
        {
            parts.Add(address.Trim());
        }

        if (endpoint.SortOrder > 0)
        {
            parts.Add("顺序 " + endpoint.SortOrder.ToString(CultureInfo.InvariantCulture));
        }

        parts.Add(string.IsNullOrWhiteSpace(endpoint.Reason)
            ? SourceSyncEndpointPolicy.SecondaryNotImported
            : endpoint.Reason.Trim());
        return string.Join(" · ", parts);
    }
}

/// <summary>
/// One apply round: plan a peer envelope, let the user's decisions through the approval gate, write the
/// allowed records through a caller-supplied writer, then advance the stored baseline.
/// </summary>
/// <remarks>
/// <para>
/// This is the only place in the product that turns an envelope into local writes, which is why the App and its
/// tests share it instead of each keeping a copy. <see cref="Preview"/> writes nothing at all: it reads the
/// mirrored source list, the stored baseline and the envelope.
/// </para>
/// <para>
/// The baseline is advanced only with the records this round actually wrote, so a second import of the same
/// envelope — including after a restart, because the baseline is a file — plans every entry as up to date.
/// A baseline that cannot be read degrades to a full plan and is reported through
/// <see cref="SourceSyncApplyPreview.BaselineError"/>; it is never silently ignored.
/// </para>
/// </remarks>
public sealed class SourceSyncImportRound
{
    private readonly ILocalSourceStore _localSources;
    private readonly ISourceSyncBaselineStore? _baselines;
    private readonly Func<string?>? _baselineError;

    /// <param name="localSources">Mirrored local records; the planner's only view of this device.</param>
    /// <param name="baselines">Where the per-peer baseline lives; null keeps the round stateless.</param>
    /// <param name="baselineError">
    /// Optional probe for a store whose last load failed (a file-backed store knows why); its result is reported
    /// on the preview instead of being dropped.
    /// </param>
    public SourceSyncImportRound(
        ILocalSourceStore localSources,
        ISourceSyncBaselineStore? baselines = null,
        Func<string?>? baselineError = null)
    {
        ArgumentNullException.ThrowIfNull(localSources);

        _localSources = localSources;
        _baselines = baselines;
        _baselineError = baselineError;
    }

    /// <summary>Mirrored local records this round plans against.</summary>
    public ILocalSourceStore LocalSources => _localSources;

    /// <summary>The stored baseline, when the caller supplied one.</summary>
    public ISourceSyncBaselineStore? BaselineStore => _baselines;

    /// <summary>Computes what applying the envelope would do. Pure: nothing is written, no identity is assigned.</summary>
    public SourceSyncApplyPreview Preview(SourceSyncEnvelope peer)
    {
        ArgumentNullException.ThrowIfNull(peer);

        SourceSyncPlan plan = SourceSyncPlanner.Plan(peer, _localSources.GetAll());
        var items = new List<SourceSyncApprovalItem>(plan.Items.Count);
        int secrets = 0;

        foreach (SourceSyncCandidate candidate in plan.Items)
        {
            SourceSyncApprovalItem item = SourceSyncApprovalItem.FromCandidate(candidate);
            if (FindRecord(peer, item.SyncSourceId)?.Secrets is not null)
            {
                // The planner has no opinion about credentials; this layer does. A block sealed on another machine
                // cannot be opened here, so the entry is withheld rather than written half-configured.
                secrets++;
                item = item with { Reason = SyncRoundReasons.SecretsRequireApproval, RequiresApproval = true };
            }

            items.Add(item);
        }

        SourceSyncEndpointSummary endpoints = SourceSyncEndpointPolicy.Summarize(peer);
        SourceSyncBaseline baseline = LoadBaseline(peer.DeviceId);
        string? baselineError = _baselines is null ? null : _baselineError?.Invoke();

        return new SourceSyncApplyPreview
        {
            Envelope = peer,
            Items = items,
            Gate = SourceSyncApprovalGate.Decide(items),
            Summary = SourceSyncPreviewSummary.FromPlan(plan),
            SecretCount = secrets,
            NotImportedEndpoints = endpoints.Items,
            EndpointSummary = endpoints,
            BaselineKnown = !baseline.IsEmpty,
            BaselineEntryCount = baseline.Entries.Count,
            BaselineError = string.IsNullOrWhiteSpace(baselineError) ? null : baselineError!.Trim(),
        };
    }

    /// <summary>
    /// Writes the entries the user allowed and advances the baseline with exactly those records.
    /// </summary>
    /// <remarks>
    /// An allow list on its own cannot carry a skip: an entry that needs no approval is applied by default, so
    /// "the page showed this row and the user skipped it" has to travel as its own list. A skip always outranks
    /// an allow for the same identity — the gate keeps the last decision per id, and the skips are appended last.
    /// </remarks>
    /// <param name="preview">Result of <see cref="Preview"/>.</param>
    /// <param name="allowedIds">Identities the user explicitly allowed; everything else keeps the default verdict.</param>
    /// <param name="writer">App-layer writer; a null record means the peer stated a deletion.</param>
    /// <param name="skippedIds">Identities the user explicitly skipped; never written, even when their default verdict is "apply".</param>
    /// <param name="refusedSink">W6-103: called once per refused write, with the round's own row; the app turns it
    /// into a log line, and a test can watch it without a logger.</param>
    /// <param name="withheldSink">W6-105: called once per entry the gate holds back, with the round's own row; the
    /// same shape as <paramref name="refusedSink"/>, so both halves of 保留未应用 reach the log.</param>
    /// <param name="cancellationToken">Cancels between entries, never mid-write.</param>
    public async Task<SourceSyncApplyReport> ApplyAsync(
        SourceSyncApplyPreview preview,
        IReadOnlyList<string>? allowedIds,
        SourceSyncRecordWriter writer,
        IReadOnlyList<string>? skippedIds = null,
        Action<SourceSyncRefusedSource>? refusedSink = null,
        Action<SourceSyncWithheldSource>? withheldSink = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(preview);
        ArgumentNullException.ThrowIfNull(writer);

        List<SourceSyncApprovalDecision> decisions = Decisions(allowedIds, skippedIds);
        SourceSyncApprovalResult gate = SourceSyncApprovalGate.Decide(preview.Items, decisions);
        var withheldSources = new List<SourceSyncWithheldSource>();
        foreach (SourceSyncApprovalItem item in gate.Withheld)
        {
            var withheld = new SourceSyncWithheldSource
            {
                SyncSourceId = item.SyncSourceId.Trim(),
                DisplayName = item.DisplayName,
                Reason = item.Reason,
            };
            withheldSources.Add(withheld);
            withheldSink?.Invoke(withheld);
        }

        var written = new List<SourceRecord>();
        var writtenSources = new List<SourceSyncWrittenSource>();
        var refusedSources = new List<SourceSyncRefusedSource>();
        int applied = 0;
        int failed = 0;

        foreach (SourceSyncApprovalItem item in gate.Applied)
        {
            cancellationToken.ThrowIfCancellationRequested();

            SourceRecord? record = FindRecord(preview.Envelope, item.SyncSourceId);
            SourceSyncWriteOutcome outcome;
            try
            {
                outcome = await writer(item, record).ConfigureAwait(false);
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException or InvalidOperationException)
            {
                // The writer owns the real source list; a refused write is a kept entry, not a crashed round.
                outcome = SourceSyncWriteOutcome.Refused(error.Message);
            }

            if (outcome.Applied)
            {
                applied++;
                if (record is not null)
                {
                    written.Add(record);
                }

                // The count says how many rows landed; this says which ones. `item` carries the peer identity and
                // the title the gate read, and the writer may add the local id it produced or matched.
                writtenSources.Add(new SourceSyncWrittenSource
                {
                    SyncSourceId = item.SyncSourceId.Trim(),
                    ServerId = outcome.ServerId ?? item.ServerId,
                    DisplayName = item.DisplayName,
                    Action = item.Action,
                });
            }
            else
            {
                failed++;
                // The reason text is the writer's own sentence; blank is preserved and rendered as an explicit
                // sentence by SourceSyncRefusedText, because a row with an empty tail looks like a broken page.
                var refused = new SourceSyncRefusedSource
                {
                    SyncSourceId = item.SyncSourceId.Trim(),
                    DisplayName = item.DisplayName,
                    Action = item.Action,
                    Reason = outcome.Message,
                };
                refusedSources.Add(refused);

                // W6-103: the summary says how many were kept back; this says which one and why, one line per
                // refusal, the moment the round decides it.
                refusedSink?.Invoke(refused);
            }
        }

        bool baselineUpdated = false;
        var warnings = new List<string>();
        int baselineEntries = preview.BaselineEntryCount;
        if (_baselines is not null && written.Count > 0)
        {
            try
            {
                SourceSyncBaseline next = SourceSyncBaseline.FromRecords(
                    written,
                    preview.Envelope.DeviceId,
                    preview.Envelope.SnapshotId);
                SourceSyncBaselineSaveResult save = _baselines.Save(preview.Envelope.DeviceId, next);
                baselineUpdated = save.Saved;
                if (save.RejectedCount > 0)
                {
                    warnings.Add(
                        SourceSyncSessionReasons.BaselineRejectedPrefix + ":"
                        + save.RejectedCount.ToString(CultureInfo.InvariantCulture) + ":"
                        + (save.Reason ?? string.Empty));
                }
            }
            catch (IOException)
            {
                warnings.Add(SourceSyncSessionReasons.BaselineWriteFailed);
            }
            catch (UnauthorizedAccessException)
            {
                warnings.Add(SourceSyncSessionReasons.BaselineWriteFailed);
            }

            baselineEntries = LoadBaseline(preview.Envelope.DeviceId).Entries.Count;
        }

        return new SourceSyncApplyReport
        {
            Applied = applied,
            Skipped = gate.SkippedCount,
            Withheld = gate.WithheldCount + failed,
            NotApplied = gate.WithheldCount + failed + gate.SkippedCount,
            Written = writtenSources,
            Refused = refusedSources,
            WithheldEntries = withheldSources,
            BlockingReasons = gate.BlockingReasons,
            AcknowledgedRisk = gate.AcknowledgedRisk,
            BaselineUpdated = baselineUpdated,
            BaselineEntryCount = baselineEntries,
            Warnings = warnings,
        };
    }

    private static List<SourceSyncApprovalDecision> Decisions(
        IReadOnlyList<string>? allowedIds,
        IReadOnlyList<string>? skippedIds = null)
    {
        var decisions = new List<SourceSyncApprovalDecision>();
        foreach (string? id in allowedIds ?? [])
        {
            if (!string.IsNullOrWhiteSpace(id))
            {
                decisions.Add(SourceSyncApprovalDecision.Allow(id));
            }
        }

        // Appended after the allows on purpose: the gate takes the last decision per identity, so an id that
        // arrives in both lists ends up skipped. The user saying "do not write this" must never be outranked by
        // a default verdict or by a stale allow from the same screen.
        foreach (string? id in skippedIds ?? [])
        {
            if (!string.IsNullOrWhiteSpace(id))
            {
                decisions.Add(SourceSyncApprovalDecision.Skip(id));
            }
        }

        return decisions;
    }

    private SourceSyncBaseline LoadBaseline(string peerDeviceId)
    {
        if (_baselines is null || string.IsNullOrWhiteSpace(peerDeviceId))
        {
            return SourceSyncBaseline.Empty;
        }

        try
        {
            return _baselines.Load(peerDeviceId);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            // A store that cannot answer is a store that knows nothing: the round degrades to a full plan.
            return SourceSyncBaseline.Empty;
        }
    }

    private static SourceRecord? FindRecord(SourceSyncEnvelope envelope, string syncSourceId)
    {
        string key = syncSourceId.Trim();
        foreach (SourceRecord record in envelope.Sources)
        {
            if (record is not null && string.Equals(record.SyncSourceId.Trim(), key, StringComparison.OrdinalIgnoreCase))
            {
                return record;
            }
        }

        return null;
    }
}

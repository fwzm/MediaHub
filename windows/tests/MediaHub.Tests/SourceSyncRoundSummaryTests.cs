using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// W6-102: the one line a finished apply round leaves in the Windows diagnostic log. The page shows counts and
/// the per-row lists; this line is what a bug report carries, so it must identify the peer and the snapshot and
/// must never quote a display name, an address or a credential. The Android side got the same line in W6-101
/// (device / snapshot / applied / skipped / withheld / refused / removed).
/// </summary>
public sealed class SourceSyncRoundSummaryTests
{
    [Fact]
    public void TheSummaryNamesThePeerAndTheSnapshotAndEveryCount()
    {
        SourceSyncApplyReport report = new()
        {
            Applied = 3,
            Skipped = 1,
            Withheld = 3,
            NotApplied = 4,
            BaselineUpdated = true,
            Written =
            [
                new SourceSyncWrittenSource { SyncSourceId = "sync-a", Action = SourceSyncAction.Create },
                new SourceSyncWrittenSource { SyncSourceId = "sync-b", Action = SourceSyncAction.Update },
                new SourceSyncWrittenSource { SyncSourceId = "sync-c", Action = SourceSyncAction.Delete },
            ],
            Refused = [new SourceSyncRefusedSource { SyncSourceId = "sync-d", Reason = "本机存储只读" }],
            WithheldEntries =
            [
                new SourceSyncWithheldSource { SyncSourceId = "sync-e", Reason = "secrets-require-approval" },
                new SourceSyncWithheldSource { SyncSourceId = "sync-f", Reason = "unknown-source-type" },
            ],
        };

        string line = SourceSyncRoundSummaryText.InfoLine("device-a", "snap-1", report);

        Assert.Equal(
            "已应用对端信封：对端 device-a · 快照 snap-1 · 写入 3 条 · 移除 1 条 · 跳过 1 条 · 保留未应用 3 条（扣住 2 · 写入被拒 1） · 本机基线已更新（不含任何凭据）",
            line);
    }

    [Fact]
    public void TheSummaryNeverQuotesANameAnAddressOrACredential()
    {
        SourceSyncApplyReport report = new()
        {
            Applied = 1,
            Written =
            [
                new SourceSyncWrittenSource
                {
                    SyncSourceId = "sync-a",
                    DisplayName = "书房 Jellyfin",
                    Action = SourceSyncAction.Create,
                },
            ],
            Refused =
            [
                new SourceSyncRefusedSource
                {
                    SyncSourceId = "sync-b",
                    DisplayName = "客厅 Emby",
                    Reason = "无法连接 http://nas-a:8096/emby?api_key=AAAA ",
                },
            ],
            WithheldEntries =
            [
                new SourceSyncWithheldSource
                {
                    SyncSourceId = "sync-c",
                    DisplayName = "卧室 Emby",
                    Reason = "secrets-require-approval",
                },
            ],
        };

        string line = SourceSyncRoundSummaryText.InfoLine("device-a", "snap-1", report);

        Assert.Contains("对端 device-a", line, StringComparison.Ordinal);
        Assert.DoesNotContain("书房", line, StringComparison.Ordinal);
        Assert.DoesNotContain("客厅", line, StringComparison.Ordinal);
        Assert.DoesNotContain("卧室", line, StringComparison.Ordinal);
        Assert.DoesNotContain("http://nas-a", line, StringComparison.Ordinal);
        Assert.DoesNotContain("api_key", line, StringComparison.Ordinal);
        Assert.DoesNotContain("AAAA", line, StringComparison.Ordinal);
    }

    [Fact]
    public void AMissingIdentitySaysSoInsteadOfInventingOne()
    {
        SourceSyncApplyReport report = new() { Applied = 0 };

        Assert.Contains(
            "已应用对端信封：对端 device-a · 写入 0 条",
            SourceSyncRoundSummaryText.InfoLine("device-a", null, report),
            StringComparison.Ordinal);
        Assert.Contains(
            "已应用对端信封：快照 snap-1 · 写入 0 条",
            SourceSyncRoundSummaryText.InfoLine("  ", "snap-1", report),
            StringComparison.Ordinal);
        Assert.Contains(
            "已应用对端信封：对端与快照未知 · 写入 0 条",
            SourceSyncRoundSummaryText.InfoLine("   ", "", report),
            StringComparison.Ordinal);
        Assert.Contains(
            "已应用对端信封：对端与快照未知 · 写入 0 条",
            SourceSyncRoundSummaryText.InfoLine(null, null, report),
            StringComparison.Ordinal);
    }

    [Fact]
    public void TheWarningAppearsOnlyWhenSomethingWasKeptBack()
    {
        Assert.Null(SourceSyncRoundSummaryText.WarnLine(new SourceSyncApplyReport { Withheld = 0 }));
        Assert.Equal(
            "应用对端信封时有 2 条保留未应用（含凭据或写入被拒）",
            SourceSyncRoundSummaryText.WarnLine(new SourceSyncApplyReport { Withheld = 2 }));
    }
}

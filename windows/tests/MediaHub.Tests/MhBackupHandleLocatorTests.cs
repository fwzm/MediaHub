using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using MediaHub.Backup;
using MediaHub.Backup.Providers;
using MediaHub.Domain;
using MediaHub.Net;
using MediaHub.Providers;
using MediaHub.Providers.MediaBrowser;
using MediaHub.TestSupport;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// 句柄配对（W31）：备份里的来源怎么对上本机<b>已登录</b>的句柄，以及配不上时为什么宁可不写。
/// </summary>
/// <remarks>
/// 配对的三个出口必须可分辨：对上了（写）、本机没有这个来源（需登录）、有来源但地址不一致或不止一个候选
/// （拒绝并说明原因）。最后一种绝不是「没登录」——让用户去重登一个已经登录的来源只会掩盖问题。
/// </remarks>
public sealed class MhBackupHandleLocatorTests
{
    private const string User = "tester";
    private const string Password = "secret";

    // ------------------------------------------------------------ 样本构造

    private static MhBackupServerDto BackupServer(string backupId, string type, params string[] urls) => new()
    {
        BackupId = backupId,
        Name = backupId,
        Type = type,
        Username = "mh",
        Endpoints = urls.Select((url, index) => new MhBackupEndpointDto
        {
            Name = "线路 " + (index + 1).ToString(System.Globalization.CultureInfo.InvariantCulture),
            Url = url,
            IsPrimary = index == 0,
            Enabled = true,
            SortOrder = index,
        }).ToList(),
    };

    private static MhBackupProgressDto Progress(string serverBackupId, string itemId) => new()
    {
        ServerBackupId = serverBackupId,
        ItemId = itemId,
        PositionMs = 120_000,
        DurationMs = 600_000,
        UpdatedAtEpochMs = 1_790_000_001_000,
        ItemTitle = "Long Resume",
        ItemType = "Movie",
    };

    private static MhBackupPayload Payload(
        IEnumerable<MhBackupServerDto> servers,
        IEnumerable<MhBackupProgressDto>? progress = null) => new()
    {
        Servers = servers.ToList(),
        Progress = progress?.ToList() ?? new List<MhBackupProgressDto>(),
        Favorites = new List<MhBackupFavoriteDto>(),
    };

    private static ProviderHandle Handle(string serverId, ServerType type, string displayName, params string[] urls)
        => new()
        {
            ServerId = serverId,
            Server = new MediaServer(serverId, type, displayName, urls.Select(url => new ServerEndpoint(url)).ToArray()),
            Descriptor = type == ServerType.Emby ? new EmbyProviderFactory().Descriptor : new JellyfinProviderFactory().Descriptor,
        };

    private static (MhBackupRestorePlan Plan, MhBackupWriteOutlook Outlook) PlanOf(MhBackupPayload payload)
    {
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(
            payload, Array.Empty<MhBackupLocalSource>(), MhBackupRestoreStrategy.Merge);
        return (plan, MhBackupWriteOutlooks.Classify(payload, plan));
    }

    private static NetworkPolicy DirectPolicy() => new() { Mode = ProxyMode.Direct };

    // ------------------------------------------------------------ 对上了

    [Fact]
    public void ASingleLoggedInHandleIsFoundByAddressEvenWhenTheSpellingDiffers()
    {
        ProviderHandle handle = Handle("local-emby", ServerType.Emby, "客厅 Emby", "https://Media.Example/emby/");
        MhBackupPayload payload = Payload(new[] { BackupServer("srv-1", "EMBY", "https://media.example:443/emby") });

        MhBackupHandleLocator locator = MhBackupHandleLocator.Create(payload, new[] { handle });

        MhBackupHandleMatch match = locator.Match("srv-1");
        Assert.Equal(MhBackupHandleMatchKind.Matched, match.Kind);
        Assert.Same(handle, match.Handle);
        Assert.Equal("matched", match.KindCode);
        Assert.NotNull(locator.ForServer("srv-1"));
    }

    [Fact]
    public async Task AnEndToEndWriteUsesTheLocatedHandleAndTheServerKeepsThePosition()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        IMediaProviderFactory factory = new EmbyProviderFactory();
        MediaServer server = new("mock-server", ServerType.Emby, "Mock source",
            new[] { new ServerEndpoint(mock.BaseUri.GetLeftPart(UriPartial.Authority)) });
        using ProviderHandle handle = factory.Create(server, DirectPolicy());
        await handle.Auth!.AuthenticateAsync(new Credentials(User, Password));

        string address = mock.BaseUri.GetLeftPart(UriPartial.Authority);
        MhBackupPayload payload = Payload(
            new[] { BackupServer("srv-1", "EMBY", address + "/") },
            new[] { Progress("srv-1", "item-003") });

        MhBackupHandleLocator locator = MhBackupHandleLocator.Create(payload, new[] { handle });
        (MhBackupRestorePlan plan, MhBackupWriteOutlook outlook) = PlanOf(payload);

        MhBackupWritebackReport report = await MhBackupWritebacks.RunAsync(plan, outlook, locator);

        MhBackupWriteAttempt attempt = Assert.Single(report.Attempts, a => a.Record == MhBackupRecordKind.Progress);
        Assert.Equal(MhBackupWriteOutcome.Written, attempt.Outcome);
        Assert.Equal(1_200_000_000L, mock.UserDataOf("item-003")!.PlaybackPositionTicks);
        Assert.Equal(1, report.WrittenCount);
    }

    [Fact]
    public void WhenOneOfTwoHandlesMatchesTheAddressThatHandleWins()
    {
        ProviderHandle livingRoom = Handle("a", ServerType.Emby, "客厅", "https://a.example");
        ProviderHandle bedroom = Handle("b", ServerType.Emby, "卧室", "https://b.example");
        MhBackupPayload payload = Payload(new[] { BackupServer("srv-1", "EMBY", "https://b.example/") });

        MhBackupHandleLocator locator = MhBackupHandleLocator.Create(payload, new[] { livingRoom, bedroom });

        MhBackupHandleMatch match = locator.Match("srv-1");
        Assert.Equal(MhBackupHandleMatchKind.Matched, match.Kind);
        Assert.Same(bedroom, match.Handle);
    }

    [Fact]
    public void ABackupSourceWithoutAnyAddressFallsBackToTheOnlySameKindHandle()
    {
        ProviderHandle only = Handle("a", ServerType.Jellyfin, "唯一的 Jellyfin", "https://jelly.example");
        MhBackupPayload payload = Payload(new[] { BackupServer("srv-1", "JELLYFIN") });

        MhBackupHandleLocator locator = MhBackupHandleLocator.Create(payload, new[] { only });

        MhBackupHandleMatch match = locator.Match("srv-1");
        Assert.Equal(MhBackupHandleMatchKind.Matched, match.Kind);
        Assert.Same(only, match.Handle);
        Assert.Contains("本机只有一个同类型来源", match.Reason, StringComparison.Ordinal);
    }

    // ------------------------------------------------------------ 配不上（三类，各有各的说法）

    [Fact]
    public void WithoutAHandleOfThatKindTheRecordNeedsLoginInsteadOfBeingRefused()
    {
        MhBackupPayload payload = Payload(new[] { BackupServer("srv-1", "EMBY", "https://a.example") });

        MhBackupHandleLocator locator = MhBackupHandleLocator.Create(payload, Array.Empty<ProviderHandle>());

        MhBackupHandleMatch match = locator.Match("srv-1");
        Assert.Equal(MhBackupHandleMatchKind.NoHandle, match.Kind);
        Assert.Equal("no-handle", match.KindCode);
        Assert.Null(locator.ForServer("srv-1"));
        Assert.Contains("需要先添加来源并登录", match.Reason, StringComparison.Ordinal);
    }

    [Fact]
    public async Task ADifferentAddressIsRefusedInsteadOfPairingTheWrongServer()
    {
        ProviderHandle other = Handle("a", ServerType.Emby, "另一台", "https://other.example");
        MhBackupPayload payload = Payload(
            new[] { BackupServer("srv-1", "EMBY", "https://wanted.example") },
            new[] { Progress("srv-1", "item-1") });

        MhBackupHandleLocator locator = MhBackupHandleLocator.Create(payload, new[] { other });
        MhBackupHandleMatch match = locator.Match("srv-1");
        Assert.Equal(MhBackupHandleMatchKind.AddressMismatch, match.Kind);
        Assert.Equal("address-mismatch", match.KindCode);
        Assert.Contains("地址不一致时不猜", match.Reason, StringComparison.Ordinal);

        (MhBackupRestorePlan plan, MhBackupWriteOutlook outlook) = PlanOf(payload);
        MhBackupWritebackReport report = await MhBackupWritebacks.RunAsync(plan, outlook, locator);

        MhBackupWriteAttempt attempt = Assert.Single(report.Attempts, a => a.Record == MhBackupRecordKind.Progress);
        Assert.Equal(MhBackupWriteOutcome.Failed, attempt.Outcome);
        Assert.Contains("地址不一致时不猜", attempt.Reason, StringComparison.Ordinal);
        Assert.Equal(0, report.WrittenCount);
        Assert.Equal(0, report.NeedsLoginCount);
    }

    [Fact]
    public async Task TwoHandlesOnTheSameAddressAreAmbiguousAndNothingIsWritten()
    {
        ProviderHandle first = Handle("a", ServerType.Emby, "客厅甲", "https://same.example");
        ProviderHandle second = Handle("b", ServerType.Emby, "客厅乙", "https://SAME.example/");
        MhBackupPayload payload = Payload(
            new[] { BackupServer("srv-1", "EMBY", "https://same.example") },
            new[] { Progress("srv-1", "item-1") });

        MhBackupHandleLocator locator = MhBackupHandleLocator.Create(payload, new[] { first, second });
        MhBackupHandleMatch match = locator.Match("srv-1");
        Assert.Equal(MhBackupHandleMatchKind.Ambiguous, match.Kind);
        Assert.Equal("ambiguous", match.KindCode);
        Assert.Contains("不猜", match.Reason, StringComparison.Ordinal);

        (MhBackupRestorePlan plan, MhBackupWriteOutlook outlook) = PlanOf(payload);
        MhBackupWritebackReport report = await MhBackupWritebacks.RunAsync(plan, outlook, locator);

        MhBackupWriteAttempt attempt = Assert.Single(report.Attempts, a => a.Record == MhBackupRecordKind.Progress);
        Assert.Equal(MhBackupWriteOutcome.Failed, attempt.Outcome);
        Assert.Equal(0, report.WrittenCount);
    }

    [Fact]
    public void TwoSameKindHandlesWithoutAnyAddressAreAlsoAmbiguous()
    {
        ProviderHandle first = Handle("a", ServerType.Emby, "甲", "https://a.example");
        ProviderHandle second = Handle("b", ServerType.Emby, "乙", "https://b.example");
        MhBackupPayload payload = Payload(new[] { BackupServer("srv-2", "EMBY") });

        MhBackupHandleLocator locator = MhBackupHandleLocator.Create(payload, new[] { first, second });

        Assert.Equal(MhBackupHandleMatchKind.Ambiguous, locator.Match("srv-2").Kind);
    }

    [Fact]
    public void TypesWithoutAServerWriteApiAndUnknownIdsNeverPair()
    {
        ProviderHandle handle = Handle("a", ServerType.Emby, "客厅", "https://a.example");
        MhBackupPayload payload = Payload(new[]
        {
            BackupServer("srv-local", "LOCAL"),
            BackupServer("srv-webdav", "WEBDAV", "https://dav.example"),
            BackupServer("srv-plex", "PLEX", "https://plex.example"),
        });

        MhBackupHandleLocator locator = MhBackupHandleLocator.Create(payload, new[] { handle });

        Assert.Equal(MhBackupHandleMatchKind.NoHandle, locator.Match("srv-local").Kind);
        Assert.Equal(MhBackupHandleMatchKind.NoHandle, locator.Match("srv-webdav").Kind);
        Assert.Equal(MhBackupHandleMatchKind.NoHandle, locator.Match("srv-plex").Kind);
        Assert.Equal(MhBackupHandleMatchKind.NoHandle, locator.Match("srv-not-in-payload").Kind);
        Assert.Equal(MhBackupHandleMatchKind.NoHandle, locator.Match("").Kind);
    }
}

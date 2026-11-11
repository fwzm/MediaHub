using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// W6-13: the text the sync page shows for already-paired devices.
/// </summary>
/// <remarks>
/// These tests pin the wording and the amount of the fingerprint that reaches the screen, not a rendering. The page
/// only decides visibility, so everything a user reads is asserted here without a UI. Registry-backed cases use a
/// throwaway directory under the temp path; nothing touches %LOCALAPPDATA% and no test opens a socket.
/// </remarks>
public sealed class PairedDeviceListViewTests : IDisposable
{
    private const long FixedNow = 1_700_000_000_000;

    private readonly string _root = Path.Combine(Path.GetTempPath(), "mh-paired-view-" + Guid.NewGuid().ToString("N"));

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
        catch (UnauthorizedAccessException)
        {
        }
    }

    // ---------------------------------------------------------------- one row

    [Fact]
    public void Row_ShowsOnlyTheFingerprintTail()
    {
        string fingerprint = Hex(7);
        PairedDeviceRow row = PairedDeviceListPresenter.BuildRow(
            PairedDevice.Create("dev-a", fingerprint, "桌面", FixedNow));

        Assert.EndsWith(fingerprint[^PairedDeviceListPresenter.FingerprintSuffixLength..], row.FingerprintLabel);
        Assert.DoesNotContain(fingerprint, row.FingerprintLabel, StringComparison.Ordinal);
        Assert.Equal("指纹 …" + fingerprint[^8..], row.FingerprintLabel);
    }

    [Fact]
    public void Row_NeverRepeatsTheWholeFingerprintAnywhere()
    {
        string fingerprint = Hex(11);
        PairedDeviceRow row = PairedDeviceListPresenter.BuildRow(
            PairedDevice.Create("dev-a", fingerprint, "客厅", FixedNow));

        Assert.DoesNotContain(fingerprint, row.SummaryLine, StringComparison.Ordinal);
        Assert.DoesNotContain(fingerprint, row.DetailLine, StringComparison.Ordinal);
        Assert.DoesNotContain(fingerprint, row.ForgetLabel, StringComparison.Ordinal);
    }

    [Fact]
    public void Row_FallsBackToTheLeadingDeviceIdWhenNoNameWasDeclared()
    {
        PairedDeviceRow row = PairedDeviceListPresenter.BuildRow(
            PairedDevice.Create("0123456789abcdef", Hex(1), "   ", FixedNow));

        Assert.Equal("01234567", row.DisplayName);
        Assert.StartsWith("01234567 · ", row.SummaryLine, StringComparison.Ordinal);
        Assert.Equal("忘记 01234567", row.ForgetLabel);
    }

    [Fact]
    public void Row_KeepsAShortDeviceIdWholeInTheFallback()
    {
        PairedDeviceRow row = PairedDeviceListPresenter.BuildRow(
            PairedDevice.Create("dev", Hex(1), null, FixedNow));

        Assert.Equal("dev", row.DisplayName);
    }

    [Fact]
    public void Row_FormatsBothTimestampsInLocalTime()
    {
        string expected = DateTimeOffset.FromUnixTimeMilliseconds(FixedNow)
            .ToLocalTime()
            .ToString("yyyy-MM-dd HH:mm", CultureInfo.InvariantCulture);

        PairedDeviceRow row = PairedDeviceListPresenter.BuildRow(
            PairedDevice.Create("dev-a", Hex(1), "桌面", FixedNow));

        Assert.Equal("首次配对 " + expected, row.PairedLabel);
        Assert.Equal("最后相见 " + expected, row.LastSeenLabel);
    }

    [Fact]
    public void Row_SaysUnknownInsteadOfAPlausibleDate()
    {
        PairedDeviceRow row = PairedDeviceListPresenter.BuildRow(
            PairedDevice.Create("dev-a", Hex(1), "桌面", FixedNow, pairedAtEpochMs: 0));

        Assert.Equal("首次配对 未知", row.PairedLabel);
        Assert.True(PairedDevice.Create("dev-a", Hex(1), "桌面", FixedNow, pairedAtEpochMs: 0).PairedAtEpochMs == 0);
    }

    [Fact]
    public void Row_StatesTheSourceCountAndJoinsEveryPartWithSeparators()
    {
        PairedDeviceRow row = PairedDeviceListPresenter.BuildRow(
            PairedDevice.Create("dev-a", Hex(1), "桌面", FixedNow, sourceCount: 3));

        Assert.Equal("3 个来源", row.SourceLabel);
        Assert.Contains(" · ", row.SummaryLine, StringComparison.Ordinal);
        Assert.Contains("首次配对 ", row.SummaryLine, StringComparison.Ordinal);
        Assert.Contains("最后相见 ", row.SummaryLine, StringComparison.Ordinal);
        Assert.Contains("桌面", row.SummaryLine, StringComparison.Ordinal);

        // The caption repeats the line without the name, because the name is the row title.
        Assert.DoesNotContain("桌面", row.DetailLine, StringComparison.Ordinal);
        Assert.StartsWith(row.FingerprintLabel, row.DetailLine, StringComparison.Ordinal);
    }

    // ---------------------------------------------------------------- the section

    [Fact]
    public void Build_EmptyRegistryShowsTheEmptySentence()
    {
        PairedDeviceListView view = PairedDeviceListPresenter.Build([]);

        Assert.True(view.IsEmpty);
        Assert.Equal("还没有配对过的设备。", view.EmptyLabel);
        Assert.Equal("已配对 0 台", view.CountLabel);
        Assert.Empty(view.Rows);
        Assert.False(view.IsFull);
        Assert.Equal(string.Empty, view.CapacityLabel);
    }

    [Fact]
    public void Build_TreatsAMissingListAsAnEmptyRegistry()
    {
        PairedDeviceListView view = PairedDeviceListPresenter.Build(null);

        Assert.True(view.IsEmpty);
        Assert.Equal(PairedDeviceRegistry.DefaultCapacity, view.Capacity);
    }

    [Fact]
    public void Build_CountLabelFollowsTheNumberOfRows()
    {
        PairedDeviceListView view = PairedDeviceListPresenter.Build(
        [
            PairedDevice.Create("dev-a", Hex(1), "桌面", FixedNow),
            PairedDevice.Create("dev-b", Hex(2), "客厅", FixedNow),
        ]);

        Assert.Equal(2, view.Count);
        Assert.Equal("已配对 2 台", view.CountLabel);
        Assert.False(view.IsEmpty);
    }

    [Fact]
    public void Build_PreservesTheOrderItWasGiven()
    {
        PairedDeviceListView view = PairedDeviceListPresenter.Build(
        [
            PairedDevice.Create("dev-b", Hex(2), "客厅", FixedNow),
            PairedDevice.Create("dev-a", Hex(1), "桌面", FixedNow),
        ]);

        Assert.Equal(["dev-b", "dev-a"], view.Rows.Select(row => row.DeviceId).ToArray());
    }

    [Fact]
    public void Build_WarnsOnlyWhenTheRegistryIsFull()
    {
        PairedDevice[] one = [PairedDevice.Create("dev-a", Hex(1), "桌面", FixedNow)];
        PairedDevice[] two =
        [
            PairedDevice.Create("dev-a", Hex(1), "桌面", FixedNow),
            PairedDevice.Create("dev-b", Hex(2), "客厅", FixedNow),
        ];

        PairedDeviceListView notFull = PairedDeviceListPresenter.Build(one, capacity: 2);
        Assert.False(notFull.IsFull);
        Assert.Equal(string.Empty, notFull.CapacityLabel);

        PairedDeviceListView full = PairedDeviceListPresenter.Build(two, capacity: 2);
        Assert.True(full.IsFull);
        Assert.Contains("2 台", full.CapacityLabel, StringComparison.Ordinal);
        Assert.Contains("上限", full.CapacityLabel, StringComparison.Ordinal);
        Assert.Contains("忘记", full.CapacityLabel, StringComparison.Ordinal);
    }

    [Fact]
    public void Build_DefaultCapacityIsTheRegistryCapacity()
    {
        PairedDeviceListView view = PairedDeviceListPresenter.Build([]);

        Assert.Equal(PairedDeviceRegistry.DefaultCapacity, view.Capacity);
    }

    [Fact]
    public void Tofu_SaysThePeerCannotBeVerifiedAtFirstPairing()
    {
        PairedDeviceListView view = PairedDeviceListPresenter.Build([]);

        Assert.Contains("首次配对", view.TofuLabel, StringComparison.Ordinal);
        Assert.Contains("无法验证", view.TofuLabel, StringComparison.Ordinal);
        Assert.Contains("同一台", view.TofuLabel, StringComparison.Ordinal);
        Assert.DoesNotContain("已登录", view.TofuLabel, StringComparison.Ordinal);
        Assert.DoesNotContain("已信任", view.TofuLabel, StringComparison.Ordinal);
    }

    // ---------------------------------------------------------------- registry round trips

    [Fact]
    public void Forget_DropsTheRowAndWritesTheFile()
    {
        var store = new FilePairedDeviceStore(_root);
        var registry = new PairedDeviceRegistry(store);
        Assert.True(registry.Register(Observation("dev-a", Hex(1), "桌面")).Accepted);

        PairedDeviceListView before = PairedDeviceListPresenter.Build(registry.GetAll(), registry.Capacity);
        Assert.Single(before.Rows);
        int writesBefore = store.WriteCount;

        Assert.True(registry.Forget("dev-a"));
        PairedDeviceListView after = PairedDeviceListPresenter.Build(registry.GetAll(), registry.Capacity);

        Assert.Empty(after.Rows);
        Assert.True(after.IsEmpty);
        Assert.Equal("已配对 0 台", after.CountLabel);
        Assert.True(store.WriteCount > writesBefore, "forgetting must persist, not only mutate memory");
    }

    [Fact]
    public void Forget_AnUnknownDeviceReportsFalseAndLeavesTheFileAlone()
    {
        var store = new FilePairedDeviceStore(_root);
        var registry = new PairedDeviceRegistry(store);
        Assert.True(registry.Register(Observation("dev-a", Hex(1), "桌面")).Accepted);
        int writesBefore = store.WriteCount;

        Assert.False(registry.Forget("dev-unknown"));
        Assert.False(registry.Forget("  "));

        Assert.Equal(writesBefore, store.WriteCount);
        Assert.Single(registry.GetAll());
    }

    [Fact]
    public void Mismatch_KeepsShowingThePinnedFingerprint()
    {
        string pinned = Hex(1);
        string impostor = Hex(2);
        var store = new FilePairedDeviceStore(_root);
        var registry = new PairedDeviceRegistry(store);
        Assert.True(registry.Register(Observation("dev-a", pinned, "桌面")).Accepted);

        PairedDeviceRegistration second = registry.Register(Observation("dev-a", impostor, "桌面"));
        Assert.True(second.IsMismatch);
        Assert.False(second.Accepted);

        PairedDeviceRow row = Assert.Single(
            PairedDeviceListPresenter.Build(registry.GetAll(), registry.Capacity).Rows);

        Assert.Equal("指纹 …" + pinned[^8..], row.FingerprintLabel);
        Assert.NotEqual("指纹 …" + impostor[^8..], row.FingerprintLabel);
        Assert.DoesNotContain(impostor[^8..], row.SummaryLine, StringComparison.Ordinal);
    }

    // ---------------------------------------------------------------- helpers

    private static PairedDeviceObservation Observation(string deviceId, string fingerprint, string displayName) => new()
    {
        DeviceId = deviceId,
        Fingerprint = fingerprint,
        DisplayName = displayName,
        SourceCount = 1,
        NowEpochMs = FixedNow,
    };

    /// <summary>32 deterministic bytes rendered as lowercase hex — a well-formed fingerprint.</summary>
    private static string Hex(int seed)
    {
        byte[] bytes = new byte[32];
        for (int index = 0; index < bytes.Length; index++)
        {
            bytes[index] = (byte)(seed + index);
        }

        return Convert.ToHexString(bytes).ToLowerInvariant();
    }
}

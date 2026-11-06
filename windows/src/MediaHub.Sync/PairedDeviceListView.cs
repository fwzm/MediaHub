using System;
using System.Collections.Generic;
using System.Globalization;

namespace MediaHub.Sync;

/// <summary>
/// One rendered row of the paired-device list (W6-13).
/// </summary>
/// <remarks>
/// <para>
/// The row carries text, never the raw device record: a page binds these properties and does not decide how a
/// device is named, how much of a fingerprint is shown, or how a timestamp is formatted.
/// </para>
/// <para>
/// <see cref="SummaryLine"/> is the whole sentence for one device and is what an assistive technology reads;
/// <see cref="DetailLine"/> is the same sentence without the name, because the name is already the row title.
/// </para>
/// </remarks>
public sealed record PairedDeviceRow
{
    /// <summary>Identity of the device; the only value the page needs in order to forget it.</summary>
    public required string DeviceId { get; init; }

    /// <summary>Display name, or the leading part of the device id when the peer declared no name.</summary>
    public string DisplayName { get; init; } = "";

    /// <summary>The tail of the pinned fingerprint. Never the whole fingerprint: eight hex digits identify it, the
    /// full value is only useful for copying and would just add noise to a list.</summary>
    public string FingerprintLabel { get; init; } = "";

    /// <summary>When this machine first pinned the device.</summary>
    public string PairedLabel { get; init; } = "";

    /// <summary>When the device was last seen pairing.</summary>
    public string LastSeenLabel { get; init; } = "";

    /// <summary>How many sources the peer listed, in words.</summary>
    public string SourceLabel { get; init; } = "";

    /// <summary>Name · fingerprint · first paired · last seen · sources.</summary>
    public string SummaryLine { get; init; } = "";

    /// <summary>The same line without the name, for the caption under the row title.</summary>
    public string DetailLine { get; init; } = "";

    /// <summary>Accessible name of the forget button: 「忘记 &lt;device&gt;」.</summary>
    public string ForgetLabel { get; init; } = "";

    /// <summary>Last-seen stamp, kept unformatted so a caller can sort by it.</summary>
    public long LastSeenEpochMs { get; init; }
}

/// <summary>
/// Everything the paired-device section shows at one moment (W6-13): the rows, the count, the empty text, the
/// capacity warning and the trust-on-first-use caveat.
/// </summary>
public sealed record PairedDeviceListView
{
    /// <summary>The rows, in the order they were handed in.</summary>
    public required IReadOnlyList<PairedDeviceRow> Rows { get; init; }

    /// <summary>「已配对 N 台」.</summary>
    public string CountLabel { get; init; } = "";

    /// <summary>Shown instead of the list when nothing has been paired yet.</summary>
    public string EmptyLabel { get; init; } = "";

    /// <summary>The sentence that must survive any future edit: first pairing cannot verify the peer.</summary>
    public string TofuLabel { get; init; } = "";

    /// <summary>Non-empty only when the registry is full; then every new pairing would be refused.</summary>
    public string CapacityLabel { get; init; } = "";

    /// <summary>True when no device is registered.</summary>
    public bool IsEmpty => Rows.Count == 0;

    /// <summary>True when a new pairing would be refused for lack of room.</summary>
    public bool IsFull => CapacityLabel.Length > 0;

    /// <summary>How many devices are listed.</summary>
    public int Count => Rows.Count;

    /// <summary>The capacity the list was built against.</summary>
    public int Capacity { get; init; }
}

/// <summary>
/// Turns the W6-12 paired-device registry into the text the sync page renders (W6-13).
/// </summary>
/// <remarks>
/// <para>
/// Every sentence lives here rather than in XAML or the code-behind, for two reasons: the tests can assert the
/// wording without a UI, and a translator has one file to touch. The page only decides visibility.
/// </para>
/// <para>
/// The semantics are deliberately narrow. A row proves only that this machine once exchanged a self-declared card
/// with a device and pinned the fingerprint it carried; it does not prove the peer is trusted, that the sources were
/// imported, or that anyone is signed in. Forgetting a device deletes the local entry and nothing else: the sources,
/// the credentials and the peer are untouched, and the next pairing is treated as a first pairing again.
/// </para>
/// </remarks>
public static class PairedDeviceListPresenter
{
    /// <summary>How many characters of the device id stand in for a missing display name.</summary>
    public const int FallbackNameLength = 8;

    /// <summary>How many trailing hex digits of the fingerprint are shown.</summary>
    public const int FingerprintSuffixLength = 8;

    /// <summary>Shown when the registry is empty.</summary>
    public const string EmptyText = "还没有配对过的设备。";

    /// <summary>
    /// Trust-on-first-use caveat. It says what the fingerprint can and cannot do, because "paired" otherwise reads
    /// as "trusted".
    /// </summary>
    public const string TofuText =
        "首次配对时无法验证对方身份：指纹只用来识别「上次那台是不是同一台」，不代表对方可信。";

    /// <summary>Prefix of the capacity warning; the number and the instruction are appended.</summary>
    public const string CapacityPrefix = "已登记 ";

    /// <summary>Builds the whole section in one call.</summary>
    /// <param name="devices">Registered devices, typically <see cref="PairedDeviceRegistry.GetAll"/>.</param>
    /// <param name="capacity">Registry capacity; zero or less falls back to <see cref="PairedDeviceRegistry.DefaultCapacity"/>.</param>
    /// <param name="nowEpochMs">Unused today, kept so a caller never has to change when relative wording arrives.</param>
    public static PairedDeviceListView Build(
        IReadOnlyList<PairedDevice>? devices,
        int capacity = PairedDeviceRegistry.DefaultCapacity,
        long nowEpochMs = 0)
    {
        _ = nowEpochMs; // no relative wording yet: an absolute stamp cannot be misread across time zones
        int limit = capacity > 0 ? capacity : PairedDeviceRegistry.DefaultCapacity;
        devices ??= Array.Empty<PairedDevice>();

        var rows = new List<PairedDeviceRow>(devices.Count);
        foreach (PairedDevice device in devices)
        {
            if (device is not null)
            {
                rows.Add(BuildRow(device));
            }
        }

        return new PairedDeviceListView
        {
            Rows = rows,
            CountLabel = CountText(rows.Count),
            EmptyLabel = EmptyText,
            TofuLabel = TofuText,
            CapacityLabel = rows.Count >= limit ? CapacityText(limit) : string.Empty,
            Capacity = limit,
        };
    }

    /// <summary>Builds one row.</summary>
    /// <exception cref="ArgumentNullException"><paramref name="device"/> is null.</exception>
    public static PairedDeviceRow BuildRow(PairedDevice device)
    {
        ArgumentNullException.ThrowIfNull(device);

        string name = ResolveName(device);
        string fingerprint = FingerprintText(device.Fingerprint);
        string paired = "首次配对 " + FormatEpoch(device.PairedAtEpochMs);
        string lastSeen = "最后相见 " + FormatEpoch(device.LastSeenEpochMs);
        string sources = SourceText(device.SourceCount);

        return new PairedDeviceRow
        {
            DeviceId = device.DeviceId,
            DisplayName = name,
            FingerprintLabel = fingerprint,
            PairedLabel = paired,
            LastSeenLabel = lastSeen,
            SourceLabel = sources,
            SummaryLine = string.Join(" · ", new[] { name, fingerprint, paired, lastSeen, sources }),
            DetailLine = string.Join(" · ", new[] { fingerprint, paired, lastSeen, sources }),
            ForgetLabel = "忘记 " + name,
            LastSeenEpochMs = device.LastSeenEpochMs,
        };
    }

    /// <summary>「已配对 N 台」.</summary>
    public static string CountText(int count) => "已配对 " + count.ToString(CultureInfo.InvariantCulture) + " 台";

    /// <summary>The sentence shown when the registry is full; a new pairing would then be refused.</summary>
    public static string CapacityText(int capacity)
        => CapacityPrefix + capacity.ToString(CultureInfo.InvariantCulture)
            + " 台，已达上限：新的设备不会被登记，请先忘记一台不再使用的设备。";

    /// <summary>Display name, falling back to the leading part of the device id.</summary>
    public static string ResolveName(PairedDevice device)
    {
        ArgumentNullException.ThrowIfNull(device);

        if (!string.IsNullOrWhiteSpace(device.DisplayName))
        {
            return device.DisplayName.Trim();
        }

        string id = device.DeviceId ?? string.Empty;
        return id.Length <= FallbackNameLength ? id : id[..FallbackNameLength];
    }

    /// <summary>Trailing hex digits of the fingerprint, or a plain statement that there is none.</summary>
    public static string FingerprintText(string? fingerprint)
    {
        string value = (fingerprint ?? string.Empty).Trim();
        if (value.Length == 0)
        {
            return "无指纹";
        }

        string suffix = value.Length <= FingerprintSuffixLength
            ? value
            : value[^FingerprintSuffixLength..];
        return "指纹 …" + suffix;
    }

    /// <summary>「N 个来源」.</summary>
    public static string SourceText(int sourceCount)
        => Math.Max(sourceCount, 0).ToString(CultureInfo.InvariantCulture) + " 个来源";

    /// <summary>
    /// Local timestamp for a stored epoch, or 「未知」 when the value cannot be one. Local time is right here: the
    /// user compares it against their own clock.
    /// </summary>
    public static string FormatEpoch(long epochMs)
    {
        if (epochMs <= 0 || epochMs > DateTimeOffset.MaxValue.ToUnixTimeMilliseconds())
        {
            return "未知";
        }

        try
        {
            return DateTimeOffset.FromUnixTimeMilliseconds(epochMs)
                .ToLocalTime()
                .ToString("yyyy-MM-dd HH:mm", CultureInfo.InvariantCulture);
        }
        catch (ArgumentOutOfRangeException)
        {
            return "未知";
        }
    }
}

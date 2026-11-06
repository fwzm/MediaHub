using MediaHub.Backup;
using MediaHub.Domain;
using MediaHub.Providers;

namespace MediaHub.Backup.Providers;

/// <summary>How one backup source was paired with a logged-in local handle.</summary>
public enum MhBackupHandleMatchKind
{
    /// <summary>Exactly one local handle is the same server.</summary>
    Matched,

    /// <summary>No local handle of that kind exists: the user has to add the source and log in first.</summary>
    NoHandle,

    /// <summary>The backup names an address this machine has no logged-in source for; pairing is refused rather than guessed.</summary>
    AddressMismatch,

    /// <summary>Several local handles fit equally well; writing to the wrong one would be worse than not writing.</summary>
    Ambiguous,
}

/// <summary>One pairing decision, with the sentence shown to the user when it is not a match.</summary>
public sealed record MhBackupHandleMatch(MhBackupHandleMatchKind Kind, ProviderHandle? Handle, string Reason)
{
    public bool IsMatched => Kind == MhBackupHandleMatchKind.Matched;

    /// <summary>Stable code for reports and logs.</summary>
    public string KindCode => Kind switch
    {
        MhBackupHandleMatchKind.Matched => "matched",
        MhBackupHandleMatchKind.NoHandle => "no-handle",
        MhBackupHandleMatchKind.AddressMismatch => "address-mismatch",
        _ => "ambiguous",
    };
}

/// <summary>
/// Pairs every source in a backup with a <b>logged-in</b> local handle, so the write-back layer gets a
/// session instead of a null.
/// </summary>
/// <remarks>
/// Pairing is deliberately conservative: the source kind must match and an endpoint address must match.
/// When nothing matches, or several handles match equally well, this returns an explicit refusal
/// rather than guessing — writing a resume position into the wrong library is worse than not writing it.
/// The address comparison normalises scheme/host case, drops a default port and a trailing slash, so
/// "https://Media.Example:443/emby/" and "https://media.example/emby" are the same endpoint.
/// </remarks>
public sealed class MhBackupHandleLocator : IMhBackupServerWriters
{
    private readonly Dictionary<string, MhBackupHandleMatch> _matches;

    private MhBackupHandleLocator(Dictionary<string, MhBackupHandleMatch> matches) => _matches = matches;

    /// <summary>Build the locator from a validated payload and the handles this build currently holds.</summary>
    public static MhBackupHandleLocator Create(MhBackupPayload payload, IEnumerable<ProviderHandle> handles)
    {
        ArgumentNullException.ThrowIfNull(payload);
        ArgumentNullException.ThrowIfNull(handles);

        ProviderHandle[] candidates = handles.Where(handle => handle is not null).ToArray();
        var matches = new Dictionary<string, MhBackupHandleMatch>(StringComparer.Ordinal);
        foreach (MhBackupServerDto server in payload.Servers)
        {
            string? backupId = server.BackupId;
            if (string.IsNullOrWhiteSpace(backupId) || matches.ContainsKey(backupId)) continue;
            matches[backupId] = MatchOne(server, candidates);
        }

        return new MhBackupHandleLocator(matches);
    }

    /// <summary>Pairing decision for one backup source id; unknown ids report <see cref="MhBackupHandleMatchKind.NoHandle"/>.</summary>
    public MhBackupHandleMatch Match(string serverBackupId)
    {
        if (!string.IsNullOrEmpty(serverBackupId) && _matches.TryGetValue(serverBackupId, out MhBackupHandleMatch? match))
            return match;
        return new MhBackupHandleMatch(
            MhBackupHandleMatchKind.NoHandle,
            null,
            "备份里的这个来源在本机没有对应的已登录来源（需要先添加来源并登录）");
    }

    /// <inheritdoc />
    public IMhBackupServerWriter? ForServer(string serverBackupId)
    {
        MhBackupHandleMatch match = Match(serverBackupId);
        return match.Kind switch
        {
            MhBackupHandleMatchKind.Matched => new MhBackupProviderServerWriter(match.Handle!),
            MhBackupHandleMatchKind.NoHandle => null,
            _ => new MhBackupRefusingWriter(match.Reason),
        };
    }

    private static MhBackupHandleMatch MatchOne(MhBackupServerDto server, IReadOnlyList<ProviderHandle> candidates)
    {
        string? type = NormaliseType(server.Type);
        ServerType? wanted = type switch
        {
            "EMBY" => ServerType.Emby,
            "JELLYFIN" => ServerType.Jellyfin,
            _ => null,
        };
        if (wanted is null)
        {
            return new MhBackupHandleMatch(
                MhBackupHandleMatchKind.NoHandle,
                null,
                "备份里的来源类型「" + (type ?? "未知") + "」没有可写的服务端接口");
        }

        ProviderHandle[] sameKind = candidates.Where(handle => handle.Server.Type == wanted.Value).ToArray();
        if (sameKind.Length == 0)
        {
            return new MhBackupHandleMatch(
                MhBackupHandleMatchKind.NoHandle,
                null,
                "本机还没有这个来源（或没有同类型的已登录来源），需要先添加来源并登录");
        }

        var wantedAddresses = new HashSet<string>(StringComparer.Ordinal);
        foreach (MhBackupEndpointDto endpoint in server.Endpoints)
        {
            string? normalised = NormaliseEndpoint(endpoint.Url);
            if (normalised is not null) wantedAddresses.Add(normalised);
        }

        ProviderHandle[] addressMatches = sameKind
            .Where(handle => handle.Server.Endpoints.Any(endpoint => wantedAddresses.Contains(NormaliseEndpoint(endpoint.Url) ?? string.Empty)))
            .ToArray();

        if (addressMatches.Length == 1)
        {
            return new MhBackupHandleMatch(
                MhBackupHandleMatchKind.Matched,
                addressMatches[0],
                "按地址对上本机已登录来源「" + addressMatches[0].Server.DisplayName + "」");
        }

        if (addressMatches.Length > 1)
        {
            return new MhBackupHandleMatch(
                MhBackupHandleMatchKind.Ambiguous,
                null,
                "本机有 " + addressMatches.Length + " 个同地址的已登录来源，无法确定写哪一个（不猜）");
        }

        if (wantedAddresses.Count == 0)
        {
            if (sameKind.Length == 1)
            {
                return new MhBackupHandleMatch(
                    MhBackupHandleMatchKind.Matched,
                    sameKind[0],
                    "备份里没有来源地址，本机只有一个同类型来源「" + sameKind[0].Server.DisplayName + "」");
            }

            return new MhBackupHandleMatch(
                MhBackupHandleMatchKind.Ambiguous,
                null,
                "备份里没有来源地址，本机有 " + sameKind.Length + " 个同类型来源，无法确定写哪一个（不猜）");
        }

        return new MhBackupHandleMatch(
            MhBackupHandleMatchKind.AddressMismatch,
            null,
            "备份里的地址「" + string.Join("、", wantedAddresses) + "」在本机没有对应的已登录来源（地址不一致时不猜）");
    }

    /// <summary>备份里的类型码与 <see cref="ServerType"/> 的对应关系（只认这两种有服务端写口的类型）。</summary>
    private static string? NormaliseType(string? type)
    {
        if (string.IsNullOrWhiteSpace(type)) return null;
        string trimmed = type.Trim().ToUpperInvariant();
        return trimmed switch
        {
            "EMBY" => "EMBY",
            "JELLYFIN" => "JELLYFIN",
            _ => trimmed,
        };
    }

    /// <summary>Address identity used for pairing: case-insensitive scheme/host, no default port, no trailing slash.</summary>
    private static string? NormaliseEndpoint(string? url)
    {
        if (string.IsNullOrWhiteSpace(url)) return null;
        if (!Uri.TryCreate(url.Trim(), UriKind.Absolute, out Uri? uri)) return null;
        if (uri.Scheme is not ("http" or "https")) return null;

        string authority = uri.Host.ToLowerInvariant();
        if (!uri.IsDefaultPort) authority = authority + ":" + uri.Port.ToString(System.Globalization.CultureInfo.InvariantCulture);

        string path = uri.AbsolutePath.TrimEnd('/');
        return uri.Scheme.ToLowerInvariant() + "://" + authority + path;
    }
}

/// <summary>Refuses every write with a fixed sentence, used when pairing is refused (not "not logged in").</summary>
internal sealed class MhBackupRefusingWriter : IMhBackupServerWriter
{
    private readonly string _reason;

    public MhBackupRefusingWriter(string reason) => _reason = reason;

    public Task<MhBackupWriteAnswer> SavePlaybackPositionAsync(string itemId, long positionTicks, CancellationToken ct)
        => Task.FromResult(MhBackupWriteAnswer.Fail(_reason));

    public Task<MhBackupWriteAnswer> SetFavoriteAsync(string itemId, CancellationToken ct)
        => Task.FromResult(MhBackupWriteAnswer.Fail(_reason));
}

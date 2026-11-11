using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using MediaHub.Domain;
using MediaHub.Net;
using MediaHub.Providers;
using MediaHub.Providers.MediaBrowser;

namespace MediaHub.ResumeProbe;

/// <summary>
/// Headless proof of the W8 resume contract: the product provider stack records a playback position,
/// a brand-new client instance (the equivalent of restarting the app) reads it back, and finishing a
/// title clears it. Nothing here opens a window, touches libmpv or reassembles an HTTP request by
/// hand: every call goes through <see cref="IMediaProviderFactory"/>, <see cref="ProviderHandle"/>,
/// <see cref="IProviderProgress"/> and <see cref="IProviderDetail"/>, which are the exact types the
/// App itself uses.
/// </summary>
/// <remarks>
/// <para>
/// Why a fresh client per step: an assertion read back through the same <c>HttpClient</c>/provider
/// object that reported the position could be satisfied by state held in this process. Rebuilding the
/// handle (new factory output, new login, new HttpClient) is the closest headless equivalent of a
/// restart, so a pass means the server — not our object graph — remembers.
/// </para>
/// <para>
/// Why the position assertions carry both numbers: <see cref="MediaBrowserProvider"/> swallows
/// progress-report failures by design (reporting is best effort, see MediaBrowserProvider.cs:393).
/// A report that never reached the server therefore looks exactly like a successful one, and only the
/// reported-versus-read-back comparison can tell them apart.
/// </para>
/// </remarks>
internal static class Program
{
    private const long TicksPerSecond = TimeSpan.TicksPerSecond;

    /// <summary>
    /// Servers ignore resume points on titles shorter than five minutes and treat them as finished
    /// (measured on Jellyfin 12.2: <c>MinResumeDurationSeconds=300</c>, windows/docs/W8-EVIDENCE.md).
    /// Shorter items would make this probe test the duration rule instead of the round trip.
    /// </summary>
    internal const long MinimumRuntimeTicks = 300 * TicksPerSecond;

    private static async Task<int> Main(string[] args)
    {
        ProbeOptions? options;
        try
        {
            options = ProbeOptions.Parse(args);
        }
        catch (ArgumentException ex)
        {
            Console.Error.WriteLine("[probe] argument-error " + ex.Message);
            Console.Error.WriteLine(ProbeOptions.Usage);
            return 2;
        }

        if (options is null)
        {
            Console.WriteLine(ProbeOptions.Usage);
            return 0;
        }

        return await new ProbeRunner(options).RunAsync();
    }
}

/// <summary>Command line of the probe; see <see cref="Usage"/>.</summary>
internal sealed record ProbeOptions
{
    public const string Usage =
        "MediaHub.ResumeProbe - headless W8 resume proof over the product provider stack\n" +
        "\n" +
        "Usage:\n" +
        "  MediaHub.ResumeProbe --server <emby|jellyfin> --url <base-url> --user <name> --password <secret>\n" +
        "                      [--json <path>] [--label <text>] [--settle-ms <milliseconds>]\n" +
        "\n" +
        "Exit codes: 0 = every assertion passed, 1 = an assertion failed, 2 = usage error or no connection.\n";

    public required string ServerKey { get; init; }
    public required ServerType Type { get; init; }
    public required Uri BaseUrl { get; init; }
    public required string User { get; init; }
    public required string Password { get; init; }
    public string? JsonPath { get; init; }
    public string Label { get; init; } = "resume";
    public int SettleMilliseconds { get; init; } = 750;

    /// <summary>Parses the command line; returns null when the caller asked for help.</summary>
    /// <exception cref="ArgumentException">The command line is unusable.</exception>
    public static ProbeOptions? Parse(string[] args)
    {
        string? server = null;
        string? url = null;
        string? user = null;
        string? password = null;
        string? jsonPath = null;
        string label = "resume";
        int settle = 750;

        for (int i = 0; i < args.Length; i++)
        {
            string name = args[i];
            string Next()
            {
                if (i + 1 >= args.Length) throw new ArgumentException(name + " needs a value");
                return args[++i];
            }

            switch (name)
            {
                case "-h":
                case "--help":
                case "help":
                    return null;
                case "--server":
                    server = Next();
                    break;
                case "--url":
                    url = Next();
                    break;
                case "--user":
                    user = Next();
                    break;
                case "--password":
                    password = Next();
                    break;
                case "--json":
                    jsonPath = Next();
                    break;
                case "--label":
                    label = Next();
                    break;
                case "--settle-ms":
                {
                    string value = Next();
                    if (!int.TryParse(value, NumberStyles.Integer, CultureInfo.InvariantCulture, out settle) || settle < 0)
                        throw new ArgumentException("--settle-ms needs a non-negative integer");
                    break;
                }
                default:
                    throw new ArgumentException("unknown option '" + name + "'");
            }
        }

        if (string.IsNullOrWhiteSpace(server)) throw new ArgumentException("--server is required");
        ServerType type = server.Trim().ToLowerInvariant() switch
        {
            "emby" => ServerType.Emby,
            "jellyfin" => ServerType.Jellyfin,
            _ => throw new ArgumentException("--server must be emby or jellyfin"),
        };

        if (!Uri.TryCreate(url, UriKind.Absolute, out Uri? baseUri) ||
            (baseUri.Scheme != Uri.UriSchemeHttp && baseUri.Scheme != Uri.UriSchemeHttps))
        {
            throw new ArgumentException("--url must be an absolute http(s) URL");
        }

        if (baseUri.UserInfo.Length > 0) throw new ArgumentException("--url must not carry user information");
        if (string.IsNullOrEmpty(user)) throw new ArgumentException("--user is required");
        if (string.IsNullOrEmpty(password)) throw new ArgumentException("--password is required");

        return new ProbeOptions
        {
            ServerKey = type == ServerType.Emby ? "emby" : "jellyfin",
            Type = type,
            BaseUrl = baseUri,
            User = user,
            Password = password,
            JsonPath = jsonPath,
            Label = label,
            SettleMilliseconds = settle,
        };
    }
}

/// <summary>What one progress report actually put on the wire, plus the session it belonged to.</summary>
internal sealed record ReportedSample(
    string ItemId,
    long RuntimeTicks,
    long ProgressTicks,
    long FinalTicks,
    string? SessionId,
    string Mode);

/// <summary>Per-item read-back state, taken through a freshly built client.</summary>
internal sealed record DetailState(long? ResumeTicks, long? DetailTicks, bool? Played);

/// <summary>Everything a fresh client can see in one read-back pass.</summary>
internal sealed record ReadbackState(
    IReadOnlyDictionary<string, DetailState> Details,
    IReadOnlySet<string> ResumeIds,
    JsonArray ResumeList);

/// <summary>Runs the four phases and collects the evidence.</summary>
internal sealed class ProbeRunner
{
    private const long TicksPerSecond = TimeSpan.TicksPerSecond;

    private readonly ProbeOptions _options;
    private readonly IMediaProviderFactory _factory;
    private readonly MediaServer _server;
    private readonly NetworkPolicy _policy;
    private readonly Credentials _credentials;
    private readonly DateTimeOffset _startedUtc = DateTimeOffset.UtcNow;
    private readonly JsonObject _items = new();
    private readonly JsonArray _phases = new();
    private readonly JsonArray _assertions = new();
    private readonly List<string> _console = new();

    private int _clients;
    private bool _connected;

    public ProbeRunner(ProbeOptions options)
    {
        _options = options;
        _factory = options.Type == ServerType.Emby ? new EmbyProviderFactory() : new JellyfinProviderFactory();
        _server = new MediaServer(
            "w8-resume",
            options.Type,
            "W8 headless resume probe",
            new[] { new ServerEndpoint(options.BaseUrl.GetLeftPart(UriPartial.Authority)) });
        _policy = new NetworkPolicy { Mode = ProxyMode.Direct };
        _credentials = new Credentials(options.User, options.Password);
    }

    public async Task<int> RunAsync()
    {
        string? error = null;
        bool connectionFailure = false;

        try
        {
            await RunPhasesAsync(CancellationToken.None);
        }
        catch (Exception ex)
        {
            error = ex.GetType().Name + ": " + ex.Message;
            connectionFailure = !_connected;
            Log("[probe] fatal " + error);
        }

        WriteReport(error);

        int passed = CountPassed();
        int failed = _assertions.Count - passed;
        Console.WriteLine("RESULT: " + passed + " passed, " + failed + " failed");
        if (error is not null) return connectionFailure ? 2 : 1;
        return failed == 0 ? 0 : 1;
    }

    private async Task RunPhasesAsync(CancellationToken ct)
    {
        Log("[probe] server=" + _options.ServerKey +
            " url=" + _options.BaseUrl.GetLeftPart(UriPartial.Authority) +
            " label=" + _options.Label);

        MediaItem target;

        using (var discovery = await OpenClientAsync("discovery", ct))
        {
            var libraries = await discovery.Library!.GetLibrariesAsync(ct);
            Log("[probe] libraries=" + libraries.Count + " [" +
                string.Join(" | ", libraries.Select(l => l.Name + ":" + (l.CollectionType ?? l.Type.ToString()))) + "]");

            // The fixtures keep every movie in its own folder, so a library listing answers with
            // folders, not playables. Walking the tree with the same GetItemsAsync the App's browse
            // page uses keeps discovery inside the product API and works on both servers.
            var playables = await CollectPlayablesAsync(discovery.Library, libraries.Select(l => l.Id), ct);
            var candidates = playables
                .Where(i => i.RunTimeTicks is { } runtime && runtime >= Program.MinimumRuntimeTicks)
                .OrderByDescending(i => i.RunTimeTicks!.Value)
                .ToList();
            Log("[probe] walked " + playables.Count + " playable item(s); " + candidates.Count +
                " of them are at least 300 s long");

            if (candidates.Count == 0)
            {
                throw new InvalidOperationException(
                    "no playable item of at least 300 s was found under the libraries; below that the server " +
                    "applies its minimum-resume-duration rule and the round trip cannot be observed");
            }

            // Every phase shares one title on purpose. Both fixtures ship exactly one item long enough
            // for the server's resume rules (600 s "Long Resume"), and the phases are naturally ordered:
            // a 40 % stop, then a 1 % stop that must overwrite it, then a 2 % stop that only Emby keeps,
            // then a finish. Running them in sequence is also what makes the floor cases meaningful —
            // each starts from a position the server had actually stored, not from a blank item.
            target = candidates[0];
            Log("[probe] selected target=" + target.Id + " \"" + target.Name + "\" " +
                Seconds(target.RunTimeTicks!.Value) + "s (the longest item at or above 300 s)");
            _items["target"] = ItemJson(target, "resume-floors-and-finish");
        }

        // Nothing below is allowed to assume the fixture reset worked; it is asserted instead, so a
        // stale "played" flag is reported as a fixture problem instead of a resume failure.
        ReadbackState preState = await ReadAsync(new[] { target.Id }, "prestate", ct);
        Check(
            "precondition-target-is-unplayed",
            "the driver's fixture reset left the target Played = false, so the finish phase is not a no-op",
            preState.Details[target.Id].Played == false,
            false,
            preState.Details[target.Id].Played);
        Check(
            "precondition-target-has-no-position",
            "the target starts without a stored position, so phase 1 measures our report and not an old one",
            preState.Details[target.Id].ResumeTicks is null or 0,
            0,
            preState.Details[target.Id].ResumeTicks);

        // ---- phase 1: 40 % of item A, reported, then read back by a brand-new client.
        ReportedSample sample1 = await ReportAsync(target, 2, 5, 2, 5, "phase1-resume", ct);
        await SettleAsync(ct);
        ReadbackState read1 = await ReadAsync(new[] { target.Id }, "phase1", ct);
        DetailState detail1 = read1.Details[target.Id];

        Check(
            "resume-position-roundtrip",
            "the position GetResumePositionAsync reports equals the position we last sent",
            detail1.ResumeTicks == sample1.FinalTicks,
            sample1.FinalTicks,
            detail1.ResumeTicks);
        Check(
            "resume-position-roundtrip-detail",
            "GetItemDetailAsync agrees with GetResumePositionAsync on the same item",
            detail1.DetailTicks == sample1.FinalTicks,
            sample1.FinalTicks,
            detail1.DetailTicks);
        Check(
            "resume-visible-from-a-new-client",
            "a freshly built client finds the title in GetContinueWatchingAsync",
            read1.ResumeIds.Contains(target.Id),
            true,
            read1.ResumeIds.Contains(target.Id));
        Check(
            "resume-not-marked-played",
            "40 % of the runtime does not mark the title as played",
            detail1.Played == false,
            false,
            detail1.Played);
        Check(
            "session-resolved-through-the-product-path",
            "ResolvePlaybackAsync returned the server session id the progress reports need",
            !string.IsNullOrEmpty(sample1.SessionId),
            "non-empty",
            string.IsNullOrEmpty(sample1.SessionId) ? "(empty)" : Prefix(sample1.SessionId!));
        RecordPhase("phase1-resume", sample1, read1, target.Id);

        // ---- phase 2: 1 % of the same title — below both servers' resume floors, so the position that
        // phase 1 had just stored must be dropped. Reusing one item turns the threshold rule into a
        // visible overwrite instead of a comparison against a blank item.
        // Floors, measured rather than assumed: Emby 4.10.2 discards a stop under 2 % of the runtime
        // (1.99 % dropped, 2.00 % kept on this 600 s title) and Jellyfin 12.2 discards below 5 %
        // (MinResumePct=5, windows/docs/W8-EVIDENCE.md). 1 % is under both, so the assertion holds on
        // either server; the disagreement at exactly 2 % is pinned separately in phase 2b.
        ReportedSample sample2 = await ReportAsync(target, 1, 100, 1, 100, "phase2-below-the-floor", ct);
        await SettleAsync(ct);
        ReadbackState read2 = await ReadAsync(new[] { target.Id }, "phase2", ct);
        DetailState detail2 = read2.Details[target.Id];

        Check(
            "below-floor-clears-the-position",
            "a 1 % stop erases the 40 % position the server had stored, so the floor rule ran " +
                "(stored before this phase: " + Format(detail1.ResumeTicks) + " ticks)",
            detail2.ResumeTicks is null or 0,
            0,
            detail2.ResumeTicks);
        Check(
            "below-floor-does-not-mark-played",
            "stopping below the resume floor does not mark the title as played",
            detail2.Played == false,
            false,
            detail2.Played);
        Check(
            "below-floor-stays-out-of-the-resume-list",
            "the item is not offered as continue-watching after a sub-floor stop, even though it was " +
                "listed there one phase earlier",
            !read2.ResumeIds.Contains(target.Id),
            false,
            read2.ResumeIds.Contains(target.Id));
        RecordPhase("phase2-below-the-floor", sample2, read2, target.Id);

        // ---- phase 2b: exactly 2 % of the runtime, which is where the two servers part company.
        // Emby 4.10.2 keeps a position at or above 2 % (measured on this 600 s title: 1.99 % dropped,
        // 2.00 % = 120,000,000 ticks kept and listed as resumable), Jellyfin 12.2 clears anything under
        // 5 %. Asserting each server's own answer records the difference as a contract instead of
        // pretending one threshold fits both; the observed value is always written to the report.
        bool emby = _options.Type == ServerType.Emby;
        long twoPercent = Scale(target.RunTimeTicks!.Value, 2, 100);
        ReportedSample sample2b = await ReportAsync(target, 2, 100, 2, 100, "phase2b-two-percent", ct);
        await SettleAsync(ct);
        ReadbackState read2b = await ReadAsync(new[] { target.Id }, "phase2b", ct);
        DetailState detail2b = read2b.Details[target.Id];
        bool twoPercentListed = read2b.ResumeIds.Contains(target.Id);

        Check(
            "two-percent-floor-is-per-server",
            "at exactly 2 % of a " + Seconds(target.RunTimeTicks!.Value) + "s title " +
                (emby
                    ? "Emby keeps the position (its floor is 2 %, inclusive)"
                    : "Jellyfin clears it (its floor is 5 %, so 2 % is under it)"),
            emby ? detail2b.ResumeTicks == twoPercent : detail2b.ResumeTicks is null or 0,
            emby ? twoPercent : 0,
            detail2b.ResumeTicks);
        Check(
            "two-percent-stop-does-not-mark-played",
            "a stop at 2 % never marks the title as played, whichever server answered",
            detail2b.Played == false,
            false,
            detail2b.Played);
        Check(
            "two-percent-resume-list-follows-the-same-floor",
            "the continue-watching list agrees with the position: " +
                (emby ? "listed on Emby, which kept the position" : "absent on Jellyfin, which cleared it"),
            twoPercentListed == emby,
            emby,
            twoPercentListed);
        RecordPhase("phase2b-two-percent", sample2b, read2b, target.Id);

        // ---- phase 3: finish the same title (95 % report, then a stop at the exact runtime).
        ReportedSample sample3 = await ReportAsync(target, 95, 100, 1, 1, "phase3-finished", ct);
        await SettleAsync(ct);
        ReadbackState read3 = await ReadAsync(new[] { target.Id }, "phase3", ct);
        DetailState detail3 = read3.Details[target.Id];

        Check(
            "finished-marks-played",
            "finishing the title leaves Played = true on the server",
            detail3.Played == true,
            true,
            detail3.Played);
        Check(
            "finished-clears-the-position",
            "finishing the title clears the stored position",
            detail3.ResumeTicks is null or 0,
            0,
            detail3.ResumeTicks);
        Check(
            "finished-leaves-the-resume-list",
            "a finished title is no longer offered as continue-watching",
            !read3.ResumeIds.Contains(target.Id),
            false,
            read3.ResumeIds.Contains(target.Id));
        RecordPhase("phase3-finished", sample3, read3, target.Id);
    }

    // ------------------------------------------------------------------ product calls

    /// <summary>
    /// Builds one complete client (factory -> handle -> login). Every call returns a new instance on
    /// purpose: sharing one would let in-process state answer a question about server state.
    /// </summary>
    private async Task<ProviderHandle> OpenClientAsync(string purpose, CancellationToken ct)
    {
        ProviderHandle handle = _factory.Create(_server, _policy);
        try
        {
            if (handle.Auth is null)
                throw new InvalidOperationException("the provider factory returned a handle without IProviderAuth");

            AuthResult auth = await handle.Auth.AuthenticateAsync(_credentials, ct);
            _connected = true;
            _clients++;
            Log("[probe] client #" + _clients + " authenticated (" + purpose + ") user=" + auth.UserName +
                " capabilities=" + handle.CapabilitySummary);
            return handle;
        }
        catch
        {
            handle.Dispose();
            throw;
        }
    }

    /// <summary>
    /// Resolves a real playback session, then sends the product's start/progress/stop reports at the
    /// requested fractions of the runtime. Returns what was reported so the read-back can be compared.
    /// </summary>
    private async Task<ReportedSample> ReportAsync(
        MediaItem item,
        int progressNumerator,
        int progressDenominator,
        int finalNumerator,
        int finalDenominator,
        string phase,
        CancellationToken ct)
    {
        using ProviderHandle client = await OpenClientAsync("report:" + phase, ct);

        PlaybackSource source = await client.Playback!.ResolvePlaybackAsync(item, new PlaybackOptions(), ct);
        long runtime = source.RunTimeTicks ?? item.RunTimeTicks ?? 0;
        if (runtime <= 0)
            throw new InvalidOperationException("the server reported no runtime for " + item.Id + ", so a percentage cannot be formed");
        if (string.IsNullOrWhiteSpace(source.SessionId))
            throw new InvalidOperationException("ResolvePlaybackAsync returned no session id for " + item.Id +
                "; Emby rejects progress bodies without PlaySessionId");

        long progressTicks = Scale(runtime, progressNumerator, progressDenominator);
        long finalTicks = Scale(runtime, finalNumerator, finalDenominator);
        IProviderProgress progress = client.Progress!;

        await progress.ReportPlaybackStartedAsync(Sample(item, 0, runtime, source), ct);
        await progress.ReportProgressAsync(Sample(item, progressTicks, runtime, source), ct);
        await progress.ReportFinalProgressAsync(Sample(item, finalTicks, runtime, source), ct);

        Log("[probe] " + phase + ": reported start=0 progress=" + progressTicks + " (" + Seconds(progressTicks) +
            "s) final=" + finalTicks + " (" + Seconds(finalTicks) + "s) runtime=" + runtime + " (" +
            Seconds(runtime) + "s) session=" + Prefix(source.SessionId));

        return new ReportedSample(item.Id, runtime, progressTicks, finalTicks, source.SessionId, source.Mode.ToString());
    }

    /// <summary>
    /// Reads the item back through a freshly built client: both resume readers, the detail call, and
    /// the continue-watching list. The list is read for every item in one pass so membership checks
    /// describe the same server state.
    /// </summary>
    private async Task<ReadbackState> ReadAsync(IReadOnlyList<string> itemIds, string phase, CancellationToken ct)
    {
        using ProviderHandle client = await OpenClientAsync("readback:" + phase, ct);
        IProviderProgress progress = client.Progress!;
        IProviderDetail detail = client.Detail!;

        IReadOnlyList<MediaItem> resume = await progress.GetContinueWatchingAsync(100, ct);
        var details = new Dictionary<string, DetailState>(StringComparer.Ordinal);
        foreach (string itemId in itemIds)
        {
            long? resumeTicks = await progress.GetResumePositionAsync(itemId, ct);
            MediaDetail mediaDetail = await detail.GetItemDetailAsync(itemId, ct);
            details[itemId] = new DetailState(resumeTicks, mediaDetail.Item.UserDataPlaybackPositionTicks, mediaDetail.Item.UserDataPlayed);
            Log("[probe] " + phase + " readback " + itemId + ": resumeTicks=" + Format(resumeTicks) +
                " detailTicks=" + Format(mediaDetail.Item.UserDataPlaybackPositionTicks) +
                " played=" + Format(mediaDetail.Item.UserDataPlayed));
        }

        var resumeList = new JsonArray();
        var ids = new HashSet<string>(StringComparer.Ordinal);
        foreach (MediaItem entry in resume)
        {
            ids.Add(entry.Id);
            resumeList.Add(new JsonObject
            {
                ["id"] = entry.Id,
                ["name"] = entry.Name,
                ["positionTicks"] = Num(entry.UserDataPlaybackPositionTicks),
            });
        }

        Log("[probe] " + phase + " readback: continueWatching=" + resume.Count + " [" +
            string.Join(", ", resume.Select(i => i.Id + "@" + Format(i.UserDataPlaybackPositionTicks))) + "]");

        return new ReadbackState(details, ids, resumeList);
    }

    private PlaybackProgress Sample(MediaItem item, long positionTicks, long runtimeTicks, PlaybackSource source) => new()
    {
        ServerId = _server.ServerId,
        ItemId = item.Id,
        PositionTicks = positionTicks,
        DurationTicks = runtimeTicks,
        IsPaused = false,
        IsMuted = false,
        SessionId = source.SessionId,
        Mode = source.Mode,
    };

    // ------------------------------------------------------------------ plumbing

    /// <summary>
    /// Breadth-first walk over the product's own library listing, collecting non-folder items.
    /// </summary>
    /// <remarks>
    /// Depth is capped because the fixtures nest movie folders one level deep and TV shows three,
    /// and a cap keeps a misbehaving server (a folder that lists itself) from looping forever.
    /// </remarks>
    private async Task<List<MediaItem>> CollectPlayablesAsync(
        IProviderLibrary library,
        IEnumerable<string> libraryIds,
        CancellationToken ct)
    {
        const int MaximumDepth = 3;
        var collected = new List<MediaItem>();
        var visited = new HashSet<string>(StringComparer.Ordinal);
        var queue = new Queue<(string Id, int Depth)>();
        foreach (string id in libraryIds) queue.Enqueue((id, 0));

        while (queue.Count > 0)
        {
            (string parentId, int depth) = queue.Dequeue();
            if (!visited.Add(parentId) || depth > MaximumDepth) continue;

            PagedResult<MediaItem> page = await library.GetItemsAsync(parentId, PageRequest.First(200), ct);
            foreach (MediaItem item in page.Items)
            {
                if (item.IsFolder)
                {
                    queue.Enqueue((item.Id, depth + 1));
                    continue;
                }

                collected.Add(item);
            }
        }

        return collected;
    }

    private async Task SettleAsync(CancellationToken ct)
    {
        if (_options.SettleMilliseconds > 0) await Task.Delay(_options.SettleMilliseconds, ct);
    }

    /// <summary>Integer-exact fraction of the runtime; no floating point on a tick count.</summary>
    private static long Scale(long runtimeTicks, int numerator, int denominator)
    {
        if (denominator <= 0) throw new ArgumentOutOfRangeException(nameof(denominator));
        long whole = runtimeTicks / denominator * numerator;
        long remainder = runtimeTicks % denominator * numerator / denominator;
        return whole + remainder;
    }

    private void RecordPhase(string phase, ReportedSample sample, ReadbackState readback, string focusItemId)
    {
        DetailState detail = readback.Details[focusItemId];
        _phases.Add(new JsonObject
        {
            ["phase"] = phase,
            ["itemId"] = sample.ItemId,
            ["runtimeTicks"] = sample.RuntimeTicks,
            ["reportedProgressTicks"] = sample.ProgressTicks,
            ["reportedFinalTicks"] = sample.FinalTicks,
            ["sessionIdPrefix"] = Prefix(sample.SessionId),
            ["mode"] = sample.Mode,
            ["readbackResumeTicks"] = Num(detail.ResumeTicks),
            ["readbackDetailTicks"] = Num(detail.DetailTicks),
            ["readbackPlayed"] = detail.Played,
            ["inResumeList"] = readback.ResumeIds.Contains(focusItemId),
            ["resumeList"] = readback.ResumeList.DeepClone(),
        });
    }

    private JsonObject ItemJson(MediaItem item, string role) => new()
    {
        ["role"] = role,
        ["id"] = item.Id,
        ["name"] = item.Name,
        ["runtimeTicks"] = Num(item.RunTimeTicks),
        ["runtimeSeconds"] = item.RunTimeTicks is { } runtime ? Math.Round(runtime / (double)TicksPerSecond, 1) : null,
        ["type"] = item.Type.ToString(),
    };

    private void Check(string id, string what, bool pass, object? expected, object? actual, string? note = null)
    {
        var entry = new JsonObject
        {
            ["id"] = id,
            ["what"] = what,
            ["pass"] = pass,
            ["expected"] = Format(expected),
            ["actual"] = Format(actual),
        };
        if (!string.IsNullOrEmpty(note)) entry["note"] = note;
        _assertions.Add(entry);

        string line = "[assert] " + id + " " + (pass ? "PASS" : "FAIL") + " expected=" + Format(expected) +
                      " actual=" + Format(actual);
        if (!string.IsNullOrEmpty(note)) line += " (" + note + ")";
        Log(line);
    }

    private int CountPassed()
    {
        int passed = 0;
        foreach (JsonNode? node in _assertions)
        {
            if (node is JsonObject entry && entry["pass"] is JsonValue value && value.TryGetValue(out bool pass) && pass) passed++;
        }

        return passed;
    }

    private void WriteReport(string? error)
    {
        string? path = null;
        if (!string.IsNullOrEmpty(_options.JsonPath))
        {
            path = Path.GetFullPath(_options.JsonPath!);
            Log("[probe] report path " + path);
        }

        var root = new JsonObject
        {
            ["tool"] = "MediaHub.ResumeProbe",
            ["label"] = _options.Label,
            ["server"] = _options.ServerKey,
            ["baseUrl"] = _options.BaseUrl.GetLeftPart(UriPartial.Authority),
            ["startedUtc"] = _startedUtc.ToString("o", CultureInfo.InvariantCulture),
            ["finishedUtc"] = DateTimeOffset.UtcNow.ToString("o", CultureInfo.InvariantCulture),
            ["durationMs"] = (long)(DateTimeOffset.UtcNow - _startedUtc).TotalMilliseconds,
            ["clientInstances"] = _clients,
            ["error"] = error,
            ["items"] = _items.DeepClone(),
            ["phases"] = _phases.DeepClone(),
            ["assertions"] = _assertions.DeepClone(),
            ["log"] = new JsonArray(_console.Select(l => (JsonNode?)JsonValue.Create(l)).ToArray()),
        };

        if (path is not null)
        {
            string? directory = Path.GetDirectoryName(path);
            if (!string.IsNullOrEmpty(directory)) Directory.CreateDirectory(directory);
            File.WriteAllText(
                path,
                root.ToJsonString(new JsonSerializerOptions { WriteIndented = true }) + Environment.NewLine,
                new UTF8Encoding(false));
            Console.WriteLine("[probe] wrote " + path);
        }
    }

    private void Log(string line)
    {
        _console.Add(line);
        Console.WriteLine(line);
    }

    private static JsonNode? Num(long? value) => value is { } number ? JsonValue.Create(number) : null;

    private static string Format(object? value) => value switch
    {
        null => "null",
        bool flag => flag ? "true" : "false",
        long number => number.ToString(CultureInfo.InvariantCulture),
        int number => number.ToString(CultureInfo.InvariantCulture),
        _ => value.ToString() ?? "",
    };

    private static string Seconds(long ticks) => (ticks / (double)TicksPerSecond).ToString("0.0", CultureInfo.InvariantCulture);

    /// <summary>Session ids are not credentials, but the full value is noise in a report.</summary>
    private static string Prefix(string? value) =>
        string.IsNullOrEmpty(value) ? "(none)" : value!.Length <= 6 ? value! : value!.Substring(0, 6) + "...";
}

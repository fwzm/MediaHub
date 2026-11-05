using System.Net;
using System.Text;
using System.Text.Json;
using MediaHub.Domain;
using MediaHub.Net;
using MediaHub.Providers;
using MediaHub.Providers.MediaBrowser;
using MediaHub.TestSupport;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// The resume rules Emby and Jellyfin apply to a reported playback position, pinned against
/// <see cref="MockMediaServer"/> so a regression is caught without a real server.
/// </summary>
/// <remarks>
/// <para>
/// The numbers come from the W8 headless runs, not from either server's documentation:
/// <c>windows/docs/W8-HEADLESS-RESUME.md</c> §5 records Emby 4.10.2 discarding 1.99 % of a 600 s title
/// (119,400,000 ticks) and keeping exactly 2.00 % (120,000,000 ticks), and
/// <c>windows/docs/W8-EVIDENCE.md</c> §S1.1 quotes the decompiled
/// <c>UserDataManager.UpdatePlayState</c> both servers share together with Jellyfin 12.2's
/// <c>MinResumePct=5</c> / <c>MinResumeDurationSeconds=300</c>.
/// </para>
/// <para>
/// Every case here drives the mock over real sockets, through the same routes the product client uses.
/// The point is not that the mock stores a number: it is that the mock refuses to store one the real
/// server would have thrown away, which is the shape of fidelity this project keeps relearning.
/// </para>
/// </remarks>
public sealed class ResumeFloorMockFidelityTests
{
    private const string Token = "token-abc";
    private const string UserId = "user-1";
    private const string ItemId = "item-000";

    /// <summary>Ten minutes: the runtime of the fixture both servers hold as "Long Resume".</summary>
    private const long TenMinutes = 600 * TimeSpan.TicksPerSecond;

    [Fact]
    public void The_mock_publishes_the_resume_floor_of_the_server_it_emulates()
    {
        Assert.Equal(2, MockResumeFloor.ForServerType(ServerType.Emby).MinimumPercent);
        Assert.Equal(5, MockResumeFloor.ForServerType(ServerType.Jellyfin).MinimumPercent);

        foreach (MockResumeFloor floor in new[] { MockResumeFloor.Emby, MockResumeFloor.Jellyfin })
        {
            Assert.Equal(300, floor.MinimumRuntimeSeconds);
            Assert.Equal(90, floor.MaximumPercent);
            Assert.Equal(300L * TimeSpan.TicksPerSecond, floor.MinimumRuntimeTicks);
        }
    }

    [Theory]
    [InlineData(ServerType.Emby, 2)]
    [InlineData(ServerType.Jellyfin, 5)]
    public async Task A_position_at_the_floor_is_stored_exactly(ServerType type, int minimumPercent)
    {
        await using MockMediaServer mock = await MockMediaServer.StartAsync(type, Options());
        long reported = Percent(TenMinutes, minimumPercent);

        await ReportAsync(mock, "/Sessions/Playing/Progress", ItemId, reported);

        MockUserData state = Assert.IsType<MockUserData>(mock.UserDataOf(ItemId));
        Assert.Equal(reported, state.PlaybackPositionTicks);
        Assert.False(state.Played);
        Assert.Equal((reported, false), await ReadUserDataAsync(mock, ItemId));
        Assert.Equal(new[] { ItemId }, await ResumeIdsAsync(mock));
    }

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task A_position_below_the_floor_clears_the_position_without_marking_it_watched(ServerType type)
    {
        await using MockMediaServer mock = await MockMediaServer.StartAsync(type, Options());

        await ReportAsync(mock, "/Sessions/Playing/Progress", ItemId, Percent(TenMinutes, 1));

        MockUserData state = Assert.IsType<MockUserData>(mock.UserDataOf(ItemId));
        Assert.Equal(0L, state.PlaybackPositionTicks);
        Assert.False(state.Played);
        Assert.Empty(await ResumeIdsAsync(mock));
    }

    [Fact]
    public async Task Emby_discards_one_point_nine_nine_percent_and_keeps_exactly_two()
    {
        await using MockMediaServer mock = await MockMediaServer.StartAsync(ServerType.Emby, Options());

        // The two probes W8 bisected on a 600 s title: 11.94 s and 12.0 s.
        await ReportAsync(mock, "/Sessions/Playing/Stopped", ItemId, 119_400_000L);
        Assert.Equal(0L, Assert.IsType<MockUserData>(mock.UserDataOf(ItemId)).PlaybackPositionTicks);

        await ReportAsync(mock, "/Sessions/Playing/Stopped", ItemId, 120_000_000L);
        MockUserData state = Assert.IsType<MockUserData>(mock.UserDataOf(ItemId));
        Assert.Equal(120_000_000L, state.PlaybackPositionTicks);
        Assert.False(state.Played);
    }

    [Fact]
    public async Task Two_percent_is_kept_by_Emby_and_dropped_by_Jellyfin()
    {
        const long twoPercent = 120_000_000L;

        await using MockMediaServer emby = await MockMediaServer.StartAsync(ServerType.Emby, Options());
        await ReportAsync(emby, "/Sessions/Playing/Stopped", ItemId, twoPercent);
        Assert.Equal(twoPercent, Assert.IsType<MockUserData>(emby.UserDataOf(ItemId)).PlaybackPositionTicks);
        Assert.Equal(new[] { ItemId }, await ResumeIdsAsync(emby));

        await using MockMediaServer jellyfin = await MockMediaServer.StartAsync(ServerType.Jellyfin, Options());
        await ReportAsync(jellyfin, "/Sessions/Playing/Stopped", ItemId, twoPercent);
        Assert.Equal(0L, Assert.IsType<MockUserData>(jellyfin.UserDataOf(ItemId)).PlaybackPositionTicks);
        Assert.Empty(await ResumeIdsAsync(jellyfin));
    }

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task A_below_floor_report_erases_a_position_the_server_already_held(ServerType type)
    {
        await using MockMediaServer mock = await MockMediaServer.StartAsync(type, Options());

        await ReportAsync(mock, "/Sessions/Playing/Progress", ItemId, Percent(TenMinutes, 40));
        Assert.Equal(new[] { ItemId }, await ResumeIdsAsync(mock));

        // One report below the floor replaces the stored 40 % with nothing: the server does not keep
        // the larger of the two, and a client that assumed it did would re-offer the old position.
        await ReportAsync(mock, "/Sessions/Playing/Stopped", ItemId, Percent(TenMinutes, 1));

        MockUserData state = Assert.IsType<MockUserData>(mock.UserDataOf(ItemId));
        Assert.Equal(0L, state.PlaybackPositionTicks);
        Assert.False(state.Played);
        Assert.Empty(await ResumeIdsAsync(mock));
    }

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task Finishing_a_title_clears_the_position_and_marks_it_watched(ServerType type)
    {
        await using MockMediaServer mock = await MockMediaServer.StartAsync(type, Options());

        await ReportAsync(mock, "/Sessions/Playing/Stopped", ItemId, TenMinutes);

        MockUserData state = Assert.IsType<MockUserData>(mock.UserDataOf(ItemId));
        Assert.Equal(0L, state.PlaybackPositionTicks);
        Assert.True(state.Played);
        Assert.Equal((0L, true), await ReadUserDataAsync(mock, ItemId));
        Assert.Empty(await ResumeIdsAsync(mock));
    }

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task A_position_within_ten_seconds_of_the_end_counts_as_finished(ServerType type)
    {
        await using MockMediaServer mock = await MockMediaServer.StartAsync(type, Options());

        await ReportAsync(mock, "/Sessions/Playing/Stopped", ItemId, TenMinutes - 5 * TimeSpan.TicksPerSecond);

        MockUserData state = Assert.IsType<MockUserData>(mock.UserDataOf(ItemId));
        Assert.Equal(0L, state.PlaybackPositionTicks);
        Assert.True(state.Played);
        Assert.Empty(await ResumeIdsAsync(mock));
    }

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task A_stop_without_a_position_is_read_as_watched_to_the_end(ServerType type)
    {
        await using MockMediaServer mock = await MockMediaServer.StartAsync(type, Options());

        // Emby takes a null position as the full runtime. The product client always sends the field,
        // so this branch is what fails if that discipline ever regresses.
        await ReportAsync(mock, "/Sessions/Playing/Stopped", ItemId, positionTicks: null);

        MockUserData state = Assert.IsType<MockUserData>(mock.UserDataOf(ItemId));
        Assert.Equal(0L, state.PlaybackPositionTicks);
        Assert.True(state.Played);
        Assert.Empty(await ResumeIdsAsync(mock));
    }

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task A_title_shorter_than_the_minimum_duration_never_keeps_a_resume_point(ServerType type)
    {
        long fourMinutes = 240 * TimeSpan.TicksPerSecond;
        await using MockMediaServer mock = await MockMediaServer.StartAsync(type, Options(runtimeTicks: fourMinutes));

        await ReportAsync(mock, "/Sessions/Playing/Stopped", ItemId, Percent(fourMinutes, 50));

        MockUserData state = Assert.IsType<MockUserData>(mock.UserDataOf(ItemId));
        Assert.Equal(0L, state.PlaybackPositionTicks);
        Assert.True(state.Played);
        Assert.Empty(await ResumeIdsAsync(mock));
    }

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task The_floor_applies_to_reports_only_not_to_the_state_a_test_seeds(ServerType type)
    {
        long seeded = Percent(TenMinutes, 1);
        await using MockMediaServer mock = await MockMediaServer.StartAsync(
            type,
            Options(seed: new MockUserData(seeded, Played: false)));

        // A seed is "the server already holds this", not a report: the rules must not launder it away,
        // or a test could never build the pre-existing state it wants to observe.
        Assert.Equal((seeded, false), await ReadUserDataAsync(mock, ItemId));
        Assert.Equal(new[] { ItemId }, await ResumeIdsAsync(mock));
    }

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task The_played_items_reset_route_puts_a_title_back_to_never_played(ServerType type)
    {
        await using MockMediaServer mock = await MockMediaServer.StartAsync(
            type,
            Options(seed: new MockUserData(Percent(TenMinutes, 40), Played: false)));
        Assert.Equal(new[] { ItemId }, await ResumeIdsAsync(mock));

        await DeleteAsync(mock, "/Users/" + UserId + "/PlayedItems/" + ItemId, HttpStatusCode.NoContent);

        Assert.Equal((0L, false), await ReadUserDataAsync(mock, ItemId));
        Assert.Empty(await ResumeIdsAsync(mock));

        // An id the catalogue does not hold is a 404, exactly like every other item route.
        await DeleteAsync(mock, "/Users/" + UserId + "/PlayedItems/item-999", HttpStatusCode.NotFound);
    }

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task Starting_playback_does_not_erase_the_resume_point_it_is_about_to_use(ServerType type)
    {
        long stored = Percent(TenMinutes, 40);
        await using MockMediaServer mock = await MockMediaServer.StartAsync(
            type,
            Options(seed: new MockUserData(stored, Played: false)));

        await ReportAsync(mock, "/Sessions/Playing", ItemId, 0L);

        Assert.Equal(stored, Assert.IsType<MockUserData>(mock.UserDataOf(ItemId)).PlaybackPositionTicks);
        Assert.Equal(new[] { ItemId }, await ResumeIdsAsync(mock));
    }

    /// <summary>
    /// One item of the given runtime, with the default "every seventh item is resumable" pattern
    /// replaced by an explicit state so nothing depends on the catalogue's numbering.
    /// </summary>
    private static MockServerOptions Options(long runtimeTicks = TenMinutes, MockUserData? seed = null) => new()
    {
        ItemCount = 2,
        RunTimeTicks = new Dictionary<string, long>(StringComparer.Ordinal) { [ItemId] = runtimeTicks },
        UserData = new Dictionary<string, MockUserData>(StringComparer.Ordinal)
        {
            [ItemId] = seed ?? new MockUserData(0L, Played: false),
        },
    };

    private static long Percent(long runtimeTicks, decimal percent) => (long)(runtimeTicks * percent / 100m);

    /// <summary>Posts a playback report exactly as the provider does, position field included or omitted.</summary>
    private static async Task ReportAsync(MockMediaServer mock, string route, string itemId, long? positionTicks)
    {
        var payload = new Dictionary<string, object?>
        {
            ["ItemId"] = itemId,
            ["PlaySessionId"] = "playsession-" + itemId,
            ["IsPaused"] = false,
            ["IsMuted"] = false,
            ["PlayMethod"] = "DirectPlay",
        };
        if (positionTicks is not null) payload["PositionTicks"] = positionTicks.Value;

        using var client = new HttpClient();
        using var request = new HttpRequestMessage(HttpMethod.Post, mock.Url(route))
        {
            Content = new StringContent(JsonSerializer.Serialize(payload), Encoding.UTF8, "application/json"),
        };
        request.Headers.TryAddWithoutValidation("X-Emby-Token", Token);
        using HttpResponseMessage response = await client.SendAsync(request);
        Assert.Equal(HttpStatusCode.NoContent, response.StatusCode);
    }

    private static async Task DeleteAsync(MockMediaServer mock, string route, HttpStatusCode expected)
    {
        using var client = new HttpClient();
        using var request = new HttpRequestMessage(HttpMethod.Delete, mock.Url(route));
        request.Headers.TryAddWithoutValidation("X-Emby-Token", Token);
        using HttpResponseMessage response = await client.SendAsync(request);
        Assert.Equal(expected, response.StatusCode);
    }

    /// <summary>The playback state one item payload reports, read the way a client reads it.</summary>
    private static async Task<(long Ticks, bool Played)> ReadUserDataAsync(MockMediaServer mock, string itemId)
    {
        using JsonDocument document = JsonDocument.Parse(
            await GetAsync(mock, "/Users/" + UserId + "/Items/" + itemId));
        JsonElement userData = document.RootElement.GetProperty("UserData");
        long ticks = userData.TryGetProperty("PlaybackPositionTicks", out JsonElement position) ? position.GetInt64() : 0L;
        bool played = userData.TryGetProperty("Played", out JsonElement flag) && flag.GetBoolean();
        return (ticks, played);
    }

    /// <summary>Ids in the continue-watching answer, in the order the server returns them.</summary>
    private static async Task<string[]> ResumeIdsAsync(MockMediaServer mock)
    {
        string json = await GetAsync(
            mock,
            "/Users/" + UserId + "/Items?Recursive=true&Filters=IsResumable&SortBy=DatePlayed&SortOrder=Descending&Limit=50");
        using JsonDocument document = JsonDocument.Parse(json);
        return document.RootElement.GetProperty("Items")
            .EnumerateArray()
            .Select(entry => entry.GetProperty("Id").GetString()!)
            .ToArray();
    }

    private static async Task<string> GetAsync(MockMediaServer mock, string route)
    {
        using var client = new HttpClient();
        using var request = new HttpRequestMessage(HttpMethod.Get, mock.Url(route));
        request.Headers.TryAddWithoutValidation("X-Emby-Token", Token);
        using HttpResponseMessage response = await client.SendAsync(request);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        return await response.Content.ReadAsStringAsync();
    }
}

/// <summary>
/// The same rules seen from the product's side: a position reported through
/// <see cref="IProviderProgress"/> must come back through <see cref="IProviderProgress.GetResumePositionAsync"/>
/// and appear in <see cref="IProviderProgress.GetContinueWatchingAsync"/>, and anything the server drops
/// must leave the client with nothing to offer.
/// </summary>
/// <remarks>
/// The client cannot overrule the server here. <c>MediaBrowserProvider.GetContinueWatchingAsync</c>
/// re-filters the answer with the same predicate the server applies, so a mock that stored a
/// below-floor position would still surface as a row — which is exactly the regression these cases stop:
/// the two layers agreeing on a number the server would never have kept.
/// </remarks>
public sealed class ResumeFloorClientViewTests
{
    private const string User = "tester";
    private const string Password = "secret";
    private const string UserId = "user-1";
    private const string ItemId = "item-000";

    /// <summary>Ten minutes: long enough to clear Jellyfin's 300 s minimum-duration rule.</summary>
    private const long TenMinutes = 600 * TimeSpan.TicksPerSecond;

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task The_client_reads_back_exactly_the_position_it_reported(ServerType type)
    {
        await using MockMediaServer mock = await MockMediaServer.StartAsync(type, Options());
        long reported = Percent(TenMinutes, 40);

        await ReportAsync(mock, type, reported, final: true);
        ClientView view = await ReadClientViewAsync(mock, type);

        Assert.Equal(reported, view.ResumeTicks);
        Assert.Equal(reported, view.DetailTicks);
        Assert.False(view.Played);
        MediaItem row = Assert.Single(view.Rows);
        Assert.Equal(ItemId, row.Id);
        Assert.Equal(reported, row.UserDataPlaybackPositionTicks);
    }

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task A_below_floor_stop_leaves_the_client_with_no_resume_row(ServerType type)
    {
        await using MockMediaServer mock = await MockMediaServer.StartAsync(type, Options());

        await ReportAsync(mock, type, Percent(TenMinutes, 1), final: true);
        ClientView view = await ReadClientViewAsync(mock, type);

        Assert.Equal(0L, view.ResumeTicks);
        Assert.Equal(0L, view.DetailTicks);
        Assert.False(view.Played);
        Assert.Empty(view.Rows);
    }

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task A_finished_title_leaves_the_client_resume_list(ServerType type)
    {
        await using MockMediaServer mock = await MockMediaServer.StartAsync(type, Options());

        await ReportAsync(mock, type, TenMinutes, final: true);
        ClientView view = await ReadClientViewAsync(mock, type);

        Assert.Equal(0L, view.ResumeTicks);
        Assert.True(view.Played);
        Assert.Empty(view.Rows);
    }

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task A_server_that_holds_no_position_gives_the_client_no_rows(ServerType type)
    {
        await using MockMediaServer mock = await MockMediaServer.StartAsync(type, Options());

        // Nothing was ever reported: the control case for the three above, so a passing suite cannot be
        // explained by the client simply always answering zero rows.
        ClientView view = await ReadClientViewAsync(mock, type);

        Assert.Equal(0L, view.ResumeTicks);
        Assert.Equal(0L, view.DetailTicks);
        Assert.False(view.Played);
        Assert.Empty(view.Rows);
    }

    private sealed record ClientView(long? ResumeTicks, long? DetailTicks, bool? Played, IReadOnlyList<MediaItem> Rows);

    private static MockServerOptions Options() => new()
    {
        ItemCount = 2,
        RunTimeTicks = new Dictionary<string, long>(StringComparer.Ordinal) { [ItemId] = TenMinutes },
        UserData = new Dictionary<string, MockUserData>(StringComparer.Ordinal)
        {
            [ItemId] = new MockUserData(0L, Played: false),
        },
    };

    private static long Percent(long runtimeTicks, decimal percent) => (long)(runtimeTicks * percent / 100m);

    private static NetworkPolicy DirectPolicy() => new() { Mode = ProxyMode.Direct };

    private static IMediaProviderFactory FactoryFor(ServerType type) =>
        type == ServerType.Emby ? new EmbyProviderFactory() : new JellyfinProviderFactory();

    private static MediaServer ServerFor(MockMediaServer mock, ServerType type) => new(
        "mock-server",
        type,
        "Mock source",
        new[] { new ServerEndpoint(mock.BaseUri.GetLeftPart(UriPartial.Authority)) });

    private static async Task<ProviderHandle> ConnectAsync(MockMediaServer mock, ServerType type)
    {
        ProviderHandle handle = FactoryFor(type).Create(ServerFor(mock, type), DirectPolicy());
        await handle.Auth!.AuthenticateAsync(new Credentials(User, Password));
        return handle;
    }

    /// <summary>
    /// Plays the item the way the player does: resolve a session through the product path, open it with a
    /// zero position, then stop at the requested one.
    /// </summary>
    private static async Task ReportAsync(MockMediaServer mock, ServerType type, long positionTicks, bool final)
    {
        using ProviderHandle handle = await ConnectAsync(mock, type);
        MediaDetail detail = await handle.Detail!.GetItemDetailAsync(ItemId);
        PlaybackSource source = await handle.Playback!.ResolvePlaybackAsync(detail.Item, new PlaybackOptions());
        Assert.False(string.IsNullOrEmpty(source.SessionId));

        var progress = new PlaybackProgress
        {
            ServerId = handle.ServerId,
            ItemId = ItemId,
            PositionTicks = positionTicks,
            DurationTicks = source.RunTimeTicks,
            SessionId = source.SessionId,
            Mode = PlaybackMode.DirectPlay,
        };

        await handle.Progress!.ReportPlaybackStartedAsync(progress with { PositionTicks = 0 });
        if (final)
        {
            await handle.Progress.ReportFinalProgressAsync(progress);
        }
        else
        {
            await handle.Progress.ReportProgressAsync(progress);
        }
    }

    /// <summary>Reads the state through a brand new client, which is what "restart the app" means here.</summary>
    private static async Task<ClientView> ReadClientViewAsync(MockMediaServer mock, ServerType type)
    {
        using ProviderHandle handle = await ConnectAsync(mock, type);
        long? resumeTicks = await handle.Progress!.GetResumePositionAsync(ItemId);
        MediaDetail detail = await handle.Detail!.GetItemDetailAsync(ItemId);
        IReadOnlyList<MediaItem> rows = await handle.Progress.GetContinueWatchingAsync(10);
        return new ClientView(
            resumeTicks,
            detail.Item.UserDataPlaybackPositionTicks,
            detail.Item.UserDataPlayed,
            rows);
    }
}

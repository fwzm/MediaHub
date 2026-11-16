using MediaHub.Domain;
using MediaHub.Net;
using MediaHub.Providers;
using MediaHub.Providers.MediaBrowser;
using MediaHub.TestSupport;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// One pass over a whole series in both directions: "previous episode" and auto-advance must describe
/// the same chain, including both season boundaries.
/// </summary>
/// <remarks>
/// The earlier suites pin individual answers. This one walks the entire chain and back, so an ordering
/// rule that only disagrees at a boundary — which is exactly where a mis-sorted season folds without
/// being noticed — cannot stay green. The mock answers in seed order, and the seed is deliberately
/// jumbled across three seasons.
/// </remarks>
public sealed class EpisodeAdjacencySweepTests
{
    private const string User = "tester";
    private const string Password = "secret";
    private const string SeriesId = "series-1";

    private static readonly string[] ExpectedOrder =
    {
        "ep-s1e1", "ep-s1e2", "ep-s1e3",
        "ep-s2e1", "ep-s2e2",
        "ep-s3e1", "ep-s3e2",
    };

    private static NetworkPolicy DirectPolicy() => new() { Mode = ProxyMode.Direct };

    private static MockEpisode Episode(string id, int season, int number)
        => new(id, SeriesId, "Episode " + id, season, number);

    private static Task<MockMediaServer> StartAsync(ServerType type)
        => MockMediaServer.StartAsync(type, new MockServerOptions
        {
            Episodes = new[]
            {
                Episode("ep-s2e2", 2, 2),
                Episode("ep-s3e1", 3, 1),
                Episode("ep-s1e3", 1, 3),
                Episode("ep-s2e1", 2, 1),
                Episode("ep-s1e1", 1, 1),
                Episode("ep-s3e2", 3, 2),
                Episode("ep-s1e2", 1, 2),
            },
        });

    private static async Task<ProviderHandle> ConnectAsync(MockMediaServer mock, ServerType type)
    {
        IMediaProviderFactory factory = type == ServerType.Emby ? new EmbyProviderFactory() : new JellyfinProviderFactory();
        var server = new MediaServer("mock-server", type, "Mock source",
            new[] { new ServerEndpoint(mock.BaseUri.GetLeftPart(UriPartial.Authority)) });
        ProviderHandle handle = factory.Create(server, DirectPolicy());
        await handle.Auth!.AuthenticateAsync(new Credentials(User, Password));
        return handle;
    }

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task Previous_and_next_describe_the_same_chain_across_seasons(ServerType type)
    {
        await using var mock = await StartAsync(type);
        using ProviderHandle handle = await ConnectAsync(mock, type);

        // Forward from the pilot: the chain is walked with "next" only.
        Assert.Null(await handle.Library!.GetPreviousEpisodeAsync(SeriesId, ExpectedOrder[0]));
        string current = ExpectedOrder[0];
        for (int i = 1; i < ExpectedOrder.Length; i++)
        {
            Episode? next = await handle.Library.GetNextEpisodeAsync(SeriesId, current);
            Assert.True(next is not null, "missing successor of " + current);
            Assert.Equal(ExpectedOrder[i], next!.Id);
            current = next.Id;
        }
        Assert.Null(await handle.Library.GetNextEpisodeAsync(SeriesId, ExpectedOrder[^1]));

        // Backward from the finale: the chain has to read the same in reverse.
        current = ExpectedOrder[^1];
        for (int i = ExpectedOrder.Length - 2; i >= 0; i--)
        {
            Episode? previous = await handle.Library.GetPreviousEpisodeAsync(SeriesId, current);
            Assert.True(previous is not null, "missing predecessor of " + current);
            Assert.Equal(ExpectedOrder[i], previous!.Id);
            current = previous.Id;
        }
    }

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task Every_pair_agrees_between_the_two_buttons(ServerType type)
    {
        await using var mock = await StartAsync(type);
        using ProviderHandle handle = await ConnectAsync(mock, type);

        for (int i = 0; i < ExpectedOrder.Length; i++)
        {
            Episode? next = await handle.Library!.GetNextEpisodeAsync(SeriesId, ExpectedOrder[i]);
            if (next is not null)
            {
                Episode? back = await handle.Library.GetPreviousEpisodeAsync(SeriesId, next.Id);
                Assert.Equal(ExpectedOrder[i], back?.Id);
            }

            Episode? previous = await handle.Library!.GetPreviousEpisodeAsync(SeriesId, ExpectedOrder[i]);
            if (previous is not null)
            {
                Episode? forward = await handle.Library.GetNextEpisodeAsync(SeriesId, previous.Id);
                Assert.Equal(ExpectedOrder[i], forward?.Id);
            }
        }
    }

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task A_season_opener_and_the_previous_finale_are_neighbours_in_both_directions(ServerType type)
    {
        await using var mock = await StartAsync(type);
        using ProviderHandle handle = await ConnectAsync(mock, type);

        Assert.Equal("ep-s2e1", (await handle.Library!.GetNextEpisodeAsync(SeriesId, "ep-s1e3"))?.Id);
        Assert.Equal("ep-s1e3", (await handle.Library.GetPreviousEpisodeAsync(SeriesId, "ep-s2e1"))?.Id);
        Assert.Equal("ep-s3e1", (await handle.Library.GetNextEpisodeAsync(SeriesId, "ep-s2e2"))?.Id);
        Assert.Equal("ep-s2e2", (await handle.Library.GetPreviousEpisodeAsync(SeriesId, "ep-s3e1"))?.Id);
    }
}

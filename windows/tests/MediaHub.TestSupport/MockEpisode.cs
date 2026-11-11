using System.Globalization;

namespace MediaHub.TestSupport;

/// <summary>
/// One episode the mock serves, seeded through <see cref="MockServerOptions.Episodes"/>.
/// </summary>
/// <remarks>
/// The season and episode numbers are nullable on purpose. Emby and Jellyfin leave
/// <c>ParentIndexNumber</c> or <c>IndexNumber</c> out for an episode they could not match, and the
/// client has to keep such an episode from jumping the queue or being reported as E01.
/// </remarks>
/// <param name="Id">Item id, e.g. <c>ep-s1e2</c>.</param>
/// <param name="SeriesId">Series the episode belongs to; the episode route filters on it.</param>
/// <param name="Name">Display name.</param>
/// <param name="SeasonNumber"><c>ParentIndexNumber</c>; null means the server did not say.</param>
/// <param name="EpisodeNumber"><c>IndexNumber</c>; null means the server did not say.</param>
public sealed record MockEpisode(
    string Id,
    string SeriesId,
    string Name,
    int? SeasonNumber,
    int? EpisodeNumber)
{
    /// <summary>Season container id, derived so a caller does not have to invent one.</summary>
    public string SeasonId => SeasonNumber is { } season
        ? SeriesId + "-s" + season.ToString(CultureInfo.InvariantCulture)
        : SeriesId + "-season-unknown";

    /// <summary>A deterministic run time: twenty minutes, whatever the episode.</summary>
    public long RunTimeTicks => TimeSpan.FromMinutes(20).Ticks;
}

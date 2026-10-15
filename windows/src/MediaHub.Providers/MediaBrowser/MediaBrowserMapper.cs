using System.Globalization;
using System.Text.Json;
using MediaHub.Domain;

namespace MediaHub.Providers.MediaBrowser;

/// <summary>
/// Maps MediaBrowser JSON onto the unified domain model.
/// </summary>
/// <remarks>
/// Mapping is deliberately total on the domain side and tolerant on the wire side: an absent or
/// unparsable field becomes <c>null</c> (rendered as "unknown"), never a fabricated default. A
/// MediaHub client that invents a bitrate or a codec is worse than one that admits it does not know.
/// </remarks>
internal static class MediaBrowserMapper
{
    /// <summary>Fields requested for browsing lists (official Field enum values).</summary>
    public const string ItemFields =
        "PrimaryImageAspectRatio,BasicSyncInfo,CanDelete,Container,DateCreated,MediaSources,MediaStreams," +
        "Overview,ProductionYear,RunTimeTicks,CommunityRating,OfficialRating,Genres,ProviderIds,Path,SeriesStudio";

    /// <summary>Fields requested for search and identity lookups, where artwork and years drive the card.</summary>
    public const string SearchFields =
        "PrimaryImageAspectRatio,CanDelete,Container,MediaSources,MediaStreams,Overview," +
        "ProductionYear,RunTimeTicks,CommunityRating,ProviderIds,Path,SeriesStudio";

    public static MediaType MapType(JsonElement item)
    {
        string? type = GetString(item, "Type");
        return type switch
        {
            "Movie" => MediaType.Movie,
            "Series" => MediaType.Series,
            "Season" => MediaType.Season,
            "Episode" => MediaType.Episode,
            "Video" => MediaType.Video,
            "Audio" or "MusicAlbum" or "MusicArtist" => MediaType.Audio,
            "Folder" or "CollectionFolder" or "BoxSet" or "UserView" => MediaType.Folder,
            "TvChannel" or "LiveTvChannel" => MediaType.LiveTv,
            _ => MediaType.Other,
        };
    }

    public static LibraryType MapLibraryType(string? collectionType) => collectionType switch
    {
        "movies" => LibraryType.Movies,
        "tvshows" => LibraryType.TvShows,
        "music" => LibraryType.Music,
        "photos" => LibraryType.Photos,
        "mixed" => LibraryType.Mixed,
        "boxsets" => LibraryType.Movies,
        _ => LibraryType.Unknown,
    };

    public static MediaItem MapItem(string serverId, JsonElement item)
    {
        string id = GetString(item, "Id") ?? "";
        return new MediaItem
        {
            ServerId = serverId,
            Id = id,
            Name = GetString(item, "Name") ?? "(未命名)",
            Type = MapType(item),
            Path = GetString(item, "Path"),
            Overview = GetString(item, "Overview"),
            Year = GetInt(item, "ProductionYear"),
            RunTimeTicks = GetLong(item, "RunTimeTicks"),
            FileSizeBytes = GetLong(item, "Size"),
            PrimaryImageTag = GetImageTag(item, "Primary"),
            BackdropImageTag = GetImageTag(item, "Backdrop"),
            CommunityRating = GetDouble(item, "CommunityRating"),
            SeriesId = GetString(item, "SeriesId"),
            SeriesName = GetString(item, "SeriesName"),
            SeasonNumber = GetInt(item, "ParentIndexNumber"),
            EpisodeNumber = GetInt(item, "IndexNumber"),
            Genres = GetStringArray(item, "Genres"),
            UserDataPlaybackPositionTicks = GetLong(item, "UserData", "PlaybackPositionTicks"),
            UserDataPlayed = GetBool(item, "UserData", "Played"),
            IsFolder = GetBool(item, "IsFolder") ?? false,
        };
    }

    public static MediaLibrary MapLibrary(JsonElement view) => new(
        GetString(view, "Id") ?? "",
        GetString(view, "Name") ?? "(未命名媒体库)",
        MapLibraryType(GetString(view, "CollectionType")));

    public static Season MapSeason(JsonElement item) => new(
        GetString(item, "Id") ?? "",
        GetString(item, "SeriesId") ?? "",
        GetString(item, "Name") ?? "",
        GetInt(item, "IndexNumber") ?? 0);

    public static Episode MapEpisode(JsonElement item) => new(
        GetString(item, "Id") ?? "",
        GetString(item, "SeriesId") ?? "",
        GetString(item, "SeasonId") ?? "",
        GetString(item, "Name") ?? "",
        GetInt(item, "ParentIndexNumber") ?? 0,
        GetInt(item, "IndexNumber") ?? 0);

    public static IReadOnlyList<MediaStreamInfo> MapStreams(JsonElement item)
    {
        if (!item.TryGetProperty("MediaStreams", out var streams) || streams.ValueKind != JsonValueKind.Array)
            return Array.Empty<MediaStreamInfo>();

        var result = new List<MediaStreamInfo>();
        foreach (var s in streams.EnumerateArray())
        {
            string? type = GetString(s, "Type");
            string kind = type switch
            {
                "Video" => "video",
                "Audio" => "audio",
                "Subtitle" => "subtitle",
                _ => "other",
            };
            result.Add(new MediaStreamInfo
            {
                Type = kind,
                Index = GetInt(s, "Index") ?? -1,
                Codec = GetString(s, "Codec"),
                Profile = GetString(s, "Profile"),
                Width = GetInt(s, "Width"),
                Height = GetInt(s, "Height"),
                BitRate = GetLong(s, "BitRate"),
                Channels = GetInt(s, "Channels"),
                SampleRate = GetInt(s, "SampleRate"),
                Language = GetString(s, "Language"),
                DisplayTitle = GetString(s, "DisplayTitle"),
                ColorPrimaries = GetString(s, "ColorPrimaries"),
                ColorTransfer = GetString(s, "ColorTransfer"),
                PixelFormat = GetString(s, "PixelFormat"),
                IsDefault = GetBool(s, "IsDefault") ?? false,
                IsExternal = GetBool(s, "IsExternal") ?? false,
            });
        }
        return result;
    }

    /// <summary>Fills in frame rate, which arrives as a text field rather than a number.</summary>
    public static double? MapFrameRate(JsonElement item)
        => double.TryParse(GetString(item, "MediaStreams", "0", "RealFrameRate") ?? GetString(item, "FrameRate"),
            NumberStyles.Float, CultureInfo.InvariantCulture, out double v) ? v : null;

    public static int? TotalRecordCount(JsonDocument doc)
        => doc.RootElement.TryGetProperty("TotalRecordCount", out var t) && t.TryGetInt32(out int count) ? count : null;

    public static IEnumerable<JsonElement> Items(JsonDocument doc)
    {
        if (doc.RootElement.TryGetProperty("Items", out var items) && items.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in items.EnumerateArray()) yield return item;
        }
    }

    public static string? GetImageTag(JsonElement item, string imageType)
    {
        if (!item.TryGetProperty("ImageTags", out var tags) || tags.ValueKind != JsonValueKind.Object) return null;
        return tags.TryGetProperty(imageType, out var tag) && tag.ValueKind == JsonValueKind.String ? tag.GetString() : null;
    }

    // ---------------------------------------------------------------- primitive readers

    public static string? GetString(JsonElement element, params string[] path)
    {
        JsonElement? found = Navigate(element, path);
        return found is { ValueKind: JsonValueKind.String } e ? e.GetString() : null;
    }

    public static int? GetInt(JsonElement element, params string[] path)
    {
        JsonElement? found = Navigate(element, path);
        if (found is not { } e) return null;
        if (e.ValueKind == JsonValueKind.Number && e.TryGetInt32(out int i)) return i;
        if (e.ValueKind == JsonValueKind.String && int.TryParse(e.GetString(), NumberStyles.Integer, CultureInfo.InvariantCulture, out int parsed)) return parsed;
        return null;
    }

    public static long? GetLong(JsonElement element, params string[] path)
    {
        JsonElement? found = Navigate(element, path);
        if (found is not { } e) return null;
        if (e.ValueKind == JsonValueKind.Number && e.TryGetInt64(out long l)) return l;
        if (e.ValueKind == JsonValueKind.String && long.TryParse(e.GetString(), NumberStyles.Integer, CultureInfo.InvariantCulture, out long parsed)) return parsed;
        return null;
    }

    public static double? GetDouble(JsonElement element, params string[] path)
    {
        JsonElement? found = Navigate(element, path);
        if (found is not { } e) return null;
        if (e.ValueKind == JsonValueKind.Number && e.TryGetDouble(out double d)) return d;
        return null;
    }

    public static bool? GetBool(JsonElement element, params string[] path)
    {
        JsonElement? found = Navigate(element, path);
        if (found is not { } e) return null;
        return e.ValueKind switch
        {
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            _ => null,
        };
    }

    public static IReadOnlyList<string> GetStringArray(JsonElement element, params string[] path)
    {
        JsonElement? found = Navigate(element, path);
        if (found is not { ValueKind: JsonValueKind.Array } array) return Array.Empty<string>();
        return array.EnumerateArray()
            .Where(v => v.ValueKind == JsonValueKind.String)
            .Select(v => v.GetString()!)
            .ToList();
    }

    private static JsonElement? Navigate(JsonElement element, string[] path)
    {
        JsonElement current = element;
        foreach (string segment in path)
        {
            if (current.ValueKind != JsonValueKind.Object) return null;
            if (!current.TryGetProperty(segment, out var next))
            {
                // Property names on the wire are camel-cased; try the C#-style spelling too so a
                // server-side casing change cannot silently null out a field.
                string alt = char.ToUpperInvariant(segment[0]) + segment[1..];
                if (!current.TryGetProperty(alt, out next)) return null;
            }
            current = next;
        }
        return current;
    }
}

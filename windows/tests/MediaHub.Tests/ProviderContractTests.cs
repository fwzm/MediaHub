using System.Text.Json;
using MediaHub.Domain;
using MediaHub.Providers;
using MediaHub.Providers.Local;
using MediaHub.Providers.MediaBrowser;
using Xunit;

namespace MediaHub.Tests;

public sealed class ProviderCapabilityTests
{
    [Fact]
    public void Runtime_capabilities_are_derived_from_implemented_interfaces()
    {
        var handle = new ProviderHandle
        {
            ServerId = "s",
            Server = new MediaServer("s", ServerType.Local, "n", Array.Empty<ServerEndpoint>()),
            Descriptor = new ProviderDescriptor(ServerType.Local, "n", "d", ProviderCapability.None),
            Auth = new NoopAuth(),
            Library = new NoopLibrary(),
        };

        Assert.True(handle.RuntimeCapabilities.HasFlag(ProviderCapability.Auth));
        Assert.True(handle.RuntimeCapabilities.HasFlag(ProviderCapability.Library));
        Assert.False(handle.RuntimeCapabilities.HasFlag(ProviderCapability.Playback));
        Assert.False(handle.RuntimeCapabilities.HasFlag(ProviderCapability.Progress));
    }

    [Fact]
    public void A_handle_with_no_capabilities_reports_none()
    {
        var handle = new ProviderHandle
        {
            ServerId = "s",
            Server = new MediaServer("s", ServerType.Local, "n", Array.Empty<ServerEndpoint>()),
            Descriptor = new ProviderDescriptor(ServerType.Local, "n", "d", ProviderCapability.Playback),
        };
        Assert.Equal(ProviderCapability.None, handle.RuntimeCapabilities);
        Assert.Equal("尚无可用能力", handle.CapabilitySummary);
    }

    [Fact]
    public void Declared_capabilities_are_not_runtime_capabilities()
    {
        // The Android ADR-022 distinction: a provider may declare a plan for a capability it does
        // not implement yet, and the UI must only trust the runtime set.
        var descriptor = new ProviderDescriptor(ServerType.Emby, "Emby", "d",
            ProviderCapability.Auth | ProviderCapability.Playback | ProviderCapability.Transcode);

        var handle = new ProviderHandle
        {
            ServerId = "s",
            Server = new MediaServer("s", ServerType.Emby, "n", Array.Empty<ServerEndpoint>()),
            Descriptor = descriptor,
            Playback = new NoopPlayback(),
        };

        Assert.False((handle.RuntimeCapabilities & ProviderCapability.Auth) != 0);
        Assert.False((handle.RuntimeCapabilities & ProviderCapability.Transcode) != 0);
        Assert.True((handle.RuntimeCapabilities & ProviderCapability.Playback) != 0);
    }

    [Fact]
    public void Registry_exposes_this_builds_providers()
    {
        var registry = new ProviderRegistry();
        registry.Register(new LocalMediaProviderFactory());
        registry.Register(new EmbyProviderFactory());
        registry.Register(new JellyfinProviderFactory());

        var types = registry.Descriptors.Select(d => d.Type).ToList();
        Assert.Contains(ServerType.Local, types);
        Assert.Contains(ServerType.Emby, types);
        Assert.Contains(ServerType.Jellyfin, types);
    }

    [Fact]
    public void Registry_does_not_claim_providers_this_build_does_not_contain()
    {
        var registry = new ProviderRegistry();
        registry.Register(new LocalMediaProviderFactory());
        Assert.Null(registry.Find(ServerType.Plex));
        Assert.Null(registry.Find(ServerType.WebDav));
    }

    [Fact]
    public void Media_server_factories_expose_the_expected_runtime_capabilities()
    {
        foreach (var factory in new IMediaProviderFactory[] { new EmbyProviderFactory(), new JellyfinProviderFactory() })
        {
            var server = new MediaServer("id", factory.Descriptor.Type, "name",
                new[] { new ServerEndpoint("http://127.0.0.1:8096") });
            using var handle = factory.Create(server, Net.NetworkPolicy.FollowSystem);

            Assert.True(handle.RuntimeCapabilities.HasFlag(ProviderCapability.Auth));
            Assert.True(handle.RuntimeCapabilities.HasFlag(ProviderCapability.Library));
            Assert.True(handle.RuntimeCapabilities.HasFlag(ProviderCapability.Playback));
            Assert.True(handle.RuntimeCapabilities.HasFlag(ProviderCapability.Progress));
            Assert.True(handle.RuntimeCapabilities.HasFlag(ProviderCapability.Search));
        }
    }

    private sealed class NoopAuth : IProviderAuth
    {
        public SessionMaterial CurrentSession => new(null, null, null);

        public Task<AuthResult> AuthenticateAsync(Credentials c, CancellationToken ct = default) => Task.FromResult(new AuthResult("u", "n"));
        public Task<AuthSessionState> RestoreSessionAsync(CancellationToken ct = default) => Task.FromResult(AuthSessionState.Authenticated);
        public Task LogoutAsync(CancellationToken ct = default) => Task.CompletedTask;
    }

    private sealed class NoopLibrary : IProviderLibrary
    {
        public Task<IReadOnlyList<MediaLibrary>> GetLibrariesAsync(CancellationToken ct = default) => Task.FromResult<IReadOnlyList<MediaLibrary>>(Array.Empty<MediaLibrary>());
        public Task<PagedResult<MediaItem>> GetItemsAsync(string id, PageRequest page, CancellationToken ct = default) => Task.FromResult(PagedResult<MediaItem>.Empty);
        public Task<IReadOnlyList<Season>> GetSeasonsAsync(string id, CancellationToken ct = default) => Task.FromResult<IReadOnlyList<Season>>(Array.Empty<Season>());
        public Task<IReadOnlyList<Episode>> GetEpisodesAsync(string id, CancellationToken ct = default) => Task.FromResult<IReadOnlyList<Episode>>(Array.Empty<Episode>());
    }

    private sealed class NoopPlayback : IProviderPlayback
    {
        public IReadOnlyDictionary<string, string> StreamRequestHeaders => new Dictionary<string, string>();
        public Uri? StreamHeaderOrigin => null;

        public Task<PlaybackSource> ResolvePlaybackAsync(MediaItem item, PlaybackOptions options, CancellationToken ct = default)
            => Task.FromResult(new PlaybackSource { Url = new Uri("file:///x") });
    }
}

public sealed class MediaBrowserDialectTests
{
    [Fact]
    public void Emby_mounts_the_api_under_slash_emby()
    {
        var dialect = MediaBrowserDialect.Emby(new Uri("http://192.168.1.10:8096"));
        Assert.Equal("http://192.168.1.10:8096/emby/System/Info/Public", dialect.BuildUrl("/System/Info/Public", null));
    }

    [Fact]
    public void Emby_does_not_double_an_already_present_emby_suffix()
    {
        var dialect = MediaBrowserDialect.Emby(new Uri("http://192.168.1.10:8096/emby"));
        Assert.Equal("http://192.168.1.10:8096/emby/System/Info/Public", dialect.BuildUrl("/System/Info/Public", null));
    }

    [Fact]
    public void Emby_honours_a_reverse_proxy_sub_path()
    {
        var dialect = MediaBrowserDialect.Emby(new Uri("https://media.example.com/emby"), "/emby");
        Assert.Equal("https://media.example.com/emby/Users/u1/Views", dialect.BuildUrl("/Users/u1/Views", null));
    }

    [Fact]
    public void Jellyfin_serves_from_the_root()
    {
        var dialect = MediaBrowserDialect.Jellyfin(new Uri("http://192.168.1.10:8096"));
        Assert.Equal("http://192.168.1.10:8096/System/Info/Public", dialect.BuildUrl("/System/Info/Public", null));
    }

    [Fact]
    public void Emby_identity_header_follows_the_official_schema()
    {
        var dialect = MediaBrowserDialect.Emby(new Uri("http://h:8096"));
        string value = dialect.BuildAuthorizationValue("user-1", null);
        Assert.StartsWith("MediaBrowser ", value);
        Assert.Contains("Client=\"MediaHub\"", value);
        Assert.Contains("DeviceId=\"mediahub-windows\"", value);
        Assert.Contains("UserId=\"user-1\"", value);
    }

    [Fact]
    public void Jellyfin_carries_the_token_in_the_authorization_header()
    {
        var dialect = MediaBrowserDialect.Jellyfin(new Uri("http://h:8096"));
        Assert.True(dialect.TokenInAuthHeader);
        Assert.Contains("Token=\"tok\"", dialect.BuildAuthorizationValue(null, "tok"));
    }

    [Fact]
    public void Emby_keeps_the_token_out_of_the_authorization_header()
    {
        var dialect = MediaBrowserDialect.Emby(new Uri("http://h:8096"));
        Assert.False(dialect.TokenInAuthHeader);
        Assert.DoesNotContain("Token=", dialect.BuildAuthorizationValue("user-1", null));
    }

    [Fact]
    public void Query_values_are_url_encoded()
    {
        var dialect = MediaBrowserDialect.Emby(new Uri("http://h:8096"));
        string url = dialect.BuildUrl("/Users/u/Items", new Dictionary<string, string?> { ["SearchTerm"] = "a b&c" });
        Assert.Contains("SearchTerm=a%20b%26c", url);
    }
}

public sealed class MediaBrowserMapperTests
{
    private const string SampleItemJson = """
    {
      "Id": "item-1",
      "Name": "Blade Runner 2049",
      "Type": "Movie",
      "Path": "/media/movies/Blade Runner 2049 (2017).mkv",
      "Overview": "A young blade runner...",
      "ProductionYear": 2017,
      "RunTimeTicks": 98000000000,
      "CommunityRating": 8.1,
      "ImageTags": { "Primary": "tag-abc" },
      "Genres": ["Sci-Fi", "Drama"],
      "UserData": { "PlaybackPositionTicks": 1234567, "Played": false },
      "MediaStreams": [
        { "Type": "Video", "Index": 0, "Codec": "hevc", "Width": 3840, "Height": 2160, "ColorTransfer": "smpte2084", "IsDefault": true },
        { "Type": "Audio", "Index": 1, "Codec": "truehd", "Channels": 8, "SampleRate": 48000, "Language": "eng" },
        { "Type": "Subtitle", "Index": 2, "Codec": "subrip", "Language": "chi", "IsExternal": true }
      ]
    }
    """;

    [Fact]
    public void Item_fields_map_onto_the_domain_model()
    {
        using var doc = JsonDocument.Parse(SampleItemJson);
        var item = MediaBrowserMapper.MapItem("srv", doc.RootElement);

        Assert.Equal("item-1", item.Id);
        Assert.Equal(MediaType.Movie, item.Type);
        Assert.Equal(2017, item.Year);
        Assert.Equal("tag-abc", item.PrimaryImageTag);
        Assert.Equal(1234567, item.UserDataPlaybackPositionTicks);
        Assert.False(item.UserDataPlayed);
        Assert.Equal(new[] { "Sci-Fi", "Drama" }, item.Genres);
    }

    [Fact]
    public void Streams_are_classified_into_video_audio_and_subtitle()
    {
        using var doc = JsonDocument.Parse(SampleItemJson);
        var streams = MediaBrowserMapper.MapStreams(doc.RootElement);

        Assert.Equal(3, streams.Count);
        var video = streams.Single(s => s.Type == "video");
        Assert.Equal("hevc", video.Codec);
        Assert.Equal(3840, video.Width);
        Assert.Equal("smpte2084", video.ColorTransfer);

        var audio = streams.Single(s => s.Type == "audio");
        Assert.Equal(8, audio.Channels);
        Assert.Equal(48000, audio.SampleRate);

        Assert.True(streams.Single(s => s.Type == "subtitle").IsExternal);
    }

    [Fact]
    public void Every_media_source_becomes_a_labelled_version()
    {
        // Shape taken from a live Jellyfin 12.2 item: one id per file, per-source MediaStreams, and
        // the supports flags the server computed for this client.
        const string json = """
        {
          "Id": "item-9",
          "Name": "Version Movie",
          "MediaSources": [
            {
              "Id": "src-1080",
              "Name": "1080p",
              "Container": "mp4",
              "SupportsDirectPlay": true,
              "SupportsDirectStream": true,
              "MediaStreams": [ { "Type": "Video", "Codec": "h264", "Width": 1920, "Height": 1080 } ]
            },
            {
              "Id": "src-720",
              "Name": "720p",
              "Container": "mp4",
              "SupportsDirectPlay": false,
              "SupportsDirectStream": false,
              "MediaStreams": [ { "Type": "Video", "Codec": "hevc", "Width": 1280, "Height": 720 } ]
            }
          ]
        }
        """;

        using var doc = JsonDocument.Parse(json);
        var versions = MediaBrowserMapper.MapVersions(doc.RootElement);

        Assert.Equal(2, versions.Count);
        Assert.Equal("src-1080", versions[0].MediaSourceId);
        Assert.Equal("1920×1080 · h264 · mp4 · 直连", versions[0].Label);
        Assert.True(versions[0].IsDefault);
        Assert.False(versions[0].TranscodeOnly);
        Assert.Equal("1280×720 · hevc · mp4 · 仅转码", versions[1].Label);
        Assert.True(versions[1].TranscodeOnly);
    }

    [Fact]
    public void An_item_without_media_sources_has_no_versions()
    {
        using var doc = JsonDocument.Parse("""{ "Id": "x", "Name": "y", "Type": "Movie" }""");
        Assert.Empty(MediaBrowserMapper.MapVersions(doc.RootElement));
    }

    [Fact]
    public void Technical_badges_come_from_the_first_media_source()
    {
        // A multi-version title: the poster badge must describe the version the client will play
        // (the first one the server lists), not the second file's resolution.
        const string json = """
        {
          "Id": "item-2",
          "Name": "Multi Version",
          "Type": "Movie",
          "MediaSources": [
            {
              "Id": "src-4k",
              "Container": "mkv",
              "BitRate": 60000000,
              "MediaStreams": [
                { "Type": "Video", "Codec": "hevc", "Width": 3840, "Height": 2160, "VideoRange": "HDR", "BitRate": 58000000 },
                { "Type": "Audio", "Codec": "truehd", "Channels": 8 }
              ]
            },
            {
              "Id": "src-1080",
              "Container": "mp4",
              "BitRate": 8000000,
              "MediaStreams": [
                { "Type": "Video", "Codec": "h264", "Width": 1920, "Height": 1080 },
                { "Type": "Audio", "Codec": "aac", "Channels": 2 }
              ]
            }
          ]
        }
        """;

        using var doc = JsonDocument.Parse(json);
        var item = MediaBrowserMapper.MapItem("srv", doc.RootElement);

        Assert.Equal(3840, item.Width);
        Assert.Equal(2160, item.Height);
        Assert.Equal("hevc", item.VideoCodec);
        Assert.Equal("truehd", item.AudioCodec);
        Assert.Equal(8, item.AudioChannels);
        Assert.Equal("HDR", item.VideoRange);
        Assert.Equal("mkv", item.Container);
        Assert.Equal(60000000, item.BitRate);
        Assert.Equal(2, MediaBrowserMapper.MapVersions(doc.RootElement).Count);
    }

    [Fact]
    public void Item_level_streams_fill_badges_when_the_answer_has_no_media_sources()
    {
        // The list query's own shape: Emby answers item-level MediaStreams/Container for entries that
        // have a single file, and no MediaSources array at all.
        using var doc = JsonDocument.Parse(SampleItemJson);
        var item = MediaBrowserMapper.MapItem("srv", doc.RootElement);

        Assert.Equal(3840, item.Width);
        Assert.Equal(2160, item.Height);
        Assert.Equal("hevc", item.VideoCodec);
        Assert.Equal("truehd", item.AudioCodec);
        Assert.Equal(8, item.AudioChannels);
    }

    [Fact]
    public void Source_quality_prefers_the_source_fields_and_falls_back_to_its_video_stream()
    {
        using var explicitQuality = JsonDocument.Parse("""{ "Height": 2160, "BitRate": 40000000 }""");
        Assert.Equal((2160, 40000000L), MediaBrowserMapper.ReadSourceQuality(explicitQuality.RootElement));

        using var streamOnly = JsonDocument.Parse("""
        { "MediaStreams": [ { "Type": "Video", "Height": 1080, "BitRate": 9000000 } ] }
        """);
        Assert.Equal((1080, 9000000L), MediaBrowserMapper.ReadSourceQuality(streamOnly.RootElement));

        using var unknown = JsonDocument.Parse("""{ "Id": "src" }""");
        Assert.Equal((null, null), MediaBrowserMapper.ReadSourceQuality(unknown.RootElement));
    }

    [Fact]
    public void Missing_fields_stay_null_rather_than_being_invented()
    {
        using var doc = JsonDocument.Parse("""{ "Id": "x", "Name": "y", "Type": "Movie" }""");
        var item = MediaBrowserMapper.MapItem("srv", doc.RootElement);

        Assert.Null(item.RunTimeTicks);
        Assert.Null(item.Year);
        Assert.Null(item.CommunityRating);
        Assert.Null(item.FileSizeBytes);
        Assert.Null(item.UserDataPlaybackPositionTicks);
        Assert.Null(item.Width);
        Assert.Null(item.Height);
        Assert.Null(item.VideoCodec);
        Assert.Null(item.AudioCodec);
        Assert.Empty(MediaBrowserMapper.MapStreams(doc.RootElement));
    }

    [Fact]
    public void Playback_position_zero_is_not_confused_with_absent()
    {
        using var doc = JsonDocument.Parse("""{ "Id": "x", "Name": "y", "UserData": { "PlaybackPositionTicks": 0 } }""");
        Assert.Equal(0, MediaBrowserMapper.GetLong(doc.RootElement, "UserData", "PlaybackPositionTicks"));
    }

    [Theory]
    [InlineData("Movie", MediaType.Movie)]
    [InlineData("Series", MediaType.Series)]
    [InlineData("Season", MediaType.Season)]
    [InlineData("Episode", MediaType.Episode)]
    [InlineData("Folder", MediaType.Folder)]
    [InlineData("CollectionFolder", MediaType.Folder)]
    [InlineData("Video", MediaType.Video)]
    [InlineData("Audio", MediaType.Audio)]
    [InlineData("SomethingNew", MediaType.Other)]
    public void Server_type_names_map_to_unified_types(string wire, MediaType expected)
    {
        using var doc = JsonDocument.Parse("{ \"Type\": \"" + wire + "\" }");
        Assert.Equal(expected, MediaBrowserMapper.MapType(doc.RootElement));
    }

    [Fact]
    public void Total_record_count_is_read_from_the_envelope()
    {
        using var doc = JsonDocument.Parse("""{ "Items": [], "TotalRecordCount": 421 }""");
        Assert.Equal(421, MediaBrowserMapper.TotalRecordCount(doc));
    }

    [Theory]
    [InlineData("/media/x.iso", true)]
    [InlineData("/media/x.ISO", true)]
    [InlineData("/media/x.iso?api_key=zzz", true)]
    [InlineData("/media/x.mkv", false)]
    [InlineData("/media/x.mp4", false)]
    public void Bluray_iso_items_are_skipped(string path, bool expected)
    {
        var item = new MediaItem { ServerId = "s", Id = "1", Name = "n", Path = path };
        Assert.Equal(expected, MediaBrowserProvider.IsBluRayIso(item));
    }

    [Fact]
    public void Iso_detection_falls_back_to_the_item_id()
    {
        var item = new MediaItem { ServerId = "s", Id = "/media/movie.iso", Name = "n" };
        Assert.True(MediaBrowserProvider.IsBluRayIso(item));
    }
}

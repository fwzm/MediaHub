using MediaHub.Domain;
using MediaHub.Player;
using MediaHub.Player.Mpv;
using Xunit;

namespace MediaHub.Tests;

public sealed class MpvHeaderFormattingTests
{
    [Fact]
    public void No_headers_produces_an_empty_field_list()
    {
        Assert.Equal("", MpvHeaders.Format(null));
        Assert.Equal("", MpvHeaders.Format(new Dictionary<string, string>()));
    }

    [Fact]
    public void A_single_header_is_formatted_as_name_colon_value()
    {
        var headers = new Dictionary<string, string> { ["X-Emby-Token"] = "abc" };
        Assert.Equal("X-Emby-Token: abc", MpvHeaders.Format(headers));
    }

    [Fact]
    public void Multiple_headers_are_comma_separated()
    {
        var headers = new Dictionary<string, string> { ["A"] = "1", ["B"] = "2" };
        string formatted = MpvHeaders.Format(headers);
        Assert.Contains("A: 1", formatted);
        Assert.Contains("B: 2", formatted);
        Assert.Equal(1, formatted.Count(c => c == ','));
    }

    [Fact]
    public void Literal_commas_in_a_value_are_escaped_so_headers_do_not_merge()
    {
        // The engine splits on unescaped commas; an unescaped comma silently turns one header into
        // two, changing what the server receives without any visible error.
        var headers = new Dictionary<string, string> { ["Accept"] = "video/mp4,audio/aac" };
        Assert.Equal(@"Accept: video/mp4\,audio/aac", MpvHeaders.Format(headers));
    }

    [Fact]
    public void Backslashes_are_escaped_before_commas()
    {
        var headers = new Dictionary<string, string> { ["X-Path"] = @"a\b,c" };
        string formatted = MpvHeaders.Format(headers);
        Assert.Equal(@"X-Path: a\\b\,c", formatted);
    }

    [Fact]
    public void Blank_keys_are_dropped()
    {
        var headers = new Dictionary<string, string> { [" "] = "x", ["Ok"] = "y" };
        Assert.Equal("Ok: y", MpvHeaders.Format(headers));
    }
}

public sealed class PlaybackContractTests
{
    [Fact]
    public void Empty_snapshot_reports_idle_and_zero_progress()
    {
        var snapshot = PlayerSnapshot.Empty;
        Assert.Equal(TransportState.Idle, snapshot.State);
        Assert.Equal(0, snapshot.ProgressFraction);
        Assert.True(snapshot.Seekable);
        Assert.Empty(snapshot.AudioTracks);
        Assert.Null(snapshot.Technical.VideoCodec);
    }

    [Fact]
    public void Hdr_is_detected_from_the_transfer_characteristic()
    {
        Assert.True(new TechnicalInfo { ColorTransfer = "pq" }.IsHdr);
        Assert.True(new TechnicalInfo { ColorTransfer = "smpte2084" }.IsHdr);
        Assert.True(new TechnicalInfo { ColorTransfer = "hlg" }.IsHdr);
        Assert.False(new TechnicalInfo { ColorTransfer = "bt.1886" }.IsHdr);
        Assert.False(new TechnicalInfo().IsHdr);
    }

    [Fact]
    public void Progress_fraction_is_clamped()
    {
        Assert.Equal(0.5, new PlayerSnapshot { PositionSeconds = 50, DurationSeconds = 100 }.ProgressFraction);
        Assert.Equal(1.0, new PlayerSnapshot { PositionSeconds = 500, DurationSeconds = 100 }.ProgressFraction);
        Assert.Equal(0.0, new PlayerSnapshot { PositionSeconds = 5, DurationSeconds = 0 }.ProgressFraction);
    }

    [Fact]
    public void Progress_throttle_tiers_match_the_android_contract()
    {
        Assert.Equal(TimeSpan.FromSeconds(5), ProgressSyncPolicy.LocalSnapshotInterval);
        Assert.Equal(TimeSpan.FromSeconds(10), ProgressSyncPolicy.DefaultRemoteInterval);
    }

    [Fact]
    public void Engine_tracks_are_identified_by_the_engine_id_not_a_server_index()
    {
        var track = new EngineAudioTrack(7, "eng", "Surround", "truehd", 8, 48000, true, true, false);
        Assert.Equal(7, track.Id);
        Assert.NotEqual(0, track.Id);
    }
}

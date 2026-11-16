using MediaHub.Player;
using MediaHub.Player.Mpv;
using Xunit;

namespace MediaHub.Tests;

public sealed class MpvColorPipelineTests
{
    [Fact]
    public void Default_options_keep_the_validated_engine_behaviour()
    {
        var options = MpvColorPipeline.BuildOptions(HdrDynamicMetadataMode.FollowSource);

        Assert.Equal("auto", options["hdr-compute-peak"]);
        Assert.Equal("auto", options["target-colorspace-hint"]);
        Assert.Equal("auto", options["tone-mapping"]);
    }

    [Theory]
    [InlineData(HdrDynamicMetadataMode.FollowSource, "auto")]
    [InlineData(HdrDynamicMetadataMode.SourceMetadata, "no")]
    [InlineData(HdrDynamicMetadataMode.ComputePeak, "yes")]
    public void Each_mode_maps_to_one_engine_value(HdrDynamicMetadataMode mode, string expected)
        => Assert.Equal(expected, MpvColorPipeline.BuildOptions(mode)["hdr-compute-peak"]);

    [Theory]
    [InlineData(HdrDynamicMetadataMode.FollowSource)]
    [InlineData(HdrDynamicMetadataMode.SourceMetadata)]
    [InlineData(HdrDynamicMetadataMode.ComputePeak)]
    public void Stored_value_round_trips(HdrDynamicMetadataMode mode)
        => Assert.Equal(mode, MpvColorPipeline.Parse(MpvColorPipeline.ToStorageValue(mode)));

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("nonsense")]
    public void Unknown_stored_values_fall_back_to_the_default(string? stored)
        => Assert.Equal(HdrDynamicMetadataMode.FollowSource, MpvColorPipeline.Parse(stored));

    [Fact]
    public void Stored_aliases_from_hand_edited_settings_still_map_to_a_mode()
    {
        // Older or hand-edited config files may carry the engine value rather than the stored value.
        Assert.Equal(HdrDynamicMetadataMode.SourceMetadata, MpvColorPipeline.Parse("no"));
        Assert.Equal(HdrDynamicMetadataMode.ComputePeak, MpvColorPipeline.Parse("YES"));
    }

    [Fact]
    public void Dolby_vision_is_recognised_from_the_engine_colour_matrix()
    {
        Assert.True(new TechnicalInfo { ColorMatrix = "dolbyvision" }.IsDolbyVision);
        Assert.True(new TechnicalInfo { ColorMatrix = "DolbyVision" }.IsDolbyVision);
        Assert.False(new TechnicalInfo { ColorMatrix = "bt.2020-ncl" }.IsDolbyVision);
        Assert.False(new TechnicalInfo().IsDolbyVision);
    }

    [Fact]
    public void Dolby_vision_metadata_is_still_hdr()
        => Assert.True(new TechnicalInfo { ColorMatrix = "dolbyvision", ColorTransfer = "pq" }.IsHdr);
}

public sealed class MpvPlaybackErrorTests
{
    [Fact]
    public void A_failed_video_output_explains_the_gpu_requirement()
    {
        string text = MpvPlaybackErrors.Describe("loading failed",
            new[] { "vo/gpu-next/d3d11: Failed to initialize GPU context" }, isDolbyVision: false);

        Assert.Contains("视频输出", text);
        Assert.Contains("显卡驱动", text);
    }

    [Fact]
    public void A_decoder_failure_points_at_the_hardware_decoding_setting()
    {
        string text = MpvPlaybackErrors.Describe("loading failed",
            new[] { "vd/1: Failed to initialize a decoder for codec 'hevc'" }, isDolbyVision: true);

        Assert.Contains("硬件解码", text);
        Assert.Contains("杜比视界", text);
    }

    [Fact]
    public void Dolby_vision_is_named_when_the_source_is_known_to_be_dv()
    {
        string text = MpvPlaybackErrors.Describe("loading failed",
            new[] { "Failed to initialize the video output" }, isDolbyVision: true);

        Assert.Contains("杜比视界", text);
    }

    [Theory]
    [InlineData("HTTP error 401 Unauthorized", "登录")]
    [InlineData("HTTP error 403 Forbidden", "登录")]
    [InlineData("HTTP error 404 Not Found", "不存在")]
    [InlineData("Connection timed out", "网络")]
    [InlineData("SSL: certificate verify failed", "证书")]
    public void Common_stream_failures_are_named(string logLine, string expected)
        => Assert.Contains(expected, MpvPlaybackErrors.Describe("loading failed", new[] { logLine }, false));

    [Fact]
    public void An_unrecognised_failure_still_keeps_the_engine_reason()
    {
        string text = MpvPlaybackErrors.Describe("unknown format",
            new[] { "cplayer: something unexpected happened" }, isDolbyVision: false);

        Assert.Contains("unknown format", text);
        Assert.Contains("something unexpected happened", text);
    }

    [Fact]
    public void A_failure_with_no_log_does_not_pretend_to_know_the_cause()
    {
        string text = MpvPlaybackErrors.Describe("loading failed", Array.Empty<string>(), false);

        Assert.Contains("loading failed", text);
        Assert.Contains("引擎日志", text);
    }
}

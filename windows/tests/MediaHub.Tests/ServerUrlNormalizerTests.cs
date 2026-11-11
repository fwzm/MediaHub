using MediaHub.Domain;
using MediaHub.Net.Security;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Normalisation decides the identity of a source: two spellings that normalise identically share credentials,
/// two that do not, do not. These tests pin the shapes users actually type, plus the ones that must be refused
/// rather than half-accepted.
/// </summary>
public sealed class ServerUrlNormalizerTests
{
    [Theory]
    [InlineData("media.example.com", "http://media.example.com", "http://media.example.com/emby", false, false)]
    [InlineData("media.example.com:8096", "http://media.example.com:8096", "http://media.example.com:8096/emby", true, false)]
    [InlineData("http://h", "http://h", "http://h/emby", false, false)]
    [InlineData("https://h", "https://h", "https://h/emby", false, false)]
    [InlineData("  HTTPS://Media.Example.COM  ", "https://media.example.com", "https://media.example.com/emby", false, false)]
    [InlineData("https://h/", "https://h", "https://h/emby", false, false)]
    // A port equal to the scheme default disappears from the rendered address, but the input did carry one.
    [InlineData("http://h:80", "http://h", "http://h/emby", true, false)]
    [InlineData("https://h:443", "https://h", "https://h/emby", true, false)]
    // 443 is not the default port of http, so it stays.
    [InlineData("http://h:443", "http://h:443", "http://h:443/emby", true, false)]
    [InlineData("https://h:8920", "https://h:8920", "https://h:8920/emby", true, false)]
    // Emby already present: never /emby/emby, whatever the casing the user typed.
    [InlineData("https://h/emby", "https://h/emby", "https://h/emby", false, true)]
    [InlineData("https://h/emby/", "https://h/emby", "https://h/emby", false, true)]
    [InlineData("https://h/Emby", "https://h/Emby", "https://h/Emby", false, true)]
    // Reverse-proxy sub-path that is not the Emby prefix: kept, and /emby is added after it.
    [InlineData("https://h/media", "https://h/media", "https://h/media/emby", false, true)]
    [InlineData("https://h/media/", "https://h/media", "https://h/media/emby", false, true)]
    // A single-label host is a real deployment (NAS boxes, localhost), not garbage.
    [InlineData("nas", "http://nas", "http://nas/emby", false, false)]
    public void Emby_addresses_normalise(string input, string display, string apiRoot, bool explicitPort, bool subPath)
    {
        Assert.True(ServerUrlNormalizer.TryNormalize(input, ServerType.Emby, out NormalizedServerUrl result, out string? error), error);

        Assert.Equal(display, result.DisplayText);
        Assert.Equal(apiRoot, result.ApiRoot.AbsoluteUri);
        Assert.Equal(new Uri(display), result.BaseUri);
        Assert.Equal(explicitPort, result.HadExplicitPort);
        Assert.Equal(subPath, result.HadSubPath);
    }

    [Theory]
    [InlineData("https://h", "https://h", false, false)]
    [InlineData("h", "http://h", false, false)]
    [InlineData("http://h:8096", "http://h:8096", true, false)]
    [InlineData("https://h/", "https://h", false, false)]
    // Jellyfin answers at the base address, so a reverse-proxy sub-path is already the API root.
    [InlineData("https://h/jellyfin", "https://h/jellyfin", false, true)]
    [InlineData("https://h/jellyfin/", "https://h/jellyfin", false, true)]
    [InlineData("https://h:8920/jellyfin/", "https://h:8920/jellyfin", true, true)]
    [InlineData("h/jellyfin", "http://h/jellyfin", false, true)]
    public void Jellyfin_addresses_normalise(string input, string display, bool explicitPort, bool subPath)
    {
        Assert.True(ServerUrlNormalizer.TryNormalize(input, ServerType.Jellyfin, out NormalizedServerUrl result, out string? error), error);

        Assert.Equal(display, result.DisplayText);
        // Uri equality rather than text: a root path renders as "https://h/" even though it addresses the same
        // origin as "https://h", and Jellyfin's API root is exactly the base address.
        Assert.Equal(new Uri(display), result.ApiRoot);
        Assert.Equal(explicitPort, result.HadExplicitPort);
        Assert.Equal(subPath, result.HadSubPath);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("   ")]
    public void Missing_input_is_refused_with_its_own_reason(string? input)
    {
        Assert.False(ServerUrlNormalizer.TryNormalize(input!, ServerType.Emby, out NormalizedServerUrl result, out string? error));
        Assert.Null(result);
        Assert.Equal("请输入服务器地址。", error);
    }

    [Theory]
    [InlineData("ftp://host", "http")]
    [InlineData("file:///C:/media", "http")]
    [InlineData("ws://host", "http")]
    [InlineData("https://host/x?y=1", "查询参数")]
    // An empty query still means someone pasted a request URL rather than a base address.
    [InlineData("https://host?", "查询参数")]
    [InlineData("https://host/x#frag", "片段")]
    [InlineData("https://alice:hunter2@host", "用户名或密码")]
    [InlineData("http://", "无法解析")]
    [InlineData("http:///path", "无法解析")]
    [InlineData("http://host:99999", "无法解析")]
    [InlineData("not a url", "无法解析")]
    public void Unusable_input_is_refused_with_a_specific_reason(string input, string expectedFragment)
    {
        Assert.False(ServerUrlNormalizer.TryNormalize(input, ServerType.Emby, out NormalizedServerUrl result, out string? error));

        Assert.Null(result);
        Assert.NotNull(error);
        Assert.Contains(expectedFragment, error!);
    }

    [Fact]
    public void A_refusal_never_repeats_the_input()
    {
        // Errors reach logs and support bundles, and users paste addresses with credentials in them.
        Assert.False(ServerUrlNormalizer.TryNormalize("https://alice:hunter2@host", ServerType.Emby, out _, out string? error));

        Assert.NotNull(error);
        Assert.DoesNotContain("hunter2", error!);
        Assert.DoesNotContain("alice", error!);
    }

    [Theory]
    [InlineData("media.example.com", ServerType.Emby)]
    [InlineData("media.example.com:8096", ServerType.Emby)]
    [InlineData("https://h/emby/", ServerType.Emby)]
    [InlineData("http://h:80", ServerType.Emby)]
    [InlineData("https://h:8920/jellyfin/", ServerType.Jellyfin)]
    public void Normalising_a_display_text_again_is_stable(string input, ServerType type)
    {
        Assert.True(ServerUrlNormalizer.TryNormalize(input, type, out NormalizedServerUrl first, out string? error), error);
        Assert.True(ServerUrlNormalizer.TryNormalize(first.DisplayText, type, out NormalizedServerUrl second, out string? secondError), secondError);

        Assert.Equal(first.DisplayText, second.DisplayText);
        Assert.Equal(first.ApiRoot, second.ApiRoot);
        // HadExplicitPort is deliberately not compared: once a default port has been dropped from the display
        // text, the information that a port was typed is gone, which is the point of dropping it.
    }

    [Fact]
    public void Api_root_composes_the_way_the_dialect_layer_builds_request_urls()
    {
        // MediaBrowserDialect.BuildUrl composes "ApiRoot.ToString().TrimEnd('/') + path". ApiRoot therefore must
        // not rely on Uri relative resolution, which would silently drop the last segment of a sub-path.
        Assert.True(ServerUrlNormalizer.TryNormalize("https://h", ServerType.Emby, out NormalizedServerUrl emby, out _));
        Assert.Equal("https://h/emby/System/Info/Public", emby.ApiRoot.ToString().TrimEnd('/') + "/System/Info/Public");

        Assert.True(ServerUrlNormalizer.TryNormalize("https://h/jellyfin", ServerType.Jellyfin, out NormalizedServerUrl jellyfin, out _));
        Assert.Equal("https://h/jellyfin/System/Info/Public", jellyfin.ApiRoot.ToString().TrimEnd('/') + "/System/Info/Public");
    }

    [Fact]
    public void An_ipv6_literal_is_accepted_and_its_port_is_detected()
    {
        // The host keeps its brackets, and the colons inside the literal must not be mistaken for a port.
        Assert.True(ServerUrlNormalizer.TryNormalize("http://[::1]:8096", ServerType.Emby, out NormalizedServerUrl result, out string? error), error);

        Assert.Equal("http://[::1]:8096", result.DisplayText);
        Assert.Equal("http://[::1]:8096/emby", result.ApiRoot.AbsoluteUri);
        Assert.True(result.HadExplicitPort);
    }
}

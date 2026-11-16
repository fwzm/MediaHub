using MediaHub.Net;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Address policy for HLS children the relay is asked to fetch. The child URIs come from a playlist
/// the media server sent, so they are attacker-controlled input: the relay must not be usable as a
/// proxy into the machine or the LAN it runs on.
/// </summary>
public sealed class HlsTargetPolicyTests
{
    private static readonly Uri Origin = new("https://media.example.com/Items/1/stream.m3u8");

    [Theory]
    [InlineData("https://media.example.com/hls/480/index.m3u8")]
    [InlineData("https://media.example.com:8443/hls/480/index.m3u8")]
    [InlineData("https://cdn.example.net/hls/segment1.ts")]
    [InlineData("https://203.0.113.10/hls/segment1.ts")]
    public void SameOriginAndPublicHostsAreRelayed(string child)
    {
        Assert.True(HlsTargetPolicy.IsAllowed(new Uri(child), Origin));
    }

    [Theory]
    [InlineData("http://127.0.0.1:8096/admin")]
    [InlineData("http://localhost:8096/admin")]
    [InlineData("http://10.0.0.5/router")]
    [InlineData("http://172.16.4.2/status")]
    [InlineData("http://192.168.1.1/setup.cgi")]
    [InlineData("http://169.254.169.254/latest/meta-data/")]
    [InlineData("http://100.64.0.1/nat")]
    [InlineData("http://[::1]:8096/admin")]
    [InlineData("http://[fe80::1]/lan")]
    [InlineData("http://[fd00::1]/lan")]
    [InlineData("http://router.lan/admin")]
    [InlineData("http://nas.local/")]
    [InlineData("http://intranet/admin")]
    [InlineData("http://files/")]
    public void LoopbackPrivateAndLocalNamesAreRefused(string child)
    {
        Assert.False(HlsTargetPolicy.IsAllowed(new Uri(child), Origin));
    }

    [Theory]
    [InlineData("file:///C:/Windows/win.ini")]
    [InlineData("ftp://media.example.com/stream.ts")]
    [InlineData("skd://key-id-1")]
    public void NonHttpSchemesAreRefused(string child)
    {
        Assert.False(HlsTargetPolicy.IsAllowed(new Uri(child), Origin));
    }

    [Fact]
    public void TheOriginHostItselfStaysAllowedEvenWhenItIsPrivate()
    {
        var origin = new Uri("http://127.0.0.1:8096/Items/1/stream.m3u8");
        Assert.True(HlsTargetPolicy.IsAllowed(new Uri("http://127.0.0.1:8096/Items/1/index.m3u8"), origin));
    }
}

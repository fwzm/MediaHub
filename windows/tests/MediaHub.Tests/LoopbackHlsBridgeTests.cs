using System.Net;
using System.Net.Http;
using System.Text;
using MediaHub.Net;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// HLS relay behaviour: nested playlists, relative resolution, and credential scoping per origin.
/// </summary>
/// <remarks>
/// An HLS stream is a tree of references. The engine only ever knows the relay's opaque addresses, so
/// every level has to be rewritten here and every level has to authenticate like the first request.
/// These tests use real HTTP between the relay and a local origin, and a real HttpClient as the engine,
/// so a missing rewrite fails as a 404/401 rather than as an assertion about a string.
/// </remarks>
public sealed class LoopbackHlsBridgeTests
{
    private static readonly byte[] SegmentBytes = Encoding.ASCII.GetBytes("SEGMENT-0-PAYLOAD");
    private static readonly byte[] KeyBytes = Encoding.ASCII.GetBytes("0123456789abcdef");

    private static BridgeTarget Target(Uri url)
        => new(url, new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
        {
            ["X-Emby-Token"] = "TOKEN-CANARY",
        }, new NetworkPolicy { Mode = ProxyMode.Direct });

    private static async Task WriteTextAsync(HttpListenerContext context, string text)
    {
        byte[] bytes = Encoding.UTF8.GetBytes(text);
        context.Response.StatusCode = 200;
        context.Response.ContentType = "application/vnd.apple.mpegurl";
        context.Response.ContentLength64 = bytes.Length;
        await context.Response.OutputStream.WriteAsync(bytes).ConfigureAwait(false);
        context.Response.Close();
    }

    private static async Task WriteBytesAsync(HttpListenerContext context, byte[] bytes, string contentType)
    {
        context.Response.StatusCode = 200;
        context.Response.ContentType = contentType;
        context.Response.ContentLength64 = bytes.Length;
        await context.Response.OutputStream.WriteAsync(bytes).ConfigureAwait(false);
        context.Response.Close();
    }

    private static async Task RequireCanaryAsync(HttpListenerContext context, byte[] bytes, string contentType)
    {
        string token = context.Request.Headers["X-Emby-Token"] ?? "";
        if (!string.Equals(token, "TOKEN-CANARY", StringComparison.Ordinal))
        {
            context.Response.StatusCode = 401;
            context.Response.ContentLength64 = 0;
            context.Response.Close();
            return;
        }
        await WriteBytesAsync(context, bytes, contentType).ConfigureAwait(false);
    }

    private static string FirstUriLine(string playlist)
        => playlist.Split('\n').Select(line => line.Trim())
            .First(line => line.Length > 0 && line[0] != '#');

    private static string AttributeUri(string playlist, string attributeLinePrefix)
    {
        string line = playlist.Split('\n').First(l => l.Contains(attributeLinePrefix, StringComparison.Ordinal));
        const string marker = "URI=\"";
        int start = line.IndexOf(marker, StringComparison.Ordinal) + marker.Length;
        int end = line.IndexOf('"', start);
        return line[start..end];
    }

    private static HttpClient Engine() => new() { Timeout = TimeSpan.FromSeconds(15) };

    [Fact]
    public async Task Master_child_and_segment_are_rewritten_and_every_level_is_fetchable()
    {
        await using BridgeTestOrigin origin = await BridgeTestOrigin.StartAsync();
        origin.Map("/hls/master.m3u8", ctx => WriteTextAsync(ctx,
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nv1/index.m3u8\n"));
        origin.Map("/hls/v1/index.m3u8", ctx => WriteTextAsync(ctx,
            "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n"
            + "#EXTINF:2.0,\n../seg/seg0.ts\n#EXT-X-ENDLIST\n"));
        origin.Map("/hls/seg/seg0.ts", ctx => RequireCanaryAsync(ctx, SegmentBytes, "video/mp2t"));

        await using LoopbackMediaBridge bridge = await LoopbackMediaBridge.StartAsync(
            Target(new Uri(origin.BaseUri, "hls/master.m3u8")));
        using HttpClient engine = Engine();

        string master = await engine.GetStringAsync(bridge.BridgeUri);
        Assert.DoesNotContain("v1/index.m3u8", master, StringComparison.Ordinal);
        string childUrl = FirstUriLine(master);
        Assert.StartsWith("http://127.0.0.1:" + bridge.Port + "/", childUrl, StringComparison.Ordinal);

        string child = await engine.GetStringAsync(new Uri(childUrl));
        Assert.DoesNotContain("../seg/seg0.ts", child, StringComparison.Ordinal);
        string segmentUrl = FirstUriLine(child);
        Assert.StartsWith("http://127.0.0.1:" + bridge.Port + "/", segmentUrl, StringComparison.Ordinal);

        // A segment fetched through the relay must not 401: the origin only answers with the canary
        // header, and only the relay can supply it.
        byte[] segment = await engine.GetByteArrayAsync(new Uri(segmentUrl));
        Assert.Equal(SegmentBytes, segment);
        Assert.True(origin.SawPathWithHeader("/hls/seg/seg0.ts", "X-Emby-Token", "TOKEN-CANARY"));

        // The engine's URLs never carry the server's own query string or origin.
        Assert.DoesNotContain(origin.HostPort, master, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain(origin.HostPort, child, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task A_cross_origin_segment_keeps_its_own_query_but_gets_no_session_credentials()
    {
        await using BridgeTestOrigin cdn = await BridgeTestOrigin.StartAsync();
        cdn.Map("/media/seg0.ts", ctx => WriteBytesAsync(ctx, SegmentBytes, "video/mp2t"));

        await using BridgeTestOrigin origin = await BridgeTestOrigin.StartAsync();
        origin.Map("/hls/master.m3u8", ctx => WriteTextAsync(ctx,
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\n"
            + cdn.BaseUri + "media/seg0.ts?token=ORIGIN-B-SECRET\n"));

        await using LoopbackMediaBridge bridge = await LoopbackMediaBridge.StartAsync(
            Target(new Uri(origin.BaseUri, "hls/master.m3u8")));
        using HttpClient engine = Engine();

        string master = await engine.GetStringAsync(bridge.BridgeUri);
        string segmentUrl = FirstUriLine(master);
        Assert.DoesNotContain("ORIGIN-B-SECRET", segmentUrl, StringComparison.Ordinal);

        byte[] segment = await engine.GetByteArrayAsync(new Uri(segmentUrl));
        Assert.Equal(SegmentBytes, segment);

        // The upstream query is preserved for the origin, and the session credential was not sent to
        // a different origin. Both halves matter: dropping the query breaks signed CDN URLs, sending
        // the token re-creates the leak the relay exists to prevent.
        var seen = cdn.RequestsFor("/media/seg0.ts");
        Assert.Single(seen);
        Assert.Equal("?token=ORIGIN-B-SECRET", seen[0].Query);
        Assert.False(cdn.SawPathWithHeader("/media/seg0.ts", "X-Emby-Token", "TOKEN-CANARY"));
    }

    [Fact]
    public async Task An_EXT_X_KEY_uri_is_rewritten_with_its_query_hidden()
    {
        await using BridgeTestOrigin origin = await BridgeTestOrigin.StartAsync();
        origin.Map("/hls/index.m3u8", ctx => WriteTextAsync(ctx,
            "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-KEY:METHOD=AES-128,"
            + "URI=\"keys/key.bin?api_key=KEY-SECRET\",IV=0x00000000000000000000000000000001\n"
            + "#EXTINF:2.0,\nseg0.ts\n#EXT-X-ENDLIST\n"));
        origin.Map("/hls/seg0.ts", ctx => RequireCanaryAsync(ctx, SegmentBytes, "video/mp2t"));
        origin.Map("/hls/keys/key.bin", ctx => RequireCanaryAsync(ctx, KeyBytes, "application/octet-stream"));

        await using LoopbackMediaBridge bridge = await LoopbackMediaBridge.StartAsync(
            Target(new Uri(origin.BaseUri, "hls/index.m3u8")));
        using HttpClient engine = Engine();

        string playlist = await engine.GetStringAsync(bridge.BridgeUri);
        Assert.Contains("#EXT-X-KEY:", playlist, StringComparison.Ordinal);
        Assert.DoesNotContain("KEY-SECRET", playlist, StringComparison.Ordinal);

        string keyUrl = AttributeUri(playlist, "#EXT-X-KEY:");
        byte[] key = await engine.GetByteArrayAsync(new Uri(keyUrl));
        Assert.Equal(KeyBytes, key);

        var seen = origin.RequestsFor("/hls/keys/key.bin");
        Assert.Single(seen);
        Assert.Equal("?api_key=KEY-SECRET", seen[0].Query);
        Assert.True(origin.SawPathWithHeader("/hls/keys/key.bin", "X-Emby-Token", "TOKEN-CANARY"));
    }

    [Fact]
    public async Task An_unknown_hls_id_is_404_and_never_reaches_the_upstream()
    {
        await using BridgeTestOrigin origin = await BridgeTestOrigin.StartAsync();
        origin.Map("/stream", ctx => WriteBytesAsync(ctx, SegmentBytes, "video/mp4"));

        await using LoopbackMediaBridge bridge = await LoopbackMediaBridge.StartAsync(
            Target(new Uri(origin.BaseUri, "stream")));
        using HttpClient engine = Engine();

        using HttpResponseMessage response = await engine.GetAsync(
            new Uri("http://127.0.0.1:" + bridge.Port + "/" + bridge.Token + "/hls/0123456789abcdef"));
        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
        Assert.False(origin.SawPath("/stream"));
    }

    [Fact]
    public async Task A_playlist_naming_a_url_outside_http_is_left_alone()
    {
        await using BridgeTestOrigin origin = await BridgeTestOrigin.StartAsync();
        origin.Map("/hls/master.m3u8", ctx => WriteTextAsync(ctx,
            "#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI=\"skd://key-id\"\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=800000\nindex.m3u8\n"));

        await using LoopbackMediaBridge bridge = await LoopbackMediaBridge.StartAsync(
            Target(new Uri(origin.BaseUri, "hls/master.m3u8")));
        using HttpClient engine = Engine();

        string master = await engine.GetStringAsync(bridge.BridgeUri);
        Assert.Contains("skd://key-id", master, StringComparison.Ordinal);
        Assert.StartsWith("http://127.0.0.1:" + bridge.Port + "/",
            FirstUriLine(master), StringComparison.Ordinal);
    }
}

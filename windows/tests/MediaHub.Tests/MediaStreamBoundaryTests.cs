using System.Net;
using System.Text;
using MediaHub.Net;
using MediaHub.Player;
using MediaHub.Player.Mpv;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Local HTTP origin used by the bridge tests. Supports Range so the relay's seek behaviour is
/// observable, and records every request so credential scoping can be asserted on facts.
/// </summary>
internal sealed class BridgeTestOrigin : IAsyncDisposable
{
    private readonly HttpListener _listener = new();
    private readonly Dictionary<string, Func<HttpListenerContext, Task>> _routes = new(StringComparer.OrdinalIgnoreCase);
    private readonly List<(string Path, Dictionary<string, string> Headers)> _seen = new();
    private readonly object _gate = new();

    private BridgeTestOrigin(int port) => Port = port;

    public int Port { get; }
    public string HostPort => "127.0.0.1:" + Port;
    public Uri BaseUri => new("http://127.0.0.1:" + Port + "/");
    public IReadOnlyList<(string Path, Dictionary<string, string> Headers)> Seen { get { lock (_gate) return _seen.ToList(); } }

    public void Map(string path, Func<HttpListenerContext, Task> handler) => _routes[path] = handler;

    public static Task<BridgeTestOrigin> StartAsync()
    {
        for (int attempt = 0; attempt < 30; attempt++)
        {
            int port = Random.Shared.Next(20000, 60000);
            var origin = new BridgeTestOrigin(port);
            origin._listener.Prefixes.Add("http://127.0.0.1:" + port + "/");
            try { origin._listener.Start(); }
            catch (HttpListenerException) { continue; }
            _ = Task.Run(origin.Loop);
            return Task.FromResult(origin);
        }
        throw new InvalidOperationException("could not bind a loopback origin");
    }

    private async Task Loop()
    {
        while (_listener.IsListening)
        {
            HttpListenerContext ctx;
            try { ctx = await _listener.GetContextAsync().ConfigureAwait(false); }
            catch { return; }

            var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            foreach (string? key in ctx.Request.Headers.AllKeys)
            {
                if (key is null) continue;
                headers[key] = ctx.Request.Headers[key] ?? "";
            }
            string path = ctx.Request.Url?.AbsolutePath ?? "";
            lock (_gate) _seen.Add((path, headers));

            if (_routes.TryGetValue(path, out var handler))
            {
                try { await handler(ctx).ConfigureAwait(false); }
                catch { try { ctx.Response.Close(); } catch { } }
            }
            else
            {
                ctx.Response.StatusCode = 404;
                ctx.Response.ContentLength64 = 0;
                ctx.Response.Close();
            }
        }
    }

    public bool SawPathWithHeader(string path, string headerName, string value)
    {
        lock (_gate)
        {
            return _seen.Any(e => e.Path.Equals(path, StringComparison.OrdinalIgnoreCase)
                                  && e.Headers.TryGetValue(headerName, out string? v)
                                  && v.Contains(value, StringComparison.Ordinal));
        }
    }

    public bool SawPath(string path)
    {
        lock (_gate) return _seen.Any(e => e.Path.Equals(path, StringComparison.OrdinalIgnoreCase));
    }

    public void Clear() { lock (_gate) _seen.Clear(); }

    public ValueTask DisposeAsync()
    {
        try { _listener.Stop(); _listener.Close(); } catch { }
        return ValueTask.CompletedTask;
    }

    internal static async Task ServeRangeAsync(HttpListenerContext ctx, byte[] data, string contentType = "video/mp4")
    {
        int start = 0, end = data.Length - 1;
        bool partial = false;
        string? range = ctx.Request.Headers["Range"];
        if (!string.IsNullOrEmpty(range) && range.StartsWith("bytes=", StringComparison.OrdinalIgnoreCase))
        {
            string spec = range["bytes=".Length..];
            string[] parts = spec.Split('-');
            if (int.TryParse(parts[0], out int s)) start = s;
            if (parts.Length > 1 && int.TryParse(parts[1], out int e)) end = e;
            if (start >= data.Length)
            {
                ctx.Response.StatusCode = 416;
                ctx.Response.Headers["Content-Range"] = "bytes */" + data.Length;
                ctx.Response.Close();
                return;
            }
            end = Math.Min(end, data.Length - 1);
            partial = true;
        }

        int count = end - start + 1;
        ctx.Response.StatusCode = partial ? 206 : 200;
        ctx.Response.ContentType = contentType;
        ctx.Response.Headers["Accept-Ranges"] = "bytes";
        if (partial) ctx.Response.Headers["Content-Range"] = "bytes " + start + "-" + end + "/" + data.Length;
        ctx.Response.ContentLength64 = count;
        await ctx.Response.OutputStream.WriteAsync(data.AsMemory(start, count));
        ctx.Response.Close();
    }
}

/// <summary>
/// Evidence for the two questions the network work must answer: whether the engine's route matches
/// the configured policy, and whether credentials can leave their origin through the engine.
/// </summary>
public sealed class MediaStreamBoundaryTests : IDisposable
{
    private const string Canary = "TOKEN-CANARY-9317";

    private static string FixturePath =>
        Path.Combine(AppContext.BaseDirectory, "assets", "sample-av.mp4");

    private static Dictionary<string, string> CanaryHeaders() => new(StringComparer.OrdinalIgnoreCase)
    {
        ["X-Emby-Token"] = Canary,
    };

    private static MpvPlayerOptions Headless(string? proxy = "") => new()
    {
        Wid = IntPtr.Zero,
        VideoOutput = "null",
        AudioOutput = "null",
        HwDec = "no",
        HttpProxy = proxy,
    };

    // ---------------------------------------------------------------- bridge relay

    [Fact]
    public async Task Bridge_relays_the_full_body()
    {
        byte[] payload = Encoding.UTF8.GetBytes(new string('m', 200_000));
        await using var origin = await BridgeTestOrigin.StartAsync();
        origin.Map("/stream", ctx => BridgeTestOrigin.ServeRangeAsync(ctx, payload));

        await using var bridge = await LoopbackMediaBridge.StartAsync(
            new BridgeTarget(new Uri(origin.BaseUri, "stream"), CanaryHeaders(), NetworkPolicy.FollowSystem));

        using var client = new HttpClient();
        byte[] received;
        try
        {
            received = await client.GetByteArrayAsync(bridge.BridgeUri);
        }
        catch (Exception ex)
        {
            Assert.Fail("bridge relay failed: " + ex.GetType().Name + ": " + ex.Message
                + " | bridge errors: " + string.Join(" | ", bridge.RelayedErrors));
            throw;
        }

        Assert.True(bridge.RelayedErrors.Count == 0, "bridge reported: " + string.Join(" | ", bridge.RelayedErrors));
        Assert.Equal(payload.Length, received.Length);
        Assert.Equal(payload, received);
        Assert.Equal(1, bridge.RequestCount);
        Assert.Equal(0, bridge.DeniedRequestCount);
    }

    [Fact]
    public async Task Bridge_forwards_range_and_relays_206()
    {
        byte[] payload = new byte[100_000];
        Random.Shared.NextBytes(payload);
        await using var origin = await BridgeTestOrigin.StartAsync();
        origin.Map("/stream", ctx => BridgeTestOrigin.ServeRangeAsync(ctx, payload));

        await using var bridge = await LoopbackMediaBridge.StartAsync(
            new BridgeTarget(new Uri(origin.BaseUri, "stream"), CanaryHeaders(), NetworkPolicy.FollowSystem));

        using var client = new HttpClient();
        using var request = new HttpRequestMessage(HttpMethod.Get, bridge.BridgeUri);
        request.Headers.Range = new System.Net.Http.Headers.RangeHeaderValue(1000, 1999);
        using HttpResponseMessage response = await client.SendAsync(request);

        Assert.Equal(HttpStatusCode.PartialContent, response.StatusCode);
        byte[] body = await response.Content.ReadAsByteArrayAsync();
        Assert.Equal(1000, body.Length);
        Assert.Equal(payload.AsSpan(1000, 1000).ToArray(), body);
        Assert.Equal("no-store", response.Headers.CacheControl?.ToString());
    }

    // ---------------------------------------------------------------- access control

    [Fact]
    public async Task Bridge_refuses_a_request_without_the_session_token()
    {
        await using var origin = await BridgeTestOrigin.StartAsync();
        origin.Map("/stream", ctx => BridgeTestOrigin.ServeRangeAsync(ctx, new byte[64]));

        await using var bridge = await LoopbackMediaBridge.StartAsync(
            new BridgeTarget(new Uri(origin.BaseUri, "stream"), CanaryHeaders(), NetworkPolicy.FollowSystem));

        using var client = new HttpClient();
        using HttpResponseMessage response = await client.GetAsync("http://127.0.0.1:" + bridge.Port + "/stream");

        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
        Assert.False(origin.SawPath("/stream"));
    }

    [Fact]
    public async Task Bridge_refuses_a_browser_origin()
    {
        await using var origin = await BridgeTestOrigin.StartAsync();
        origin.Map("/stream", ctx => BridgeTestOrigin.ServeRangeAsync(ctx, new byte[64]));

        await using var bridge = await LoopbackMediaBridge.StartAsync(
            new BridgeTarget(new Uri(origin.BaseUri, "stream"), CanaryHeaders(), NetworkPolicy.FollowSystem));

        using var client = new HttpClient();
        using var request = new HttpRequestMessage(HttpMethod.Get, bridge.BridgeUri);
        request.Headers.TryAddWithoutValidation("Origin", "https://evil.example");
        using HttpResponseMessage response = await client.SendAsync(request);

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
        Assert.Contains(bridge.DeniedRequests, d => d.Contains("cross-origin", StringComparison.OrdinalIgnoreCase));
        Assert.False(origin.SawPath("/stream"));
    }

    // ---------------------------------------------------------------- the credential boundary

    [Fact]
    public async Task Engine_through_the_bridge_never_delivers_the_token_to_a_redirect_target()
    {
        byte[] media = await File.ReadAllBytesAsync(FixturePath);
        await using var originA = await BridgeTestOrigin.StartAsync();
        await using var originB = await BridgeTestOrigin.StartAsync();

        // Origin A requires the token and redirects to a *different origin*, which is the shape of an
        // Emby direct-stream hand-off to object storage.
        originA.Map("/stream", ctx =>
        {
            ctx.Response.StatusCode = 302;
            ctx.Response.Headers["Location"] = originB.BaseUri + "media";
            ctx.Response.ContentLength64 = 0;
            ctx.Response.Close();
            return Task.CompletedTask;
        });
        originB.Map("/media", ctx => BridgeTestOrigin.ServeRangeAsync(ctx, media));

        await using var bridge = await LoopbackMediaBridge.StartAsync(
            new BridgeTarget(new Uri(originA.BaseUri, "stream"), CanaryHeaders(), NetworkPolicy.FollowSystem));

        // The engine is given a loopback URL and NO credentials of any kind.
        using var player = new MpvPlayer(Headless());
        await player.LoadAsync(bridge.BridgeUri.ToString(), new PlaybackSettings { Volume = 0 });

        for (int i = 0; i < 30 && player.Snapshot.DurationSeconds <= 0; i++) await Task.Delay(200);

        Assert.True(originA.SawPath("/stream"), "the bridge should have contacted the origin");
        Assert.True(originB.SawPath("/media"), "the redirect target should have been reached");
        Assert.False(
            originB.SawPathWithHeader("/media", "X-Emby-Token", Canary),
            "the token must not reach the redirect target");
        Assert.True(player.Snapshot.DurationSeconds > 0,
            "the engine must still receive playable media through the bridge");
    }

    [Fact]
    public async Task Engine_handed_credentials_directly_delivers_them_to_a_redirect_target()
    {
        // Characterisation of why the bridge exists. The assertion is deliberately one-sided: if a
        // future engine build starts stripping headers on an origin change, this test still passes and
        // the recorded fact changes. What must never regress is the bridge-side guarantee above.
        byte[] media = await File.ReadAllBytesAsync(FixturePath);
        await using var originA = await BridgeTestOrigin.StartAsync();
        await using var originB = await BridgeTestOrigin.StartAsync();

        originA.Map("/stream", ctx =>
        {
            ctx.Response.StatusCode = 302;
            ctx.Response.Headers["Location"] = originB.BaseUri + "media";
            ctx.Response.ContentLength64 = 0;
            ctx.Response.Close();
            return Task.CompletedTask;
        });
        originB.Map("/media", ctx => BridgeTestOrigin.ServeRangeAsync(ctx, media));

        using var player = new MpvPlayer(Headless());
        await player.LoadAsync(originA.BaseUri + "stream", new PlaybackSettings
        {
            Volume = 0,
            Headers = CanaryHeaders(),
        });
        for (int i = 0; i < 30 && player.Snapshot.DurationSeconds <= 0; i++) await Task.Delay(200);

        bool leaked = originB.SawPathWithHeader("/media", "X-Emby-Token", Canary);
        Assert.True(originB.SawPath("/media"), "the redirect target should have been reached");
        Console.WriteLine(leaked
            ? "RECORDED FACT: direct hand-off delivers the token to the redirect target (bridge required)."
            : "RECORDED FACT: this engine build did not forward the token; the bridge remains the guarantee.");
    }

    // ---------------------------------------------------------------- proxy policy

    [Fact]
    public void Engine_receives_the_proxy_value_the_policy_computes()
    {
        // "The engine follows the policy" is only meaningful if the value actually reached the engine.
        // The engine exposes its options as properties, so this reads the effective configuration back
        // rather than trusting that an API call with a void result did what it was told.
        using var direct = new MpvPlayer(Headless(""));
        Assert.Equal("", direct.GetOptionValue("http-proxy"));

        using var explicitProxy = new MpvPlayer(Headless("http://127.0.0.1:7890"));
        Assert.Equal("http://127.0.0.1:7890", explicitProxy.GetOptionValue("http-proxy"));

        // A misspelled option is rejected silently by the engine, so the accepted name is pinned here.
        Assert.Empty(explicitProxy.OptionErrors);
    }

    [Fact]
    public async Task Ambient_no_proxy_can_bypass_the_proxy_and_that_is_why_the_bridge_exists()
    {
        // Documented hazard, not a defect to fix: libavformat honours the NO_PROXY/http_proxy
        // environment variables, so an engine configured with a reachable proxy can still connect
        // directly when the environment excludes the host. This is precisely why the media route is
        // not left to the engine: the bridge fetches the bytes with an explicitly constructed handler.
        byte[] media = await File.ReadAllBytesAsync(FixturePath);
        await using var origin = await BridgeTestOrigin.StartAsync();
        origin.Map("/media", ctx => BridgeTestOrigin.ServeRangeAsync(ctx, media));

        bool loopbackExcluded = (Environment.GetEnvironmentVariable("NO_PROXY")
                                 ?? Environment.GetEnvironmentVariable("no_proxy") ?? "")
            .Contains("127.0.0.1", StringComparison.OrdinalIgnoreCase);

        using var player = new MpvPlayer(Headless("http://127.0.0.1:1"));
        await player.LoadAsync(origin.BaseUri + "media", new PlaybackSettings { Volume = 0 });
        await Task.Delay(2500);

        Console.WriteLine("NO_PROXY excludes loopback: " + loopbackExcluded
            + "; dead proxy prevented the fetch: " + !origin.SawPath("/media"));

        // The bridge is the guarantee, so this asserts the guarantee rather than the environment.
        await using var bridge = await LoopbackMediaBridge.StartAsync(
            new BridgeTarget(new Uri(origin.BaseUri, "media"), CanaryHeaders(), new NetworkPolicy { Mode = ProxyMode.Direct }));
        using var client = new HttpClient();
        byte[] received = await client.GetByteArrayAsync(bridge.BridgeUri);
        Assert.Equal(media.Length, received.Length);
    }

    [Fact]
    public void Policy_engine_value_is_explicit_for_every_mode()
    {
        var target = new Uri("http://media.example.com/x");

        // Direct must be an explicit empty value, not null: null would leave the engine free to honour
        // an ambient http_proxy variable.
        Assert.Equal("", new NetworkPolicy { Mode = ProxyMode.Direct }.ToEngineProxyValue(target));
        Assert.Equal("http://127.0.0.1:7890",
            new NetworkPolicy { Mode = ProxyMode.Explicit, ProxyUrl = "http://127.0.0.1:7890" }.ToEngineProxyValue(target));
        Assert.NotNull(NetworkPolicy.FollowSystem.ToEngineProxyValue(target));
    }

    public void Dispose() { }
}

using System.Net;
using System.Text;
using MediaHub.Player;
using MediaHub.Player.Mpv;

namespace MediaHub.Tools.MpvNetworkProbe;

/// <summary>
/// Answers one question with evidence: does the playback engine obey the same network policy as the
/// application layer, and does it keep credentials scoped to their origin on redirects?
/// </summary>
/// <remarks>
/// The application layer follows redirects itself and strips identity headers on an origin change
/// (see OriginScopedCredentialHandler). The engine fetches the media stream with its own stack, so
/// none of that logic applies to it unless it is explicitly configured to. This probe observes what
/// the engine actually sends: which proxy it used, and which headers arrived at the redirect target.
/// Guessing here would mean shipping a client whose credential-leak protection covers only half its
/// traffic.
/// </remarks>
internal static class Program
{
    private static byte[] _media = Array.Empty<byte>();

    private static async Task<int> Main(string[] args)
    {
        string fixture = args.Length > 0
            ? args[0]
            : Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", "tests", "assets", "sample-av.mp4");
        fixture = Path.GetFullPath(fixture);
        if (!File.Exists(fixture))
        {
            Console.Error.WriteLine("fixture not found: " + fixture);
            return 2;
        }
        _media = await File.ReadAllBytesAsync(fixture);
        Console.WriteLine("fixture: " + fixture + " (" + _media.Length + " bytes)");
        Console.WriteLine("libmpv client API: " + MpvPlayer.ClientApiVersion);
        Console.WriteLine();

        await using var originA = await Server.StartAsync("A");
        await using var originB = await Server.StartAsync("B");
        await using var proxy = await ProxyServer.StartAsync();

        _currentOrigin = originA;
        _currentProxy = proxy;

        originA.Map("/real", ServeMedia);
        originB.Map("/real", ServeMedia);
        originA.Map("/redirect-same", ctx => Redirect(ctx, originA.BaseUri + "real"));
        originA.Map("/redirect-cross", ctx => Redirect(ctx, originB.BaseUri + "real"));

        // Which proxy option names does this engine build actually accept? A rejected option is
        // silent at runtime, so this has to be established before any routing conclusion is drawn.
        Console.WriteLine("0. proxy option names accepted by this engine build");
        foreach (string candidate in new[] { "http-proxy", "proxy", "http_proxy", "network-timeout" })
        {
            var probeOptions = new MpvPlayerOptions
            {
                Wid = IntPtr.Zero,
                VideoOutput = "null",
                AudioOutput = "null",
                HwDec = "no",
                ExtraOptions = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase) { [candidate] = "http://127.0.0.1:1" },
            };
            using var probePlayer = new MpvPlayer(probeOptions);
            bool rejected = probePlayer.OptionErrors.Any(e => e.StartsWith(candidate + ":", StringComparison.OrdinalIgnoreCase));
            Console.WriteLine("   " + candidate.PadRight(18) + (rejected ? "REJECTED -> " + probePlayer.OptionErrors[0] : "accepted"));
        }
        Console.WriteLine();

        int failures = 0;
        failures += Run("1. proxy option accepted and honoured",
            "http://" + originA.HostPort + "/real",
            headers: null,
            proxyUrl: "http://" + proxy.HostPort,
            expect: () =>
            {
                if (proxy.Requests.Any(r => r.Contains("/real"))) return null;
                if (originA.Requests.Count > 0)
                    return "engine bypassed the configured proxy and connected to the origin directly";
                return "engine made no request at all (cannot conclude)";
            });

        failures += Run("2. headers reach the origin",
            "http://" + originA.HostPort + "/real",
            headers: Tokens(),
            proxyUrl: null,
            expect: () =>
            {
                var hit = originA.Requests.LastOrDefault(r => r.Path.StartsWith("/real"));
                if (hit is null) return "origin never received the request";
                return HasToken(hit) ? null : "origin did not receive the token header";
            });

        failures += Run("3. same-origin redirect keeps credentials",
            "http://" + originA.HostPort + "/redirect-same",
            headers: Tokens(),
            proxyUrl: null,
            expect: () =>
            {
                var hit = originA.Requests.LastOrDefault(r => r.Path.StartsWith("/real"));
                if (hit is null) return "redirect target never received the request";
                return HasToken(hit) ? null : "token was lost on a same-origin redirect";
            });

        Console.WriteLine();
        Console.WriteLine("4. CROSS-ORIGIN redirect — the security-critical observation");
        originA.Clear();
        originB.Clear();
        Run("   cross-origin probe",
            "http://" + originA.HostPort + "/redirect-cross",
            headers: Tokens(),
            proxyUrl: null,
            expect: () => null);

        var crossHit = originB.Requests.FirstOrDefault(r => r.Path.StartsWith("/real"));
        if (crossHit is null)
        {
            Console.WriteLine("   RESULT: second origin never received the request (cannot conclude)");
        }
        else
        {
            Console.WriteLine("   headers that arrived at the OTHER origin (" + originB.HostPort + "):");
            foreach (var kv in crossHit.Headers.OrderBy(k => k.Key, StringComparer.OrdinalIgnoreCase))
                Console.WriteLine("     " + kv.Key + ": " + kv.Value);
            Console.WriteLine(HasToken(crossHit)
                ? "   RESULT: *** CREDENTIAL LEAKED across origins by the engine ***"
                : "   RESULT: no identity header crossed the origin boundary");
        }

        Console.WriteLine();
        Console.WriteLine(failures == 0 ? "MPV_NETWORK_PROBE: no gaps in cases 1-3" : "MPV_NETWORK_PROBE: " + failures + " gap(s) found");
        return 0;
    }

    private static Dictionary<string, string> Tokens() => new(StringComparer.OrdinalIgnoreCase)
    {
        ["X-Emby-Token"] = "TOKEN-CANARY",
        ["Authorization"] = "MediaBrowser Token=\"TOKEN-CANARY\"",
    };

    private static bool HasToken(RequestRecord r) =>
        r.Headers.Any(kv => kv.Value.Contains("TOKEN-CANARY", StringComparison.Ordinal));

    private static Server? _currentOrigin;
    private static ProxyServer? _currentProxy;

    private static int Run(string label, string url, Dictionary<string, string>? headers, string? proxyUrl, Func<string?> expect)
    {
        Console.WriteLine(label);
        Console.WriteLine("   url=" + url + (proxyUrl is null ? "" : "  proxy=" + proxyUrl));

        // Each case starts from empty observation logs. Sharing them across cases would compare a
        // request from a previous configuration, which is how a probe ends up reporting the opposite
        // of the truth.
        _currentOrigin?.Clear();
        _currentProxy?.Clear();

        var extra = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        if (proxyUrl is not null) extra["http-proxy"] = proxyUrl;

        var options = new MpvPlayerOptions
        {
            Wid = IntPtr.Zero,
            VideoOutput = "null",
            AudioOutput = "null",
            HwDec = "no",
            ExtraOptions = extra,
        };

        try
        {
            using var player = new MpvPlayer(options);
            if (player.OptionErrors.Count > 0)
                Console.WriteLine("   OPTION ERRORS: " + string.Join("; ", player.OptionErrors));
            if (player.ObserveErrors.Count > 0)
                Console.WriteLine("   observe errors: " + string.Join("; ", player.ObserveErrors));

            var settings = new PlaybackSettings { Volume = 0, Headers = headers ?? new Dictionary<string, string>() };
            player.LoadAsync(url, settings).GetAwaiter().GetResult();
            Thread.Sleep(3500);
            player.Stop();
        }
        catch (Exception ex)
        {
            Console.WriteLine("   engine error: " + ex.GetType().Name + ": " + ex.Message);
            return 1;
        }

        if (_currentProxy is { } px)
            Console.WriteLine("   proxy saw: " + (px.Requests.Count == 0 ? "(nothing)" : string.Join(", ", px.Requests)));
        if (_currentOrigin is { } og)
            Console.WriteLine("   origin saw: " + (og.Requests.Count == 0 ? "(nothing)" : string.Join(", ", og.Requests)));

        string? problem = expect();
        Console.WriteLine(problem is null ? "   -> OK" : "   -> GAP: " + problem);
        Console.WriteLine();
        return problem is null ? 0 : 1;
    }

    private static Task ServeMedia(HttpListenerContext ctx) => ServeBytes(ctx, _media, "video/mp4");

    internal static async Task ServeBytes(HttpListenerContext ctx, byte[] data, string contentType)
    {
        var range = ctx.Request.Headers["Range"];
        int start = 0, end = data.Length - 1;
        bool partial = false;
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

    private static Task Redirect(HttpListenerContext ctx, string location)
    {
        ctx.Response.StatusCode = 302;
        ctx.Response.Headers["Location"] = location;
        ctx.Response.ContentLength64 = 0;
        ctx.Response.Close();
        return Task.CompletedTask;
    }
}

internal sealed record RequestRecord(
    string Method, string Host, string Path, IReadOnlyDictionary<string, string> Headers)
{
    public override string ToString() => Method + " " + Host + Path;
}

internal sealed class Server : IAsyncDisposable
{
    private readonly HttpListener _listener = new();
    private readonly Dictionary<string, Func<HttpListenerContext, Task>> _routes = new(StringComparer.OrdinalIgnoreCase);
    private readonly List<RequestRecord> _requests = new();
    private readonly object _gate = new();

    private Server(int port) { Port = port; }
    public int Port { get; }
    public string HostPort => "127.0.0.1:" + Port;
    public Uri BaseUri => new("http://127.0.0.1:" + Port + "/");
    public List<RequestRecord> Requests { get { lock (_gate) return _requests.ToList(); } }
    public void Clear() { lock (_gate) _requests.Clear(); }
    public void Map(string path, Func<HttpListenerContext, Task> handler) => _routes[path] = handler;

    public static Task<Server> StartAsync(string name)
    {
        for (int attempt = 0; attempt < 30; attempt++)
        {
            int port = Random.Shared.Next(20000, 60000);
            var server = new Server(port);
            server._listener.Prefixes.Add("http://127.0.0.1:" + port + "/");
            try { server._listener.Start(); }
            catch (HttpListenerException) { continue; }
            _ = Task.Run(server.Loop);
            return Task.FromResult(server);
        }
        throw new InvalidOperationException("could not bind " + name);
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
            lock (_gate)
            {
                _requests.Add(new RequestRecord(ctx.Request.HttpMethod, ctx.Request.Url?.Host ?? "", ctx.Request.Url?.AbsolutePath ?? "", headers));
            }

            string path = ctx.Request.Url?.AbsolutePath ?? "";
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

    public ValueTask DisposeAsync()
    {
        try { _listener.Stop(); _listener.Close(); } catch { }
        return ValueTask.CompletedTask;
    }
}

/// <summary>Minimal forward proxy. Records the absolute request URI so the probe can prove routing.</summary>
internal sealed class ProxyServer : IAsyncDisposable
{
    private readonly HttpListener _listener = new();
    private readonly List<string> _requests = new();
    private readonly object _gate = new();

    public int Port { get; private init; }
    public string HostPort => "127.0.0.1:" + Port;
    public List<string> Requests { get { lock (_gate) return _requests.ToList(); } }
    public void Clear() { lock (_gate) _requests.Clear(); }

    public static Task<ProxyServer> StartAsync()
    {
        for (int attempt = 0; attempt < 30; attempt++)
        {
            int port = Random.Shared.Next(20000, 60000);
            var proxy = new ProxyServer { Port = port };
            proxy._listener.Prefixes.Add("http://127.0.0.1:" + port + "/");
            try { proxy._listener.Start(); }
            catch (HttpListenerException) { continue; }
            _ = Task.Run(proxy.Loop);
            return Task.FromResult(proxy);
        }
        throw new InvalidOperationException("could not bind proxy");
    }

    private async Task Loop()
    {
        while (_listener.IsListening)
        {
            HttpListenerContext ctx;
            try { ctx = await _listener.GetContextAsync().ConfigureAwait(false); }
            catch { return; }

            string target = ctx.Request.Url?.AbsoluteUri ?? ctx.Request.RawUrl ?? "";
            lock (_gate) _requests.Add(target);

            try
            {
                using var client = new HttpClient();
                using var upstream = await client.GetAsync(target, HttpCompletionOption.ResponseHeadersRead).ConfigureAwait(false);
                ctx.Response.StatusCode = (int)upstream.StatusCode;
                foreach (var h in upstream.Headers) ctx.Response.Headers[h.Key] = string.Join(",", h.Value);
                foreach (var h in upstream.Content.Headers) ctx.Response.Headers[h.Key] = string.Join(",", h.Value);
                byte[] body = await upstream.Content.ReadAsByteArrayAsync().ConfigureAwait(false);
                ctx.Response.ContentLength64 = body.Length;
                await ctx.Response.OutputStream.WriteAsync(body);
            }
            catch
            {
                ctx.Response.StatusCode = 502;
            }
            try { ctx.Response.Close(); } catch { }
        }
    }

    public ValueTask DisposeAsync()
    {
        try { _listener.Stop(); _listener.Close(); } catch { }
        return ValueTask.CompletedTask;
    }
}

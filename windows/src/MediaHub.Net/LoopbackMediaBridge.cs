using System.Net;
using System.Text;

namespace MediaHub.Net;

/// <summary>What the bridge should fetch on behalf of the engine.</summary>
public sealed record BridgeTarget(
    Uri Url,
    IReadOnlyDictionary<string, string> Headers,
    NetworkPolicy Policy);

/// <summary>
/// Local, single-session, loopback relay that fetches a credentialed media stream on behalf of the
/// playback engine.
/// </summary>
/// <remarks>
/// <para>
/// <b>Why this exists (measured, not assumed).</b> The engine fetches the media stream with its own
/// network stack. A probe against this build recorded that
/// <list type="bullet">
/// <item>it forwards <c>--http-header-fields</c> verbatim to the target of a 302, including when the
/// target is a different origin, so an Emby token handed to it is delivered to a third-party host;</item>
/// <item>its proxy behaviour follows <c>--http-proxy</c> plus the ambient <c>http_proxy</c> /
/// <c>NO_PROXY</c> environment variables, so routing depends on the user's environment rather than on
/// the configured policy.</item>
/// </list>
/// Handing a long-lived token to the engine is therefore not safe on its own, and "the engine is
/// configured correctly" is not a property that can be asserted once and forgotten.
/// </para>
/// <para>
/// <b>The fix.</b> The engine is given a loopback URL that carries no credentials at all. The bytes
/// are fetched here, by the same <see cref="MediaSourceHttpClientFactory"/> and
/// <see cref="OriginScopedCredentialHandler"/> the API calls use. Proxy policy and redirect
/// credential scoping are then not merely <i>equal</i> to the application layer's — they are the same
/// code, so they cannot drift.
/// </para>
/// <para>
/// <b>Locking the bridge down.</b> A local HTTP endpoint that can reach credentialed media is
/// attractive to any other local process or web page, so access is restricted on several axes:
/// <list type="bullet">
/// <item>bound to the loopback interface only;</item>
/// <item>the URL contains a 256-bit random token that changes per session and is never persisted;</item>
/// <item>requests carrying an <c>Origin</c> header are refused, which blocks a web page from driving
/// the bridge through the user's browser (a browser always sends <c>Origin</c> for cross-origin
/// fetches, and a loopback endpoint is cross-origin to every site);</item>
/// <item>the <c>Host</c> header must match the bound endpoint, which blocks DNS-rebinding attempts;</item>
/// <item>the listener is closed and in-flight work cancelled when playback ends.</item>
/// </list>
/// </para>
/// <para>
/// <b>Resource discipline.</b> Nothing is cached and nothing is buffered beyond one bounded copy
/// buffer per connection. Range requests are forwarded unchanged so seeking stays a single round trip,
/// concurrency is capped so a misbehaving consumer cannot open unbounded upstream connections, and
/// disposal cancels outstanding work instead of leaving requests to drain in the background.
/// </para>
/// </remarks>
public sealed class LoopbackMediaBridge : IAsyncDisposable
{
    /// <summary>Read buffer per connection. Bounds memory without hurting throughput.</summary>
    private const int CopyBufferSize = 64 * 1024;

    /// <summary>Upstream connections the engine may hold open at once.</summary>
    private const int MaxConcurrentRequests = 4;

    private readonly HttpListener _listener;
    private readonly BridgeTarget _target;
    private readonly MediaSourceHttpClientFactory _httpFactory;
    private readonly CancellationTokenSource _shutdown = new();
    private readonly SemaphoreSlim _concurrency = new(MaxConcurrentRequests, MaxConcurrentRequests);
    private readonly List<string> _denied = new();
    private readonly object _gate = new();

    private long _bytesServed;
    private int _requestCount;
    private int _deniedCount;

    private LoopbackMediaBridge(HttpListener listener, int port, string token, BridgeTarget target)
    {
        _listener = listener;
        Port = port;
        Token = token;
        _target = target;
        _httpFactory = new MediaSourceHttpClientFactory(target.Policy);
        BridgeUri = new Uri("http://127.0.0.1:" + port + "/" + token + "/stream");
    }

    public int Port { get; }

    /// <summary>Per-session secret. Never logged, never persisted.</summary>
    public string Token { get; }

    /// <summary>URL handed to the playback engine. Contains no server credentials.</summary>
    public Uri BridgeUri { get; }

    public long BytesServed => Interlocked.Read(ref _bytesServed);
    public int RequestCount => Volatile.Read(ref _requestCount);
    public int DeniedRequestCount => Volatile.Read(ref _deniedCount);

    /// <summary>Rejected requests, for diagnostics and for the security regression tests.</summary>
    public IReadOnlyList<string> DeniedRequests { get { lock (_gate) return _denied.ToList(); } }

    /// <summary>Starts a bridge for one playback attempt.</summary>
    public static Task<LoopbackMediaBridge> StartAsync(BridgeTarget target, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(target);
        target.Policy.Validate();

        // 32 bytes of cryptographic randomness: guessing it is not a realistic attack, and it is
        // regenerated for every playback so a leaked URL is worthless after the session ends.
        string token = Convert.ToHexString(System.Security.Cryptography.RandomNumberGenerator.GetBytes(32)).ToLowerInvariant();

        HttpListener? listener = null;
        int boundPort = 0;
        foreach (string host in new[] { "127.0.0.1", "localhost" })
        {
            for (int attempt = 0; attempt < 12; attempt++)
            {
                int port = Random.Shared.Next(30000, 60000);
                var candidate = new HttpListener();
                candidate.Prefixes.Add("http://" + host + ":" + port + "/" + token + "/");
                try
                {
                    candidate.Start();
                    listener = candidate;
                    boundPort = port;
                    break;
                }
                catch (HttpListenerException)
                {
                    candidate.Close();
                }
            }
            if (listener is not null) break;
        }

        if (listener is null)
            throw new InvalidOperationException("无法建立本地媒体转发端口（回环监听失败）");

        var bridge = new LoopbackMediaBridge(listener, boundPort, token, target);
        _ = Task.Run(() => bridge.AcceptLoopAsync(bridge._shutdown.Token), CancellationToken.None);
        return Task.FromResult(bridge);
    }

    private async Task AcceptLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested && _listener.IsListening)
        {
            HttpListenerContext context;
            try
            {
                context = await _listener.GetContextAsync().ConfigureAwait(false);
            }
            catch
            {
                return; // listener stopped
            }

            _ = Task.Run(() => HandleAsync(context, ct), CancellationToken.None);
        }
    }

    private async Task HandleAsync(HttpListenerContext context, CancellationToken ct)
    {
        try
        {
            if (!await _concurrency.WaitAsync(TimeSpan.FromSeconds(30), ct).ConfigureAwait(false))
            {
                await FailAsync(context, 503, "bridge is busy").ConfigureAwait(false);
                return;
            }

            try
            {
                string? denial = Authorize(context);
                if (denial is not null)
                {
                    Interlocked.Increment(ref _deniedCount);
                    lock (_gate) _denied.Add(denial);
                    await FailAsync(context, 403, denial).ConfigureAwait(false);
                    return;
                }

                Interlocked.Increment(ref _requestCount);
                await ProxyAsync(context, ct).ConfigureAwait(false);
            }
            finally
            {
                _concurrency.Release();
            }
        }
        catch (OperationCanceledException)
        {
            try { context.Response.Abort(); } catch { }
        }
        catch (Exception ex)
        {
            // Recorded because a silent 502/socket abort here is indistinguishable from a network
            // problem at the caller, and this relay is the only hop the caller cannot observe.
            RecordError(ex);
            try { await FailAsync(context, 502, "bridge upstream failure").ConfigureAwait(false); }
            catch { try { context.Response.Abort(); } catch { } }
        }
    }

    private void RecordError(Exception ex)
    {
        lock (_gate)
        {
            if (_errors.Count >= 20) return;
            _errors.Add(ex.GetType().Name + ": " + ex.Message);
        }
    }

    private readonly List<string> _errors = new();

    /// <summary>Internal failures observed while relaying. Empty in normal operation.</summary>
    public IReadOnlyList<string> RelayedErrors { get { lock (_gate) return _errors.ToList(); } }

    /// <summary>Returns a denial reason, or null when the request may proceed.</summary>
    private string? Authorize(HttpListenerContext context)
    {
        HttpListenerRequest request = context.Request;

        // A browser always sends Origin on a cross-origin fetch, and a loopback endpoint is
        // cross-origin to every page. Rejecting it is what stops a web page from using this bridge
        // as a confused deputy to read credentialed media.
        if (request.Headers["Origin"] is { Length: > 0 } origin)
            return "refused: cross-origin (Origin: " + origin + ")";

        if (request.Headers["Referer"] is { Length: > 0 })
            return "refused: browser referrer present";

        // DNS rebinding: an attacker-controlled name resolving to 127.0.0.1 would still carry its own
        // Host header, so requiring the exact bound endpoint closes that path.
        string expectedHost = "127.0.0.1:" + Port;
        string? host = request.Headers["Host"];
        if (host is not null && !host.Equals(expectedHost, StringComparison.OrdinalIgnoreCase) &&
            !host.Equals("localhost:" + Port, StringComparison.OrdinalIgnoreCase))
        {
            return "refused: unexpected Host header (" + host + ")";
        }

        // The token lives in the path, so a request that reached us without it is already suspect.
        string path = request.Url?.AbsolutePath ?? "";
        if (!path.StartsWith("/" + Token + "/", StringComparison.Ordinal))
            return "refused: missing session token";

        return null;
    }

    private async Task ProxyAsync(HttpListenerContext context, CancellationToken ct)
    {
        HttpListenerRequest incoming = context.Request;

        using var upstreamRequest = new HttpRequestMessage(
            string.Equals(incoming.HttpMethod, "HEAD", StringComparison.OrdinalIgnoreCase) ? HttpMethod.Head : HttpMethod.Get,
            _target.Url);

        foreach (var kv in _target.Headers)
        {
            if (string.IsNullOrWhiteSpace(kv.Key)) continue;
            upstreamRequest.Headers.TryAddWithoutValidation(kv.Key, kv.Value);
        }

        // Range is forwarded verbatim so a seek stays one round trip; the upstream decides between
        // 200, 206 and 416 and we relay whichever it produced.
        string? range = incoming.Headers["Range"];
        if (!string.IsNullOrEmpty(range)) upstreamRequest.Headers.TryAddWithoutValidation("Range", range);
        string? ifRange = incoming.Headers["If-Range"];
        if (!string.IsNullOrEmpty(ifRange)) upstreamRequest.Headers.TryAddWithoutValidation("If-Range", ifRange);

        using var client = _httpFactory.CreateMediaClient();
        using HttpResponseMessage upstream = await client
            .SendAsync(upstreamRequest, HttpCompletionOption.ResponseHeadersRead, ct)
            .ConfigureAwait(false);

        HttpListenerResponse response = context.Response;
        response.StatusCode = (int)upstream.StatusCode;

        // Response headers must be set through their dedicated properties. The WebHeaderCollection
        // exposed by HttpListenerResponse treats Content-Length, Content-Type and Transfer-Encoding as
        // restricted and throws "invalid parameter" when they are assigned directly, which aborts the
        // connection and surfaces at the caller as a socket reset.
        if (upstream.Content.Headers.ContentType is { } contentType) response.ContentType = contentType.ToString();

        bool bodyAllowed = upstream.RequestMessage?.Method != HttpMethod.Head
                           && response.StatusCode is not (204 or 304);

        long? declaredLength = upstream.Content.Headers.ContentLength;
        if (!bodyAllowed)
        {
            response.ContentLength64 = 0;
            response.Close();
            return;
        }

        if (declaredLength is { } length)
        {
            // A mismatched Content-Length is itself an HttpListener error, so the length is only
            // declared when the upstream actually stated one; otherwise the response is chunked.
            response.ContentLength64 = length;
        }
        else
        {
            response.SendChunked = true;
        }

        if (upstream.Content.Headers.ContentRange is { } contentRange)
            response.AddHeader("Content-Range", contentRange.ToString());
        response.AddHeader("Accept-Ranges", "bytes");
        if (upstream.Headers.ETag is { } etag) response.AddHeader("ETag", etag.ToString());
        if (upstream.Content.Headers.LastModified is { } lastModified)
            response.AddHeader("Last-Modified", lastModified.ToString("R"));
        // The engine must never be told it may reuse a stale body.
        response.AddHeader("Cache-Control", "no-store");

        await using Stream source = await upstream.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        Stream destination = response.OutputStream;
        byte[] buffer = new byte[CopyBufferSize];
        long served = 0;
        int read;
        while ((read = await source.ReadAsync(buffer, ct).ConfigureAwait(false)) > 0)
        {
            await destination.WriteAsync(buffer.AsMemory(0, read), ct).ConfigureAwait(false);
            served += read;
        }
        Interlocked.Add(ref _bytesServed, served);
        response.Close();
    }

    private static Task FailAsync(HttpListenerContext context, int status, string message)
    {
        try
        {
            byte[] body = Encoding.UTF8.GetBytes(message);
            context.Response.StatusCode = status;
            context.Response.ContentType = "text/plain; charset=utf-8";
            context.Response.ContentLength64 = body.Length;
            context.Response.OutputStream.Write(body);
            context.Response.Close();
        }
        catch
        {
            try { context.Response.Abort(); } catch { }
        }
        return Task.CompletedTask;
    }

    public ValueTask DisposeAsync()
    {
        try { _shutdown.Cancel(); } catch { }
        try { _listener.Stop(); _listener.Close(); } catch { }
        _httpFactory.Dispose();
        _concurrency.Dispose();
        _shutdown.Dispose();
        return ValueTask.CompletedTask;
    }
}

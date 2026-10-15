using System.Net;
using System.Text;

namespace MediaHub.WebDav.Tests;

/// <summary>One request as the server saw it, including the raw (still encoded) request target.</summary>
internal sealed record WebDavSeenRequest(
    string Method,
    string RawUrl,
    string DecodedPath,
    IReadOnlyDictionary<string, string> Headers,
    string? Body,
    string? Depth,
    string? Range)
{
    public string? Authorization => Headers.TryGetValue("Authorization", out string? value) ? value : null;

    public bool HasHeader(string name) => Headers.ContainsKey(name);

    public string? Header(string name) => Headers.TryGetValue(name, out string? value) ? value : null;
}

/// <summary>One entry for the multistatus body builder.</summary>
internal sealed record WebDavEntry(
    string Href,
    bool Collection = false,
    long? Length = null,
    string? ContentType = null,
    string? DisplayName = null);

/// <summary>
/// A real HTTP server on loopback serving canned PROPFIND XML, byte ranges and redirects.
/// </summary>
/// <remarks>
/// Same approach as <c>BridgeTestOrigin</c> in MediaStreamBoundaryTests: a real listener, so the
/// evidence is what actually crossed the wire — recorded method, request target, headers, Depth and
/// whether the body was replayed on a redirect hop — rather than what the parser later produced.
/// The request target is recorded in both forms: <see cref="WebDavSeenRequest.RawUrl"/> keeps the
/// percent-encoding the client sent, which is the only way to prove a non-ASCII name round tripped.
/// </remarks>
internal sealed class WebDavTestServer : IAsyncDisposable
{
    private readonly HttpListener _listener = new();
    private readonly Dictionary<string, Func<HttpListenerContext, Task>> _routes = new(StringComparer.Ordinal);
    private readonly List<WebDavSeenRequest> _seen = new();
    private readonly object _gate = new();
    private readonly int _port;
    private readonly string _prefix;

    private WebDavTestServer(int port)
    {
        _port = port;
        _prefix = "http://127.0.0.1:" + port + "/";
    }

    /// <summary>The WebDAV collection this server serves; the source root under test.</summary>
    public const string CollectionPath = "/dav";

    public int Port => _port;

    public Uri BaseUri => new("http://127.0.0.1:" + _port + "/");

    /// <summary>Source root URL (with the trailing slash a PROPFIND target needs).</summary>
    public Uri DavUri => new(BaseUri, "dav/");

    public IReadOnlyList<WebDavSeenRequest> Requests { get { lock (_gate) return _seen.ToList(); } }

    public static Task<WebDavTestServer> StartAsync()
    {
        for (int attempt = 0; attempt < 40; attempt++)
        {
            int port = Random.Shared.Next(20000, 60000);
            var server = new WebDavTestServer(port);
            server._listener.Prefixes.Add(server._prefix);
            try
            {
                server._listener.Start();
            }
            catch (HttpListenerException)
            {
                continue;
            }
            _ = Task.Run(server.LoopAsync);
            return Task.FromResult(server);
        }
        throw new InvalidOperationException("could not bind a loopback listener");
    }

    /// <summary>Stops serving but keeps the port reservation, so the same source can come back.</summary>
    public void Stop()
    {
        try { _listener.Stop(); } catch { /* already stopped */ }
    }

    /// <summary>Resumes serving on the same port — the "server came back" half of a reconnect test.</summary>
    public void Restart()
    {
        _listener.Start();
        _ = Task.Run(LoopAsync);
    }

    public void Map(string method, string decodedPath, Func<HttpListenerContext, Task> handler)
        => _routes[method.ToUpperInvariant() + " " + decodedPath] = handler;

    public void MapAny(string decodedPath, Func<HttpListenerContext, Task> handler)
        => _routes["* " + decodedPath] = handler;

    public void MapPropfind(string decodedPath, string xml, int status = 207, string? wwwAuthenticate = null)
        => Map("PROPFIND", decodedPath, ctx => WriteAsync(ctx, status, xml, "application/xml; charset=utf-8", wwwAuthenticate));

    public void MapStatus(string method, string decodedPath, int status, string? wwwAuthenticate = null)
        => Map(method, decodedPath, ctx => WriteAsync(ctx, status, "", "text/plain", wwwAuthenticate));

    public void MapOptions(string decodedPath, int status = 200, string? davHeader = "1,2,3", string? server = "MockWebDAV/1.0")
        => Map("OPTIONS", decodedPath, ctx =>
        {
            if (davHeader is not null) ctx.Response.Headers["DAV"] = davHeader;
            if (server is not null) ctx.Response.Headers["Server"] = server;
            return WriteAsync(ctx, status, "", "text/plain");
        });

    public void MapRedirect(string decodedPath, string location, int status = 302)
        => MapAny(decodedPath, ctx =>
        {
            ctx.Response.StatusCode = status;
            ctx.Response.Headers["Location"] = location;
            ctx.Response.ContentLength64 = 0;
            ctx.Response.Close();
            return Task.CompletedTask;
        });

    /// <summary>Serves a file with or without Range support, so the 206/200 distinction is observable.</summary>
    public void MapRangeFile(string decodedPath, byte[] data, bool honourRange, string contentType = "video/x-matroska")
        => MapAny(decodedPath, ctx => ServeRangeAsync(ctx, data, honourRange, contentType));

    public bool Saw(string decodedPath)
        => Requests.Any(r => string.Equals(r.DecodedPath, decodedPath, StringComparison.OrdinalIgnoreCase));

    public bool Saw(string decodedPath, string method)
        => Requests.Any(r => string.Equals(r.DecodedPath, decodedPath, StringComparison.OrdinalIgnoreCase)
                             && string.Equals(r.Method, method, StringComparison.OrdinalIgnoreCase));

    public WebDavSeenRequest? LastRequest(string decodedPath)
        => Requests.LastOrDefault(r => string.Equals(r.DecodedPath, decodedPath, StringComparison.OrdinalIgnoreCase));

    /// <summary>Path of a request target, percent-decoded — the key routes are registered under.</summary>
    private static string DecodePath(string rawUrl)
    {
        int cut = rawUrl.IndexOfAny(new[] { '?', '#' });
        string path = cut < 0 ? rawUrl : rawUrl[..cut];
        try
        {
            return Uri.UnescapeDataString(path);
        }
        catch
        {
            return path;
        }
    }

    private async Task LoopAsync()
    {
        while (_listener.IsListening)
        {
            HttpListenerContext ctx;
            try
            {
                ctx = await _listener.GetContextAsync().ConfigureAwait(false);
            }
            catch
            {
                return;
            }

            var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            foreach (string? key in ctx.Request.Headers.AllKeys)
            {
                if (key is null) continue;
                headers[key] = ctx.Request.Headers[key] ?? "";
            }

            string? body = null;
            if (ctx.Request.HasEntityBody)
            {
                using var reader = new StreamReader(ctx.Request.InputStream, Encoding.UTF8);
                body = await reader.ReadToEndAsync().ConfigureAwait(false);
            }

            string raw = ctx.Request.RawUrl ?? "";
            string decoded = DecodePath(raw);
            var seen = new WebDavSeenRequest(
                ctx.Request.HttpMethod,
                raw,
                decoded,
                headers,
                body,
                headers.TryGetValue("Depth", out string? depth) ? depth : null,
                headers.TryGetValue("Range", out string? range) ? range : null);

            lock (_gate) _seen.Add(seen);

            string routeKey = ctx.Request.HttpMethod.ToUpperInvariant() + " " + decoded;
            if (!_routes.TryGetValue(routeKey, out Func<HttpListenerContext, Task>? handler)
                && !_routes.TryGetValue("* " + decoded, out handler))
            {
                await WriteAsync(ctx, 404, "", "text/plain").ConfigureAwait(false);
                continue;
            }

            try
            {
                await handler(ctx).ConfigureAwait(false);
            }
            catch
            {
                // A client that aborts mid-body (the oversized-response and range probes do exactly
                // that) must not take the listener down.
                try { ctx.Response.Close(); } catch { }
            }
        }
    }

    /// <summary>Responds with a status, body and optional challenge — for routes with custom logic.</summary>
    public static Task RespondAsync(HttpListenerContext ctx, int status, string body = "", string? wwwAuthenticate = null, string contentType = "application/xml; charset=utf-8")
        => WriteAsync(ctx, status, body, contentType, wwwAuthenticate);

    private static async Task WriteAsync(HttpListenerContext ctx, int status, string body, string contentType, string? wwwAuthenticate = null)
    {
        ctx.Response.StatusCode = status;
        ctx.Response.ContentType = contentType;
        if (wwwAuthenticate is not null) ctx.Response.Headers["WWW-Authenticate"] = wwwAuthenticate;
        byte[] bytes = Encoding.UTF8.GetBytes(body);
        ctx.Response.ContentLength64 = bytes.Length;
        if (bytes.Length > 0) await ctx.Response.OutputStream.WriteAsync(bytes).ConfigureAwait(false);
        ctx.Response.Close();
    }

    private static async Task ServeRangeAsync(HttpListenerContext ctx, byte[] data, bool honourRange, string contentType)
    {
        int start = 0;
        int end = data.Length - 1;
        bool partial = false;

        string? range = ctx.Request.Headers["Range"];
        if (honourRange && !string.IsNullOrEmpty(range) && range.StartsWith("bytes=", StringComparison.OrdinalIgnoreCase))
        {
            string spec = range["bytes=".Length..];
            string[] parts = spec.Split('-');
            if (int.TryParse(parts[0], out int s)) start = s;
            if (parts.Length > 1 && int.TryParse(parts[1], out int e)) end = e;
            if (start >= data.Length)
            {
                ctx.Response.StatusCode = 416;
                ctx.Response.Headers["Content-Range"] = "bytes */" + data.Length;
                ctx.Response.ContentLength64 = 0;
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
        await ctx.Response.OutputStream.WriteAsync(data.AsMemory(start, count)).ConfigureAwait(false);
        ctx.Response.Close();
    }

    public ValueTask DisposeAsync()
    {
        try { _listener.Stop(); _listener.Close(); } catch { }
        return ValueTask.CompletedTask;
    }

    // ---------------------------------------------------------------- multistatus builder

    /// <summary>Builds a well-formed 207 body from entries.</summary>
    public static string Multistatus(params WebDavEntry[] entries)
    {
        var xml = new StringBuilder();
        xml.Append("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
        xml.Append("<D:multistatus xmlns:D=\"DAV:\">");
        foreach (WebDavEntry entry in entries)
        {
            xml.Append("<D:response>");
            xml.Append("<D:href>").Append(WebUtility.HtmlEncode(entry.Href)).Append("</D:href>");
            xml.Append("<D:propstat><D:prop>");
            if (entry.Collection) xml.Append("<D:resourcetype><D:collection/></D:resourcetype>");
            else xml.Append("<D:resourcetype/>");
            if (entry.Length is { } length) xml.Append("<D:getcontentlength>").Append(length).Append("</D:getcontentlength>");
            if (entry.ContentType is not null) xml.Append("<D:getcontenttype>").Append(entry.ContentType).Append("</D:getcontenttype>");
            if (entry.DisplayName is not null) xml.Append("<D:displayname>").Append(WebUtility.HtmlEncode(entry.DisplayName)).Append("</D:displayname>");
            xml.Append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>");
            xml.Append("</D:response>");
        }
        xml.Append("</D:multistatus>");
        return xml.ToString();
    }
}

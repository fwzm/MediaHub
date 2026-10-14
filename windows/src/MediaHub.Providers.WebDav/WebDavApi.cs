using System.Globalization;
using System.Net.Http.Headers;
using System.Text;
using MediaHub.Domain;
using MediaHub.Net;

namespace MediaHub.Providers.WebDav;

/// <summary>Everything a connection probe needs from an <c>OPTIONS</c> response.</summary>
internal sealed record WebDavOptionsResponse(
    int StatusCode,
    IReadOnlyList<string> DavClasses,
    string? Server,
    IReadOnlyList<string> AuthenticateSchemes,
    string RedactedUrl);

/// <summary>How a server answered a <c>Range</c> request.</summary>
public enum WebDavRangeSupport
{
    /// <summary>Not probed yet, or the server answered something that proves nothing either way.</summary>
    Unknown = 0,

    /// <summary>The server answered 206 (or 416) for a range request: seek is real.</summary>
    Supported,

    /// <summary>The server answered 200 with the whole entity: the range request was ignored.</summary>
    NotSupported,
}

/// <summary>Raw outcome of one range probe, kept for diagnostics.</summary>
internal sealed record WebDavRangeProbe(WebDavRangeSupport Support, int StatusCode, string? ContentRange, string? AcceptRanges);

/// <summary>
/// A parsed PROPFIND response together with the URI it was actually retrieved from.
/// </summary>
/// <remarks>
/// The two differ whenever a redirect moved the request. RFC 3986 resolves hrefs against the
/// <i>retrieved</i> URI, so resolving them against the URI we asked for would mis-resolve every entry
/// after a same-origin redirect — including the collection's own entry, which then reappears in the
/// listing as a duplicate.
/// </remarks>
internal sealed record WebDavPropfindResult(IReadOnlyList<WebDavResource> Resources, Uri EffectiveUri);

/// <summary>
/// Read-only WebDAV protocol client: <c>PROPFIND</c> (RFC 4918 §9.1), <c>OPTIONS</c> and a range probe.
/// </summary>
/// <remarks>
/// <para>
/// The <see cref="HttpClient"/> is supplied by <see cref="MediaSourceHttpClientFactory"/>, never built
/// here, so every request inherits the source's <see cref="NetworkPolicy"/> and the
/// <see cref="OriginScopedCredentialHandler"/> redirect semantics. Building a client here would put the
/// request outside that chain, and the credential boundary would stop being a property of the source.
/// </para>
/// <para>
/// Redirects are followed by that handler, which drops identity headers when the target origin
/// differs. Because the hop is invisible from here, every response is checked afterwards: if the
/// final request URI left the source origin, the response is refused instead of being parsed. Without
/// that check a cross-origin redirect would silently turn into "an empty directory", which is both
/// confusing and a quiet invitation to trust content from a host the user never configured.
/// </para>
/// <para>
/// Bodies are read with a hard byte ceiling and never through <c>ReadAsStringAsync</c>: a directory
/// listing is attacker-influenced data, not a trusted document.
/// </para>
/// </remarks>
internal sealed class WebDavApi
{
    /// <summary>Property set requested on every PROPFIND. Mirrors the Android client's request body.</summary>
    internal const string PropfindBody =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
        "<D:propfind xmlns:D=\"DAV:\">" +
        "<D:prop>" +
        "<D:resourcetype/>" +
        "<D:getcontentlength/>" +
        "<D:getcontenttype/>" +
        "<D:getlastmodified/>" +
        "<D:getetag/>" +
        "<D:displayname/>" +
        "</D:prop>" +
        "</D:propfind>";

    private readonly HttpClient _http;
    private readonly string _serverId;
    private readonly WebDavSession _session;
    private readonly Uri _sourceRoot;

    public WebDavApi(HttpClient http, string serverId, WebDavSession session, Uri sourceRoot)
    {
        _http = http;
        _serverId = serverId;
        _session = session;
        _sourceRoot = sourceRoot;
    }

    /// <summary>PROPFIND. <paramref name="depth"/> 0 = the resource itself, 1 = itself plus children.</summary>
    public async Task<WebDavPropfindResult> PropfindAsync(Uri url, int depth, CancellationToken ct)
    {
        var request = new HttpRequestMessage(new HttpMethod("PROPFIND"), url)
        {
            // ByteArrayContent rather than StringContent: the origin-scoped handler replays the body
            // on a redirect hop, and only a materialised body can be replayed.
            Content = new ByteArrayContent(Encoding.UTF8.GetBytes(PropfindBody)),
        };
        request.Content.Headers.ContentType = new MediaTypeHeaderValue("application/xml") { CharSet = "utf-8" };
        request.Headers.TryAddWithoutValidation("Depth", depth.ToString(CultureInfo.InvariantCulture));
        ApplyAuthorization(request);

        using HttpResponseMessage response = await SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct)
            .ConfigureAwait(false);
        EnsureStayedOnSourceOrigin(response, url);

        if (!response.IsSuccessStatusCode) throw MapFailure(response, "PROPFIND", url);

        Uri effective = response.RequestMessage?.RequestUri ?? url;
        byte[] body = await ReadBoundedAsync(response.Content, ct).ConfigureAwait(false);
        return new WebDavPropfindResult(WebDavMultistatusParser.Parse(body, _serverId), effective);
    }

    /// <summary>
    /// OPTIONS probe. Returns the status instead of throwing, because a connection test needs to
    /// distinguish 401 from 403 from 200 rather than treat them all as failure.
    /// </summary>
    public async Task<WebDavOptionsResponse> OptionsAsync(Uri url, CancellationToken ct)
    {
        var request = new HttpRequestMessage(new HttpMethod("OPTIONS"), url);
        ApplyAuthorization(request);

        using HttpResponseMessage response = await SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct)
            .ConfigureAwait(false);
        EnsureStayedOnSourceOrigin(response, url);

        var classes = new List<string>();
        if (response.Headers.TryGetValues("DAV", out IEnumerable<string>? davValues))
        {
            foreach (string value in davValues)
            {
                foreach (string part in value.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
                {
                    if (!classes.Contains(part, StringComparer.OrdinalIgnoreCase)) classes.Add(part);
                }
            }
        }

        string? server = response.Headers.TryGetValues("Server", out IEnumerable<string>? serverValues)
            ? serverValues.FirstOrDefault()
            : null;

        return new WebDavOptionsResponse(
            (int)response.StatusCode,
            classes,
            server,
            WebDavAuthChallenge.Schemes(response.Headers),
            UrlRedactor.Redact(url));
    }

    /// <summary>
    /// Asks the server for a single byte and reports what it did with the <c>Range</c> header.
    /// </summary>
    /// <remarks>
    /// A range request answered with 200 means the server sent the whole entity and ignored the
    /// header, which is the only reliable signal that seeking will not work: <c>Accept-Ranges</c> is
    /// advisory and is present on servers that then ignore the request. 416 counts as support — the
    /// server parsed the header and found the range unsatisfiable, which it could not have done
    /// without implementing Range. The body is never read; on a Range-ignoring server that is the
    /// difference between one byte of traffic and a whole film.
    /// </remarks>
    public async Task<WebDavRangeProbe> ProbeRangeAsync(Uri url, CancellationToken ct)
    {
        var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.Range = new RangeHeaderValue(0, 0);
        ApplyAuthorization(request);

        using HttpResponseMessage response = await SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct)
            .ConfigureAwait(false);
        EnsureStayedOnSourceOrigin(response, url);

        int status = (int)response.StatusCode;
        switch (status)
        {
            case 401:
            case 403:
            case 404:
            case 410:
            case 429:
                throw MapFailure(response, "GET", url);
        }

        string? contentRange = response.Content.Headers.TryGetValues("Content-Range", out IEnumerable<string>? cr)
            ? cr.FirstOrDefault()
            : null;
        string? acceptRanges = response.Headers.TryGetValues("Accept-Ranges", out IEnumerable<string>? ar)
            ? ar.FirstOrDefault()
            : null;

        WebDavRangeSupport support = status switch
        {
            206 => WebDavRangeSupport.Supported,
            416 => WebDavRangeSupport.Supported,
            200 => WebDavRangeSupport.NotSupported,
            _ => WebDavRangeSupport.Unknown,
        };

        return new WebDavRangeProbe(support, status, contentRange, acceptRanges);
    }

    private void ApplyAuthorization(HttpRequestMessage request)
    {
        string? header = _session.AuthorizationHeader;
        if (header is null) return;
        // Typed rather than TryAddWithoutValidation: the header is then built by the framework's own
        // serialiser, so no value this method accepts can introduce a second header line.
        request.Headers.Authorization = AuthenticationHeaderValue.Parse(header);
    }

    private async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, HttpCompletionOption option, CancellationToken ct)
    {
        try
        {
            return await _http.SendAsync(request, option, ct).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            // Cancellation is not a network fault and must keep propagating.
            throw;
        }
        catch (HttpRequestException ex)
        {
            throw ProviderException.Network(_serverId, ex);
        }
    }

    /// <summary>Refuses a response whose final hop left the source origin.</summary>
    private void EnsureStayedOnSourceOrigin(HttpResponseMessage response, Uri requested)
    {
        Uri final = response.RequestMessage?.RequestUri ?? requested;
        if (string.Equals(UrlRedactor.OriginOf(final), UrlRedactor.OriginOf(_sourceRoot), StringComparison.OrdinalIgnoreCase))
        {
            return;
        }
        throw ProviderException.Connection(
            _serverId,
            "服务器把请求重定向到其它来源（" + UrlRedactor.OriginOf(final) + "），已拒绝使用其响应");
    }

    private ProviderException MapFailure(HttpResponseMessage response, string method, Uri url)
    {
        int status = (int)response.StatusCode;
        switch (status)
        {
            case 401:
                IReadOnlyList<string> schemes = WebDavAuthChallenge.Schemes(response.Headers);
                // "Log in again" is the wrong answer when no password would ever work.
                return WebDavAuthChallenge.BasicIsNotOffered(schemes)
                    ? ProviderException.AuthFailed(_serverId, WebDavAuthChallenge.DescribeUnsupported(schemes))
                    : ProviderException.AuthExpired(_serverId);
            case 404:
            case 410:
                return ProviderException.NotFound(_serverId, WebDavUrl.DisplayNameOf(url.AbsolutePath));
            case 429:
                return ProviderException.RateLimited(_serverId);
            default:
                return new ProviderHttpException(_serverId, status, method, UrlRedactor.Redact(url));
        }
    }

    private async Task<byte[]> ReadBoundedAsync(HttpContent content, CancellationToken ct)
    {
        if (content.Headers.ContentLength is { } declared && declared > WebDavMultistatusParser.MaxResponseBytes)
        {
            throw ProviderException.Parse(_serverId);
        }

        await using Stream stream = await content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        using var buffer = new MemoryStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = await stream.ReadAsync(chunk.AsMemory(), ct).ConfigureAwait(false)) > 0)
        {
            if (buffer.Length + read > WebDavMultistatusParser.MaxResponseBytes) throw ProviderException.Parse(_serverId);
            buffer.Write(chunk, 0, read);
        }

        if (buffer.Length == 0) throw ProviderException.Parse(_serverId);
        return buffer.ToArray();
    }
}

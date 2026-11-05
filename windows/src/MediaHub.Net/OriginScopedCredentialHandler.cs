using System.Net;
using System.Net.Http.Headers;

namespace MediaHub.Net;

/// <summary>
/// Enforces credential scoping across redirects.
/// </summary>
/// <remarks>
/// <para>
/// Carried over from the Android client (ADR-030), where a double-MockWebServer regression proved
/// that the media3 HTTP data source replays every original request header on every redirect hop: an
/// <c>X-Emby-Token</c> issued for an HTTPS Emby server was delivered to a third-party host over
/// cleartext HTTP.
/// </para>
/// <para>
/// The BCL does remove <c>Authorization</c> on some redirects, but that behaviour is neither
/// complete (custom token headers are left alone) nor contractual. Relying on it would make a
/// security property depend on an implementation detail, so redirects are followed here instead:
/// <c>AllowAutoRedirect</c> is disabled on the inner handler and every hop is evaluated explicitly
/// against the origin of the request that started the chain.
/// </para>
/// <para>
/// Rules:
/// <list type="bullet">
/// <item>Same origin as the first request: credentials are forwarded unchanged.</item>
/// <item>Different origin: <c>Authorization</c>, <c>Proxy-Authorization</c>, <c>Cookie</c> and every
/// Emby/Jellyfin token header are dropped.</item>
/// <item>HTTPS to HTTP: refused outright (configurable, default on).</item>
/// <item>More hops than the policy allows: the request fails with a structured error rather than
/// continuing.</item>
/// </list>
/// Content headers such as <c>Range</c> and <c>User-Agent</c> are preserved — they carry no identity.
/// </para>
/// </remarks>
public sealed class OriginScopedCredentialHandler : DelegatingHandler
{
    /// <summary>Header names that identify a caller and must never cross an origin boundary.</summary>
    public static readonly string[] SensitiveHeaders =
    {
        "Authorization",
        "Proxy-Authorization",
        "Cookie",
        "X-Emby-Token",
        "X-MediaBrowser-Token",
        "X-Emby-Authorization",
        "X-MediaBrowser-Authorization",
    };

    private readonly NetworkPolicy _policy;

    public OriginScopedCredentialHandler(NetworkPolicy policy, HttpMessageHandler inner)
        : base(inner)
    {
        _policy = policy;
    }

    /// <summary>Origin of the request that started the current chain, or null when not redirecting.</summary>
    private static readonly HttpRequestOptionsKey<Uri> ChainOriginKey = new("MediaHub.ChainOrigin");

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
    {
        request.Options.Set(ChainOriginKey, request.RequestUri!);
        return await FollowAsync(request, 0, ct).ConfigureAwait(false);
    }

    private async Task<HttpResponseMessage> FollowAsync(HttpRequestMessage request, int hop, CancellationToken ct)
    {
        HttpResponseMessage response = await base.SendAsync(request, ct).ConfigureAwait(false);

        if (!IsRedirect(response.StatusCode)) return response;

        int maxRedirects = _policy.MaxRedirects;
        if (hop >= maxRedirects)
        {
            response.Dispose();
            throw new HttpRequestException($"重定向次数超过上限（{maxRedirects}）");
        }

        Uri? location = response.Headers.Location;
        if (location is null)
        {
            string? raw = response.Headers.TryGetValues("Location", out var values) ? values.FirstOrDefault() : null;
            if (raw is not null && Uri.TryCreate(request.RequestUri, raw, out var parsed)) location = parsed;
        }
        if (location is null) return response;

        Uri current = request.RequestUri!;
        Uri target = location.IsAbsoluteUri ? location : new Uri(current, location);

        // HTTPS -> HTTP downgrade: refuse rather than silently weakening transport security.
        if (_policy.BlockHttpsDowngrade && IsDowngrade(current, target))
        {
            response.Dispose();
            throw new HttpRequestException("拒绝 HTTPS 到 HTTP 的重定向降级：" + UrlRedactor.Redact(target));
        }

        Uri chainOrigin = request.Options.TryGetValue(ChainOriginKey, out Uri? origin) && origin is not null
            ? origin
            : current;

        bool sameOrigin = string.Equals(UrlRedactor.OriginOf(chainOrigin), UrlRedactor.OriginOf(target), StringComparison.OrdinalIgnoreCase);

        var next = new HttpRequestMessage(request.Method, target)
        {
            Version = request.Version,
            VersionPolicy = request.VersionPolicy,
        };
        next.Options.Set(ChainOriginKey, chainOrigin);

        foreach (var header in request.Headers)
        {
            if (!sameOrigin && IsSensitive(header.Key)) continue;
            next.Headers.TryAddWithoutValidation(header.Key, header.Value);
        }

        if (request.Content is not null)
        {
            // The request body has already been consumed by the first hop; only replayable content
            // can be forwarded. Provider API calls that redirect are GETs, so this is safe, and a
            // non-replayable body fails loudly instead of sending an empty request.
            if (request.Content is ByteArrayContent or StringContent)
            {
                byte[] body = await request.Content.ReadAsByteArrayAsync(ct).ConfigureAwait(false);
                next.Content = new ByteArrayContent(body);
                foreach (var header in request.Content.Headers)
                {
                    next.Content.Headers.TryAddWithoutValidation(header.Key, header.Value);
                }
            }
            else
            {
                response.Dispose();
                throw new HttpRequestException("无法重放含流式请求体的重定向请求");
            }
        }

        response.Dispose();
        return await FollowAsync(next, hop + 1, ct).ConfigureAwait(false);
    }

    /// <summary>
    /// True when <paramref name="to"/> drops from HTTPS to plain HTTP.
    /// </summary>
    /// <remarks>
    /// Extracted so the policy decision is unit-testable without standing up a TLS listener; the
    /// surrounding hop logic is covered end-to-end by the loopback redirect tests.
    /// </remarks>
    public static bool IsDowngrade(Uri from, Uri to)
        => string.Equals(from.Scheme, Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase)
           && string.Equals(to.Scheme, Uri.UriSchemeHttp, StringComparison.OrdinalIgnoreCase);

    /// <summary>True when a header carries caller identity and must not cross an origin boundary.</summary>
    public static bool IsSensitive(string headerName)
    {
        foreach (string sensitive in SensitiveHeaders)
        {
            if (string.Equals(sensitive, headerName, StringComparison.OrdinalIgnoreCase)) return true;
        }
        return false;
    }

    private static bool IsRedirect(HttpStatusCode status) => status switch
    {
        HttpStatusCode.MovedPermanently => true,
        HttpStatusCode.Found => true,
        HttpStatusCode.SeeOther => true,
        HttpStatusCode.TemporaryRedirect => true,
        HttpStatusCode.PermanentRedirect => true,
        _ => false,
    };
}

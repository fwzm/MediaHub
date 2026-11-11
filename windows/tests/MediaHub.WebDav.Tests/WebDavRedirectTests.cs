using System.Text;
using MediaHub.Domain;
using MediaHub.Net;
using MediaHub.Providers;
using MediaHub.Providers.WebDav;
using Xunit;

namespace MediaHub.WebDav.Tests;

/// <summary>
/// Redirect behaviour across two loopback origins: the credential boundary under a 302/307.
/// </summary>
/// <remarks>
/// Same shape as the MediaStreamBoundaryTests redirect evidence — two real listeners, and the
/// assertion is on what the second listener actually received. The property under test is that the
/// redirect is followed for the request's <i>content</i> (method, Depth, body) while identity is
/// dropped, and that the provider additionally refuses to trust a response that arrived from an
/// origin the user never configured.
/// </remarks>
public sealed class WebDavRedirectTests
{
    private const string User = "tester";
    private const string Password = "secret";

    private static string ExpectedBasic()
        => "Basic " + Convert.ToBase64String(Encoding.UTF8.GetBytes(User + ":" + Password));

    private static ProviderHandle CreateHandle(Uri source, IReadOnlyDictionary<string, string>? credentials = null)
    {
        var server = new MediaServer("wdav-redirect", ServerType.WebDav, "NAS", new[] { new ServerEndpoint(source.ToString()) });
        return new WebDavProviderFactory().Create(server, new NetworkPolicy { Mode = ProxyMode.Direct }, credentials);
    }

    private static Dictionary<string, string> Credentials()
        => new(StringComparer.OrdinalIgnoreCase) { ["username"] = User, ["password"] = Password };

    [Fact]
    public async Task Cross_origin_redirect_does_not_carry_authorization()
    {
        await using WebDavTestServer originA = await WebDavTestServer.StartAsync();
        await using WebDavTestServer originB = await WebDavTestServer.StartAsync();

        originA.MapRedirect("/dav/", originB.DavUri.ToString(), 302);
        originB.MapPropfind("/dav/", WebDavTestServer.Multistatus(new WebDavEntry("/dav/movie.mkv", Length: 10, ContentType: "video/x-matroska")));

        ProviderHandle handle = CreateHandle(originA.DavUri, Credentials());
        var provider = (WebDavProvider)handle.Playback!;
        try
        {
            // The hop is followed by the origin-scoped handler, then refused here: content from an
            // origin that is not the configured source must not be presented as the source's content.
            ProviderException failure = await Assert.ThrowsAsync<ProviderException>(
                () => handle.Browse!.ListFolderAsync(null, new PageRequest(0, 50)));

            Assert.Equal(ProviderErrorCode.Connection, failure.Code);
            Assert.Contains("重定向", failure.Message, StringComparison.Ordinal);

            WebDavSeenRequest? first = originA.LastRequest("/dav/");
            Assert.NotNull(first);
            Assert.Equal(ExpectedBasic(), first!.Authorization);

            WebDavSeenRequest? second = originB.LastRequest("/dav/");
            Assert.NotNull(second);
            Assert.Null(second!.Authorization);
            // Only identity is dropped: the method, Depth and body still travel, or the redirect would
            // not be a redirect at all.
            Assert.Equal("PROPFIND", second.Method);
            Assert.Equal("1", second.Depth);
            Assert.Contains("propfind", second.Body ?? "", StringComparison.OrdinalIgnoreCase);
        }
        finally
        {
            provider.Dispose();
        }
    }

    [Fact]
    public async Task Cross_origin_redirect_on_a_307_does_not_carry_authorization()
    {
        await using WebDavTestServer originA = await WebDavTestServer.StartAsync();
        await using WebDavTestServer originB = await WebDavTestServer.StartAsync();

        originA.MapRedirect("/dav/", originB.DavUri.ToString(), 307);
        originB.MapPropfind("/dav/", WebDavTestServer.Multistatus(new WebDavEntry("/dav/movie.mkv", Length: 10)));

        ProviderHandle handle = CreateHandle(originA.DavUri, Credentials());
        var provider = (WebDavProvider)handle.Playback!;
        try
        {
            await Assert.ThrowsAsync<ProviderException>(() => handle.Browse!.ListFolderAsync(null, new PageRequest(0, 50)));

            WebDavSeenRequest? second = originB.LastRequest("/dav/");
            Assert.NotNull(second);
            Assert.Equal("PROPFIND", second.Method);
            Assert.Null(second.Authorization);
        }
        finally
        {
            provider.Dispose();
        }
    }

    [Fact]
    public async Task Same_origin_redirect_keeps_authorization_and_still_browses()
    {
        await using WebDavTestServer origin = await WebDavTestServer.StartAsync();

        origin.MapRedirect("/dav/", origin.BaseUri + "dav/sub/", 302);
        origin.MapPropfind("/dav/sub/", WebDavTestServer.Multistatus(
            new WebDavEntry("/dav/sub/", Collection: true),
            new WebDavEntry("/dav/sub/movie.mkv", Length: 10, ContentType: "video/x-matroska")));

        ProviderHandle handle = CreateHandle(origin.DavUri, Credentials());
        var provider = (WebDavProvider)handle.Playback!;
        try
        {
            PagedResult<MediaItem> page = await handle.Browse!.ListFolderAsync(null, new PageRequest(0, 50));

            MediaItem item = Assert.Single(page.Items);
            Assert.Equal("movie.mkv", item.Name);

            WebDavSeenRequest? followed = origin.LastRequest("/dav/sub/");
            Assert.NotNull(followed);
            // Same origin: the credential is forwarded, which is what makes this a redirect rather
            // than a blanket "strip everything on any 3xx".
            Assert.Equal(ExpectedBasic(), followed!.Authorization);
            Assert.Equal("1", followed.Depth);
        }
        finally
        {
            provider.Dispose();
        }
    }

    [Fact]
    public async Task Redirect_loop_is_bounded_by_the_policy()
    {
        await using WebDavTestServer origin = await WebDavTestServer.StartAsync();
        origin.MapRedirect("/dav/", origin.DavUri.ToString(), 302);

        var policy = new NetworkPolicy { Mode = ProxyMode.Direct, MaxRedirects = 3 };
        var server = new MediaServer("wdav-loop", ServerType.WebDav, "NAS", new[] { new ServerEndpoint(origin.DavUri.ToString()) });
        ProviderHandle handle = new WebDavProviderFactory().Create(server, policy, Credentials());
        var provider = (WebDavProvider)handle.Playback!;
        try
        {
            ProviderException failure = await Assert.ThrowsAsync<ProviderException>(
                () => handle.Browse!.ListFolderAsync(null, new PageRequest(0, 50)));

            Assert.Equal(ProviderErrorCode.Network, failure.Code);
            // 1 initial request plus at most MaxRedirects hops; a hostile chain cannot spin forever.
            Assert.Equal(policy.MaxRedirects + 1, origin.Requests.Count);
        }
        finally
        {
            provider.Dispose();
        }
    }
}

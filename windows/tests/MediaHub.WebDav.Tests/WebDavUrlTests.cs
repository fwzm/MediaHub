using MediaHub.Providers.WebDav;
using Xunit;

namespace MediaHub.WebDav.Tests;

/// <summary>
/// Boundary tests for href resolution: the point where untrusted server output becomes a request.
/// </summary>
/// <remarks>
/// Every test here exercises a rule whose removal would turn into a live defect — a credential on
/// another host, a request outside the configured subtree, or a file name that never round trips.
/// The tests assert the <i>reason</i> as well as the refusal, so a future change that refuses for the
/// wrong reason (masking a bypass as "unparseable") fails too.
/// </remarks>
public sealed class WebDavUrlTests
{
    private static readonly Uri Root = new("http://127.0.0.1:5000/dav/");
    private static readonly Uri RequestUri = new("http://127.0.0.1:5000/dav/sub/");

    private static WebDavHrefResult Resolve(string href, Uri? requestUri = null, Uri? root = null)
        => WebDavUrl.Resolve(requestUri ?? RequestUri, href, root ?? Root);

    // ---------------------------------------------------------------- traversal

    [Fact]
    public void Href_with_parent_traversal_is_rejected()
    {
        WebDavHrefResult result = Resolve("../../../etc/passwd");

        Assert.False(result.Ok);
        Assert.Equal(WebDavHrefRejection.Traversal, result.Rejection);
    }

    [Fact]
    public void Href_with_percent_encoded_traversal_is_rejected()
    {
        // "%2e%2e" is a dot-dot that a naive literal scan does not see; the BCL silently normalises it.
        WebDavHrefResult result = Resolve("%2e%2e/%2e%2e/secret.mkv");

        Assert.False(result.Ok);
        Assert.Equal(WebDavHrefRejection.Traversal, result.Rejection);
    }

    [Fact]
    public void Href_with_backslash_traversal_is_rejected()
    {
        // A proxy that maps backslashes onto slashes would turn this into a traversal after our check.
        WebDavHrefResult result = Resolve(@"..\..\windows\win.ini");

        Assert.False(result.Ok);
        Assert.Equal(WebDavHrefRejection.Traversal, result.Rejection);
    }

    [Fact]
    public void Traversal_is_still_rejected_when_the_source_root_is_the_host_root()
    {
        // Containment cannot help here: every path on the host is "inside" a source mounted at "/",
        // so the raw segment scan is the only thing standing between the href and /etc/passwd.
        WebDavHrefResult result = Resolve("../../etc/passwd", new Uri("http://127.0.0.1:5000/"), new Uri("http://127.0.0.1:5000/"));

        Assert.False(result.Ok);
        Assert.Equal(WebDavHrefRejection.Traversal, result.Rejection);
    }

    // ---------------------------------------------------------------- containment

    [Fact]
    public void Absolute_href_outside_the_source_root_is_rejected()
    {
        WebDavHrefResult result = Resolve("/other/x.mkv");

        Assert.False(result.Ok);
        Assert.Equal(WebDavHrefRejection.OutsideRoot, result.Rejection);
    }

    [Fact]
    public void Absolute_href_sharing_a_root_prefix_is_rejected()
    {
        // "/dav-evil" starts with "/dav" as a string but is not inside "/dav/".
        WebDavHrefResult result = Resolve("/dav-evil/x.mkv");

        Assert.False(result.Ok);
        Assert.Equal(WebDavHrefRejection.OutsideRoot, result.Rejection);
    }

    [Fact]
    public void Href_inside_the_root_is_accepted()
    {
        WebDavHrefResult result = Resolve("/dav/sub/movie.mkv");

        Assert.True(result.Ok);
        Assert.Equal("http://127.0.0.1:5000/dav/sub/movie.mkv", result.Url!.AbsoluteUri);
    }

    // ---------------------------------------------------------------- origin

    [Fact]
    public void Href_on_another_host_is_rejected()
    {
        WebDavHrefResult result = Resolve("http://evil.example/dav/x.mkv");

        Assert.False(result.Ok);
        Assert.Equal(WebDavHrefRejection.DifferentOrigin, result.Rejection);
    }

    [Fact]
    public void Protocol_relative_href_to_another_host_is_rejected()
    {
        WebDavHrefResult result = Resolve("//evil.example/dav/x.mkv");

        Assert.False(result.Ok);
        Assert.Equal(WebDavHrefRejection.DifferentOrigin, result.Rejection);
    }

    [Fact]
    public void Href_on_another_port_of_the_same_host_is_rejected()
    {
        // A different port is a different origin: credentials are scoped to scheme+host+port.
        WebDavHrefResult result = Resolve("http://127.0.0.1:5001/dav/x.mkv");

        Assert.False(result.Ok);
        Assert.Equal(WebDavHrefRejection.DifferentOrigin, result.Rejection);
    }

    [Fact]
    public void Href_with_embedded_credentials_is_rejected()
    {
        WebDavHrefResult result = Resolve("http://user:pass@127.0.0.1:5000/dav/x.mkv");

        Assert.False(result.Ok);
        Assert.Equal(WebDavHrefRejection.UserInfo, result.Rejection);
    }

    [Theory]
    [InlineData("file:///C:/Windows/win.ini")]
    [InlineData("javascript:alert(1)")]
    [InlineData("data:text/plain,hi")]
    [InlineData("ftp://127.0.0.1/dav/x.mkv")]
    public void Href_with_a_non_http_scheme_is_rejected(string href)
    {
        WebDavHrefResult result = Resolve(href);

        Assert.False(result.Ok);
        Assert.Equal(WebDavHrefRejection.NonHttpScheme, result.Rejection);
    }

    // ---------------------------------------------------------------- resolution

    [Fact]
    public void Relative_href_resolves_against_the_request_uri()
    {
        Assert.Equal("http://127.0.0.1:5000/dav/sub/movie.mkv", Resolve("movie.mkv").Url!.AbsoluteUri);
        Assert.Equal("http://127.0.0.1:5000/dav/sub/other/", Resolve("other/").Url!.AbsoluteUri);
        Assert.Equal("http://127.0.0.1:5000/dav/movie.mkv",
            Resolve("movie.mkv", new Uri("http://127.0.0.1:5000/dav/")).Url!.AbsoluteUri);
    }

    [Fact]
    public void Href_with_raw_spaces_and_cjk_is_encoded_and_resolved()
    {
        // RFC 4918 requires an encoded URI, but servers return raw non-ASCII paths.
        WebDavHrefResult result = Resolve("/dav/\u4e2d\u6587 \u7247.mkv");

        Assert.True(result.Ok);
        Assert.Equal("http://127.0.0.1:5000/dav/%E4%B8%AD%E6%96%87%20%E7%89%87.mkv", result.Url!.AbsoluteUri);
    }

    // ---------------------------------------------------------------- encoding round trip

    [Fact]
    public void Percent_decoding_round_trips_chinese_and_emoji()
    {
        Assert.Equal("\u4e2d\u6587 \u7247 \U0001F3AC.mkv",
            WebDavUrl.PercentDecode("%E4%B8%AD%E6%96%87%20%E7%89%87%20%F0%9F%8E%AC.mkv"));
        Assert.Equal("\u4e2d\u6587.mkv", WebDavUrl.DisplayNameOf("/dav/%E4%B8%AD%E6%96%87.mkv"));
        Assert.Equal("\U0001F3AC", WebDavUrl.DisplayNameOf("/dav/sub/%F0%9F%8E%AC"));
    }

    [Fact]
    public void Percent_decoding_does_not_treat_plus_as_space()
    {
        // Form decoding would corrupt a legal file name that contains '+'.
        Assert.Equal("a+b c.mkv", WebDavUrl.PercentDecode("a+b%20c.mkv"));
        Assert.Equal("a+b.mkv", WebDavUrl.DisplayNameOf("/dav/a+b.mkv"));
    }

    [Fact]
    public void Percent_decoding_preserves_malformed_escapes()
    {
        Assert.Equal("\u4e2d%", WebDavUrl.PercentDecode("%E4%B8%AD%"));
        Assert.Equal("100%done", WebDavUrl.PercentDecode("100%done"));
    }

    [Fact]
    public void Display_name_of_a_collection_uses_the_last_segment()
    {
        Assert.Equal("sub", WebDavUrl.DisplayNameOf("/dav/sub/"));
        Assert.Equal("sub", WebDavUrl.DisplayNameOf("/dav/sub"));
    }

    // ---------------------------------------------------------------- control characters

    [Fact]
    public void Href_with_a_literal_control_character_is_rejected()
    {
        WebDavHrefResult result = Resolve("movie\u0000mkv");

        Assert.False(result.Ok);
        Assert.Equal(WebDavHrefRejection.ControlCharacter, result.Rejection);
    }

    [Fact]
    public void Href_with_an_encoded_control_character_is_refused()
    {
        // Whether the transport rejects "%00" or our decode does, the entry must never become a request.
        WebDavHrefResult result = Resolve("/dav/a%00b.mkv");

        Assert.False(result.Ok);
    }

    [Fact]
    public void Empty_href_is_rejected()
    {
        Assert.Equal(WebDavHrefRejection.Empty, Resolve("").Rejection);
        Assert.Equal(WebDavHrefRejection.Empty, Resolve("   ").Rejection);
        Assert.Equal(WebDavHrefRejection.Empty, WebDavUrl.Resolve(RequestUri, null, Root).Rejection);
    }

    // ---------------------------------------------------------------- base normalisation

    [Fact]
    public void Base_normalisation_adds_a_trailing_slash()
    {
        Assert.True(WebDavUrl.TryNormalizeBase("http://nas.local/dav", out Uri? uri, out _));
        Assert.Equal("http://nas.local/dav/", uri!.AbsoluteUri);

        Assert.True(WebDavUrl.TryNormalizeBase("  http://nas.local/dav/  ", out Uri? trimmed, out _));
        Assert.Equal("http://nas.local/dav/", trimmed!.AbsoluteUri);
    }

    [Fact]
    public void Base_normalisation_preserves_percent_encoding()
    {
        // The path must not be re-escaped: a naive UriBuilder round trip turns %E4 into %25E4.
        Assert.True(WebDavUrl.TryNormalizeBase("http://nas.local/dav/%E4%B8%AD%E6%96%87", out Uri? uri, out _));
        Assert.Equal("http://nas.local/dav/%E4%B8%AD%E6%96%87/", uri!.AbsoluteUri);
    }

    [Theory]
    [InlineData("ftp://nas.local/dav")]
    [InlineData("file:///C:/dav")]
    [InlineData("http://user:pass@nas.local/dav")]
    [InlineData("not a url")]
    [InlineData("")]
    public void Base_normalisation_rejects_unusable_addresses(string raw)
    {
        Assert.False(WebDavUrl.TryNormalizeBase(raw, out Uri? uri, out string? error));

        Assert.Null(uri);
        Assert.False(string.IsNullOrWhiteSpace(error));
    }

    // ---------------------------------------------------------------- collection helpers

    [Fact]
    public void AsCollection_adds_a_trailing_slash_without_re_escaping()
    {
        Assert.Equal("http://nas.local/dav/sub/", WebDavUrl.AsCollection(new Uri("http://nas.local/dav/sub")).AbsoluteUri);
        Assert.Equal("http://nas.local/dav/sub/", WebDavUrl.AsCollection(new Uri("http://nas.local/dav/sub/")).AbsoluteUri);
        Assert.Equal("http://nas.local/dav/%E4%B8%AD%E6%96%87/", WebDavUrl.AsCollection(new Uri("http://nas.local/dav/%E4%B8%AD%E6%96%87")).AbsoluteUri);
    }

    [Fact]
    public void ParentCollection_returns_the_containing_collection()
    {
        Assert.Equal("http://nas.local/dav/sub/", WebDavUrl.ParentCollection(new Uri("http://nas.local/dav/sub/movie.mkv")).AbsoluteUri);
        Assert.Equal("http://nas.local/dav/", WebDavUrl.ParentCollection(new Uri("http://nas.local/dav/movie.mkv")).AbsoluteUri);
    }

    [Fact]
    public void Canonical_key_ignores_a_trailing_slash_and_case()
    {
        Assert.Equal(WebDavUrl.CanonicalKey(new Uri("http://nas.local/dav")), WebDavUrl.CanonicalKey(new Uri("http://nas.local/dav/")));
        Assert.Equal("/dav/sub", WebDavUrl.CanonicalKey(new Uri("http://nas.local/DAV/Sub/")));
    }

    [Fact]
    public void Every_rejection_reason_has_a_message()
    {
        foreach (WebDavHrefRejection reason in Enum.GetValues<WebDavHrefRejection>())
        {
            Assert.False(string.IsNullOrWhiteSpace(WebDavUrl.Describe(reason)));
        }
    }
}

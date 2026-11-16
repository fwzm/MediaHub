using System.Diagnostics;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using MediaHub.Domain;
using MediaHub.Net;
using MediaHub.Providers;
using MediaHub.Providers.OneDrive;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// The OneDrive source: PKCE, the authorization-code exchange, Graph browsing and range playback.
/// </summary>
/// <remarks>
/// Every HTTP interaction here runs against a local mock of Microsoft's endpoints, and that is the
/// honest label: no Microsoft account or Entra registration is involved. The mock pins the contract
/// this client relies on - PKCE S256, a rotating refresh token, 429 with Retry-After, 401 then retry,
/// a signed redirect that must be followed with the bearer stripped - while the real-account path is
/// left unverified rather than simulated into a pass.
/// </remarks>
public sealed class OneDriveSourceTests
{
    private const string AccessCanary = "ACCESS-TOKEN-CANARY";
    private const string RefreshCanary = "REFRESH-TOKEN-CANARY";
    private const string RotatedCanary = "REFRESH-TOKEN-ROTATED";
    private const string SignedSecret = "SIGNED-URL-SECRET";

    // ---------------------------------------------------------------- configuration

    [Fact]
    public void An_empty_client_id_is_reported_as_not_configured()
    {
        OneDriveConfig config = OneDriveConfig.FromSettings(null, null);

        Assert.False(config.IsConfigured);
        Assert.Contains("客户端 ID", config.Validate(), StringComparison.Ordinal);
        Assert.Equal(OneDriveConfig.DefaultRedirectUri, config.RedirectUri);
    }

    [Fact]
    public void A_client_id_that_is_not_a_guid_names_the_field_to_fix()
    {
        OneDriveConfig config = OneDriveConfig.FromSettings("not-a-guid", null);

        Assert.False(config.IsConfigured);
        Assert.Contains("GUID", config.Validate(), StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("https://localhost:53682/")]
    [InlineData("http://example.com/callback")]
    [InlineData("http://localhost:53682/?code=1")]
    public void A_callback_that_is_not_a_plain_loopback_address_is_refused(string redirect)
    {
        OneDriveConfig config = OneDriveConfig.FromSettings(Guid.NewGuid().ToString(), redirect);

        Assert.False(config.IsConfigured);
        Assert.Contains("回调地址", config.Validate(), StringComparison.Ordinal);
    }

    [Fact]
    public void A_registered_public_client_is_configured()
    {
        OneDriveConfig config = OneDriveConfig.FromSettings(Guid.NewGuid().ToString(), "http://localhost:53682/");

        Assert.True(config.IsConfigured);
        Assert.Contains("offline_access", config.Scope, StringComparison.Ordinal);
        Assert.Contains("common", config.AuthorizeEndpoint, StringComparison.Ordinal);
    }

    // ---------------------------------------------------------------- pkce

    [Fact]
    public void The_challenge_is_the_sha256_of_the_verifier_and_both_are_base64url()
    {
        PkceChallenge pkce = PkceChallenge.Create();

        Assert.InRange(pkce.Verifier.Length, 43, 128);
        Assert.DoesNotContain('=', pkce.Verifier);
        Assert.DoesNotMatch("[+/]", pkce.Verifier);

        byte[] hash = System.Security.Cryptography.SHA256.HashData(Encoding.ASCII.GetBytes(pkce.Verifier));
        string expected = Convert.ToBase64String(hash).TrimEnd('=').Replace('+', '-').Replace('/', '_');
        Assert.Equal(expected, pkce.Challenge);
        Assert.NotEqual(pkce.Verifier, pkce.State);
        Assert.NotEqual(pkce.Verifier, PkceChallenge.Create().Verifier);
    }

    [Fact]
    public void The_authorize_url_carries_no_secret_and_asks_for_a_code_with_s256()
    {
        var config = OneDriveConfig.FromSettings(Guid.NewGuid().ToString(), "http://localhost:53682/");
        PkceChallenge pkce = PkceChallenge.Create();

        string url = pkce.AuthorizeUri(config).ToString();

        Assert.Contains("response_type=code", url, StringComparison.Ordinal);
        Assert.Contains("code_challenge_method=S256", url, StringComparison.Ordinal);
        Assert.Contains("code_challenge=" + PkceChallenge.Base64Url(
            System.Security.Cryptography.SHA256.HashData(Encoding.ASCII.GetBytes(pkce.Verifier))), url,
            StringComparison.Ordinal);
        Assert.Contains("state=" + pkce.State, url, StringComparison.Ordinal);
        Assert.Contains("offline_access", Uri.UnescapeDataString(url), StringComparison.Ordinal);
        Assert.DoesNotContain(pkce.Verifier, url, StringComparison.Ordinal);
    }

    // ---------------------------------------------------------------- token endpoint

    [Fact]
    public async Task A_token_response_omitting_the_refresh_token_keeps_the_previous_one()
    {
        await using BridgeTestOrigin origin = await BridgeTestOrigin.StartAsync();
        origin.Map("/common/oauth2/v2.0/token", ctx => WriteJsonAsync(ctx,
            "{\"access_token\":\"new-access\",\"expires_in\":3600}"));

        var config = TestConfig(origin);
        using var http = new HttpClient();
        var client = new OneDriveTokenClient(http, config);

        OneDriveTokens tokens = await client.RefreshAsync("OLD-REFRESH", CancellationToken.None);

        Assert.Equal("new-access", tokens.AccessToken);
        Assert.Equal("OLD-REFRESH", tokens.RefreshToken);
        Assert.True(tokens.ExpiresAtUtc > DateTimeOffset.UtcNow.AddMinutes(50));
    }

    [Theory]
    [InlineData("{\"error\":\"invalid_grant\",\"error_description\":\"AADSTS70008: expired\"}", "授权已失效")]
    [InlineData("{\"error\":\"invalid_client\"}", "客户端 ID")]
    [InlineData("{\"error\":\"consent_required\"}", "尚未同意")]
    public async Task Token_errors_become_readable_sentences_without_the_service_description(
        string body, string expected)
    {
        await using BridgeTestOrigin origin = await BridgeTestOrigin.StartAsync();
        origin.Map("/common/oauth2/v2.0/token", ctx => WriteJsonAsync(ctx, body, HttpStatusCode.BadRequest));

        var config = TestConfig(origin);
        using var http = new HttpClient();
        var client = new OneDriveTokenClient(http, config);

        OneDriveAuthException error = await Assert.ThrowsAsync<OneDriveAuthException>(
            () => client.RefreshAsync("REFRESH", CancellationToken.None));

        Assert.Contains(expected, error.Message, StringComparison.Ordinal);
        Assert.DoesNotContain("AADSTS", error.Message, StringComparison.Ordinal);
        Assert.DoesNotContain("REFRESH", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public async Task A_token_pair_never_prints_its_secrets()
    {
        var tokens = new OneDriveTokens(AccessCanary, RefreshCanary, DateTimeOffset.UtcNow);

        string text = tokens.ToString();

        Assert.DoesNotContain(AccessCanary, text, StringComparison.Ordinal);
        Assert.DoesNotContain(RefreshCanary, text, StringComparison.Ordinal);
    }

    // ---------------------------------------------------------------- authorization-code flow

    [Fact]
    public async Task The_loopback_callback_is_exchanged_with_pkce_and_a_matching_state()
    {
        await using BridgeTestOrigin authority = await BridgeTestOrigin.StartAsync();
        string? form = null;
        authority.Map("/common/oauth2/v2.0/token", async ctx =>
        {
            form = await ReadBodyAsync(ctx);
            await WriteJsonAsync(ctx,
                "{\"access_token\":\"" + AccessCanary + "\",\"refresh_token\":\"" + RefreshCanary +
                "\",\"expires_in\":3600}");
        });

        int port = FreePort();
        string redirect = "http://127.0.0.1:" + port + "/callback/";
        OneDriveConfig config = TestConfig(authority) with { RedirectUri = redirect };

        using var http = new HttpClient();
        var state = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);

        Task<OneDriveTokens> signIn = OneDriveOAuth.SignInAsync(config, http, uri =>
        {
            // Stand in for the browser: echo the state back with a code, exactly as Entra would.
            string value = QueryValue(uri, "state");
            state.SetResult(value);
            _ = Task.Run(async () =>
            {
                using var browser = new HttpClient();
                for (int attempt = 0; attempt < 20; attempt++)
                {
                    try
                    {
                        await browser.GetStringAsync(redirect + "?code=THE-CODE&state=" + value);
                        return;
                    }
                    catch (HttpRequestException) { await Task.Delay(50); }
                }
            });
        }, TimeSpan.FromSeconds(10), CancellationToken.None);

        OneDriveTokens tokens = await signIn;

        Assert.Equal(AccessCanary, tokens.AccessToken);
        Assert.Equal(RefreshCanary, tokens.RefreshToken);
        Assert.False(string.IsNullOrEmpty(await state.Task));
        Assert.NotNull(form);
        Assert.Contains("grant_type=authorization_code", form, StringComparison.Ordinal);
        Assert.Contains("code=THE-CODE", form, StringComparison.Ordinal);
        Assert.Contains("code_verifier=", form, StringComparison.Ordinal);
        Assert.Contains("redirect_uri=" + Uri.EscapeDataString(redirect), form, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Cancelling_in_the_browser_is_reported_as_cancelled_not_as_a_failure()
    {
        int port = FreePort();
        string redirect = "http://127.0.0.1:" + port + "/callback/";
        OneDriveConfig config = OneDriveConfig.FromSettings(Guid.NewGuid().ToString(), redirect);

        using var http = new HttpClient();
        Task<OneDriveTokens> signIn = OneDriveOAuth.SignInAsync(config, http, uri =>
        {
            string state = QueryValue(uri, "state");
            _ = Task.Run(async () =>
            {
                using var browser = new HttpClient();
                for (int attempt = 0; attempt < 20; attempt++)
                {
                    try
                    {
                        await browser.GetStringAsync(redirect + "?error=access_denied&state=" + state);
                        return;
                    }
                    catch (HttpRequestException) { await Task.Delay(50); }
                }
            });
        }, TimeSpan.FromSeconds(10), CancellationToken.None);

        OneDriveAuthException error = await Assert.ThrowsAsync<OneDriveAuthException>(() => signIn);
        Assert.Contains("取消了 OneDrive 授权", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public async Task An_unanswered_browser_wait_times_out_with_a_retry_hint()
    {
        int port = FreePort();
        OneDriveConfig config = OneDriveConfig.FromSettings(
            Guid.NewGuid().ToString(), "http://127.0.0.1:" + port + "/callback/");

        using var http = new HttpClient();
        OneDriveAuthException error = await Assert.ThrowsAsync<OneDriveAuthException>(() =>
            OneDriveOAuth.SignInAsync(config, http, _ => { }, TimeSpan.FromMilliseconds(400), CancellationToken.None));

        Assert.Contains("超时", error.Message, StringComparison.Ordinal);
        Assert.Contains("login.microsoftonline.com", error.Message, StringComparison.Ordinal);
    }

    // ---------------------------------------------------------------- browsing

    [Fact]
    public async Task Browsing_lists_folders_first_and_keeps_only_video_files()
    {
        await using BridgeTestOrigin graph = await GraphOriginAsync();
        using var api = NewApi(graph);
        var provider = new OneDriveProvider("onedrive:acc", TestConfig(graph), api);

        PagedResult<MediaItem> page = await provider.ListFolderAsync(
            null, new PageRequest(0, 50), CancellationToken.None);

        Assert.Equal(2, page.Items.Count);
        Assert.Equal(MediaType.Folder, page.Items[0].Type);
        Assert.Equal("Shows", page.Items[0].Name);
        Assert.Equal("FOLDER-ID", page.Items[0].Path);
        Assert.Equal(MediaType.Video, page.Items[1].Type);
        Assert.Equal("movie.mp4", page.Items[1].Name);
        Assert.Equal("VIDEO-ID", page.Items[1].Id);
        Assert.Equal(TimeSpan.FromSeconds(120).Ticks, page.Items[1].RunTimeTicks);
        Assert.DoesNotContain(page.Items, i => i.Name.EndsWith(".txt", StringComparison.Ordinal));
    }

    [Fact]
    public async Task A_missing_folder_is_reported_as_deleted()
    {
        await using BridgeTestOrigin graph = await GraphOriginAsync();
        using var api = NewApi(graph);
        var provider = new OneDriveProvider("onedrive:acc", TestConfig(graph), api);

        ProviderException error = await Assert.ThrowsAsync<ProviderException>(() =>
            provider.ListFolderAsync(new MediaItem
            {
                ServerId = "onedrive:acc",
                Id = "GONE",
                Path = "GONE",
                Name = "Gone",
                Type = MediaType.Folder,
            }, new PageRequest(0, 50), CancellationToken.None));

        Assert.Equal(ProviderErrorCode.NotFound, error.Code);
        Assert.Contains("删除", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public async Task A_permission_failure_is_reported_as_no_access()
    {
        await using BridgeTestOrigin graph = await GraphOriginAsync();
        graph.Map("/v1.0/me/drive/items/LOCKED/children",
            ctx => WriteJsonAsync(ctx, "{\"error\":{\"code\":\"accessDenied\"}}", HttpStatusCode.Forbidden));

        using var api = NewApi(graph);
        var provider = new OneDriveProvider("onedrive:acc", TestConfig(graph), api);

        ProviderException error = await Assert.ThrowsAsync<ProviderException>(() =>
            provider.ListFolderAsync(new MediaItem
            {
                ServerId = "onedrive:acc",
                Id = "LOCKED",
                Path = "LOCKED",
                Name = "Locked",
                Type = MediaType.Folder,
            }, new PageRequest(0, 50), CancellationToken.None));

        Assert.Equal(ProviderErrorCode.AuthFailed, error.Code);
        Assert.Contains("没有访问权限", error.Message, StringComparison.Ordinal);
    }

    // ---------------------------------------------------------------- throttling and refresh

    [Fact]
    public async Task A_429_is_retried_after_the_service_retry_after_instead_of_failing_at_once()
    {
        await using BridgeTestOrigin graph = await GraphOriginAsync();
        int calls = 0;
        var started = DateTimeOffset.UtcNow;
        graph.Map("/v1.0/me/drive/root/children", async ctx =>
        {
            if (Interlocked.Increment(ref calls) == 1)
            {
                ctx.Response.Headers["Retry-After"] = "1";
                ctx.Response.StatusCode = 429;
                ctx.Response.ContentLength64 = 0;
                ctx.Response.Close();
                return;
            }
            await WriteJsonAsync(ctx, "{\"value\":[]}");
        });

        using var api = NewApi(graph);
        var provider = new OneDriveProvider("onedrive:acc", TestConfig(graph), api);

        PagedResult<MediaItem> page = await provider.ListFolderAsync(
            null, new PageRequest(0, 50), CancellationToken.None);

        Assert.Empty(page.Items);
        Assert.Equal(2, calls);
        Assert.True(DateTimeOffset.UtcNow - started >= TimeSpan.FromMilliseconds(900),
            "Retry-After was not honoured");
    }

    [Fact]
    public async Task Persistent_throttling_ends_in_a_readable_rate_limited_message()
    {
        await using BridgeTestOrigin graph = await GraphOriginAsync();
        int calls = 0;
        graph.Map("/v1.0/me/drive/root/children", ctx =>
        {
            Interlocked.Increment(ref calls);
            ctx.Response.Headers["Retry-After"] = "0";
            ctx.Response.StatusCode = 429;
            ctx.Response.ContentLength64 = 0;
            ctx.Response.Close();
            return Task.CompletedTask;
        });

        using var api = NewApi(graph);
        var provider = new OneDriveProvider("onedrive:acc", TestConfig(graph), api);

        ProviderException error = await Assert.ThrowsAsync<ProviderException>(() =>
            provider.ListFolderAsync(null, new PageRequest(0, 50), CancellationToken.None));

        Assert.Equal(ProviderErrorCode.RateLimited, error.Code);
        Assert.Contains("退避", error.Message, StringComparison.Ordinal);
        Assert.Equal(4, calls);
    }

    [Fact]
    public async Task A_401_is_refreshed_once_and_the_rotated_refresh_token_is_reported()
    {
        await using BridgeTestOrigin graph = await GraphOriginAsync();
        graph.Map("/common/oauth2/v2.0/token", ctx => WriteJsonAsync(ctx,
            "{\"access_token\":\"ACCESS-REFRESHED\",\"refresh_token\":\"" + RotatedCanary + "\",\"expires_in\":3600}"));

        string? seen = null;
        graph.Map("/v1.0/me/drive/root/children", ctx =>
        {
            string auth = ctx.Request.Headers["Authorization"] ?? "";
            if (auth.Contains(AccessCanary, StringComparison.Ordinal))
            {
                seen = auth;
                ctx.Response.StatusCode = 401;
                ctx.Response.ContentLength64 = 0;
                ctx.Response.Close();
                return Task.CompletedTask;
            }
            seen = auth;
            return WriteJsonAsync(ctx, "{\"value\":[]}");
        });

        string? rotated = null;
        using var api = new OneDriveApi(
            "onedrive:acc", TestConfig(graph), new HttpClient(),
            new OneDriveTokens(AccessCanary, RefreshCanary, DateTimeOffset.UtcNow.AddHours(1)),
            token => rotated = token);
        var provider = new OneDriveProvider("onedrive:acc", TestConfig(graph), api);

        await provider.ListFolderAsync(null, new PageRequest(0, 50), CancellationToken.None);

        Assert.Equal("Bearer ACCESS-REFRESHED", seen);
        Assert.Equal(RotatedCanary, rotated);
        Assert.Equal(RotatedCanary, api.RefreshToken);
    }

    [Fact]
    public async Task A_revoked_grant_is_reported_as_expired_rather_than_offline()
    {
        await using BridgeTestOrigin graph = await GraphOriginAsync();
        graph.Map("/common/oauth2/v2.0/token", ctx => WriteJsonAsync(ctx,
            "{\"error\":\"invalid_grant\",\"error_description\":\"AADSTS70008\"}", HttpStatusCode.BadRequest));
        graph.Map("/v1.0/me/drive/root/children", ctx =>
        {
            ctx.Response.StatusCode = 401;
            ctx.Response.ContentLength64 = 0;
            ctx.Response.Close();
            return Task.CompletedTask;
        });

        using var api = NewApi(graph);
        var provider = new OneDriveProvider("onedrive:acc", TestConfig(graph), api);

        ProviderException error = await Assert.ThrowsAsync<ProviderException>(() =>
            provider.ListFolderAsync(null, new PageRequest(0, 50), CancellationToken.None));

        Assert.Equal(ProviderErrorCode.AuthExpired, error.Code);
        Assert.Contains("重新登录", error.Message, StringComparison.Ordinal);
    }

    // ---------------------------------------------------------------- playback

    [Fact]
    public async Task Playback_hands_the_relay_a_content_url_and_keeps_the_token_out_of_it()
    {
        await using BridgeTestOrigin graph = await GraphOriginAsync();
        using var api = NewApi(graph);
        var provider = new OneDriveProvider("onedrive:acc", TestConfig(graph), api);

        PlaybackSource source = await provider.ResolvePlaybackAsync(new MediaItem
        {
            ServerId = "onedrive:acc",
            Id = "VIDEO-ID",
            Path = "VIDEO-ID",
            Name = "movie.mp4",
            Type = MediaType.Video,
        }, new PlaybackOptions { StartPositionTicks = TimeSpan.FromSeconds(30).Ticks }, CancellationToken.None);

        Assert.EndsWith("/v1.0/me/drive/items/VIDEO-ID/content", source.Url.AbsoluteUri, StringComparison.Ordinal);
        Assert.DoesNotContain(SignedSecret, source.Url.AbsoluteUri, StringComparison.Ordinal);
        Assert.DoesNotContain(AccessCanary, source.Url.AbsoluteUri, StringComparison.Ordinal);
        Assert.Empty(source.Headers);
        Assert.Equal(TimeSpan.FromSeconds(30).Ticks, source.StartPositionTicks);
        Assert.Equal("mp4", source.Container);

        Assert.Equal("Bearer " + AccessCanary, provider.StreamRequestHeaders["Authorization"]);
        Assert.Equal(TestConfig(graph).GraphBase, provider.StreamHeaderOrigin);
    }

    [Fact]
    public async Task A_deleted_file_fails_before_the_engine_opens_rather_than_as_a_load_error()
    {
        await using BridgeTestOrigin graph = await GraphOriginAsync();
        using var api = NewApi(graph);
        var provider = new OneDriveProvider("onedrive:acc", TestConfig(graph), api);

        ProviderException error = await Assert.ThrowsAsync<ProviderException>(() =>
            provider.ResolvePlaybackAsync(new MediaItem
            {
                ServerId = "onedrive:acc",
                Id = "GONE",
                Path = "GONE",
                Name = "gone.mp4",
                Type = MediaType.Video,
            }, new PlaybackOptions(), CancellationToken.None));

        Assert.Equal(ProviderErrorCode.NotFound, error.Code);
        Assert.Contains("删除", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Range_and_the_signed_redirect_work_through_the_relay_without_leaking_the_bearer()
    {
        await using BridgeTestOrigin graph = await GraphOriginAsync();
        // A different origin is the point: the real service redirects to a storage host, and that hop
        // is where the bearer must disappear.
        await using BridgeTestOrigin cdn = await BridgeTestOrigin.StartAsync();
        byte[] body = Encoding.ASCII.GetBytes(new string('x', 4096) + "RANGE-TARGET" + new string('y', 4096));

        // /content answers with a short-lived signed redirect; the storage host serves the range.
        graph.Map("/v1.0/me/drive/items/VIDEO-ID/content", ctx =>
        {
            ctx.Response.StatusCode = 302;
            ctx.Response.RedirectLocation = cdn.BaseUri + "cdn/VIDEO-ID?tempauth=" + SignedSecret;
            ctx.Response.ContentLength64 = 0;
            ctx.Response.Close();
            return Task.CompletedTask;
        });
        cdn.Map("/cdn/VIDEO-ID", ctx => BridgeTestOrigin.ServeRangeAsync(ctx, body));

        using var api = NewApi(graph);
        var provider = new OneDriveProvider("onedrive:acc", TestConfig(graph), api);
        PlaybackSource source = await provider.ResolvePlaybackAsync(new MediaItem
        {
            ServerId = "onedrive:acc",
            Id = "VIDEO-ID",
            Path = "VIDEO-ID",
            Name = "movie.mp4",
            Type = MediaType.Video,
        }, new PlaybackOptions(), CancellationToken.None);

        await using LoopbackMediaBridge bridge = await LoopbackMediaBridge.StartAsync(
            new BridgeTarget(source.Url, provider.StreamRequestHeaders, new NetworkPolicy { Mode = ProxyMode.Direct }));

        using var engine = new HttpClient { Timeout = TimeSpan.FromSeconds(15) };
        using var request = new HttpRequestMessage(HttpMethod.Get, bridge.BridgeUri);
        request.Headers.Range = new System.Net.Http.Headers.RangeHeaderValue(4096, 4107);
        using HttpResponseMessage response = await engine.SendAsync(request);

        Assert.Equal(HttpStatusCode.PartialContent, response.StatusCode);
        Assert.Equal("RANGE-TARGET", await response.Content.ReadAsStringAsync());

        // The signed query reached the storage host, the bearer did not: that is the isolation the
        // redirect policy owns, not a promise made by this provider.
        var cdnRequests = cdn.RequestsFor("/cdn/VIDEO-ID");
        Assert.Single(cdnRequests);
        Assert.Contains("tempauth=" + SignedSecret, cdnRequests[0].Query, StringComparison.Ordinal);
        Assert.False(cdnRequests[0].Headers.ContainsKey("Authorization"));

        // The Graph origin did get the bearer, over the same relay session.
        Assert.True(graph.SawPathWithHeader("/v1.0/me/drive/items/VIDEO-ID/content", "Authorization",
            "Bearer " + AccessCanary));

        // The engine only ever knew the relay address, so no signature can be replayed from it.
        Assert.DoesNotContain(SignedSecret, bridge.BridgeUri.AbsoluteUri, StringComparison.Ordinal);
    }

    [Fact]
    public async Task An_unreachable_service_is_reported_as_a_network_problem()
    {
        int port = FreePort();
        OneDriveConfig config = OneDriveConfig.FromSettings(Guid.NewGuid().ToString(), null) with
        {
            GraphBase = new Uri("http://127.0.0.1:" + port + "/v1.0/"),
        };

        using var api = new OneDriveApi(
            "onedrive:acc", config, new HttpClient { Timeout = TimeSpan.FromSeconds(5) },
            new OneDriveTokens(AccessCanary, RefreshCanary, DateTimeOffset.UtcNow.AddHours(1)), null);
        var provider = new OneDriveProvider("onedrive:acc", config, api);

        ProviderException error = await Assert.ThrowsAsync<ProviderException>(() =>
            provider.ListFolderAsync(null, new PageRequest(0, 50), CancellationToken.None));

        Assert.Equal(ProviderErrorCode.Network, error.Code);
        Assert.Contains("网络", error.Message, StringComparison.Ordinal);
    }

    // ---------------------------------------------------------------- helpers

    private static OneDriveConfig TestConfig(BridgeTestOrigin graph)
        => OneDriveConfig.FromSettings(Guid.NewGuid().ToString(), "http://localhost:53682/") with
        {
            GraphBase = new Uri(graph.BaseUri, "v1.0/"),
            AuthorityBase = graph.BaseUri,
        };

    private static OneDriveApi NewApi(BridgeTestOrigin graph)
        => new("onedrive:acc", TestConfig(graph), new HttpClient(),
            new OneDriveTokens(AccessCanary, RefreshCanary, DateTimeOffset.UtcNow.AddHours(1)), null);

    /// <summary>Root listing with one folder, one video and one text file; item metadata; profile.</summary>
    private static async Task<BridgeTestOrigin> GraphOriginAsync()
    {
        BridgeTestOrigin graph = await BridgeTestOrigin.StartAsync();

        graph.Map("/v1.0/me", ctx => WriteJsonAsync(ctx,
            "{\"id\":\"ACC\",\"displayName\":\"Test\",\"userPrincipalName\":\"test@example.com\"}"));

        graph.Map("/v1.0/me/drive/root/children", ctx => WriteJsonAsync(ctx,
            "{\"value\":[" +
            "{\"id\":\"FOLDER-ID\",\"name\":\"Shows\",\"folder\":{\"childCount\":2}}," +
            "{\"id\":\"VIDEO-ID\",\"name\":\"movie.mp4\",\"size\":1024,\"file\":{\"mimeType\":\"video/mp4\"}," +
            "\"video\":{\"duration\":120000}}," +
            "{\"id\":\"TEXT-ID\",\"name\":\"notes.txt\",\"file\":{\"mimeType\":\"text/plain\"}}" +
            "]}"));

        graph.Map("/v1.0/me/drive/items/LOCKED/children", ctx => WriteJsonAsync(ctx,
            "{\"error\":{\"code\":\"accessDenied\"}}", HttpStatusCode.Forbidden));

        graph.Map("/v1.0/me/drive/items/VIDEO-ID", ctx => WriteJsonAsync(ctx,
            "{\"id\":\"VIDEO-ID\",\"name\":\"movie.mp4\",\"size\":1024,\"file\":{\"mimeType\":\"video/mp4\"}," +
            "\"video\":{\"duration\":120000}}"));

        // Any other item id is treated as deleted.
        graph.Map("/v1.0/me/drive/items/GONE", ctx => WriteJsonAsync(ctx,
            "{\"error\":{\"code\":\"itemNotFound\"}}", HttpStatusCode.NotFound));

        return graph;
    }

    private static async Task WriteJsonAsync(
        HttpListenerContext context, string json, HttpStatusCode status = HttpStatusCode.OK)
    {
        byte[] bytes = Encoding.UTF8.GetBytes(json);
        context.Response.StatusCode = (int)status;
        context.Response.ContentType = "application/json; charset=utf-8";
        context.Response.ContentLength64 = bytes.Length;
        await context.Response.OutputStream.WriteAsync(bytes).ConfigureAwait(false);
        context.Response.Close();
    }

    private static async Task<string> ReadBodyAsync(HttpListenerContext context)
    {
        using var reader = new StreamReader(context.Request.InputStream, Encoding.UTF8);
        return await reader.ReadToEndAsync().ConfigureAwait(false);
    }

    private static string QueryValue(Uri uri, string key)
    {
        foreach (string pair in uri.Query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            int equals = pair.IndexOf('=');
            if (equals <= 0) continue;
            if (string.Equals(Uri.UnescapeDataString(pair[..equals]), key, StringComparison.Ordinal))
                return Uri.UnescapeDataString(pair[(equals + 1)..]);
        }
        return "";
    }

    /// <summary>A port nothing is listening on, used for the timeout and offline cases.</summary>
    private static int FreePort()
    {
        for (int attempt = 0; attempt < 50; attempt++)
        {
            int port = Random.Shared.Next(20000, 60000);
            try
            {
                using var probe = new System.Net.Sockets.TcpListener(IPAddress.Loopback, port);
                probe.Start();
                probe.Stop();
                return port;
            }
            catch (System.Net.Sockets.SocketException)
            {
                // Taken; try another.
            }
        }
        throw new InvalidOperationException("could not find a free loopback port");
    }
}

using System.Net;
using System.Text;
using MediaHub.Net;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// End-to-end tests for the redirect credential rules, driven by two real HTTP listeners on different
/// ports so that "different origin" is a property of the test, not an assumption.
/// </summary>
public sealed class RedirectCredentialTests : IDisposable
{
    private readonly List<HttpListener> _listeners = new();

    private sealed record Hop(string Path, int Status, string? Location);

    /// <summary>Starts a listener that answers according to <paramref name="hop"/>.</summary>
    private (HttpListener Listener, int Port, TaskCompletionSource<HttpRequestMessage> Seen) StartServer(Hop hop)
    {
        for (int attempt = 0; attempt < 20; attempt++)
        {
            int port = Random.Shared.Next(20000, 60000);
            var listener = new HttpListener();
            listener.Prefixes.Add("http://127.0.0.1:" + port + "/");
            try { listener.Start(); }
            catch (HttpListenerException) { continue; }

            _listeners.Add(listener);
            var seen = new TaskCompletionSource<HttpRequestMessage>(TaskCreationOptions.RunContinuationsAsynchronously);

            _ = Task.Run(async () =>
            {
                try
                {
                    var context = await listener.GetContextAsync().ConfigureAwait(false);
                    var captured = new HttpRequestMessage(new HttpMethod(context.Request.HttpMethod), context.Request.Url);
                    foreach (string key in context.Request.Headers.AllKeys)
                    {
                        if (key is null) continue;
                        captured.Headers.TryAddWithoutValidation(key, context.Request.Headers[key]);
                    }
                    seen.TrySetResult(captured);

                    context.Response.StatusCode = hop.Status;
                    if (hop.Location is not null) context.Response.Headers["Location"] = hop.Location;
                    context.Response.ContentLength64 = 0;
                    context.Response.Close();
                }
                catch { /* listener torn down */ }
            });

            return (listener, port, seen);
        }
        throw new InvalidOperationException("could not bind a loopback listener");
    }

    [Fact]
    public async Task Same_origin_redirect_keeps_credentials()
    {
        // A redirect that stays on the same scheme+host+port must not disturb the session: this is
        // the ordinary "server normalises the path" case, and stripping the token here would break
        // playback on servers that do it.
        var capture = new TaskCompletionSource<HttpRequestMessage>(TaskCreationOptions.RunContinuationsAsynchronously);
        int port = StartSameOriginChain(capture);
        string baseUrl = "http://127.0.0.1:" + port;

        using var factory = new MediaSourceHttpClientFactory(NetworkPolicy.FollowSystem);
        using var client = factory.CreateApiClient();

        using var request = new HttpRequestMessage(HttpMethod.Get, baseUrl + "/start");
        request.Headers.TryAddWithoutValidation("X-Emby-Token", "TOKEN-ABC");
        request.Headers.TryAddWithoutValidation("Authorization", "MediaBrowser Token=\"TOKEN-ABC\"");
        await client.SendAsync(request);

        var observed = await capture.Task.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.Equal("TOKEN-ABC", observed.Headers.GetValues("X-Emby-Token").Single());
        Assert.Single(observed.Headers.GetValues("Authorization"));
    }

    /// <summary>One listener that redirects /start to /final on the same origin and captures /final.</summary>
    private int StartSameOriginChain(TaskCompletionSource<HttpRequestMessage> capture)
    {
        for (int attempt = 0; attempt < 20; attempt++)
        {
            int port = Random.Shared.Next(20000, 60000);
            var listener = new HttpListener();
            listener.Prefixes.Add("http://127.0.0.1:" + port + "/");
            try { listener.Start(); }
            catch (HttpListenerException) { continue; }
            _listeners.Add(listener);

            _ = Task.Run(async () =>
            {
                while (listener.IsListening)
                {
                    HttpListenerContext context;
                    try { context = await listener.GetContextAsync().ConfigureAwait(false); }
                    catch { return; }

                    if (context.Request.Url!.AbsolutePath == "/start")
                    {
                        context.Response.StatusCode = 302;
                        context.Response.Headers["Location"] = "http://127.0.0.1:" + port + "/final";
                    }
                    else
                    {
                        var captured = new HttpRequestMessage(HttpMethod.Get, context.Request.Url);
                        foreach (string key in context.Request.Headers.AllKeys)
                        {
                            if (key is null) continue;
                            captured.Headers.TryAddWithoutValidation(key, context.Request.Headers[key]);
                        }
                        capture.TrySetResult(captured);
                        context.Response.StatusCode = 200;
                    }
                    context.Response.ContentLength64 = 0;
                    context.Response.Close();
                }
            });

            return port;
        }
        throw new InvalidOperationException("could not bind a loopback listener");
    }

    [Fact]
    public async Task Cross_origin_redirect_strips_every_identity_header()
    {
        var (_, port, seen) = StartServer(new Hop("/final", 200, null));
        var (redirector, redirectPort, _) = StartServer(new Hop("/start", 302, "http://127.0.0.1:" + port + "/final"));

        using var factory = new MediaSourceHttpClientFactory(NetworkPolicy.FollowSystem);
        using var client = factory.CreateApiClient();

        using var request = new HttpRequestMessage(HttpMethod.Get, "http://127.0.0.1:" + redirectPort + "/start");
        request.Headers.TryAddWithoutValidation("X-Emby-Token", "TOKEN-ABC");
        request.Headers.TryAddWithoutValidation("X-MediaBrowser-Token", "TOKEN-ABC");
        request.Headers.TryAddWithoutValidation("X-Emby-Authorization", "MediaBrowser Token=\"TOKEN-ABC\"");
        request.Headers.TryAddWithoutValidation("Authorization", "Bearer TOKEN-ABC");
        request.Headers.TryAddWithoutValidation("Cookie", "session=SECRET");
        request.Headers.TryAddWithoutValidation("Proxy-Authorization", "Basic SECRET");
        await client.SendAsync(request);

        var observed = await seen.Task.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.False(observed.Headers.Contains("X-Emby-Token"));
        Assert.False(observed.Headers.Contains("X-MediaBrowser-Token"));
        Assert.False(observed.Headers.Contains("X-Emby-Authorization"));
        Assert.False(observed.Headers.Contains("Authorization"));
        Assert.False(observed.Headers.Contains("Cookie"));
        Assert.False(observed.Headers.Contains("Proxy-Authorization"));
        redirector.Stop();
    }

    [Fact]
    public async Task Safe_media_headers_survive_a_cross_origin_hop()
    {
        var (_, port, seen) = StartServer(new Hop("/final", 200, null));
        var (redirector, redirectPort, _) = StartServer(new Hop("/start", 302, "http://127.0.0.1:" + port + "/final"));

        using var factory = new MediaSourceHttpClientFactory(NetworkPolicy.FollowSystem);
        using var client = factory.CreateMediaClient();

        using var request = new HttpRequestMessage(HttpMethod.Get, "http://127.0.0.1:" + redirectPort + "/start");
        request.Headers.Range = new System.Net.Http.Headers.RangeHeaderValue(0, 1023);
        request.Headers.TryAddWithoutValidation("X-Emby-Token", "TOKEN-ABC");
        await client.SendAsync(request);

        var observed = await seen.Task.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.Equal("bytes=0-1023", observed.Headers.Range!.ToString());
        Assert.False(observed.Headers.Contains("X-Emby-Token"));
        redirector.Stop();
    }

    [Fact]
    public async Task Redirect_chain_is_bounded()
    {
        // A server that always redirects to itself: without a bound this request would never end.
        var listener = new HttpListener();
        int port = Random.Shared.Next(20000, 60000);
        listener.Prefixes.Add("http://127.0.0.1:" + port + "/");
        listener.Start();
        _listeners.Add(listener);

        _ = Task.Run(async () =>
        {
            while (listener.IsListening)
            {
                try
                {
                    var ctx = await listener.GetContextAsync().ConfigureAwait(false);
                    ctx.Response.StatusCode = 302;
                    ctx.Response.Headers["Location"] = "http://127.0.0.1:" + port + "/loop";
                    ctx.Response.ContentLength64 = 0;
                    ctx.Response.Close();
                }
                catch { return; }
            }
        });

        var policy = NetworkPolicy.FollowSystem with { MaxRedirects = 3 };
        using var factory = new MediaSourceHttpClientFactory(policy);
        using var client = factory.CreateApiClient();

        await Assert.ThrowsAsync<HttpRequestException>(() => client.GetAsync("http://127.0.0.1:" + port + "/loop"));
    }

    [Theory]
    [InlineData("https://a.example.com/x", "http://a.example.com/y", true)]
    [InlineData("http://a.example.com/x", "https://a.example.com/y", false)]
    [InlineData("https://a.example.com/x", "https://b.example.com/y", false)]
    public void Downgrade_detection(string from, string to, bool expected)
        => Assert.Equal(expected, OriginScopedCredentialHandler.IsDowngrade(new Uri(from), new Uri(to)));

    [Fact]
    public void Sensitive_header_list_covers_every_provider_token_header()
    {
        foreach (string header in new[] { "X-Emby-Token", "X-MediaBrowser-Token", "X-Emby-Authorization", "Authorization", "Cookie" })
        {
            Assert.True(OriginScopedCredentialHandler.IsSensitive(header), header);
            Assert.True(OriginScopedCredentialHandler.IsSensitive(header.ToLowerInvariant()), header + " (lowercase)");
        }
        Assert.False(OriginScopedCredentialHandler.IsSensitive("Range"));
        Assert.False(OriginScopedCredentialHandler.IsSensitive("User-Agent"));
    }

    public void Dispose()
    {
        foreach (var listener in _listeners)
        {
            try { listener.Stop(); listener.Close(); } catch { }
        }
    }
}

public sealed class NetworkPolicyTests
{
    [Fact]
    public void Explicit_mode_requires_a_proxy_url()
    {
        var policy = new NetworkPolicy { Mode = ProxyMode.Explicit };
        Assert.Throws<ArgumentException>(policy.Validate);
    }

    [Fact]
    public void Explicit_mode_rejects_a_relative_proxy_url()
    {
        var policy = new NetworkPolicy { Mode = ProxyMode.Explicit, ProxyUrl = "127.0.0.1:7890" };
        Assert.Throws<ArgumentException>(policy.Validate);
    }

    [Fact]
    public void Direct_mode_disables_proxy_resolution()
    {
        var handler = NetworkPolicyHandlerFactory.CreateHandler(new NetworkPolicy { Mode = ProxyMode.Direct });
        Assert.False(handler.UseProxy);
        Assert.Null(handler.Proxy);
    }

    [Fact]
    public void Explicit_mode_installs_the_configured_proxy()
    {
        var handler = NetworkPolicyHandlerFactory.CreateHandler(new NetworkPolicy
        {
            Mode = ProxyMode.Explicit,
            ProxyUrl = "http://127.0.0.1:7890",
        });
        Assert.True(handler.UseProxy);
        Assert.IsType<WebProxy>(handler.Proxy);
    }

    [Fact]
    public void Auto_redirect_is_disabled_so_every_hop_can_be_inspected()
    {
        var handler = NetworkPolicyHandlerFactory.CreateHandler(NetworkPolicy.FollowSystem);
        Assert.False(handler.AllowAutoRedirect);
    }

    [Fact]
    public void Policy_describes_itself_for_the_diagnostics_panel()
    {
        Assert.Contains("直连", new NetworkPolicy { Mode = ProxyMode.Direct }.Describe());
        Assert.Contains("127.0.0.1", new NetworkPolicy { Mode = ProxyMode.Explicit, ProxyUrl = "http://127.0.0.1:7890" }.Describe());
        Assert.Contains("系统", NetworkPolicy.FollowSystem.Describe());
    }
}

public sealed class UrlRedactorTests
{
    [Fact]
    public void Credential_query_values_are_masked_but_the_url_stays_readable()
    {
        string redacted = UrlRedactor.Redact(new Uri("https://host.example/emby/Items?api_key=SECRET&Limit=50"));
        Assert.DoesNotContain("SECRET", redacted);
        Assert.Contains("/emby/Items", redacted);
        Assert.Contains("Limit=50", redacted);
    }

    [Fact]
    public void User_info_is_removed()
    {
        string redacted = UrlRedactor.Redact(new Uri("https://user:pass@host.example/path"));
        Assert.DoesNotContain("pass", redacted);
        Assert.DoesNotContain("user", redacted);
    }

    [Fact]
    public void Default_port_is_omitted_and_explicit_ports_kept()
    {
        Assert.Equal("https://host.example/a", UrlRedactor.Redact(new Uri("https://host.example:443/a")));
        Assert.Equal("http://host.example:8096/a", UrlRedactor.Redact(new Uri("http://host.example:8096/a")));
    }

    [Fact]
    public void Null_is_safe() => Assert.Equal("", UrlRedactor.Redact(null));
}

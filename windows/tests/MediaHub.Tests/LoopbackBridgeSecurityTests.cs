using System.Net;
using System.Net.Sockets;
using System.Text;
using MediaHub.Net;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Adversarial tests for the loopback relay.
/// </summary>
/// <remarks>
/// The relay listens on a port any local process can reach, so "it is localhost" is not a security
/// argument. Each test here corresponds to one way that assumption could be wrong, and each is
/// written so that removing the protection makes it fail rather than merely changing a status code.
/// </remarks>
public sealed class LoopbackBridgeSecurityTests
{
    private static readonly byte[] Payload = Encoding.ASCII.GetBytes(string.Concat(
        Enumerable.Range(0, 4000).Select(i => (char)('a' + (i % 26)))));

    private static BridgeTarget Target(Uri url, NetworkPolicy? policy = null)
        => new(url, CanaryHeaders(), policy ?? new NetworkPolicy { Mode = ProxyMode.Direct });

    private static Dictionary<string, string> CanaryHeaders() => new(StringComparer.OrdinalIgnoreCase)
    {
        ["X-Emby-Token"] = "TOKEN-CANARY",
    };

    private static async Task<BridgeTestOrigin> OriginAsync(string path = "stream", byte[]? body = null, bool acceptRanges = true)
    {
        BridgeTestOrigin origin = await BridgeTestOrigin.StartAsync();
        byte[] data = body ?? Payload;
        origin.Map("/" + path, ctx => BridgeTestOrigin.ServeRangeAsync(ctx, data, "video/mp4", acceptRanges));
        return origin;
    }

    // ------------------------------------------------------------ authorization

    [Fact]
    public async Task A_request_with_no_Host_header_is_refused()
    {
        await using BridgeTestOrigin origin = await OriginAsync();
        await using LoopbackMediaBridge bridge = await LoopbackMediaBridge.StartAsync(Target(new Uri(origin.BaseUri, "stream")));

        // Raw socket: HttpClient always sends Host, so the omission has to be built by hand. The path
        // is well formed on purpose — this test must exercise the Host check, not the listener's own
        // prefix rejection, which would otherwise mask it.
        string raw = await RawRequestAsync(bridge.Port,
            "GET /" + bridge.Token + "/stream HTTP/1.0\r\n\r\n");

        Assert.Contains("403", raw);
        Assert.False(origin.SawPath("/stream"), "the relay must not reach the upstream for a malformed request");
    }

    [Fact]
    public async Task A_wrong_session_token_is_refused()
    {
        await using BridgeTestOrigin origin = await OriginAsync();
        await using LoopbackMediaBridge bridge = await LoopbackMediaBridge.StartAsync(Target(new Uri(origin.BaseUri, "stream")));

        string wrong = new string('0', bridge.Token.Length);
        using var client = new HttpClient();
        HttpResponseMessage response = await client.GetAsync("http://127.0.0.1:" + bridge.Port + "/" + wrong + "/stream");

        // Which layer refuses is itself the finding: the listener is registered on a prefix that
        // contains the token, so a wrong token never reaches a handler at all and is answered with
        // 400 by the HTTP stack. The explicit constant-time comparison inside Authorize is the second
        // line of defence, for a request that does match the prefix shape. This asserts the property
        // that matters — refused, and the upstream is never contacted — rather than a specific code.
        Assert.False(response.IsSuccessStatusCode, "a wrong token must not be served");
        Assert.False(origin.SawPath("/stream"), "a wrong token must not reach the upstream");
        Assert.NotEqual(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task A_request_with_no_token_segment_is_refused()
    {
        await using BridgeTestOrigin origin = await OriginAsync();
        await using LoopbackMediaBridge bridge = await LoopbackMediaBridge.StartAsync(Target(new Uri(origin.BaseUri, "stream")));

        using var client = new HttpClient();
        HttpResponseMessage response = await client.GetAsync("http://127.0.0.1:" + bridge.Port + "/stream");

        Assert.False(response.IsSuccessStatusCode, "a request without the token must not be served");
        Assert.False(origin.SawPath("/stream"));
    }

    [Fact]
    public async Task An_unexpected_Host_header_is_refused()
    {
        await using BridgeTestOrigin origin = await OriginAsync();
        await using LoopbackMediaBridge bridge = await LoopbackMediaBridge.StartAsync(Target(new Uri(origin.BaseUri, "stream")));

        // DNS rebinding: the name resolves to 127.0.0.1 but the Host is the attacker's name.
        string raw = await RawRequestAsync(bridge.Port,
            "GET /" + bridge.Token + "/stream HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n");

        Assert.Contains("403", raw);
        Assert.False(origin.SawPath("/stream"));
    }

    // ------------------------------------------------------------ scope of authority

    [Fact]
    public async Task A_client_supplied_path_cannot_change_which_url_is_fetched()
    {
        await using BridgeTestOrigin origin = await OriginAsync();
        await using BridgeTestOrigin other = await OriginAsync("elsewhere");
        await using LoopbackMediaBridge bridge = await LoopbackMediaBridge.StartAsync(Target(new Uri(origin.BaseUri, "stream")));

        using var client = new HttpClient();
        // Every shape a client might use to try to make the relay an open proxy.
        foreach (string suffix in new[]
                 {
                     "../elsewhere",
                     "..%2felsewhere",
                     "%2e%2e/elsewhere",
                     "//127.0.0.1:" + other.Port + "/elsewhere",
                     "http://127.0.0.1:" + other.Port + "/elsewhere",
                     "stream?url=http://127.0.0.1:" + other.Port + "/elsewhere",
                 })
        {
            // The status is deliberately not asserted per shape: some are normalized away by the
            // client, some are refused by the listener's prefix. The security property under test is
            // which URL the relay ends up fetching, and that is asserted below.
            using HttpResponseMessage response = await client.GetAsync(
                "http://127.0.0.1:" + bridge.Port + "/" + bridge.Token + "/" + suffix);
        }

        // The relay always fetches its configured target; the path is only a carrier for the session
        // token. There is no code path from a client-supplied path or query to the fetch URL, so the
        // relay cannot be used to reach another service on the loopback interface.
        Assert.True(origin.SawPath("/stream"), "the configured target must still be the one fetched");
        Assert.False(other.SawPath("/elsewhere"), "the relay must never fetch a client-named URL");
        Assert.True(origin.RequestsFor("/stream").Count >= 1);
    }

    [Fact]
    public async Task A_client_supplied_query_string_is_not_forwarded_upstream()
    {
        await using BridgeTestOrigin origin = await OriginAsync();
        await using LoopbackMediaBridge bridge = await LoopbackMediaBridge.StartAsync(
            new BridgeTarget(new Uri(origin.BaseUri, "stream?keep=1"), CanaryHeaders(), new NetworkPolicy { Mode = ProxyMode.Direct }));

        using var client = new HttpClient();
        await client.GetAsync("http://127.0.0.1:" + bridge.Port + "/" + bridge.Token + "/stream?injected=1&Range=bytes%3D0-1");

        var recorded = origin.RequestsFor("/stream").FirstOrDefault();
        Assert.Contains("keep=1", recorded.Query);
        Assert.DoesNotContain("injected", recorded.Query);
    }

    [Fact]
    public async Task One_bridge_does_not_accept_another_bridges_token()
    {
        await using BridgeTestOrigin originA = await OriginAsync();
        await using BridgeTestOrigin originB = await OriginAsync();
        await using LoopbackMediaBridge a = await LoopbackMediaBridge.StartAsync(Target(new Uri(originA.BaseUri, "stream")));
        await using LoopbackMediaBridge b = await LoopbackMediaBridge.StartAsync(Target(new Uri(originB.BaseUri, "stream")));

        using var client = new HttpClient();
        // A's token against B's port must not fetch B's target with A's credentials.
        HttpResponseMessage response = await client.GetAsync(
            "http://127.0.0.1:" + b.Port + "/" + a.Token + "/stream");

        Assert.False(response.IsSuccessStatusCode, "a token from another session must not be accepted");
        Assert.False(originB.SawPath("/stream"));
        Assert.NotEqual(a.Token, b.Token);
    }

    [Fact]
    public async Task Disposing_the_bridge_invalidates_its_url_immediately()
    {
        await using BridgeTestOrigin origin = await OriginAsync();
        LoopbackMediaBridge bridge = await LoopbackMediaBridge.StartAsync(Target(new Uri(origin.BaseUri, "stream")));
        Uri url = bridge.BridgeUri;
        await bridge.DisposeAsync();

        using var client = new HttpClient();
        client.Timeout = TimeSpan.FromSeconds(5);
        await Assert.ThrowsAnyAsync<Exception>(() => client.GetAsync(url));
    }

    // ------------------------------------------------------------ request shaping

    [Fact]
    public async Task Accept_Ranges_is_only_advertised_when_the_upstream_supports_it()
    {
        // A server that ignores Range answers a range request with 200 and the whole body. Telling the
        // engine "bytes" in that case makes a seek look successful while it actually replays from zero.
        await using BridgeTestOrigin plain = await OriginAsync(acceptRanges: false);
        await using LoopbackMediaBridge bridge = await LoopbackMediaBridge.StartAsync(Target(new Uri(plain.BaseUri, "stream")));

        using var client = new HttpClient();
        HttpResponseMessage response = await client.GetAsync(bridge.BridgeUri);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.False(response.Headers.Contains("Accept-Ranges"),
            "a server that cannot serve ranges must not be advertised as range-capable");
        // Content-Range is a content header, so it is looked up on HttpContent, not on the response.
        Assert.False(response.Content.Headers.Contains("Content-Range"));
    }

    [Fact]
    public async Task A_416_from_the_upstream_is_relayed_unchanged()
    {
        await using BridgeTestOrigin origin = await BridgeTestOrigin.StartAsync();
        origin.Map("/stream", ctx =>
        {
            ctx.Response.StatusCode = 416;
            ctx.Response.AddHeader("Content-Range", "bytes */" + Payload.Length);
            ctx.Response.ContentLength64 = 0;
            ctx.Response.Close();
            return Task.CompletedTask;
        });
        await using LoopbackMediaBridge bridge = await LoopbackMediaBridge.StartAsync(Target(new Uri(origin.BaseUri, "stream")));

        using var client = new HttpClient();
        using var request = new HttpRequestMessage(HttpMethod.Get, bridge.BridgeUri);
        request.Headers.TryAddWithoutValidation("Range", "bytes=99999999-");
        HttpResponseMessage response = await client.SendAsync(request);

        Assert.Equal(HttpStatusCode.RequestedRangeNotSatisfiable, response.StatusCode);
        Assert.Equal("bytes */" + Payload.Length, response.Content.Headers.ContentRange?.ToString());
    }

    [Fact]
    public async Task A_response_without_a_content_length_is_relayed_completely()
    {
        await using BridgeTestOrigin origin = await BridgeTestOrigin.StartAsync();
        origin.Map("/stream", async ctx =>
        {
            // No Content-Length: the relay has to fall back to chunked rather than guess.
            ctx.Response.StatusCode = 200;
            ctx.Response.ContentType = "video/mp4";
            await ctx.Response.OutputStream.WriteAsync(Payload);
            ctx.Response.Close();
        });
        await using LoopbackMediaBridge bridge = await LoopbackMediaBridge.StartAsync(Target(new Uri(origin.BaseUri, "stream")));

        using var client = new HttpClient();
        byte[] received = await client.GetByteArrayAsync(bridge.BridgeUri);

        Assert.Equal(Payload.Length, received.Length);
    }

    [Fact]
    public async Task A_client_that_disconnects_mid_body_does_not_wedge_the_bridge()
    {
        byte[] large = new byte[4 * 1024 * 1024];
        Random.Shared.NextBytes(large);
        await using BridgeTestOrigin origin = await OriginAsync(body: large);
        await using LoopbackMediaBridge bridge = await LoopbackMediaBridge.StartAsync(Target(new Uri(origin.BaseUri, "stream")));

        // Read a little and slam the socket shut, the way a player does when the user seeks away.
        using (var socket = new TcpClient())
        {
            await socket.ConnectAsync(IPAddress.Loopback, bridge.Port);
            NetworkStream stream = socket.GetStream();
            byte[] request = Encoding.ASCII.GetBytes(
                "GET /" + bridge.Token + "/stream HTTP/1.1\r\nHost: 127.0.0.1:" + bridge.Port + "\r\nConnection: close\r\n\r\n");
            await stream.WriteAsync(request);
            byte[] buffer = new byte[8192];
            // Deliberately a partial read: the point is to take some bytes and then vanish, which is
            // what a player does when the user seeks away. Reading to completion would defeat it, so
            // the incomplete-read analyzer warning is suppressed here rather than "fixed".
#pragma warning disable CA2022
            await stream.ReadAsync(buffer);
#pragma warning restore CA2022
        }

        using var client = new HttpClient();
        client.Timeout = TimeSpan.FromSeconds(30);
        byte[] received = await client.GetByteArrayAsync(bridge.BridgeUri);
        Assert.Equal(large.Length, received.Length);
    }

    [Fact]
    public async Task Parallel_clients_are_all_answered_and_none_hang()
    {
        await using BridgeTestOrigin origin = await OriginAsync();
        await using LoopbackMediaBridge bridge = await LoopbackMediaBridge.StartAsync(Target(new Uri(origin.BaseUri, "stream")));

        using var client = new HttpClient();
        client.Timeout = TimeSpan.FromSeconds(30);

        // More concurrent callers than the relay admits, so some must be refused rather than queued
        // indefinitely. What must not happen is a hang or an unbounded pile of pending work.
        Task<HttpResponseMessage>[] calls = Enumerable.Range(0, 48)
            .Select(_ => client.GetAsync(bridge.BridgeUri))
            .ToArray();

        HttpResponseMessage[] responses = await Task.WhenAll(calls);
        try
        {
            Assert.All(responses, r => Assert.True(
                r.StatusCode is HttpStatusCode.OK or HttpStatusCode.ServiceUnavailable,
                "unexpected status " + r.StatusCode));
            Assert.Contains(responses, r => r.StatusCode == HttpStatusCode.OK);
        }
        finally
        {
            foreach (HttpResponseMessage response in responses) response.Dispose();
        }
    }

    [Fact]
    public async Task Recorded_errors_never_contain_the_session_token()
    {
        // Upstream is a port nothing listens on, so the fetch fails and the failure is recorded.
        var dead = new Uri("http://127.0.0.1:1/stream");
        await using LoopbackMediaBridge bridge = await LoopbackMediaBridge.StartAsync(
            new BridgeTarget(dead, CanaryHeaders(), new NetworkPolicy { Mode = ProxyMode.Direct }));

        using var client = new HttpClient();
        using HttpResponseMessage response = await client.SendAsync(
            new HttpRequestMessage(HttpMethod.Get, bridge.BridgeUri), HttpCompletionOption.ResponseHeadersRead);

        Assert.Equal(HttpStatusCode.BadGateway, response.StatusCode);
        Assert.NotEmpty(bridge.RelayedErrors);
        foreach (string error in bridge.RelayedErrors)
        {
            Assert.DoesNotContain(bridge.Token, error, StringComparison.OrdinalIgnoreCase);
        }
        foreach (string denial in bridge.DeniedRequests)
        {
            Assert.DoesNotContain(bridge.Token, denial, StringComparison.OrdinalIgnoreCase);
        }
    }

    [Fact]
    public async Task The_relay_forwards_the_upstream_status_and_does_not_invent_success()
    {
        await using BridgeTestOrigin origin = await BridgeTestOrigin.StartAsync();
        origin.Map("/stream", ctx =>
        {
            ctx.Response.StatusCode = 500;
            ctx.Response.ContentLength64 = 0;
            ctx.Response.Close();
            return Task.CompletedTask;
        });
        await using LoopbackMediaBridge bridge = await LoopbackMediaBridge.StartAsync(Target(new Uri(origin.BaseUri, "stream")));

        using var client = new HttpClient();
        HttpResponseMessage response = await client.GetAsync(bridge.BridgeUri);
        Assert.Equal(HttpStatusCode.InternalServerError, response.StatusCode);
    }

    // ------------------------------------------------------------ helpers

    /// <summary>Sends a request verbatim and returns the raw response text.</summary>
    private static async Task<string> RawRequestAsync(int port, string request)
    {
        using var socket = new TcpClient();
        await socket.ConnectAsync(IPAddress.Loopback, port);
        NetworkStream stream = socket.GetStream();
        await stream.WriteAsync(Encoding.ASCII.GetBytes(request));

        using var reader = new StreamReader(stream, Encoding.ASCII);
        var text = new StringBuilder();
        char[] buffer = new char[1024];
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        try
        {
            int read;
            while ((read = await reader.ReadAsync(buffer, cts.Token)) > 0)
            {
                text.Append(buffer, 0, read);
                if (text.Length > 65536) break;
            }
        }
        catch (OperationCanceledException) { }
        catch (IOException) { }
        return text.ToString();
    }
}

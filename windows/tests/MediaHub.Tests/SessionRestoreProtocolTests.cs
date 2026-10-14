using MediaHub.Domain;
using MediaHub.Net;
using MediaHub.Providers;
using MediaHub.Providers.MediaBrowser;
using MediaHub.TestSupport;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Pins the endpoint used to validate a restored session.
/// </summary>
/// <remarks>
/// These tests exist because of a real defect that only a real server exposed. The client proved a
/// stored token by requesting <c>/Users/{userId}/Items/{userId}</c> - the user id passed where an item
/// id belongs. The mock server answered it with a stub, so every test passed; Jellyfin 12.2 answers
/// 404, so restoring a session against a real server failed every time while the suite stayed green.
/// <para>
/// The lesson is encoded in both directions: the provider must ask for the current user, and the mock
/// must no longer serve a route the real server rejects.
/// </para>
/// </remarks>
public sealed class SessionRestoreProtocolTests
{
    private const string User = "tester";
    private const string Password = "secret";

    private static NetworkPolicy DirectPolicy() => new() { Mode = ProxyMode.Direct };

    private static MediaServer ServerFor(MockMediaServer mock)
        => new("jellyfin:session-probe", ServerType.Jellyfin, "mock",
            new[] { new ServerEndpoint(mock.BaseUri.GetLeftPart(UriPartial.Authority)) });

    private static async Task<(MockMediaServer Mock, ProviderHandle Handle)> ConnectAsync()
    {
        MockMediaServer mock = await MockMediaServer.StartAsync(ServerType.Jellyfin,
            new MockServerOptions { ServerId = "session-probe", UserId = "user-1", UserName = User, Password = Password, Token = "token-abc" });
        ProviderHandle? handle = new JellyfinProviderFactory().Create(ServerFor(mock), DirectPolicy());
        Assert.NotNull(handle);
        await handle!.Auth!.AuthenticateAsync(new Credentials(User, Password));
        return (mock, handle);
    }

    [Fact]
    public async Task Session_restore_asks_the_server_for_the_current_user()
    {
        (MockMediaServer mock, ProviderHandle handle) = await ConnectAsync();
        await using (mock)
        using (handle)
        {
            int before = mock.Requests.Count;
            AuthSessionState state = await handle.Auth!.RestoreSessionAsync();

            Assert.Equal(AuthSessionState.Authenticated, state);
            Assert.Contains(mock.Requests.Skip(before), r => r.Path == "/Users/Me");
        }
    }

    [Fact]
    public async Task Session_restore_never_asks_for_the_user_id_as_an_item_id()
    {
        (MockMediaServer mock, ProviderHandle handle) = await ConnectAsync();
        await using (mock)
        using (handle)
        {
            int before = mock.Requests.Count;
            await handle.Auth!.RestoreSessionAsync();

            // The exact shape a real Jellyfin rejects with 404. If it returns, the client is broken
            // against every real server while these tests would otherwise stay green.
            Assert.DoesNotContain(mock.Requests.Skip(before), r => r.Path == "/Users/user-1/Items/user-1");
        }
    }

    [Fact]
    public async Task The_mock_rejects_an_unknown_item_like_a_real_server_does()
    {
        // A mock that is more permissive than the server is worse than no mock: it hides exactly the
        // defects the integration test exists to catch. The user-as-item route must 404 here too.
        await using MockMediaServer mock = await MockMediaServer.StartAsync(ServerType.Jellyfin,
            new MockServerOptions { ServerId = "session-probe", UserId = "user-1" });

        using var client = new HttpClient();
        using var request = new HttpRequestMessage(HttpMethod.Get, mock.Url("/Users/user-1/Items/user-1"));
        // With a valid token, exactly as the client would send it: the point is that the ROUTE does not
        // exist, not that the mock demands authentication first.
        request.Headers.TryAddWithoutValidation("Authorization", @"MediaBrowser Token=""token-abc""");
        HttpResponseMessage response = await client.SendAsync(request);
        Assert.Equal(System.Net.HttpStatusCode.NotFound, response.StatusCode);
    }

    [Fact]
    public async Task An_invalidated_token_is_not_reported_as_authenticated()
    {
        (MockMediaServer mock, ProviderHandle handle) = await ConnectAsync();
        await using (mock)
        using (handle)
        {
            mock.SetTokenValid(false);
            AuthSessionState state = await handle.Auth!.RestoreSessionAsync();

            Assert.NotEqual(AuthSessionState.Authenticated, state);
            Assert.True(state is AuthSessionState.Expired or AuthSessionState.Unreachable,
                "an invalid token must not be reported as authenticated, was " + state);
        }
    }
}

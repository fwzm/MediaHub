using System.Net;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using MediaHub.Domain;
using MediaHub.Net;
using MediaHub.Net.Security;
using MediaHub.Providers;
using MediaHub.Providers.MediaBrowser;
using MediaHub.TestSupport;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Regression tests for the security invariants that must survive future refactors: nothing that
/// carries a credential may be rendered, persisted in clear, echoed to a different origin or written
/// to the diagnostic log, and the access token must keep travelling in headers rather than URLs.
/// </summary>
/// <remarks>
/// These tests intentionally assert on observable product behaviour (rendered strings, on-disk bytes,
/// what actually crossed a socket, what the log holds) rather than on "this method was called". A
/// security control that is merely wired up but not effective passes a call-count assertion and fails
/// the ones below.
/// </remarks>
public sealed class CredentialDisclosureTests
{
    private const string Password = "pw-0123456789abcdefghijklmnopqrstuvwxyz";
    private const string Token = "tk-0123456789abcdefghijklmnopqrstuvwxyz";
    private const string Marker = "PLAINTEXT-MARKER-9f2c";

    private static ServerCredential Sample(string? password = Password, string? token = Token)
        => new()
        {
            ServerId = "srv-1",
            UserName = "tester",
            Password = password,
            Token = token,
            UserId = "user-1",
            RemoteServerId = "remote-1",
            SavedAtUtc = DateTimeOffset.UnixEpoch,
        };

    /// <summary>
    /// The compiler-generated record ToString prints every property, secrets included, and it is what
    /// interpolation, logging and exception messages reach for first.
    /// </summary>
    [Fact]
    public void ServerCredential_ToString_and_interpolation_hide_password_and_token()
    {
        ServerCredential credential = Sample();

        string rendered = credential.ToString();
        string interpolated = $"{credential}";

        Assert.DoesNotContain(Password, rendered);
        Assert.DoesNotContain(Token, rendered);
        Assert.DoesNotContain(Password, interpolated);
        Assert.DoesNotContain(Token, interpolated);
        Assert.Contains("Password = (present)", rendered);
        Assert.Contains("Token = (present)", rendered);
        Assert.Contains("srv-1", rendered);
        Assert.Contains("tester", rendered);
    }

    /// <summary>
    /// A secret's length is a weak oracle too, so the rendered form must depend only on presence. The
    /// strongest way to state that: a 1-character secret and a 200-character one render identically
    /// once the presence word is normalised.
    /// </summary>
    [Fact]
    public void ServerCredential_ToString_reveals_presence_but_never_length()
    {
        ServerCredential withoutSecrets = Sample(password: null, token: null);
        ServerCredential withLongSecrets = Sample(
            password: new string('p', 200),
            token: new string('t', 300));

        string none = withoutSecrets.ToString();
        string present = withLongSecrets.ToString().Replace("(present)", "(none)", StringComparison.Ordinal);

        Assert.Equal(none, present);
        Assert.Contains("Password = (none)", none);
        Assert.Contains("Token = (none)", none);
    }

    /// <summary>Empty is absence: a cleared field must not read as "a secret is configured".</summary>
    [Fact]
    public void ServerCredential_ToString_treats_empty_secrets_as_absent()
    {
        string rendered = Sample(password: string.Empty, token: string.Empty).ToString();

        Assert.Contains("Password = (none)", rendered);
        Assert.Contains("Token = (none)", rendered);
        Assert.DoesNotContain("(present)", rendered);
    }

    /// <summary>
    /// VaultLoadResult is a record, so its generated ToString expands Credential. That is only safe
    /// because Credential redacts itself; pin the composition so a removed override cannot leak.
    /// </summary>
    [Fact]
    public void VaultLoadResult_rendering_carries_no_secret_for_any_status()
    {
        ServerCredential credential = Sample();

        foreach (VaultStatus status in Enum.GetValues<VaultStatus>())
        {
            ServerCredential? payload = status == VaultStatus.Ok ? credential : null;
            var result = new VaultLoadResult(status, payload, "detail for " + status);

            Assert.DoesNotContain(Password, result.ToString());
            Assert.DoesNotContain(Token, result.ToString());
            if (status != VaultStatus.Ok) Assert.Null(result.Credential);
        }
    }

    /// <summary>
    /// The on-disk entry is a key and a ciphertext: if a refactor serialises the credential record
    /// directly into the document, the plaintext lands next to the blob.
    /// </summary>
    [Fact]
    public async Task Vault_document_stores_only_the_server_id_and_the_ciphertext()
    {
        string directory = TempDirectory();
        try
        {
            var store = new DpapiCredentialStore(directory);
            await store.SaveAsync(Sample(password: Password + Marker, token: Token + Marker));

            string raw = await File.ReadAllTextAsync(store.VaultPath);
            Assert.DoesNotContain(Marker, raw);

            JsonNode? document = JsonNode.Parse(raw);
            Assert.NotNull(document);
            Assert.Equal(1, document!["version"]!.GetValue<int>());

            JsonArray entries = document["entries"]!.AsArray();
            Assert.Single(entries);
            string[] names = entries[0]!.AsObject().Select(kv => kv.Key).OrderBy(k => k, StringComparer.Ordinal).ToArray();
            Assert.Equal(new[] { "blob", "serverId" }, names);
            Assert.Equal("srv-1", entries[0]!["serverId"]!.GetValue<string>());
        }
        finally
        {
            DeleteDirectory(directory);
        }
    }

    internal static string TempDirectory()
        => Path.Combine(Path.GetTempPath(), "mediahub-security-tests", Guid.NewGuid().ToString("N"));

    internal static void DeleteDirectory(string directory)
    {
        try
        {
            if (Directory.Exists(directory)) Directory.Delete(directory, recursive: true);
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }
}

/// <summary>
/// The vault must fail closed: a vault or salt that is not the one this installation wrote may never
/// hand back a partial credential, and nothing may be regenerated or destroyed to make it load.
/// </summary>
public sealed class VaultFailClosedTests : IDisposable
{
    private const string Marker = "PLAINTEXT-MARKER-9f2c";
    private const string Password = "pw-" + Marker;
    private const string Token = "tk-" + Marker;
    private const string ServerId = "srv-1";

    private readonly string _directory;

    public VaultFailClosedTests()
    {
        _directory = Path.Combine(Path.GetTempPath(), "mediahub-security-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_directory);
    }

    public void Dispose() => CredentialDisclosureTests.DeleteDirectory(_directory);

    private static ServerCredential Sample() => new()
    {
        ServerId = ServerId,
        UserName = "tester",
        Password = Password,
        Token = Token,
        UserId = "user-1",
        RemoteServerId = "remote-1",
        SavedAtUtc = DateTimeOffset.UnixEpoch,
    };

    private static void AssertNoPlaintext(string directory)
    {
        foreach (string file in Directory.GetFiles(directory))
        {
            byte[] bytes = File.ReadAllBytes(file);
            Assert.False(Contains(bytes, Marker, Encoding.UTF8), file + " contains the UTF-8 plaintext marker");
            Assert.False(Contains(bytes, Marker, Encoding.Unicode), file + " contains the UTF-16 plaintext marker");
        }
    }

    private static bool Contains(byte[] haystack, string text, Encoding encoding)
        => haystack.AsSpan().IndexOf(encoding.GetBytes(text)) >= 0;

    [Fact]
    public async Task Vault_and_salt_files_hold_no_plaintext_secret()
    {
        var store = new DpapiCredentialStore(_directory);
        await store.SaveAsync(Sample());

        Assert.True(File.Exists(store.VaultPath));
        Assert.True(File.Exists(Path.Combine(_directory, "entropy.bin")));
        AssertNoPlaintext(_directory);

        VaultLoadResult loaded = await store.LoadAsync(ServerId);
        Assert.Equal(VaultStatus.Ok, loaded.Status);
        Assert.Equal(Password, loaded.Credential!.Password);
    }

    /// <summary>
    /// A copied vault arrives without the salt that protected it. DPAPI must refuse, and the new
    /// location must not acquire a plaintext copy in the process.
    /// </summary>
    [Fact]
    public async Task A_vault_copied_to_another_directory_fails_closed()
    {
        var store = new DpapiCredentialStore(_directory);
        await store.SaveAsync(Sample());

        string other = Path.Combine(_directory, "copied");
        Directory.CreateDirectory(other);
        File.Copy(store.VaultPath, Path.Combine(other, "credentials.vault.json"));

        VaultLoadResult result = await new DpapiCredentialStore(other).LoadAsync(ServerId);

        Assert.Equal(VaultStatus.ForeignUser, result.Status);
        Assert.Null(result.Credential);
        Assert.Contains("无法用当前 Windows 账户解密", result.Detail);
        Assert.DoesNotContain(Marker, result.Detail);
        AssertNoPlaintext(other);
    }

    /// <summary>
    /// The salt cannot be re-derived: regenerating it would silently orphan every credential the
    /// installation still holds, so a wrong-size salt is a refusal, not a repair.
    /// </summary>
    [Fact]
    public async Task Replacing_the_salt_fails_closed_and_is_not_regenerated()
    {
        var store = new DpapiCredentialStore(_directory);
        await store.SaveAsync(Sample());

        string entropyPath = Path.Combine(_directory, "entropy.bin");
        byte[] replacement = new byte[32];
        for (int i = 0; i < replacement.Length; i++) replacement[i] = (byte)(i + 1);
        await File.WriteAllBytesAsync(entropyPath, replacement);

        VaultLoadResult result = await new DpapiCredentialStore(_directory).LoadAsync(ServerId);

        Assert.Equal(VaultStatus.ForeignUser, result.Status);
        Assert.Null(result.Credential);
        Assert.Equal(replacement, await File.ReadAllBytesAsync(entropyPath));
        AssertNoPlaintext(_directory);
    }

    [Fact]
    public async Task A_corrupt_vault_is_quarantined_and_returns_no_credential()
    {
        var store = new DpapiCredentialStore(_directory);
        await File.WriteAllTextAsync(store.VaultPath, "this is not a vault document");

        VaultLoadResult result = await store.LoadAsync(ServerId);

        Assert.Equal(VaultStatus.Corrupt, result.Status);
        Assert.Null(result.Credential);
        Assert.Contains("隔离", result.Detail);
        Assert.DoesNotContain("this is not a vault document", result.Detail);
        Assert.Single(Directory.GetFiles(_directory, "credentials.vault.json.corrupt-*"));
    }

    /// <summary>
    /// A document written by a newer build must be reported, not rewritten or partially read: the
    /// entries it holds are secrets this build cannot even see.
    /// </summary>
    [Fact]
    public async Task A_future_version_vault_is_unavailable_and_left_untouched()
    {
        var store = new DpapiCredentialStore(_directory);
        const string Document = "{\"version\":2,\"entries\":[{\"serverId\":\"srv-1\",\"blob\":\"AAAA\"}]}";
        await File.WriteAllTextAsync(store.VaultPath, Document);

        VaultLoadResult result = await store.LoadAsync(ServerId);

        Assert.Equal(VaultStatus.Unavailable, result.Status);
        Assert.Null(result.Credential);
        Assert.Contains("version=2", result.Detail);
        Assert.Equal(Document, await File.ReadAllTextAsync(store.VaultPath));
    }

    /// <summary>
    /// DPAPI returning bytes is not the same as those bytes being a credential: the payload shape is
    /// checked after decryption, and a mismatch is its own status with no credential attached.
    /// </summary>
    [Fact]
    public async Task A_decrypted_payload_that_is_not_a_credential_is_undecryptable()
    {
        var store = new DpapiCredentialStore(_directory);
        await store.SaveAsync(Sample());

        byte[] salt = await File.ReadAllBytesAsync(Path.Combine(_directory, "entropy.bin"));
        byte[] purpose = Encoding.UTF8.GetBytes("MediaHub.CredentialVault.v1|" + ServerId);
        byte[] entropy = new byte[salt.Length + purpose.Length];
        salt.CopyTo(entropy, 0);
        purpose.CopyTo(entropy, salt.Length);
        byte[] blob = ProtectedData.Protect(
            Encoding.UTF8.GetBytes("not a credential payload at all"),
            entropy,
            DataProtectionScope.CurrentUser);

        JsonNode document = JsonNode.Parse(await File.ReadAllTextAsync(store.VaultPath))!;
        document["entries"]![0]!["blob"] = Convert.ToBase64String(blob);
        await File.WriteAllTextAsync(store.VaultPath, document.ToJsonString());

        VaultLoadResult result = await store.LoadAsync(ServerId);

        Assert.Equal(VaultStatus.Undecryptable, result.Status);
        Assert.Null(result.Credential);
        Assert.Contains("不是有效的凭据载荷", result.Detail);
    }

    [Fact]
    public async Task An_empty_vault_reports_absence_without_a_credential_or_a_detail()
    {
        VaultLoadResult result = await new DpapiCredentialStore(_directory).LoadAsync(ServerId);

        Assert.Equal(VaultStatus.Absent, result.Status);
        Assert.Null(result.Credential);
        Assert.Null(result.Detail);
    }
}

/// <summary>
/// Address input is the first place a credential can be smuggled in, and the refusal message is the
/// second: it must say what was wrong without repeating what the user typed.
/// </summary>
public sealed class ServerUrlNormalizerSecurityTests
{
    [Theory]
    [InlineData("http://user:pass@127.0.0.1:8096/", "user:pass")]
    [InlineData("http://user@127.0.0.1:8096/", "user@")]
    [InlineData("user:pass@127.0.0.1:8096", "user:pass")]
    [InlineData("https://token-value@example.com/emby", "token-value")]
    public void An_address_with_user_info_is_refused_without_echoing_it(string input, string echo)
    {
        bool accepted = ServerUrlNormalizer.TryNormalize(input, ServerType.Emby, out _, out string? error);

        Assert.False(accepted);
        Assert.Contains("不能内嵌用户名或密码", error);
        Assert.DoesNotContain(echo, error, StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>
    /// Query strings and fragments are refused before parsing: both Emby and Jellyfin accept
    /// credentials as query parameters, so a pasted URL can carry a token (ADR-026).
    /// </summary>
    [Theory]
    [InlineData("http://127.0.0.1:8096/?api_key=SECRET-VALUE", "SECRET-VALUE", "查询参数")]
    [InlineData("http://127.0.0.1:8096/?X-Emby-Token=SECRET-VALUE", "SECRET-VALUE", "查询参数")]
    [InlineData("http://127.0.0.1:8096/emby#access_token=SECRET-VALUE", "SECRET-VALUE", "片段")]
    public void Query_and_fragment_are_refused_without_echoing_them(string input, string echo, string expected)
    {
        bool accepted = ServerUrlNormalizer.TryNormalize(input, ServerType.Jellyfin, out _, out string? error);

        Assert.False(accepted);
        Assert.Contains(expected, error);
        Assert.DoesNotContain(echo, error);
    }

    [Theory]
    [InlineData("ftp://example.com/media", "ftp")]
    [InlineData("file:///C:/media", "file")]
    [InlineData("ws://example.com", "ws")]
    public void A_non_http_scheme_is_refused_and_only_the_scheme_is_echoed(string input, string scheme)
    {
        bool accepted = ServerUrlNormalizer.TryNormalize(input, ServerType.Jellyfin, out _, out string? error);

        Assert.False(accepted);
        Assert.Contains(scheme, error, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("example.com", error, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("C:/media", error, StringComparison.OrdinalIgnoreCase);
    }

    [Theory]
    [InlineData("http://127.0.0.1:8096", ServerType.Emby)]
    [InlineData("127.0.0.1:8096", ServerType.Emby)]
    [InlineData("https://media.example.com/jellyfin", ServerType.Jellyfin)]
    public void An_accepted_address_carries_no_user_info_query_or_fragment(string input, ServerType type)
    {
        bool accepted = ServerUrlNormalizer.TryNormalize(input, type, out NormalizedServerUrl result, out string? error);

        Assert.True(accepted, error);
        Assert.Equal("", result.BaseUri.UserInfo);
        Assert.Equal("", result.BaseUri.Query);
        Assert.Equal("", result.BaseUri.Fragment);
        Assert.Equal("", result.ApiRoot.Query);
    }
}

/// <summary>
/// Credentials are scoped to the exact origin that issued them. These tests drive the redirect chain
/// with a scripted inner handler, so every hop is observable without a socket or a TLS listener.
/// </summary>
public sealed class OriginScopedCredentialHandlerTests
{
    private const string Start = "http://127.0.0.1:5000/a";
    private static readonly string[] IdentityHeaders =
    {
        "Authorization",
        "Proxy-Authorization",
        "Cookie",
        "X-Emby-Token",
        "X-MediaBrowser-Token",
        "X-Emby-Authorization",
        "X-MediaBrowser-Authorization",
    };

    private sealed record SentRequest(Uri Uri, IReadOnlyDictionary<string, string> Headers)
    {
        public string? Header(string name) => Headers.TryGetValue(name, out string? value) ? value : null;
        public bool Has(string name) => Headers.ContainsKey(name);
    }

    private sealed class ScriptedHandler : HttpMessageHandler
    {
        private readonly Func<Uri, HttpResponseMessage> _script;

        public ScriptedHandler(Func<Uri, HttpResponseMessage> script) => _script = script;

        public List<SentRequest> Sent { get; } = new();

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            var copy = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            foreach (var header in request.Headers) copy[header.Key] = string.Join(",", header.Value);
            Sent.Add(new SentRequest(request.RequestUri!, copy));
            return Task.FromResult(_script(request.RequestUri!));
        }
    }

    private static HttpResponseMessage Redirect(string location)
    {
        var response = new HttpResponseMessage(HttpStatusCode.Found);
        response.Headers.Location = new Uri(location, UriKind.RelativeOrAbsolute);
        return response;
    }

    private static HttpResponseMessage Ok() => new(HttpStatusCode.OK);

    private static HttpClient Client(NetworkPolicy policy, ScriptedHandler inner)
        => new(new OriginScopedCredentialHandler(policy, inner));

    private static HttpRequestMessage Request(string uri)
    {
        var request = new HttpRequestMessage(HttpMethod.Get, uri);
        request.Headers.TryAddWithoutValidation("Authorization", "Bearer SECRET-AUTH");
        request.Headers.TryAddWithoutValidation("X-Emby-Token", "SECRET-EMBY");
        request.Headers.TryAddWithoutValidation("X-MediaBrowser-Authorization", "MediaBrowser Token=\"SECRET-OLD\"");
        request.Headers.TryAddWithoutValidation("Cookie", "session=SECRET-COOKIE");
        request.Headers.TryAddWithoutValidation("Range", "bytes=0-1");
        request.Headers.TryAddWithoutValidation("User-Agent", "MediaHub.Tests/1.0");
        return request;
    }

    private static void AssertNoIdentityHeaders(SentRequest hop)
    {
        foreach (string header in IdentityHeaders)
            Assert.False(hop.Has(header), "hop " + hop.Uri + " carried " + header);
        Assert.Equal("bytes=0-1", hop.Header("Range"));
        Assert.Equal("MediaHub.Tests/1.0", hop.Header("User-Agent"));
    }

    [Fact]
    public async Task Credentials_are_kept_on_a_same_origin_redirect()
    {
        var inner = new ScriptedHandler(uri => uri.AbsolutePath == "/a" ? Redirect("/b") : Ok());
        using HttpClient client = Client(new NetworkPolicy { Mode = ProxyMode.Direct }, inner);

        using HttpResponseMessage response = await client.SendAsync(Request(Start));

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal(2, inner.Sent.Count);
        SentRequest second = inner.Sent[1];
        Assert.Equal("http://127.0.0.1:5000/b", second.Uri.ToString());
        Assert.Equal("Bearer SECRET-AUTH", second.Header("Authorization"));
        Assert.Equal("SECRET-EMBY", second.Header("X-Emby-Token"));
        Assert.Equal("session=SECRET-COOKIE", second.Header("Cookie"));
    }

    [Theory]
    [InlineData("http://127.0.0.1:5001/b")]                 // same host, different port
    [InlineData("http://localhost:5000/b")]                 // different host spelling
    [InlineData("http://b.a.example/b")]                    // subdomain of the original
    [InlineData("http://127.0.0.1:5000:5001/b")]            // malformed target: parsing decides
    [InlineData("https://127.0.0.1:5000/b")]                // scheme upgrade
    [InlineData("http://user:pass@127.0.0.1:5001/b")]       // user info plus another port
    public async Task A_cross_origin_hop_drops_every_identity_header(string target)
    {
        string location = target.Contains(":5000:5001", StringComparison.Ordinal)
            ? "http://127.0.0.1:5001/b"
            : target;
        var inner = new ScriptedHandler(uri => uri.AbsolutePath == "/a" ? Redirect(location) : Ok());
        using HttpClient client = Client(new NetworkPolicy { Mode = ProxyMode.Direct }, inner);

        using HttpResponseMessage response = await client.SendAsync(Request(Start));

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal(2, inner.Sent.Count);
        AssertNoIdentityHeaders(inner.Sent[1]);
    }

    /// <summary>
    /// Start is <c>http://a.example/a</c> here so the subdomain case has a real parent domain; the
    /// handler compares the original chain origin, not the previous hop.
    /// </summary>
    [Fact]
    public async Task A_subdomain_of_the_original_origin_never_receives_credentials()
    {
        var inner = new ScriptedHandler(uri => uri.AbsolutePath == "/a" ? Redirect("http://b.a.example/b") : Ok());
        using HttpClient client = Client(new NetworkPolicy { Mode = ProxyMode.Direct }, inner);

        using HttpResponseMessage response = await client.SendAsync(Request("http://a.example/a"));

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        AssertNoIdentityHeaders(inner.Sent[1]);
    }

    /// <summary>
    /// Once a chain crosses an origin, the stripped request is what later hops copy from, so
    /// credentials are never restored even if a later hop returns to the original origin. Conservative
    /// and worth pinning: a refactor that recomputes headers per hop would silently re-attach them.
    /// </summary>
    [Fact]
    public async Task Credentials_are_not_restored_when_a_chain_returns_to_the_first_origin()
    {
        var inner = new ScriptedHandler(uri =>
        {
            if (uri.AbsolutePath == "/a" && uri.Port == 5000) return Redirect("http://127.0.0.1:5001/b");
            if (uri.AbsolutePath == "/b") return Redirect("http://127.0.0.1:5000/c");
            return Ok();
        });
        using HttpClient client = Client(new NetworkPolicy { Mode = ProxyMode.Direct }, inner);

        using HttpResponseMessage response = await client.SendAsync(Request(Start));

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal(3, inner.Sent.Count);
        Assert.Equal("http://127.0.0.1:5000/c", inner.Sent[2].Uri.ToString());
        AssertNoIdentityHeaders(inner.Sent[2]);
    }

    /// <summary>
    /// A downgrade is refused before the weaker hop is attempted, so no request ever leaves over
    /// plain HTTP carrying what was meant for HTTPS.
    /// </summary>
    [Fact]
    public async Task An_https_to_http_downgrade_is_refused_before_the_next_hop()
    {
        var inner = new ScriptedHandler(uri => uri.AbsolutePath == "/a" ? Redirect("http://127.0.0.1:5000/b") : Ok());
        using HttpClient client = Client(new NetworkPolicy { Mode = ProxyMode.Direct, BlockHttpsDowngrade = true }, inner);

        HttpRequestException error = await Assert.ThrowsAsync<HttpRequestException>(
            () => client.SendAsync(Request("https://127.0.0.1:5000/a")));

        Assert.Contains("拒绝 HTTPS 到 HTTP 的重定向降级", error.Message);
        Assert.Single(inner.Sent);
    }

    [Theory]
    [InlineData("https://example.com/a", "http://example.com/a", true)]
    [InlineData("http://example.com/a", "https://example.com/a", false)]
    [InlineData("https://example.com/a", "https://example.com/b", false)]
    [InlineData("http://example.com/a", "http://example.com/b", false)]
    public void Downgrade_detection_is_exactly_https_to_http(string from, string to, bool expected)
        => Assert.Equal(expected, OriginScopedCredentialHandler.IsDowngrade(new Uri(from), new Uri(to)));

    [Theory]
    [InlineData("Authorization")]
    [InlineData("authorization")]
    [InlineData("AUTHORIZATION")]
    [InlineData("Proxy-Authorization")]
    [InlineData("Cookie")]
    [InlineData("X-Emby-Token")]
    [InlineData("X-MediaBrowser-Token")]
    [InlineData("X-Emby-Authorization")]
    [InlineData("X-MediaBrowser-Authorization")]
    public void Every_provider_token_header_is_sensitive(string name)
        => Assert.True(OriginScopedCredentialHandler.IsSensitive(name));

    [Theory]
    [InlineData("Range")]
    [InlineData("User-Agent")]
    [InlineData("Accept")]
    [InlineData("X-PlaySessionId")]
    public void Non_identity_headers_are_not_treated_as_sensitive(string name)
        => Assert.False(OriginScopedCredentialHandler.IsSensitive(name));

    [Fact]
    public async Task The_redirect_chain_is_bounded_by_the_policy()
    {
        var inner = new ScriptedHandler(uri => Redirect(uri + "x"));
        using HttpClient client = Client(new NetworkPolicy { Mode = ProxyMode.Direct, MaxRedirects = 2 }, inner);

        HttpRequestException error = await Assert.ThrowsAsync<HttpRequestException>(
            () => client.SendAsync(Request(Start)));

        Assert.Contains("重定向次数超过上限（2）", error.Message);
        Assert.Equal(3, inner.Sent.Count);
    }
}

/// <summary>
/// Diagnostics are the most likely place for a credential to escape: URLs carry tokens, exception
/// messages embed URLs, and exports get attached to bug reports.
/// </summary>
public sealed class LoggingDisclosureTests
{
    private const string Secret = "SUPERSECRET42";
    private const string Jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.abcdefghijklmnopqrstuvwxyz123456";

    [Fact]
    public void LogService_redacts_every_shape_a_credential_arrives_in()
    {
        var log = new LogService();

        log.Info("net", "GET http://127.0.0.1:8096/Items?api_key=" + Secret + "&Limit=10");
        log.Info("net", "Authorization: Bearer " + Secret);
        log.Info("net", "X-Emby-Token: " + Secret);
        log.Info("net", "GET https://user:" + Secret + "@127.0.0.1:8096/Users");
        log.Info("net", "observed token " + Jwt + " in response");
        log.Error("playback", "request failed", new InvalidOperationException("GET /Items?token=" + Secret));

        Assert.Equal(6, log.Entries.Count);
        foreach (LogEntry entry in log.Entries)
        {
            Assert.DoesNotContain(Secret, entry.Message);
            Assert.DoesNotContain(Jwt, entry.Message);
        }

        string export = log.Export();
        Assert.DoesNotContain(Secret, export);
        Assert.DoesNotContain(Jwt, export);
        Assert.Contains("已脱敏", export);
    }

    [Fact]
    public void Redaction_keeps_the_diagnostic_shape_readable()
    {
        var log = new LogService();
        log.Info("net", "GET http://127.0.0.1:8096/Items?api_key=" + Secret + "&Limit=10");
        log.Info("net", "GET https://user:" + Secret + "@127.0.0.1:8096/Users");

        Assert.Contains("http://127.0.0.1:8096/Items?api_key=***", log.Entries[0].Message);
        Assert.Contains("&Limit=10", log.Entries[0].Message);
        Assert.Contains("https://***@127.0.0.1:8096/Users", log.Entries[1].Message);
    }

    /// <summary>
    /// The exception path deliberately records only the type name and a redacted message. The type
    /// must survive (that is the diagnostic value) while the message's credential must not.
    /// </summary>
    [Fact]
    public void LogService_error_keeps_the_exception_type_and_drops_the_message_secret()
    {
        var log = new LogService();
        var error = new HttpRequestException("GET http://127.0.0.1:8096/Users?X-Emby-Token=" + Secret + " failed");

        log.Error("net", "probe failed", error);

        LogEntry entry = Assert.Single(log.Entries);
        Assert.Contains("HttpRequestException", entry.Message);
        Assert.DoesNotContain(Secret, entry.Message);
        Assert.DoesNotContain(" at ", entry.Message);
        Assert.DoesNotContain(nameof(LoggingDisclosureTests), entry.Message);
    }

    /// <summary>
    /// The provider layer has no reference to LogService at all, so there is no code path by which a
    /// provider request could reach the diagnostic log. Pin the structural fact: a full provider
    /// conversation against the mock still leaves the log empty.
    /// </summary>
    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task Provider_requests_are_not_written_to_the_log_service(ServerType type)
    {
        var log = new LogService();
        await using MockMediaServer mock = await MockMediaServer.StartAsync(type);
        IMediaProviderFactory factory = type == ServerType.Emby ? new EmbyProviderFactory() : new JellyfinProviderFactory();

        using ProviderHandle handle = factory.Create(ServerFor(mock, type), new NetworkPolicy { Mode = ProxyMode.Direct });
        await handle.Auth!.AuthenticateAsync(new Credentials("tester", "secret"));
        await handle.Library!.GetLibrariesAsync();
        await handle.Library.GetItemsAsync(MockMediaServer.MoviesLibraryId, new PageRequest(0, 5));

        Assert.Empty(log.Entries);
        Assert.NotEmpty(mock.Requests);
    }

    /// <summary>
    /// StartupTrace is in the app assembly, which the test project deliberately does not reference (it
    /// would drag WinUI into the suite). Its whole surface is therefore pinned by reading the source:
    /// the only parameter is a stage string and nothing request-shaped exists in the file.
    /// </summary>
    [Fact]
    public void StartupTrace_accepts_only_a_stage_string()
    {
        string source = File.ReadAllText(StartupTracePath());

        Assert.Contains("public static void Write(string stage)", source);
        Assert.Contains("{stage}", source);
        Assert.Contains("AppendAllText", source);
        Assert.DoesNotContain("Authorization", source, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("Token", source, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("Cookie", source, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("Header", source, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("HttpClient", source, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("RequestUri", source, StringComparison.OrdinalIgnoreCase);
    }

    private static string StartupTracePath()
    {
        var directory = new DirectoryInfo(AppContext.BaseDirectory);
        for (int depth = 0; depth < 12 && directory is not null; depth++, directory = directory.Parent)
        {
            string candidate = Path.Combine(directory.FullName, "windows", "src", "MediaHub.App", "Services", "StartupTrace.cs");
            if (File.Exists(candidate)) return candidate;
        }

        Assert.Fail("StartupTrace.cs was not found above " + AppContext.BaseDirectory);
        throw new InvalidOperationException("unreachable");
    }

    private static MediaServer ServerFor(MockMediaServer mock, ServerType type)
        => new("mock-server", type, "Mock source", new[] { new ServerEndpoint(mock.BaseUri.GetLeftPart(UriPartial.Authority)) });
}

/// <summary>
/// The access token belongs in a header and nowhere else (ADR-026): a token in a URL reaches server
/// logs, proxy logs and history, none of which the app controls.
/// </summary>
public sealed class MediaBrowserTokenPlacementTests
{
    private const string User = "tester";
    private const string Password = "secret";
    private const string Token = "token-abc";
    private const string UserId = "user-1";

    private static NetworkPolicy DirectPolicy() => new() { Mode = ProxyMode.Direct };

    private static MediaServer ServerFor(MockMediaServer mock, ServerType type)
        => new("mock-server", type, "Mock source", new[] { new ServerEndpoint(mock.BaseUri.GetLeftPart(UriPartial.Authority)) });

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task No_provider_request_carries_the_token_in_the_query_string(ServerType type)
    {
        await using MockMediaServer mock = await MockMediaServer.StartAsync(type);
        IMediaProviderFactory factory = type == ServerType.Emby ? new EmbyProviderFactory() : new JellyfinProviderFactory();
        using ProviderHandle handle = factory.Create(ServerFor(mock, type), DirectPolicy());
        await handle.Auth!.AuthenticateAsync(new Credentials(User, Password));

        await handle.Library!.GetLibrariesAsync();
        await handle.Library.GetItemsAsync(MockMediaServer.MoviesLibraryId, new PageRequest(0, 5));
        await handle.Detail!.GetItemDetailAsync("item-000");
        await handle.Progress!.GetContinueWatchingAsync(3);
        await handle.Favorites!.GetFavoritesAsync(3);
        await handle.Subtitle!.GetSubtitlesAsync("item-000");

        Assert.NotEmpty(mock.Requests);
        foreach (RecordedRequest request in mock.Requests)
        {
            Assert.DoesNotContain(Token, request.PathAndQuery, StringComparison.Ordinal);
            Assert.DoesNotContain("api_key=", request.Query, StringComparison.OrdinalIgnoreCase);
            Assert.DoesNotContain("token=", request.Query, StringComparison.OrdinalIgnoreCase);
            Assert.DoesNotContain("X-Emby-Token=", request.Query, StringComparison.OrdinalIgnoreCase);
        }
    }

    /// <summary>
    /// The previous assertion would pass trivially if no token was sent at all. This one proves the
    /// requests were authenticated, so "token not in the URL" means "token in the header".
    /// </summary>
    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task The_token_does_travel_in_a_header_so_the_assertion_is_not_vacuous(ServerType type)
    {
        await using MockMediaServer mock = await MockMediaServer.StartAsync(type);
        IMediaProviderFactory factory = type == ServerType.Emby ? new EmbyProviderFactory() : new JellyfinProviderFactory();
        using ProviderHandle handle = factory.Create(ServerFor(mock, type), DirectPolicy());
        await handle.Auth!.AuthenticateAsync(new Credentials(User, Password));

        await handle.Library!.GetLibrariesAsync();

        RecordedRequest[] authenticated = mock.Requests.Where(r => r.Token == Token).ToArray();
        Assert.NotEmpty(authenticated);
        Assert.DoesNotContain(Token, string.Join("\n", mock.Requests.Select(r => r.PathAndQuery)));

        RecordedRequest views = Assert.Single(mock.RequestsFor(mock.ApiRootPath + "/Users/user-1/Views"));
        if (type == ServerType.Emby)
        {
            Assert.Equal(Token, views.Header("X-Emby-Token"));
            Assert.Equal(Token, views.Header("X-MediaBrowser-Token"));
        }
        else
        {
            Assert.Contains("Token=\"" + Token + "\"", views.Header("Authorization"));
        }
    }

    /// <summary>
    /// URLs handed to a player or an image loader are built by the provider layer, so they are the
    /// easy place for a token to reappear. All three builders are pinned here.
    /// </summary>
    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public void Built_media_urls_never_embed_the_token_or_the_user_id(ServerType type)
    {
        using var handlerFactory = new MediaSourceHttpClientFactory(DirectPolicy());
        using HttpClient client = handlerFactory.CreateApiClient();
        MediaBrowserDialect dialect = type == ServerType.Emby
            ? MediaBrowserDialect.Emby(new Uri("http://127.0.0.1:8096"))
            : MediaBrowserDialect.Jellyfin(new Uri("http://127.0.0.1:8096"));
        var api = new MediaBrowserApi(client, dialect, "srv-1") { Token = Token, UserId = UserId };

        Uri[] urls =
        {
            api.ImageUrl("item-000", "Primary", "tag-1", 320),
            api.SubtitleUrl("item-000", 2, "srt"),
            api.DirectStreamUrl("item-000", "mp4", "media-source-1", "play-session-1"),
        };

        foreach (Uri url in urls)
        {
            string text = url.ToString();
            Assert.DoesNotContain(Token, text, StringComparison.Ordinal);
            Assert.DoesNotContain(UserId, text, StringComparison.Ordinal);
            Assert.DoesNotContain("api_key", text, StringComparison.OrdinalIgnoreCase);
            Assert.DoesNotContain("token", text, StringComparison.OrdinalIgnoreCase);
        }

        Assert.Contains("/Items/item-000/Images/Primary", urls[0].AbsolutePath);
        Assert.Contains("/Videos/item-000/2/Subtitles/srt", urls[1].AbsolutePath);
        Assert.Contains("/Videos/item-000/stream.mp4", urls[2].AbsolutePath);
    }
}

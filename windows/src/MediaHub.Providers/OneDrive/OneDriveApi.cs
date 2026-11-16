using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Text.Json;
using MediaHub.Domain;

namespace MediaHub.Providers.OneDrive;

/// <summary>One child entry as Graph reports it, reduced to what the UI needs.</summary>
internal sealed record OneDriveEntry(string Id, string Name, long? Size, bool IsFolder, string? MimeType, long? DurationMs);

/// <summary>
/// Microsoft Graph client for one signed-in account.
/// </summary>
/// <remarks>
/// <para>
/// Two credential rules are enforced here rather than at the call sites. The bearer token is attached
/// to this client's requests only, and the client never requests a download URL that would have to be
/// stored: playback resolves <c>/content</c>, which the HTTP stack answers with a short-lived signed
/// redirect. That redirect is followed inside the existing origin-scoped redirect policy, so the
/// signature is never copied into a message, a log line or a saved field.
/// </para>
/// <para>
/// Throttling is handled, not reported away: a 429 is retried with the service's own Retry-After
/// before the user is told anything, and the message they eventually see says the retry happened.
/// </para>
/// </remarks>
internal sealed class OneDriveApi : IDisposable
{
    private readonly string _serverId;
    private readonly OneDriveConfig _config;
    private readonly HttpClient _http;
    private readonly OneDriveTokenClient _tokenClient;
    private readonly SemaphoreSlim _refreshGate = new(1, 1);
    private readonly Action<string>? _onRefreshToken;
    private OneDriveTokens? _tokens;

    public OneDriveApi(
        string serverId,
        OneDriveConfig config,
        HttpClient http,
        OneDriveTokens? tokens,
        Action<string>? onRefreshToken)
    {
        _serverId = serverId;
        _config = config;
        _http = http;
        _tokenClient = new OneDriveTokenClient(http, config);
        _tokens = tokens;
        _onRefreshToken = onRefreshToken;
    }

    /// <summary>True once a token pair exists; false means the account has to sign in again.</summary>
    public bool HasSession => _tokens is not null;

    /// <summary>Current access token for the one place that has to forward it: the media relay.</summary>
    public string? AccessToken => _tokens?.AccessToken;

    /// <summary>Current refresh token, so the owner can persist a rotation.</summary>
    public string? RefreshToken => _tokens?.RefreshToken;

    /// <summary>Replaces the token pair after an interactive sign-in.</summary>
    public void Adopt(OneDriveTokens tokens) => _tokens = tokens;

    /// <summary>Drops the pair on sign-out; the vault copy is removed by the owner.</summary>
    public void ClearSession() => _tokens = null;

    /// <summary>
    /// Refreshes when the access token is close to expiry, and reports a revoked grant as expired.
    /// </summary>
    public async Task<string> EnsureAccessTokenAsync(CancellationToken ct)
    {
        OneDriveTokens? tokens = _tokens;
        if (tokens is null) throw AuthRequired();

        if (!tokens.NeedsRefresh(DateTimeOffset.UtcNow)) return tokens.AccessToken;

        await _refreshGate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            tokens = _tokens;
            if (tokens is null) throw AuthRequired();
            if (!tokens.NeedsRefresh(DateTimeOffset.UtcNow)) return tokens.AccessToken;
            return (await RefreshLockedAsync(ct).ConfigureAwait(false)).AccessToken;
        }
        finally
        {
            _refreshGate.Release();
        }
    }

    /// <summary>Forces a refresh after the service answered 401, and returns the new access token.</summary>
    private async Task<OneDriveTokens> RefreshLockedAsync(CancellationToken ct)
    {
        OneDriveTokens current = _tokens ?? throw AuthRequired();
        try
        {
            OneDriveTokens refreshed = await _tokenClient
                .RefreshAsync(current.RefreshToken, ct)
                .ConfigureAwait(false);
            _tokens = refreshed;

            // Entra rotates the refresh token on use. The owner writes it to the vault; this client
            // only hands it over, so a crash cannot leave a half-written credential behind.
            if (!string.IsNullOrEmpty(refreshed.RefreshToken))
                _onRefreshToken?.Invoke(refreshed.RefreshToken);

            return refreshed;
        }
        catch (OneDriveAuthException ex)
        {
            throw ex.Failure switch
            {
                OneDriveAuthFailure.InvalidGrant => new ProviderException(
                    _serverId, ProviderErrorCode.AuthExpired, "OneDrive 授权已失效（令牌过期或被撤销），请重新登录。"),
                OneDriveAuthFailure.ConsentRequired => new ProviderException(
                    _serverId, ProviderErrorCode.AuthRequired, ex.Message),
                OneDriveAuthFailure.Configuration => new ProviderException(
                    _serverId, ProviderErrorCode.Connection, ex.Message),
                OneDriveAuthFailure.Network => new ProviderException(
                    _serverId, ProviderErrorCode.Network, ex.Message),
                _ => new ProviderException(_serverId, ProviderErrorCode.AuthExpired, ex.Message),
            };
        }
    }

    /// <summary>Signed-in account identity, used to name the source and to key it.</summary>
    public async Task<(string Id, string Name)> GetProfileAsync(CancellationToken ct)
    {
        using JsonDocument document = await GetJsonAsync("me?$select=id,displayName,userPrincipalName,mail", ct)
            .ConfigureAwait(false);
        JsonElement root = document.RootElement;

        string id = String(root, "id");
        if (id.Length == 0)
            throw new ProviderException(_serverId, ProviderErrorCode.Parse, "OneDrive 没有返回账号标识，请重试。");

        string name = String(root, "userPrincipalName");
        if (name.Length == 0) name = String(root, "mail");
        if (name.Length == 0) name = String(root, "displayName");
        if (name.Length == 0) name = "OneDrive";
        return (id, name);
    }

    /// <summary>
    /// Lists one folder, following Graph's paging links until the requested number of entries is held.
    /// </summary>
    /// <param name="folderId">Null for the drive root.</param>
    /// <param name="take">How many entries the caller can display; the rest is left for the next visit.</param>
    public async Task<IReadOnlyList<OneDriveEntry>> ListChildrenAsync(
        string? folderId, int take, CancellationToken ct)
    {
        // One extra entry tells the caller whether another page exists without asking for all of them.
        int wanted = Math.Clamp(take, 1, 1000) + 1;
        var entries = new List<OneDriveEntry>(Math.Min(wanted, 200));

        string path = string.IsNullOrEmpty(folderId)
            ? "me/drive/root/children"
            : "me/drive/items/" + Uri.EscapeDataString(folderId) + "/children";
        string? url = path + "?$select=id,name,size,folder,file,video&$top=200";

        while (url is not null && entries.Count < wanted)
        {
            using JsonDocument document = await GetJsonAsync(url, ct).ConfigureAwait(false);
            JsonElement root = document.RootElement;

            if (root.TryGetProperty("value", out JsonElement value) && value.ValueKind == JsonValueKind.Array)
            {
                foreach (JsonElement child in value.EnumerateArray())
                {
                    entries.Add(ReadEntry(child));
                    if (entries.Count >= wanted) break;
                }
            }

            url = root.TryGetProperty("@odata.nextLink", out JsonElement next) ? next.GetString() : null;
            if (url is not null && !IsGraphUrl(url))
            {
                // A paging link pointing somewhere else would be asked for with this account's token.
                throw new ProviderException(_serverId, ProviderErrorCode.Parse,
                    "OneDrive 返回了非预期的分页地址，已停止加载。请重试。");
            }
        }

        return entries;
    }

    /// <summary>
    /// Pre-authenticated download endpoint.
    /// </summary>
    /// <remarks>
    /// The URL itself carries no secret: the service answers it with a short-lived signed redirect,
    /// which the relay follows with the account token stripped by the redirect policy. Nothing that
    /// could be replayed is stored or written to a log.
    /// </remarks>
    public Uri ContentUri(string itemId)
        => new(_config.GraphBase, "me/drive/items/" + Uri.EscapeDataString(itemId) + "/content");

    /// <summary>Fetches one item's metadata, for a playback request that arrived without it.</summary>
    public async Task<OneDriveEntry> GetItemAsync(string itemId, CancellationToken ct)
    {
        using JsonDocument document = await GetJsonAsync(
            "me/drive/items/" + Uri.EscapeDataString(itemId), ct).ConfigureAwait(false);
        return ReadEntry(document.RootElement);
    }

    private async Task<JsonDocument> GetJsonAsync(string relativeOrAbsolute, CancellationToken ct)
    {
        using HttpResponseMessage response = await SendAsync(relativeOrAbsolute, ct).ConfigureAwait(false);
        string body = await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        try
        {
            return JsonDocument.Parse(body);
        }
        catch (JsonException ex)
        {
            throw new ProviderException(_serverId, ProviderErrorCode.Parse, "OneDrive 返回了无法解析的数据。", ex);
        }
    }

    /// <summary>
    /// Sends one request with token refresh, 429 backoff and readable status mapping.
    /// </summary>
    private async Task<HttpResponseMessage> SendAsync(string relativeOrAbsolute, CancellationToken ct)
    {
        string access = await EnsureAccessTokenAsync(ct).ConfigureAwait(false);
        Uri uri = IsAbsolute(relativeOrAbsolute)
            ? new Uri(relativeOrAbsolute)
            : new Uri(_config.GraphBase, relativeOrAbsolute);

        const int maxAttempts = 4;
        for (int attempt = 0; ; attempt++)
        {
            ct.ThrowIfCancellationRequested();

            using var request = new HttpRequestMessage(HttpMethod.Get, uri);
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", access);

            HttpResponseMessage response;
            try
            {
                response = await _http
                    .SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct)
                    .ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or IOException)
            {
                throw new ProviderException(_serverId, ProviderErrorCode.Network,
                    "无法连接 OneDrive，请检查网络连接后重试。", ex);
            }

            int status = (int)response.StatusCode;

            if (status == 401 && attempt == 0)
            {
                response.Dispose();
                // The token was rejected before its clock ran out: revoke it and refresh once. A second
                // 401 is a real authentication failure, not something to keep retrying.
                await _refreshGate.WaitAsync(ct).ConfigureAwait(false);
                try
                {
                    access = (await RefreshLockedAsync(ct).ConfigureAwait(false)).AccessToken;
                }
                finally
                {
                    _refreshGate.Release();
                }
                continue;
            }

            if (status == 429 && attempt < maxAttempts - 1)
            {
                TimeSpan delay = RetryDelay(response, attempt);
                response.Dispose();
                await Task.Delay(delay, ct).ConfigureAwait(false);
                continue;
            }

            if (response.IsSuccessStatusCode) return response;

            using (response)
            {
                throw MapStatus(status);
            }
        }
    }

    /// <summary>A readable "sign in again" that does not print the internal source key.</summary>
    private ProviderException AuthRequired() => new(
        _serverId, ProviderErrorCode.AuthRequired,
        "需要登录 OneDrive：请在来源页面点击“重新登录”完成浏览器授权。");

    private ProviderException MapStatus(int status) => status switch
    {
        401 => new ProviderException(_serverId, ProviderErrorCode.AuthExpired,
            "OneDrive 登录状态已过期，请重新登录。"),
        403 => new ProviderException(_serverId, ProviderErrorCode.AuthFailed,
            "没有访问权限：这个 OneDrive 位置当前账号打不开，可能权限已被收回。"),
        404 => new ProviderException(_serverId, ProviderErrorCode.NotFound,
            "文件或文件夹已被删除或移走（OneDrive 上找不到它）。"),
        410 => new ProviderException(_serverId, ProviderErrorCode.NotFound,
            "文件已被永久删除（OneDrive 返回 410）。"),
        429 => new ProviderException(_serverId, ProviderErrorCode.RateLimited,
            "OneDrive 正在限流（HTTP 429），已自动退避重试 3 次仍未成功，请稍后再试。"),
        _ when status >= 500 => new ProviderException(_serverId, ProviderErrorCode.Network,
            "OneDrive 服务暂时不可用（HTTP " + status.ToString(CultureInfo.InvariantCulture) + "），请稍后重试。"),
        _ => new ProviderException(_serverId, ProviderErrorCode.Http,
            "OneDrive 请求失败（HTTP " + status.ToString(CultureInfo.InvariantCulture) + "）。"),
    };

    /// <summary>Uses the service's Retry-After when present, otherwise exponential backoff, capped.</summary>
    private static TimeSpan RetryDelay(HttpResponseMessage response, int attempt)
    {
        RetryConditionHeaderValue? retryAfter = response.Headers.RetryAfter;
        if (retryAfter is null) return TimeSpan.FromSeconds(1 << attempt);

        TimeSpan delay = retryAfter.Delta
                         ?? (retryAfter.Date is { } date ? date - DateTimeOffset.UtcNow : TimeSpan.FromSeconds(1 << attempt));
        if (delay < TimeSpan.Zero) delay = TimeSpan.Zero;
        if (delay > TimeSpan.FromSeconds(10)) delay = TimeSpan.FromSeconds(10);
        return delay;
    }

    private bool IsGraphUrl(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out Uri? uri)) return false;
        return string.Equals(uri.Scheme, _config.GraphBase.Scheme, StringComparison.OrdinalIgnoreCase)
               && string.Equals(uri.Host, _config.GraphBase.Host, StringComparison.OrdinalIgnoreCase)
               && uri.Port == _config.GraphBase.Port;
    }

    private static bool IsAbsolute(string value)
        => value.StartsWith("https://", StringComparison.OrdinalIgnoreCase)
           || value.StartsWith("http://", StringComparison.OrdinalIgnoreCase);

    private static OneDriveEntry ReadEntry(JsonElement item)
    {
        string id = String(item, "id");
        string name = String(item, "name");
        long? size = item.TryGetProperty("size", out JsonElement s) && s.TryGetInt64(out long bytes) ? bytes : null;
        bool folder = item.TryGetProperty("folder", out JsonElement f) && f.ValueKind == JsonValueKind.Object;

        string? mime = null;
        if (item.TryGetProperty("file", out JsonElement file) && file.ValueKind == JsonValueKind.Object)
            mime = String(file, "mimeType");

        long? duration = null;
        if (item.TryGetProperty("video", out JsonElement video) && video.ValueKind == JsonValueKind.Object
            && video.TryGetProperty("duration", out JsonElement d) && d.TryGetInt64(out long ms))
        {
            duration = ms;
        }

        return new OneDriveEntry(id, name, size, folder, mime, duration);
    }

    private static string String(JsonElement element, string name)
        => element.TryGetProperty(name, out JsonElement value) && value.ValueKind == JsonValueKind.String
            ? value.GetString() ?? ""
            : "";

    public void Dispose()
    {
        _refreshGate.Dispose();
        _http.Dispose();
    }
}

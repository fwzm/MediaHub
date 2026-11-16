using System.Globalization;
using System.Net;
using System.Text.Json;

namespace MediaHub.Providers.OneDrive;

/// <summary>
/// The token pair held in memory for one signed-in account.
/// </summary>
/// <remarks>
/// Public because the owner (the app) performs the interactive sign-in and persists the refresh token;
/// nothing else is exposed. The refresh token is the only durable secret, the access token lives for
/// about an hour and is never written anywhere. <see cref="ToString"/> is overridden because a record's
/// generated version prints both tokens, and the first thing any log or interpolation reaches for is
/// ToString.
/// </remarks>
public sealed record OneDriveTokens(string AccessToken, string RefreshToken, DateTimeOffset ExpiresAtUtc)
{
    /// <summary>Refresh slightly early so a request cannot start with a token that expires mid-flight.</summary>
    public bool NeedsRefresh(DateTimeOffset now) => ExpiresAtUtc - now < TimeSpan.FromMinutes(5);

    public override string ToString()
        => "OneDriveTokens { AccessToken = (present), RefreshToken = (present), ExpiresAtUtc = "
           + ExpiresAtUtc.ToString("o", CultureInfo.InvariantCulture) + " }";
}

/// <summary>Why a sign-in or refresh did not produce a token.</summary>
internal enum OneDriveAuthFailure
{
    /// <summary>The user closed the consent page or pressed "cancel".</summary>
    Cancelled,

    /// <summary>The refresh token is no longer accepted; only a new sign-in helps.</summary>
    InvalidGrant,

    /// <summary>The account or its administrator has not granted the file scope.</summary>
    ConsentRequired,

    /// <summary>The client id / redirect URI is not accepted by the service.</summary>
    Configuration,

    /// <summary>The token endpoint could not be reached.</summary>
    Network,

    /// <summary>The service answered something this build does not understand.</summary>
    Protocol,
}

/// <summary>
/// A sign-in failure carrying a sentence the UI can show as-is.
/// </summary>
/// <remarks>
/// Deliberately not a <see cref="MediaHub.Domain.ProviderException"/>: this type is also used before a
/// provider exists (the add-source dialog), and its message never contains a token, a code or the
/// service's <c>error_description</c>, which routinely carries an account identifier.
/// </remarks>
public sealed class OneDriveAuthException : Exception
{
    internal OneDriveAuthFailure Failure { get; }

    internal OneDriveAuthException(OneDriveAuthFailure failure, string message, Exception? cause = null)
        : base(message, cause)
    {
        Failure = failure;
    }
}

/// <summary>Exchanges an authorization code or a refresh token for an access token.</summary>
internal sealed class OneDriveTokenClient
{
    private readonly HttpClient _http;
    private readonly OneDriveConfig _config;

    public OneDriveTokenClient(HttpClient http, OneDriveConfig config)
    {
        _http = http;
        _config = config;
    }

    public Task<OneDriveTokens> ExchangeCodeAsync(string code, string verifier, CancellationToken ct)
        => RequestAsync(new Dictionary<string, string>
        {
            ["grant_type"] = "authorization_code",
            ["code"] = code,
            ["redirect_uri"] = _config.RedirectUri,
            ["code_verifier"] = verifier,
        }, previousRefreshToken: null, ct);

    public Task<OneDriveTokens> RefreshAsync(string refreshToken, CancellationToken ct)
        => RequestAsync(new Dictionary<string, string>
        {
            ["grant_type"] = "refresh_token",
            ["refresh_token"] = refreshToken,
        }, previousRefreshToken: refreshToken, ct);

    private async Task<OneDriveTokens> RequestAsync(
        Dictionary<string, string> form, string? previousRefreshToken, CancellationToken ct)
    {
        form["client_id"] = _config.ClientId;
        form["scope"] = _config.Scope;

        HttpResponseMessage response;
        try
        {
            using var content = new FormUrlEncodedContent(form);
            response = await _http.PostAsync(_config.TokenEndpoint, content, ct).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or IOException)
        {
            throw new OneDriveAuthException(OneDriveAuthFailure.Network,
                "无法连接 Microsoft 登录服务，请检查网络后重试。", ex);
        }

        using (response)
        {
            string body = await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
                throw MapError(response.StatusCode, body);

            return ParseTokens(body, previousRefreshToken);
        }
    }

    private static OneDriveTokens ParseTokens(string body, string? previousRefreshToken)
    {
        try
        {
            using JsonDocument document = JsonDocument.Parse(body);
            JsonElement root = document.RootElement;

            string access = root.TryGetProperty("access_token", out JsonElement a) ? a.GetString() ?? "" : "";
            string refresh = root.TryGetProperty("refresh_token", out JsonElement r) ? r.GetString() ?? "" : "";
            int expiresIn = root.TryGetProperty("expires_in", out JsonElement e) && e.TryGetInt32(out int seconds)
                ? seconds
                : 3600;

            if (access.Length == 0)
                throw new OneDriveAuthException(OneDriveAuthFailure.Protocol,
                    "Microsoft 登录服务没有返回访问令牌，请重试。");

            // A refresh response may omit the refresh token, in which case the previous one stays valid.
            if (refresh.Length == 0) refresh = previousRefreshToken ?? "";
            if (refresh.Length == 0)
                throw new OneDriveAuthException(OneDriveAuthFailure.Protocol,
                    "Microsoft 登录服务没有返回刷新令牌。请确认应用注册已勾选 offline_access 权限。");

            return new OneDriveTokens(access, refresh,
                DateTimeOffset.UtcNow.AddSeconds(Math.Clamp(expiresIn, 60, 86400)));
        }
        catch (JsonException ex)
        {
            throw new OneDriveAuthException(OneDriveAuthFailure.Protocol,
                "Microsoft 登录服务返回了无法解析的响应，请重试。", ex);
        }
    }

    /// <summary>
    /// Turns an OAuth error body into a readable sentence.
    /// </summary>
    /// <remarks>
    /// The service's <c>error_description</c> is inspected for the AADSTS code only and never copied
    /// into the message: it embeds the account, the tenant and sometimes a correlation id.
    /// </remarks>
    private OneDriveAuthException MapError(HttpStatusCode status, string body)
    {
        string error = "";
        string description = "";
        try
        {
            using JsonDocument document = JsonDocument.Parse(body);
            if (document.RootElement.TryGetProperty("error", out JsonElement e)) error = e.GetString() ?? "";
            if (document.RootElement.TryGetProperty("error_description", out JsonElement d))
                description = d.GetString() ?? "";
        }
        catch (JsonException)
        {
            // Non-JSON error body: fall through to the status-based message.
        }

        if (status == HttpStatusCode.TooManyRequests)
            return new OneDriveAuthException(OneDriveAuthFailure.Network,
                "Microsoft 登录服务正在限流（HTTP 429），请稍后再试。");

        bool consent = error is "consent_required" or "interaction_required" or "invalid_scope"
                       || description.Contains("AADSTS65001", StringComparison.Ordinal)
                       || description.Contains("AADSTS90094", StringComparison.Ordinal);
        if (consent)
            return new OneDriveAuthException(OneDriveAuthFailure.ConsentRequired,
                "该 Microsoft 账号（或其管理员）尚未同意读取 OneDrive 文件。请在授权页同意后再试，或请管理员授予 Files.Read.All。");

        if (error == "invalid_grant"
            || description.Contains("AADSTS70008", StringComparison.Ordinal)
            || description.Contains("AADSTS50173", StringComparison.Ordinal)
            || description.Contains("AADSTS700082", StringComparison.Ordinal))
            return new OneDriveAuthException(OneDriveAuthFailure.InvalidGrant,
                "OneDrive 授权已失效（令牌过期或被撤销），请重新登录。");

        if (error == "invalid_client"
            || description.Contains("AADSTS700016", StringComparison.Ordinal)
            || description.Contains("AADSTS50011", StringComparison.Ordinal))
            return new OneDriveAuthException(OneDriveAuthFailure.Configuration,
                "OneDrive 客户端 ID 或回调地址未被接受（" + (error.Length > 0 ? error : "invalid_client") +
                "）。请检查 Entra 应用注册中的客户端 ID 与重定向 URI。");

        return new OneDriveAuthException(OneDriveAuthFailure.Protocol,
            "Microsoft 登录服务返回了错误（HTTP " + ((int)status).ToString(CultureInfo.InvariantCulture) +
            (error.Length > 0 ? "，" + error : "") + "），请稍后重试。");
    }
}

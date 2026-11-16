namespace MediaHub.Providers.OneDrive;

/// <summary>
/// Public-client registration parameters for the OneDrive sign-in.
/// </summary>
/// <remarks>
/// <para>
/// Nothing here is a secret: a desktop app is a public client and cannot keep one. The values are
/// nevertheless configuration rather than constants, because the client id belongs to whoever
/// registered the application in Microsoft Entra and a repository must not carry somebody's tenant
/// identity. The defaults are empty and the app says what is missing instead of starting a sign-in
/// that the service would reject.
/// </para>
/// <para>
/// The redirect URI must be a loopback address. Entra answers the authorization code to the browser,
/// which forwards it to this machine, so no hosted endpoint and no client secret is involved.
/// </para>
/// </remarks>
public sealed record OneDriveConfig
{
    /// <summary>Loopback callback used when the user has not registered a different port.</summary>
    public const string DefaultRedirectUri = "http://localhost:53682/";

    /// <summary>Scopes needed to browse and stream the signed-in user's own drive.</summary>
    public static readonly string[] DefaultScopes = ["offline_access", "Files.Read.All", "User.Read"];

    public string ClientId { get; init; } = "";

    public string RedirectUri { get; init; } = DefaultRedirectUri;

    /// <summary>Entra tenant segment; <c>common</c> accepts work, school and personal accounts.</summary>
    public string Tenant { get; init; } = "common";

    /// <summary>Graph root. Overridable so the contract can be exercised against a local server.</summary>
    public Uri GraphBase { get; init; } = new("https://graph.microsoft.com/v1.0/");

    /// <summary>Token service root, same reason as <see cref="GraphBase"/>.</summary>
    public Uri AuthorityBase { get; init; } = new("https://login.microsoftonline.com/");

    /// <summary>Scopes as one space-separated string, the form both endpoints expect.</summary>
    public string Scope => string.Join(" ", DefaultScopes);

    public string AuthorizeEndpoint => new Uri(AuthorityBase, Tenant + "/oauth2/v2.0/authorize").ToString();

    public string TokenEndpoint => new Uri(AuthorityBase, Tenant + "/oauth2/v2.0/token").ToString();

    /// <summary>True when a sign-in can be attempted at all.</summary>
    public bool IsConfigured => Validate() is null;

    /// <summary>
    /// Reads the configurable parameters from settings.
    /// </summary>
    /// <remarks>
    /// Empty stays empty. Substituting a placeholder client id would produce a browser page saying the
    /// application does not exist, which reads like a OneDrive outage rather than an unfinished setup.
    /// </remarks>
    public static OneDriveConfig FromSettings(string? clientId, string? redirectUri)
        => new()
        {
            ClientId = clientId?.Trim() ?? "",
            RedirectUri = string.IsNullOrWhiteSpace(redirectUri) ? DefaultRedirectUri : redirectUri.Trim(),
        };

    /// <summary>Null when usable, otherwise one readable sentence naming what to fix.</summary>
    public string? Validate()
    {
        if (string.IsNullOrWhiteSpace(ClientId))
            return MissingClientIdHelp;

        if (!Guid.TryParse(ClientId, out _))
            return "OneDrive 客户端 ID 不是有效的 GUID：请把 Entra 应用注册里的“应用程序(客户端) ID”原样填入设置。";

        if (!TryGetRedirectUri(out Uri redirect, out string? error))
            return error!;

        if (!string.Equals(redirect.Scheme, "http", StringComparison.OrdinalIgnoreCase))
            return "OneDrive 回调地址必须是 http://localhost 形式：桌面客户端无法接收 https 回调。";

        if (!IsLoopback(redirect.Host))
            return "OneDrive 回调地址必须是本机回环地址（localhost、127.0.0.1 或 [::1]）：" + redirect.Host;

        if (!string.IsNullOrEmpty(redirect.Fragment) || !string.IsNullOrEmpty(redirect.Query))
            return "OneDrive 回调地址不能带查询参数或片段。";

        return null;
    }

    /// <summary>Readable next step shown when <see cref="ClientId"/> has not been filled in.</summary>
    public const string MissingClientIdHelp =
        "尚未配置 OneDrive 客户端 ID。请先完成两步：\n" +
        "1）在 Microsoft Entra 管理中心注册应用，平台选“移动和桌面应用程序”，" +
        "回调地址填 http://localhost（或本设置里的完整回调地址），并允许公共客户端流；\n" +
        "2）把“应用程序(客户端) ID”填进 settings.json 的 oneDriveClientId（可选 oneDriveRedirectUri），重启应用。";

    /// <summary>Resolves the callback URI, or explains why it cannot be used.</summary>
    public bool TryGetRedirectUri(out Uri uri, out string? error)
    {
        uri = new Uri(DefaultRedirectUri);
        error = null;

        if (!Uri.TryCreate(RedirectUri, UriKind.Absolute, out Uri? parsed))
        {
            error = "OneDrive 回调地址不是有效的绝对地址：" + RedirectUri;
            return false;
        }

        if (!string.Equals(parsed.Scheme, "http", StringComparison.OrdinalIgnoreCase)
            || !IsLoopback(parsed.Host)
            || !string.IsNullOrEmpty(parsed.Fragment)
            || !string.IsNullOrEmpty(parsed.Query))
        {
            error = "OneDrive 回调地址必须是 http://localhost[:端口]/ 形式。";
            return false;
        }

        uri = parsed;
        return true;
    }

    private static bool IsLoopback(string host)
        => string.Equals(host, "localhost", StringComparison.OrdinalIgnoreCase)
           || string.Equals(host, "127.0.0.1", StringComparison.OrdinalIgnoreCase)
           || string.Equals(host, "[::1]", StringComparison.OrdinalIgnoreCase)
           || string.Equals(host, "::1", StringComparison.OrdinalIgnoreCase);
}

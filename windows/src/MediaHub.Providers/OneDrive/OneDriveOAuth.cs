using System.Net;
using System.Text;

namespace MediaHub.Providers.OneDrive;

/// <summary>
/// Runs the authorization-code flow with PKCE: system browser in, loopback callback out.
/// </summary>
/// <remarks>
/// <para>
/// The browser is where the account password is typed, so this app never sees it and no embedded
/// web view has to be trusted. The authorization code arrives on a loopback socket owned by this
/// process; the code is exchanged with the verifier that never left memory, which is what makes an
/// intercepted code useless to anyone else.
/// </para>
/// <para>
/// Everything the service might say is translated here: cancel, consent, invalid client, timeout and
/// port conflict each get one sentence, and no token, code, or <c>error_description</c> is logged or
/// surfaced.
/// </para>
/// </remarks>
public static class OneDriveOAuth
{
    public static readonly TimeSpan DefaultTimeout = TimeSpan.FromMinutes(3);

    /// <summary>
    /// Interactive sign-in followed by the two requests that prove the grant is usable.
    /// </summary>
    /// <remarks>
    /// A token that works for <c>/me</c> can still be refused for files when the account or its tenant
    /// never consented to <c>Files.Read.All</c>; checking the drive root here turns that into a refusal
    /// at add time instead of a source that lists nothing.
    /// </remarks>
    public static async Task<(OneDriveTokens Tokens, string AccountId, string AccountName)> SignInAndVerifyAsync(
        OneDriveConfig config,
        MediaHub.Net.NetworkPolicy policy,
        Action<Uri> launchBrowser,
        TimeSpan timeout,
        CancellationToken ct)
    {
        using var factory = new MediaHub.Net.MediaSourceHttpClientFactory(policy);
        using HttpClient http = factory.CreateApiClient();

        OneDriveTokens tokens = await SignInAsync(config, http, launchBrowser, timeout, ct).ConfigureAwait(false);

        using var api = new OneDriveApi("onedrive:verify", config, http, tokens, onRefreshToken: null);
        (string id, string name) = await api.GetProfileAsync(ct).ConfigureAwait(false);
        await api.ListChildrenAsync(null, 1, ct).ConfigureAwait(false);
        return (tokens, id, name);
    }

    /// <param name="http">Client used only for the token endpoint.</param>
    /// <param name="launchBrowser">Opens the system browser; injected so tests can answer the callback.</param>
    public static async Task<OneDriveTokens> SignInAsync(
        OneDriveConfig config,
        HttpClient http,
        Action<Uri> launchBrowser,
        TimeSpan timeout,
        CancellationToken ct)
    {
        if (config.Validate() is { } error)
            throw new OneDriveAuthException(OneDriveAuthFailure.Configuration, error);

        if (!config.TryGetRedirectUri(out Uri redirect, out _))
            throw new OneDriveAuthException(OneDriveAuthFailure.Configuration, "OneDrive 回调地址无效。");

        PkceChallenge pkce = PkceChallenge.Create();
        using var listener = new HttpListener();
        listener.Prefixes.Add(redirect.GetLeftPart(UriPartial.Authority) + "/");
        try
        {
            listener.Start();
        }
        catch (Exception ex) when (ex is HttpListenerException or PlatformNotSupportedException)
        {
            throw new OneDriveAuthException(OneDriveAuthFailure.Configuration,
                "无法在本机 " + redirect.Port.ToString(System.Globalization.CultureInfo.InvariantCulture) +
                " 端口接收 OneDrive 授权回调（端口被占用或系统不允许监听）。" +
                "请在 settings.json 的 oneDriveRedirectUri 改成另一个端口，并在 Entra 注册里同步。", ex);
        }

        Uri authorizeUri = pkce.AuthorizeUri(config);
        try
        {
            launchBrowser(authorizeUri);
        }
        catch (Exception ex)
        {
            throw new OneDriveAuthException(OneDriveAuthFailure.Configuration,
                "无法打开系统浏览器，请检查默认浏览器设置后重试。", ex);
        }

        (string Code, string State, string? Error) callback = await WaitForCallbackAsync(listener, redirect, timeout, ct)
            .ConfigureAwait(false);

        if (!string.IsNullOrEmpty(callback.Error))
        {
            throw callback.Error switch
            {
                "access_denied" => new OneDriveAuthException(OneDriveAuthFailure.Cancelled,
                    "你取消了 OneDrive 授权，没有添加任何内容。"),
                "consent_required" or "interaction_required" => new OneDriveAuthException(
                    OneDriveAuthFailure.ConsentRequired,
                    "该 Microsoft 账号（或其管理员）尚未同意读取 OneDrive 文件，请同意后再试。"),
                _ => new OneDriveAuthException(OneDriveAuthFailure.Protocol,
                    "Microsoft 返回了授权错误（" + callback.Error + "），请重试。"),
            };
        }

        if (!string.Equals(callback.State, pkce.State, StringComparison.Ordinal))
            throw new OneDriveAuthException(OneDriveAuthFailure.Protocol,
                "授权回调与本次请求不匹配，已拒绝这次授权。请重试。");

        if (string.IsNullOrEmpty(callback.Code))
            throw new OneDriveAuthException(OneDriveAuthFailure.Protocol,
                "授权回调没有携带授权码，请重试。");

        var tokens = new OneDriveTokenClient(http, config);
        return await tokens.ExchangeCodeAsync(callback.Code, pkce.Verifier, ct).ConfigureAwait(false);
    }

    private static async Task<(string Code, string State, string? Error)> WaitForCallbackAsync(
        HttpListener listener, Uri redirect, TimeSpan timeout, CancellationToken ct)
    {
        using var timeoutSource = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeoutSource.CancelAfter(timeout);

        while (true)
        {
            HttpListenerContext context;
            try
            {
                context = await listener.GetContextAsync().WaitAsync(timeoutSource.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (!ct.IsCancellationRequested)
            {
                throw new OneDriveAuthException(OneDriveAuthFailure.Protocol,
                    "等待浏览器授权超时（" + Math.Round(timeout.TotalMinutes) + " 分钟）。" +
                    "请重试，并确认浏览器已打开且能访问 login.microsoftonline.com。");
            }

            string path = context.Request.Url?.AbsolutePath ?? "/";
            if (!string.Equals(path, redirect.AbsolutePath, StringComparison.OrdinalIgnoreCase))
            {
                // Browsers request /favicon.ico next to the callback; answering it would end the wait
                // before the code arrives.
                await ReplyAsync(context, "OneDrive 授权", "请回到 MediaHub 完成授权。", ct).ConfigureAwait(false);
                continue;
            }

            var query = context.Request.QueryString;
            string? error = query["error"];
            string code = query["code"] ?? "";
            string state = query["state"] ?? "";

            string title = string.IsNullOrEmpty(error) ? "授权已完成" : "授权未完成";
            string message = string.IsNullOrEmpty(error)
                ? "已收到授权结果，可以关闭这个浏览器窗口并回到 MediaHub。"
                : "授权没有完成，请回到 MediaHub 查看原因。";
            await ReplyAsync(context, title, message, ct).ConfigureAwait(false);

            return (code, state, error);
        }
    }

    private static async Task ReplyAsync(HttpListenerContext context, string title, string message, CancellationToken ct)
    {
        string html =
            "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">" +
            "<title>" + WebUtility.HtmlEncode(title) + "</title>" +
            "<style>body{font-family:system-ui,'Segoe UI',sans-serif;background:#1b1b1b;color:#eee;" +
            "display:flex;align-items:center;justify-content:center;height:100vh;margin:0}" +
            "main{max-width:32rem;padding:2rem}h1{font-size:1.4rem;font-weight:600}p{color:#bbb;line-height:1.6}" +
            "</style></head><body><main><h1>" + WebUtility.HtmlEncode(title) + "</h1><p>" +
            WebUtility.HtmlEncode(message) + "</p></main></body></html>";
        byte[] body = Encoding.UTF8.GetBytes(html);

        try
        {
            context.Response.StatusCode = 200;
            context.Response.ContentType = "text/html; charset=utf-8";
            context.Response.ContentLength64 = body.Length;
            context.Response.Headers["Cache-Control"] = "no-store";
            await context.Response.OutputStream.WriteAsync(body, ct).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is IOException or HttpListenerException or ObjectDisposedException)
        {
            // The browser may close the tab while the page is being written; that is not a sign-in
            // failure, the query string has already been read.
        }
        finally
        {
            try { context.Response.Close(); } catch { /* already gone */ }
        }
    }
}

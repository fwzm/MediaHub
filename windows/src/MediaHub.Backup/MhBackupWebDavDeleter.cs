using System.Net;
using System.Net.Http.Headers;
using System.Text;

namespace MediaHub.Backup;

/// <summary>
/// WebDAV 备份删除：默认只做预览（一个请求都不发），必须显式确认才真的删。
/// </summary>
/// <remarks>
/// 删除不可逆，所以把「克制」写进语义：<br/>
/// 1) 未确认时零联网：调用方拿到的是 ConfirmRequired，不是「删失败」；<br/>
/// 2) 默认只允许删 .mhbackup（备份容器），删别的文件要调用方显式放行；<br/>
/// 3) 确认后先确认文件存在（HEAD，个别服务端不支持时退回 PROPFIND Depth: 0），
///    存在才发 DELETE，避免把「本来就没有」误报成「删除成功」；<br/>
/// 4) 只删单个文件，集合一律拒绝（本工具不做递归删除）。
/// </remarks>
public static class MhBackupWebDavDeleter
{
    public const int DefaultTimeoutSeconds = 60;

    /// <summary>稳定原因串：日志与回执都按这些字符串判断，不改写同义句。</summary>
    public static class Reasons
    {
        public const string NotHttp = "删除地址必须是 http 或 https";
        public const string MissingPath = "删除地址必须指向一个文件（路径为空）";
        public const string DirectoryTarget = "删除地址指向的是目录：本工具不删集合";
        public const string NotABackupFile = "只删 .mhbackup 备份文件；要删别的文件请先确认再加 --any";
        public const string ConfirmRequired = "这是预览：没有删除任何东西。要真删请加 --confirm";
        public const string NotFound = "远端没有这个文件（404）";
        public const string AuthenticationFailed = "远端拒绝这次删除：账号或口令不对（401/403）";
        public const string NotSupported = "远端不支持删除（405/501）";
        public const string ServerError = "远端出错";
        public const string Timeout = "删除超时";
        public const string Network = "连不上远端";
    }

    /// <summary>一次删除尝试的结果。<paramref name="Deleted"/> 只在远端真的回 200/204 时为 true。</summary>
    public sealed record Result(bool Ok, bool Deleted, bool CheckedExistence, int StatusCode, string? Reason)
    {
        public static Result Preview() => new(false, false, false, 0, Reasons.ConfirmRequired);
    }

    /// <summary>删一个远端备份文件；<paramref name="confirm"/> 为 false 时不发任何请求。</summary>
    public static async Task<Result> DeleteAsync(
        Uri target,
        string? userName = null,
        string? password = null,
        bool confirm = false,
        bool allowNonBackupFile = false,
        HttpMessageHandler? handler = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(target);

        if (target.Scheme != Uri.UriSchemeHttp && target.Scheme != Uri.UriSchemeHttps)
            return new Result(false, false, false, 0, Reasons.NotHttp);
        if (string.IsNullOrEmpty(target.AbsolutePath) || target.AbsolutePath == "/")
            return new Result(false, false, false, 0, Reasons.MissingPath);
        if (target.AbsolutePath.EndsWith("/", StringComparison.Ordinal))
            return new Result(false, false, false, 0, Reasons.DirectoryTarget);
        if (!allowNonBackupFile && !target.AbsolutePath.EndsWith(".mhbackup", StringComparison.OrdinalIgnoreCase))
            return new Result(false, false, false, 0, Reasons.NotABackupFile);
        if (!confirm) return Result.Preview();

        using var client = new HttpClient(handler ?? new HttpClientHandler(), disposeHandler: false)
        {
            Timeout = TimeSpan.FromSeconds(DefaultTimeoutSeconds),
        };

        try
        {
            HttpStatusCode exists = await CheckExistsAsync(client, target, userName, password, cancellationToken).ConfigureAwait(false);
            if (exists == HttpStatusCode.NotFound)
                return new Result(false, false, true, (int)exists, Reasons.NotFound);
            if (exists == HttpStatusCode.Unauthorized || exists == HttpStatusCode.Forbidden)
                return new Result(false, false, true, (int)exists, Reasons.AuthenticationFailed);
            if (exists != HttpStatusCode.OK && exists != HttpStatusCode.NoContent)
            {
                string reason = exists == (HttpStatusCode)405 || exists == HttpStatusCode.NotImplemented
                    ? Reasons.NotSupported
                    : ReasonFor((int)exists);
                return new Result(false, false, true, (int)exists, reason);
            }

            using var request = new HttpRequestMessage(HttpMethod.Delete, target);
            ApplyCredentials(request, userName, password);
            using HttpResponseMessage response = await client
                .SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
                .ConfigureAwait(false);
            int status = (int)response.StatusCode;
            if (response.StatusCode == HttpStatusCode.OK || response.StatusCode == HttpStatusCode.NoContent)
                return new Result(true, true, true, status, null);
            if (response.StatusCode == HttpStatusCode.NotFound)
                return new Result(false, false, true, status, Reasons.NotFound);
            return new Result(false, false, true, status, ReasonFor(status));
        }
        catch (TaskCanceledException)
        {
            return new Result(false, false, false, 0, Reasons.Timeout);
        }
        catch (HttpRequestException)
        {
            return new Result(false, false, false, 0, Reasons.Network);
        }
    }

    /// <summary>先确认文件存在：HEAD；服务端不支持 HEAD（405/501）时退回 PROPFIND Depth: 0。</summary>
    private static async Task<HttpStatusCode> CheckExistsAsync(
        HttpClient client,
        Uri target,
        string? userName,
        string? password,
        CancellationToken cancellationToken)
    {
        using (var head = new HttpRequestMessage(HttpMethod.Head, target))
        {
            ApplyCredentials(head, userName, password);
            using HttpResponseMessage response = await client
                .SendAsync(head, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
                .ConfigureAwait(false);
            if (response.StatusCode != (HttpStatusCode)405 && response.StatusCode != HttpStatusCode.NotImplemented)
                return response.StatusCode;
        }

        var propfind = new HttpRequestMessage(new HttpMethod("PROPFIND"), target)
        {
            Content = new StringContent(MhBackupWebDavLister.PropfindBody, Encoding.UTF8, "application/xml"),
        };
        propfind.Headers.Add("Depth", "0");
        ApplyCredentials(propfind, userName, password);
        using HttpResponseMessage probe = await client
            .SendAsync(propfind, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
            .ConfigureAwait(false);
        int code = (int)probe.StatusCode;
        if (code == 207 || code == 200) return HttpStatusCode.OK;
        return probe.StatusCode;
    }

    private static void ApplyCredentials(HttpRequestMessage request, string? userName, string? password)
    {
        if (string.IsNullOrEmpty(userName)) return;
        string raw = userName + ":" + (password ?? string.Empty);
        string encoded = Convert.ToBase64String(Encoding.UTF8.GetBytes(raw));
        request.Headers.Authorization = new AuthenticationHeaderValue("Basic", encoded);
    }

    private static string ReasonFor(int status) => status switch
    {
        401 or 403 => Reasons.AuthenticationFailed,
        404 => Reasons.NotFound,
        405 or 501 => Reasons.NotSupported,
        >= 500 => Reasons.ServerError,
        _ => "远端拒绝这次删除（HTTP " + status + "）",
    };
}

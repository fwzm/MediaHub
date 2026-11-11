using System.Text;

namespace MediaHub.Backup;

/// <summary>把加密备份容器 PUT 到一个 WebDAV 地址。</summary>
/// <remarks>
/// WebDAV 客户端在 <c>MediaHub.Providers.WebDav</c> 里只做过读（PROPFIND / OPTIONS / range GET）。
/// 备份上传只需要「写一个文件」，所以这里用一个独立的窄客户端，不把播放器侧的凭据库与
/// 认证弹窗拖进命令行：账号口令由调用方（CLI）从环境变量取出后传进来，本类型不读环境、不读磁盘。
/// 只做 RFC 4918 的 PUT，以及可选的最后一级集合 MKCOL；多级创建不做猜测。
/// </remarks>
public static class MhBackupWebDavUploader
{
    public const int DefaultTimeoutSeconds = 60;

    /// <summary>远端拒绝的稳定原因串，测试与回执都用它。</summary>
    public static class Reasons
    {
        public const string EmptyContent = "备份内容为空";
        public const string NotHttp = "上传地址必须是 http 或 https";
        public const string MissingPath = "上传地址必须带文件路径";
        public const string AuthenticationFailed = "远端拒绝这次上传：账号或口令不对（401/403）";
        public const string CollectionMissing = "远端没有这个目录，且没有允许创建（409）";
        public const string AlreadyExists = "远端已有同名文件，这次上传被拒绝（412）";
        public const string PreconditionFailed = "远端拒绝了这次上传的前置条件（412）";
        public const string NotAcceptable = "远端不接受这个文件（415 或 400）";
        public const string QuotaExceeded = "远端空间不足（507）";
        public const string ServerError = "远端出错";
        public const string Timeout = "上传超时";
        public const string Network = "连不上远端";
    }

    /// <summary>一次上传的结局。成功时 <see cref="Ok"/> 为真并带远端状态码与最终地址。</summary>
    public sealed record Result(bool Ok, int StatusCode, string? Reason, Uri? Location)
    {
    }

    public static async Task<Result> UploadAsync(
        byte[] content,
        Uri target,
        string? userName = null,
        string? password = null,
        bool createCollection = false,
        HttpMessageHandler? handler = null,
        bool noOverwrite = false,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(content);
        ArgumentNullException.ThrowIfNull(target);
        if (content.Length == 0) return new Result(false, 0, Reasons.EmptyContent, null);
        if (target.Scheme != Uri.UriSchemeHttp && target.Scheme != Uri.UriSchemeHttps)
            return new Result(false, 0, Reasons.NotHttp, null);
        if (target.AbsolutePath.TrimEnd('/').Length == 0) return new Result(false, 0, Reasons.MissingPath, null);

        using var client = handler is null ? new HttpClient() : new HttpClient(handler, disposeHandler: false);
        client.Timeout = TimeSpan.FromSeconds(DefaultTimeoutSeconds);

        Result first = await PutAsync(client, content, target, userName, password, noOverwrite, cancellationToken).ConfigureAwait(false);
        if (first.Ok || !createCollection) return first;
        if (first.StatusCode != 409) return first;

        Uri? parent = ParentCollection(target);
        if (parent is null) return first;
        int mkcol = await MkColAsync(client, parent, userName, password, cancellationToken).ConfigureAwait(false);
        if (mkcol is not (201 or 200 or 204 or 405)) return new Result(false, mkcol, Reasons.CollectionMissing, parent);
        return await PutAsync(client, content, target, userName, password, noOverwrite, cancellationToken).ConfigureAwait(false);
    }

    /// <summary>只建最后一级集合；父级不存在时远端回 409，交给调用方如实报告。</summary>
    public static async Task<int> MkColAsync(
        Uri collection,
        string? userName = null,
        string? password = null,
        HttpMessageHandler? handler = null,
        CancellationToken cancellationToken = default)
    {
        using var client = handler is null ? new HttpClient() : new HttpClient(handler, disposeHandler: false);
        client.Timeout = TimeSpan.FromSeconds(DefaultTimeoutSeconds);
        return await MkColAsync(client, collection, userName, password, cancellationToken).ConfigureAwait(false);
    }

    private static async Task<int> MkColAsync(
        HttpClient client,
        Uri collection,
        string? userName,
        string? password,
        CancellationToken cancellationToken)
    {
        using var request = new HttpRequestMessage(new HttpMethod("MKCOL"), collection);
        AddAuthorization(request, userName, password);
        try
        {
            using HttpResponseMessage response = await client.SendAsync(request, cancellationToken).ConfigureAwait(false);
            return (int)response.StatusCode;
        }
        catch (TaskCanceledException)
        {
            return 0;
        }
        catch (HttpRequestException)
        {
            return 0;
        }
    }

    private static async Task<Result> PutAsync(
        HttpClient client,
        byte[] content,
        Uri target,
        string? userName,
        string? password,
        bool noOverwrite,
        CancellationToken cancellationToken)
    {
        using var request = new HttpRequestMessage(HttpMethod.Put, target)
        {
            Content = new ByteArrayContent(content),
        };
        request.Content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("application/octet-stream");
        AddAuthorization(request, userName, password);
        if (noOverwrite)
        {
            // RFC 7232：If-None-Match: * 表示「只在目标不存在时写入」，已存在时服务端回 412。
            request.Headers.TryAddWithoutValidation("If-None-Match", "*");
        }
        try
        {
            using HttpResponseMessage response = await client.SendAsync(request, cancellationToken).ConfigureAwait(false);
            int code = (int)response.StatusCode;
            if (code == 412)
            {
                return new Result(false, code, noOverwrite ? Reasons.AlreadyExists : Reasons.PreconditionFailed, target);
            }
            if (code is 200 or 201 or 204)
            {
                Uri? location = response.Headers.Location is null ? target : new Uri(target, response.Headers.Location);
                return new Result(true, code, null, location);
            }
            return new Result(false, code, ReasonFor(code), target);
        }
        catch (TaskCanceledException)
        {
            return new Result(false, 0, Reasons.Timeout, target);
        }
        catch (HttpRequestException)
        {
            return new Result(false, 0, Reasons.Network, target);
        }
    }

    private static void AddAuthorization(HttpRequestMessage request, string? userName, string? password)
    {
        if (userName is null) return;
        string pair = userName + ":" + (password ?? string.Empty);
        request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue(
            "Basic",
            Convert.ToBase64String(Encoding.UTF8.GetBytes(pair)));
    }

    internal static string ReasonFor(int code)
    {
        if (code is 401 or 403) return Reasons.AuthenticationFailed;
        if (code == 409) return Reasons.CollectionMissing;
        if (code is 400 or 415) return Reasons.NotAcceptable;
        if (code == 507) return Reasons.QuotaExceeded;
        if (code >= 500) return Reasons.ServerError;
        return "远端拒绝这次上传（HTTP " + code + "）";
    }

    /// <summary>目标文件所在的最后一级集合；目标本身像目录（以 / 结尾）时返回 null。</summary>
    internal static Uri? ParentCollection(Uri target)
    {
        string path = target.AbsolutePath;
        if (path.EndsWith("/", StringComparison.Ordinal)) return null;
        int slash = path.LastIndexOf("/", StringComparison.Ordinal);
        if (slash <= 0) return null;
        var builder = new UriBuilder(target) { Path = path[..(slash + 1)], Query = string.Empty, Fragment = string.Empty };
        return builder.Uri;
    }
}

using System.Net;
using System.Net.Http.Headers;
using System.Text;

namespace MediaHub.Backup;

/// <summary>把一个 WebDAV 上的加密备份容器取回本地。</summary>
/// <remarks>
/// 与上传器同一个取舍：只做一次 GET，不列目录、不跟随重定向到别的凭据域，账号口令由调用方传入。
/// 解析口令、解密容器都不在这里——取回的是原样字节，导入交给 <see cref="MhBackupSerializer"/>。
/// 上限沿用容器自身的大小上限，超限先看 Content-Length、再在读取时计数，避免把远端一个超大文件读进内存。
/// </remarks>
public static class MhBackupWebDavDownloader
{
    public const int DefaultTimeoutSeconds = 60;

    /// <summary>备份容器自身的字节上限；超过它的文件导入端本来也会拒绝。</summary>
    public static long DefaultMaxBytes => MhBackupSerializer.MaxFileBytes;

    /// <summary>远端失败的稳定原因串，测试与回执都用它。</summary>
    public static class Reasons
    {
        public const string NotHttp = "下载地址必须是 http 或 https";
        public const string MissingPath = "下载地址必须指向一个文件（路径为空）";
        public const string DirectoryTarget = "下载地址指向的是目录：请给到具体文件";
        public const string AuthenticationFailed = "远端拒绝这次下载：账号或口令不对（401/403）";
        public const string NotFound = "远端没有这个文件（404）";
        public const string TooLarge = "远端文件超过备份容器上限";
        public const string Empty = "远端文件是空的";
        public const string Truncated = "远端文件没有按声明长度传完（可能被截断）";
        public const string ServerError = "远端出错";
        public const string Timeout = "下载超时";
        public const string Network = "连不上远端";
    }

    /// <summary>一次下载的结局。成功时 <see cref="Content"/> 是原样字节，<see cref="DeclaredLength"/> 是远端声明长度。</summary>
    public sealed record Result(bool Ok, int StatusCode, string? Reason, byte[]? Content, long? DeclaredLength)
    {
    }

    public static async Task<Result> DownloadAsync(
        Uri source,
        string? userName = null,
        string? password = null,
        long maxBytes = 0,
        HttpMessageHandler? handler = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(source);
        if (maxBytes == 0) maxBytes = DefaultMaxBytes;
        if (maxBytes < 0) throw new ArgumentOutOfRangeException(nameof(maxBytes), "maxBytes 不能为负数");
        if (source.Scheme != Uri.UriSchemeHttp && source.Scheme != Uri.UriSchemeHttps)
            return new Result(false, 0, Reasons.NotHttp, null, null);
        if (source.AbsolutePath.TrimEnd('/').Length == 0)
            return new Result(false, 0, Reasons.MissingPath, null, null);
        if (source.AbsolutePath.EndsWith("/", StringComparison.Ordinal))
            return new Result(false, 0, Reasons.DirectoryTarget, null, null);

        using var client = handler is null ? new HttpClient() : new HttpClient(handler, disposeHandler: false);
        client.Timeout = TimeSpan.FromSeconds(DefaultTimeoutSeconds);

        using var request = new HttpRequestMessage(HttpMethod.Get, source);
        if (!string.IsNullOrEmpty(userName))
        {
            string pair = userName + ":" + (password ?? string.Empty);
            request.Headers.Authorization = new AuthenticationHeaderValue(
                "Basic", Convert.ToBase64String(Encoding.UTF8.GetBytes(pair)));
        }

        HttpResponseMessage response;
        try
        {
            response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
                .ConfigureAwait(false);
        }
        catch (TaskCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            return new Result(false, 0, Reasons.Timeout, null, null);
        }
        catch (HttpRequestException)
        {
            return new Result(false, 0, Reasons.Network, null, null);
        }

        using (response)
        {
            int status = (int)response.StatusCode;
            if (status != (int)HttpStatusCode.OK)
                return new Result(false, status, ReasonFor(status), null, null);

            long? declared = response.Content.Headers.ContentLength;
            if (declared is long announced && announced > maxBytes)
                return new Result(false, status, Reasons.TooLarge, null, announced);

            using var stream = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
            using var buffer = new MemoryStream();
            byte[] chunk = new byte[81920];
            long total = 0;
            while (true)
            {
                int read;
                try
                {
                    read = await stream.ReadAsync(chunk, cancellationToken).ConfigureAwait(false);
                }
                catch (TaskCanceledException) when (!cancellationToken.IsCancellationRequested)
                {
                    return new Result(false, status, Reasons.Timeout, null, declared);
                }
                catch (HttpRequestException)
                {
                    return new Result(false, status, Reasons.Network, null, declared);
                }

                if (read == 0) break;
                total += read;
                if (total > maxBytes)
                    return new Result(false, status, Reasons.TooLarge, null, declared);
                buffer.Write(chunk, 0, read);
            }

            if (total == 0) return new Result(false, status, Reasons.Empty, null, declared);
            if (declared is long promised && total != promised)
                return new Result(false, status, Reasons.Truncated, null, declared);
            return new Result(true, status, null, buffer.ToArray(), declared);
        }
    }

    public static string ReasonFor(int status) => status switch
    {
        401 or 403 => Reasons.AuthenticationFailed,
        404 => Reasons.NotFound,
        413 => Reasons.TooLarge,
        >= 500 => Reasons.ServerError,
        _ => "远端拒绝这次下载（HTTP " + status + "）",
    };
}

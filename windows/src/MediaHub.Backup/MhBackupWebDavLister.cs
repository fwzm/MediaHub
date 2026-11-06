using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Xml.Linq;

namespace MediaHub.Backup;

/// <summary>
/// 备份目录的只读远端浏览：一次 PROPFIND（Depth: 1），把集合里的子项列出来。
/// </summary>
/// <remarks>
/// 只读、只列一层：不递归、不删除、不改远端状态。名称优先取 displayname，没有就取 href 的最后一段
/// 并做百分号解码（中文文件名在多数服务端是编码过的）。调用方负责过滤出 .mhbackup。
/// 失败原因用固定串，便于命令行映射退出码与测试断言。
/// </remarks>
public static class MhBackupWebDavLister
{
    public const int DefaultTimeoutSeconds = 60;

    /// <summary>Depth: 1 的 PROPFIND 请求体；删除前的存在性检查用同一个（Depth 由调用方改成 0）。</summary>
    public const string PropfindBody =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
        "<d:propfind xmlns:d=\"DAV:\"><d:prop>" +
        "<d:displayname/><d:resourcetype/><d:getcontentlength/><d:getlastmodified/>" +
        "</d:prop></d:propfind>";

    /// <summary>固定原因串；命令行直接把这些句子交给使用者。</summary>
    public static class Reasons
    {
        public const string NotHttp = "列目录的地址必须是 http 或 https";
        public const string AuthenticationFailed = "远端拒绝这次列目录：账号或口令不对（401/403）";
        public const string NotFound = "远端没有这个目录（404）";
        public const string NotSupported = "远端不支持列目录（PROPFIND 未实现，405/501）";
        public const string Corrupted = "远端返回的不是 WebDAV 列目录响应（7.1 多状态 XML 解析失败）";
        public const string ServerError = "远端出错";
        public const string Timeout = "列目录超时";
        public const string Network = "连不上远端";
    }

    public sealed record Entry(string Name, Uri Href, bool IsCollection, long? Size, DateTimeOffset? LastModifiedUtc);

    public sealed record Result(bool Ok, int StatusCode, string? Reason, IReadOnlyList<Entry> Entries);

    private static readonly Result EmptyFailure = new(false, 0, null, Array.Empty<Entry>());

    public static async Task<Result> ListAsync(
        Uri collection,
        string? userName = null,
        string? password = null,
        HttpMessageHandler? handler = null,
        CancellationToken cancellationToken = default)
    {
        if (!string.Equals(collection.Scheme, Uri.UriSchemeHttp, StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(collection.Scheme, Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase))
        {
            return new Result(false, 0, Reasons.NotHttp, Array.Empty<Entry>());
        }

        using var client = handler is null ? new HttpClient() : new HttpClient(handler, disposeHandler: false);
        client.Timeout = TimeSpan.FromSeconds(DefaultTimeoutSeconds);
        using var request = new HttpRequestMessage(new HttpMethod("PROPFIND"), collection);
        request.Headers.Add("Depth", "1");
        if (!string.IsNullOrWhiteSpace(userName))
        {
            string token = Convert.ToBase64String(Encoding.UTF8.GetBytes(userName + ":" + (password ?? string.Empty)));
            request.Headers.Authorization = new AuthenticationHeaderValue("Basic", token);
        }

        request.Content = new StringContent(PropfindBody, Encoding.UTF8, "application/xml");

        HttpResponseMessage response;
        try
        {
            response = await client
                .SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
                .ConfigureAwait(false);
        }
        catch (TaskCanceledException)
        {
            return new Result(false, 0, Reasons.Timeout, Array.Empty<Entry>());
        }
        catch (HttpRequestException)
        {
            return new Result(false, 0, Reasons.Network, Array.Empty<Entry>());
        }

        using (response)
        {
            int status = (int)response.StatusCode;
            if (status != 207 && status != 200)
            {
                return new Result(false, status, ReasonFor(status), Array.Empty<Entry>());
            }

            string text;
            try
            {
                text = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
            }
            catch (Exception failure) when (failure is HttpRequestException or TaskCanceledException or IOException)
            {
                return new Result(false, status, failure is TaskCanceledException ? Reasons.Timeout : Reasons.Network, Array.Empty<Entry>());
            }

            try
            {
                return new Result(true, status, null, Parse(text, collection));
            }
            catch (System.Xml.XmlException)
            {
                return new Result(false, status, Reasons.Corrupted, Array.Empty<Entry>());
            }
        }
    }

    /// <summary>解析 7.1 多状态响应；不认识的属性跳过，不猜。</summary>
    internal static IReadOnlyList<Entry> Parse(string xml, Uri collection)
    {
        XDocument document = XDocument.Parse(xml);
        // 只接受 7.1 多状态文档：有些服务端在出错时仍然回 207，正文却是 HTML/登录页，
        // 当作「空目录」会把服务端错误伪装成「远端没有备份」。
        if (document.Root is null || document.Root.Name.LocalName != "multistatus")
            throw new System.Xml.XmlException("root element is not multistatus");
        var entries = new List<Entry>();
        string selfPath = collection.AbsolutePath.TrimEnd('/');
        foreach (XElement response in document.Descendants().Where(e => e.Name.LocalName == "response"))
        {
            string? rawHref = response.Descendants().FirstOrDefault(e => e.Name.LocalName == "href")?.Value;
            if (string.IsNullOrWhiteSpace(rawHref)) continue;
            if (!Uri.TryCreate(rawHref, UriKind.Absolute, out Uri? href))
            {
                if (!Uri.TryCreate(collection, rawHref, out href) || href is null) continue;
            }

            if (string.Equals(Uri.UnescapeDataString(href.AbsolutePath).TrimEnd('/'), selfPath, StringComparison.Ordinal))
            {
                continue;
            }

            bool isCollection = response.Descendants().Any(e => e.Name.LocalName == "collection");
            string? displayName = response.Descendants().FirstOrDefault(e => e.Name.LocalName == "displayname")?.Value;
            string name = string.IsNullOrWhiteSpace(displayName) ? LastSegment(href) : displayName!;
            long? size = ParseLong(response.Descendants().FirstOrDefault(e => e.Name.LocalName == "getcontentlength")?.Value);
            DateTimeOffset? modified = ParseDate(response.Descendants().FirstOrDefault(e => e.Name.LocalName == "getlastmodified")?.Value);
            entries.Add(new Entry(name, href, isCollection, size, modified));
        }

        return entries;
    }

    private static string LastSegment(Uri href)
    {
        string path = Uri.UnescapeDataString(href.AbsolutePath).TrimEnd('/');
        int slash = path.LastIndexOf("/", StringComparison.Ordinal);
        return slash >= 0 ? path[(slash + 1)..] : path;
    }

    private static long? ParseLong(string? value)
        => long.TryParse(value, NumberStyles.Integer, CultureInfo.InvariantCulture, out long parsed) && parsed >= 0 ? parsed : null;

    private static DateTimeOffset? ParseDate(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return null;
        if (DateTimeOffset.TryParse(value, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out DateTimeOffset parsed))
            return parsed;

        // 个别服务端把星期写错（例如把周四写成 Wed），严格的 RFC 1123 解析会整条拒绝；
        // 丢掉星期再试一次，日期本身仍然可信。
        int comma = value.IndexOf(",");
        if (comma >= 0 &&
            DateTimeOffset.TryParse(value[(comma + 1)..], CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out DateTimeOffset withoutWeekday))
            return withoutWeekday;

        return null;
    }

    private static string ReasonFor(int status) => status switch
    {
        401 or 403 => Reasons.AuthenticationFailed,
        404 => Reasons.NotFound,
        405 or 501 => Reasons.NotSupported,
        >= 500 => Reasons.ServerError,
        _ => "远端拒绝这次列目录（HTTP " + status.ToString(CultureInfo.InvariantCulture) + "）",
    };
}

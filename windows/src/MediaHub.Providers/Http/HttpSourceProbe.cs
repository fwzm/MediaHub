using System.Globalization;
using System.Net;
using MediaHub.Net;

namespace MediaHub.Providers.Http;

/// <summary>
/// Reachability check for a plain http(s) address. There is no server API to interrogate, so the only
/// question worth asking is whether the address answers — and with what.
/// </summary>
/// <remarks>
/// A one-byte range request is used rather than HEAD because a static file host is free to answer
/// HEAD with 405 while serving GET normally; reading headers only means the check costs one round
/// trip and no media bytes. A server that ignores Range answers 200, which is equally valid.
/// </remarks>
public static class HttpSourceProbe
{
    public static async Task<(bool Ok, string Message)> ProbeAsync(
        Uri address, NetworkPolicy policy, CancellationToken ct = default)
    {
        using var clients = new MediaSourceHttpClientFactory(policy);
        using HttpClient http = clients.CreateApiClient();

        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, address);
            request.Headers.Range = new System.Net.Http.Headers.RangeHeaderValue(0, 0);

            using HttpResponseMessage response = await http
                .SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct)
                .ConfigureAwait(false);

            if (response.StatusCode is not (HttpStatusCode.OK or HttpStatusCode.PartialContent))
            {
                return (false, Describe(response.StatusCode));
            }

            string kind = response.Content.Headers.ContentType?.MediaType ?? "未知类型";
            // A 206 answers with the length of the one requested byte; the total lives in Content-Range.
            long? total = response.Content.Headers.ContentRange?.Length
                          ?? response.Content.Headers.ContentLength;
            string size = total is { } length ? " · " + FormatSize(length) : "";
            return (true, "连接正常：HTTP " + ((int)response.StatusCode).ToString(CultureInfo.InvariantCulture)
                + " · " + kind + size);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (OperationCanceledException)
        {
            return (false, "连接超时，请检查地址是否可访问");
        }
        catch (HttpRequestException)
        {
            // The exception message repeats the request URL, which may carry a signed query string, so
            // only the failure itself is reported. The certificate prompt in the add dialog inspects the
            // endpoint separately when the cause is TLS.
            return (false, "无法连接该地址，请检查主机、端口与证书");
        }
    }

    private static string Describe(HttpStatusCode status) => status switch
    {
        HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden =>
            "服务器拒绝访问（HTTP " + ((int)status).ToString(CultureInfo.InvariantCulture) + "），该直链可能需要额外的鉴权信息",
        HttpStatusCode.NotFound => "地址不存在（HTTP 404）",
        HttpStatusCode.MethodNotAllowed => "服务器不接受读取请求（HTTP 405）",
        HttpStatusCode.TooManyRequests => "请求过于频繁（HTTP 429），请稍后再试",
        >= HttpStatusCode.InternalServerError => "服务器出错（HTTP " + ((int)status).ToString(CultureInfo.InvariantCulture) + "）",
        _ => "服务器返回 HTTP " + ((int)status).ToString(CultureInfo.InvariantCulture),
    };

    private static string FormatSize(long bytes) => bytes switch
    {
        >= 1L << 30 => (bytes / (double)(1L << 30)).ToString("0.0", CultureInfo.InvariantCulture) + " GB",
        >= 1L << 20 => (bytes / (double)(1L << 20)).ToString("0.0", CultureInfo.InvariantCulture) + " MB",
        >= 1L << 10 => (bytes / (double)(1L << 10)).ToString("0.0", CultureInfo.InvariantCulture) + " KB",
        _ => bytes.ToString(CultureInfo.InvariantCulture) + " B",
    };
}

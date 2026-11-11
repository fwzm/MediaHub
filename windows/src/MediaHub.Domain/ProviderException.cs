namespace MediaHub.Domain;

/// <summary>Stable structured error codes. Mirrors Android <c>ProviderException.ErrorCode</c>.</summary>
public enum ProviderErrorCode
{
    Unknown = 0,
    AuthRequired,
    AuthFailed,
    AuthExpired,
    Network,
    Http,
    Parse,
    NotFound,
    RateLimited,
    Connection,
    NotImplemented,
}

/// <summary>
/// Unified provider-layer failure. <see cref="Exception.Message"/> is always a user-presentable,
/// credential-free string; structured diagnostics live in the typed fields.
/// </summary>
/// <remarks>
/// Cause sanitisation (carried over from the Android A3-4 revision): the original exception is never
/// attached verbatim, because provider/HTTP exceptions routinely embed the full request URL, which may
/// contain proxy credentials or tokens. Only the exception <i>type name</i> and the stack frames are
/// preserved, which keeps diagnosis possible without leaking request data.
/// </remarks>
public class ProviderException : Exception
{
    public string ServerId { get; }
    public ProviderErrorCode Code { get; }

    public ProviderException(string serverId, ProviderErrorCode code, string message, Exception? cause = null)
        : base(message, cause is null ? null : SanitizedCause.Wrap(cause))
    {
        ServerId = serverId;
        Code = code;
    }

    public static ProviderException AuthRequired(string serverId)
        => new(serverId, ProviderErrorCode.AuthRequired, $"需要登录：{serverId}");

    public static ProviderException AuthFailed(string serverId, string? reason = null, Exception? cause = null)
        => new(serverId, ProviderErrorCode.AuthFailed, $"登录失败{(reason is null ? "" : "：" + reason)}", cause);

    public static ProviderException AuthExpired(string serverId)
        => new(serverId, ProviderErrorCode.AuthExpired, "登录状态已过期，请重新登录");

    public static ProviderException Network(string serverId, Exception? cause = null)
        => new(serverId, ProviderErrorCode.Network, "网络错误，请检查网络连接", cause);

    public static ProviderException Parse(string serverId, Exception? cause = null)
        => new(serverId, ProviderErrorCode.Parse, "数据解析失败", cause);

    public static ProviderException NotFound(string serverId, string what)
        => new(serverId, ProviderErrorCode.NotFound, $"未找到：{what}");

    public static ProviderException RateLimited(string serverId, TimeSpan? retryAfter = null)
        => new(serverId, ProviderErrorCode.RateLimited, "请求过于频繁，请稍后重试");

    public static ProviderException Connection(string serverId, string message, Exception? cause = null)
        => new(serverId, ProviderErrorCode.Connection, message, cause);

    public static ProviderException NotYetImplemented(string serverId, string scope)
        => new(serverId, ProviderErrorCode.NotImplemented, $"{scope} 尚未实现");
}

/// <summary>Typed HTTP failure carrying the structured status code used for UI decisions.</summary>
public sealed class ProviderHttpException : ProviderException
{
    public int StatusCode { get; }
    public string Method { get; }

    /// <summary>
    /// The request URL with user-info, query string and fragment removed (see <c>UrlRedactor</c>).
    /// Never the raw URL — provider URLs can carry tokens in query parameters.
    /// </summary>
    public string RedactedUrl { get; }

    public ProviderHttpException(string serverId, int statusCode, string method, string redactedUrl)
        : base(serverId, ProviderErrorCode.Http, $"服务器返回 {statusCode}（{method}）")
    {
        StatusCode = statusCode;
        Method = method;
        RedactedUrl = redactedUrl;
    }

    /// <summary>Maps an HTTP status to the provider error code used by the session layer.</summary>
    public static ProviderErrorCode MapStatus(int status) => status switch
    {
        401 => ProviderErrorCode.AuthExpired,
        403 => ProviderErrorCode.Http,
        404 => ProviderErrorCode.NotFound,
        429 => ProviderErrorCode.RateLimited,
        >= 500 => ProviderErrorCode.Http,
        _ => ProviderErrorCode.Http,
    };
}

/// <summary>
/// Carries only the original exception type name and stack frames — never its message, which may
/// embed URLs with credentials. The wrapper is created fresh; the original object is not mutated.
/// </summary>
internal sealed class SanitizedCause : Exception
{
    public static Exception Wrap(Exception original)
    {
        // Cancellation must propagate as the same instance so callers can detect it reliably.
        if (original is OperationCanceledException) return original;
        return new SanitizedCause(original);
    }

    /// <summary>Original exception type name only — never its message, which may embed credentials.</summary>
    public string OriginalType { get; }

    private SanitizedCause(Exception original)
        : base($"sanitized: {original.GetType().FullName}", original.InnerException is null ? null : Wrap(original.InnerException))
    {
        OriginalType = original.GetType().FullName ?? original.GetType().Name;
    }
}

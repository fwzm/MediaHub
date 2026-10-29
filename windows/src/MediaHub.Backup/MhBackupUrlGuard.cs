using System.Text;

namespace MediaHub.Backup;

/// <summary>来源地址守卫的判定结果（与 Android BackupUrlGuard.Violation 同义）。</summary>
public abstract record MhBackupUrlViolation
{
    /// <summary>地址里内嵌了 user:password@（凭据绝不允许进备份）。</summary>
    public sealed record UserInfo() : MhBackupUrlViolation;

    /// <summary>查询串/片段里出现凭据类键；Key 为命中的键名（解码后小写），格式非法时为括注。</summary>
    public sealed record SensitiveQuery(string Key) : MhBackupUrlViolation;
}

/// <summary>
/// 备份来源地址守卫（照 Android BackupUrlGuard.kt）。
/// 备份文件会离开设备、进入云盘/邮件，因此任何带凭据的地址都不允许写进容器。
/// </summary>
public static class MhBackupUrlGuard
{
    public static readonly IReadOnlyCollection<string> SensitiveQueryKeys = new HashSet<string>(StringComparer.Ordinal)
    {
        "token", "access_token", "api_key", "apikey", "api-key", "auth", "authorization",
        "password", "passwd", "pwd", "secret", "signature", "sign",
        "sessionid", "session_id", "x-emby-token", "x-emby-authorization", "apikeyheader",
        "cookie", "cookies", "accesstoken", "refresh_token", "refreshtoken",
        "playsessionid", "deviceid",
        "x-amz-credential", "x-amz-signature", "x-amz-security-token",
        "x-goog-credential", "x-goog-signature",
    };

    public static MhBackupUrlViolation? Inspect(string? url)
    {
        string trimmed = (url ?? string.Empty).Trim();
        if (trimmed.Length == 0) return new MhBackupUrlViolation.SensitiveQuery("(URL 格式)");

        if (!Uri.TryCreate(trimmed, UriKind.Absolute, out Uri? uri))
            return new MhBackupUrlViolation.SensitiveQuery("(URL 格式)");

        if (uri.UserInfo.Length > 0) return new MhBackupUrlViolation.UserInfo();

        if (!string.Equals(uri.Scheme, "http", StringComparison.Ordinal) &&
            !string.Equals(uri.Scheme, "https", StringComparison.Ordinal))
            return new MhBackupUrlViolation.SensitiveQuery("(URL 格式)");

        if (uri.Host.Length == 0 || uri.HostNameType == UriHostNameType.Unknown)
            return new MhBackupUrlViolation.SensitiveQuery("(URL 格式)");

        MhBackupUrlViolation? queryViolation = InspectRaw(uri.Query.TrimStart('?'));
        if (queryViolation is not null) return queryViolation;

        return InspectRaw(uri.Fragment.TrimStart('#'));
    }

    /// <summary>查询串与片段都按 &amp; 与 ; 切分；键先 URL 解码再小写比较。</summary>
    private static MhBackupUrlViolation? InspectRaw(string raw)
    {
        if (raw.Length == 0) return null;

        foreach (string pair in raw.Split(new[] { '&', ';' }, StringSplitOptions.RemoveEmptyEntries))
        {
            int equals = pair.IndexOf('=');
            string rawKey = equals >= 0 ? pair[..equals] : pair;
            if (rawKey.Length == 0) continue;

            string key;
            try
            {
                key = Uri.UnescapeDataString(rawKey).Trim().ToLowerInvariant();
            }
            catch (Exception)
            {
                return new MhBackupUrlViolation.SensitiveQuery("(URL 编码)");
            }

            if (SensitiveQueryKeys.Contains(key)) return new MhBackupUrlViolation.SensitiveQuery(key);
        }

        return null;
    }

    /// <summary>
    /// 来源身份用的地址规范化（照 Android ServerAddressIdentity.normalize）：
    /// scheme/host 小写、丢弃 userinfo 与 fragment、路径去掉尾部斜杠、保留显式写出的端口（含默认端口 :80）。
    /// 解析失败时返回 trim 后的原文（与 Android 的 host==null 分支一致）。
    /// </summary>
    public static string NormalizeForIdentity(string? url)
    {
        string trimmed = (url ?? string.Empty).Trim();

        int schemeEnd = trimmed.IndexOf("://", StringComparison.Ordinal);
        if (schemeEnd <= 0) return trimmed;

        string scheme = trimmed[..schemeEnd];
        if (!IsValidScheme(scheme)) return trimmed;

        string rest = trimmed[(schemeEnd + 3)..];

        int hashIndex = rest.IndexOf('#');
        if (hashIndex >= 0) rest = rest[..hashIndex];

        string query = string.Empty;
        int queryIndex = rest.IndexOf('?');
        if (queryIndex >= 0)
        {
            query = rest[queryIndex..];
            rest = rest[..queryIndex];
        }

        int pathIndex = rest.IndexOf('/');
        string authority = pathIndex >= 0 ? rest[..pathIndex] : rest;
        string path = pathIndex >= 0 ? rest[pathIndex..] : string.Empty;

        int atIndex = authority.LastIndexOf('@');
        if (atIndex >= 0) authority = authority[(atIndex + 1)..];

        string host;
        string port = string.Empty;
        if (authority.StartsWith("[", StringComparison.Ordinal))
        {
            int close = authority.IndexOf(']');
            if (close < 0) return trimmed;
            host = authority[..(close + 1)];
            if (authority.Length > close + 1 && authority[close + 1] == ':') port = authority[(close + 1)..];
        }
        else
        {
            int colon = authority.LastIndexOf(':');
            if (colon >= 0 && authority.IndexOf(':') == colon)
            {
                host = authority[..colon];
                port = authority[colon..];
            }
            else
            {
                host = authority;
            }
        }

        if (host.Length == 0) return trimmed;

        return scheme.ToLowerInvariant() + "://" + host.ToLowerInvariant() + port + path.TrimEnd('/') + query;
    }

    private static bool IsValidScheme(string scheme)
    {
        if (scheme.Length == 0) return false;
        if (!char.IsAsciiLetter(scheme[0])) return false;
        foreach (char c in scheme)
        {
            if (!char.IsAsciiLetterOrDigit(c) && c != '+' && c != '-' && c != '.') return false;
        }
        return true;
    }

    /// <summary>判定来源地址身份是否唯一（照 Android hasUnambiguousEndpointIdentity）。</summary>
    public static bool HasUnambiguousEndpointIdentity(MhBackupServerDto server)
    {
        List<MhBackupEndpointDto> enabled = server.Endpoints.Where(e => e.Enabled).ToList();
        if (enabled.Count == 0) return true;

        List<MhBackupEndpointDto> primaries = enabled.Where(e => e.IsPrimary).ToList();
        List<MhBackupEndpointDto> candidates = primaries.Count > 0 ? primaries : enabled;
        int minOrder = candidates.Min(e => e.SortOrder);

        List<string> identities = candidates
            .Where(e => e.SortOrder == minOrder)
            .Select(e => NormalizeForIdentity(e.Url))
            .Distinct(StringComparer.Ordinal)
            .ToList();
        return identities.Count <= 1;
    }
}

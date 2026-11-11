using System.Text;

namespace MediaHub.Providers.WebDav;

/// <summary>
/// Basic credentials for one source, held in memory for the lifetime of its handle.
/// </summary>
/// <remarks>
/// The single source of truth for the <c>Authorization</c> header. It is never copied to a default
/// request header and never written to a URL, so there is exactly one place to change credentials and
/// exactly one place a leak could start from.
/// </remarks>
internal sealed class WebDavSession
{
    private readonly string _serverId;
    private readonly object _gate = new();

    public WebDavSession(string serverId) => _serverId = serverId;

    public string ServerId => _serverId;

    private string? _userName;
    private string? _password;

    public string? UserName
    {
        get { lock (_gate) return _userName; }
    }

    public bool HasCredentials
    {
        get { lock (_gate) return !string.IsNullOrEmpty(_userName); }
    }

    public void Set(string? userName, string? password)
    {
        lock (_gate)
        {
            _userName = userName;
            _password = password;
        }
    }

    public void Clear() => Set(null, null);

    /// <summary>
    /// Current credentials as a value, so a verification probe can be rolled back on failure.
    /// </summary>
    /// <remarks>
    /// A failed re-login must not destroy a session that was still working; the caller snapshots
    /// before probing and restores on failure.
    /// </remarks>
    public (string? UserName, string? Password) Snapshot()
    {
        lock (_gate) return (_userName, _password);
    }

    /// <summary>Current <c>Authorization</c> header value, or null for an anonymous source.</summary>
    /// <remarks>
    /// Throws rather than returning a header built from unusable input: a credential containing a
    /// control character must fail loudly at the boundary instead of being handed to the transport.
    /// </remarks>
    public string? AuthorizationHeader
    {
        get
        {
            lock (_gate)
            {
                if (string.IsNullOrEmpty(_userName)) return null;
                if (WebDavBasicAuth.HasUnsafeCharacters(_userName) || WebDavBasicAuth.HasUnsafeCharacters(_password))
                    throw WebDavBasicAuth.UnsafeCredentialError(ServerId);
                return WebDavBasicAuth.BuildHeader(_userName!, _password ?? string.Empty);
            }
        }
    }
}

/// <summary>RFC 7617 Basic authentication header construction.</summary>
internal static class WebDavBasicAuth
{
    /// <summary>
    /// Builds the header value.
    /// </summary>
    /// <remarks>
    /// UTF-8 is used for the user-pass octets. RFC 7617 leaves the charset unspecified; UTF-8 is the
    /// interoperable choice and the only one that survives a non-ASCII user name, which the OkHttp
    /// default on the Android client (ISO-8859-1) would mangle.
    /// </remarks>
    public static string BuildHeader(string userName, string password)
        => "Basic " + Convert.ToBase64String(Encoding.UTF8.GetBytes(userName + ":" + password));

    /// <summary>
    /// True when a credential cannot be placed in a header.
    /// </summary>
    /// <remarks>
    /// CR, LF and NUL are the request-splitting primitives. The transport happens to reject them
    /// today, but relying on that would make a security property depend on someone else's validation
    /// order, so the refusal happens here first.
    /// </remarks>
    public static bool HasUnsafeCharacters(string? value)
    {
        if (value is null) return false;
        foreach (char c in value)
        {
            if (c < 0x20 || c == 0x7F) return true;
        }
        return false;
    }

    internal static MediaHub.Domain.ProviderException UnsafeCredentialError(string serverId)
        => MediaHub.Domain.ProviderException.AuthFailed(serverId, "用户名或密码包含非法控制字符");
}

/// <summary>Interpretation of a <c>WWW-Authenticate</c> challenge list (RFC 7235 §4.1).</summary>
/// <remarks>
/// Only used to choose between "the stored credential is stale" and "this client cannot satisfy the
/// server at all". A wrong message here sends the user to re-type a correct password forever, which
/// is why the challenge is parsed rather than assumed.
/// </remarks>
internal static class WebDavAuthChallenge
{
    /// <summary>Authentication schemes offered by a response, in order of appearance.</summary>
    public static IReadOnlyList<string> Schemes(System.Net.Http.Headers.HttpResponseHeaders headers)
    {
        var found = new List<string>();
        if (!headers.TryGetValues("WWW-Authenticate", out IEnumerable<string>? values)) return found;

        foreach (string value in values)
        {
            foreach (string piece in SplitChallenges(value))
            {
                string scheme = LeadingToken(piece);
                if (scheme.Length == 0) continue;
                if (found.Contains(scheme, StringComparer.OrdinalIgnoreCase)) continue;
                found.Add(scheme);
            }
        }
        return found;
    }

    /// <summary>True when the server did not offer Basic, so our pre-emptive header can never succeed.</summary>
    public static bool BasicIsNotOffered(IReadOnlyList<string> schemes)
        => schemes.Count > 0 && !schemes.Any(s => string.Equals(s, "Basic", StringComparison.OrdinalIgnoreCase));

    /// <summary>User-presentable explanation for a challenge this client cannot answer.</summary>
    public static string DescribeUnsupported(IReadOnlyList<string> schemes)
        => "服务器要求 " + string.Join("/", schemes) + " 认证，本客户端 WebDAV 仅支持 Basic（HTTP 处理器未启用 Digest/NTLM 协商）";

    /// <summary>
    /// Splits one header value into challenges.
    /// </summary>
    /// <remarks>
    /// <c>Digest realm="x", nonce="y"</c> is one challenge whose parameters are comma-separated, while
    /// <c>Basic realm="a", Digest realm="b"</c> is two. The discriminator is the RFC grammar: a
    /// challenge starts with an auth-scheme token that is <i>not</i> immediately followed by
    /// <c>=</c> (that would be an auth-param). Guessing instead would let a realm string decide
    /// whether we think the server speaks Basic.
    /// </remarks>
    private static IEnumerable<string> SplitChallenges(string value)
        => value.Split(',').Select(piece => piece.Trim()).Where(piece => piece.Length > 0 && !IsParameter(piece));

    private static bool IsParameter(string piece)
    {
        int tokenEnd = LeadingTokenLength(piece);
        return tokenEnd < piece.Length && piece[tokenEnd] == '=';
    }

    private static string LeadingToken(string piece)
    {
        int length = LeadingTokenLength(piece);
        return length == 0 ? string.Empty : piece[..length];
    }

    private static int LeadingTokenLength(string piece)
    {
        int i = 0;
        while (i < piece.Length && (char.IsAsciiLetterOrDigit(piece[i]) || piece[i] == '-' || piece[i] == '_' || piece[i] == '.'))
        {
            i++;
        }
        return i;
    }
}

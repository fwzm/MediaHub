namespace MediaHub.Providers.Smb;

/// <summary>
/// A parsed SMB share address: server, share and the optional subfolder to start browsing at.
/// </summary>
/// <remarks>
/// Accepted forms are <c>\\host\share</c>, <c>\\host\share\folder</c>, <c>//host/share</c> and
/// <c>smb://host/share</c>. The canonical value is always the UNC form, because that is what the
/// Windows redirector and <see cref="System.IO"/> understand without a third-party SMB client.
/// The host is lower-cased so the same share typed as <c>\\NAS\media</c> and <c>\\nas\media</c>
/// resolves to one source instead of two; the share and folder names keep their case because a
/// server may expose case-sensitive subfolders.
/// </remarks>
public sealed record SmbAddress(string Host, string Share, string UncRoot, string DisplayName)
{
    /// <summary>The share itself — the resource a credential logon is made against.</summary>
    public string ConnectionTarget => @"\\" + Host + @"\" + Share;

    public static bool TryParse(string? input, out SmbAddress address, out string error)
    {
        address = null!;
        error = "";

        string text = (input ?? "").Trim();
        if (text.Length == 0)
        {
            error = @"请填写共享地址，例如 \\nas\media 或 smb://nas/media";
            return false;
        }

        int schemeEnd = text.IndexOf("://", StringComparison.Ordinal);
        if (schemeEnd > 0)
        {
            string scheme = text[..schemeEnd];
            if (!scheme.Equals("smb", StringComparison.OrdinalIgnoreCase)
                && !scheme.Equals("cifs", StringComparison.OrdinalIgnoreCase))
            {
                error = "SMB 共享地址只支持 smb:// 前缀，或直接填写 \\\\主机\\共享";
                return false;
            }
            text = @"\\" + text[(schemeEnd + 3)..];
        }
        else if (text.StartsWith("//", StringComparison.Ordinal))
        {
            text = @"\\" + text[2..];
        }

        text = text.TrimEnd('\\', '/');
        if (!text.StartsWith(@"\\", StringComparison.Ordinal))
        {
            error = @"共享地址应以 \\主机\共享 或 smb://主机/共享 开头";
            return false;
        }

        string[] parts = text[2..].Split(['\\', '/'], StringSplitOptions.RemoveEmptyEntries);
        if (parts.Length < 2)
        {
            error = @"共享地址需要主机名和共享名，例如 \\nas\media";
            return false;
        }

        string host = parts[0];
        string share = parts[1];
        if (!IsValidHost(host))
        {
            error = "主机名不合法：" + host;
            return false;
        }
        if (!IsValidSegment(share))
        {
            error = "共享名不合法：" + share;
            return false;
        }

        var subfolders = new List<string>();
        for (int i = 2; i < parts.Length; i++)
        {
            if (parts[i] is "." or ".." || !IsValidSegment(parts[i]))
            {
                error = "共享路径中的这一段不合法：" + parts[i];
                return false;
            }
            subfolders.Add(parts[i]);
        }

        string canonicalHost = host.ToLowerInvariant();
        string root = @"\\" + canonicalHost + @"\" + share;
        foreach (string folder in subfolders) root += @"\" + folder;

        address = new SmbAddress(canonicalHost, share, root, canonicalHost + @"\" + share
            + string.Concat(subfolders.Select(f => @"\" + f)));
        return true;
    }

    private static bool IsValidHost(string host)
    {
        if (host.Length is 0 or > 255) return false;
        // ':' is rejected because a bare "host:port" is a mistake copied from an HTTP address, and a
        // share name that actually contains one of these characters is not expressible in SMB anyway.
        foreach (char c in host)
        {
            if (c is '<' or '>' or ':' or '"' or '/' or '\\' or '|' or '?' or '*' || char.IsWhiteSpace(c))
                return false;
        }
        return true;
    }

    private static bool IsValidSegment(string segment)
    {
        // Share and folder names may contain spaces; only characters the redirector forbids are refused.
        if (segment.Length is 0 or > 255) return false;
        foreach (char c in segment)
        {
            if (c is '<' or '>' or ':' or '"' or '/' or '\\' or '|' or '?' or '*') return false;
            if (char.IsControl(c)) return false;
        }
        return true;
    }
}

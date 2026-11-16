using System.Net;
using System.Net.Sockets;

namespace MediaHub.Net;

/// <summary>
/// Decides whether an address named inside a server-supplied HLS playlist may be relayed.
/// </summary>
/// <remarks>
/// <para>
/// Every URL in a playlist is input from the media server, and the relay fetches it from this
/// machine's network position with that source's proxy policy and credentials attached. Without a
/// check, a compromised or hostile server can name an address only this machine can reach — a
/// router's admin page, a loopback-only service, a cloud metadata endpoint — and have the client
/// fetch it and hand the response to the engine.
/// </para>
/// <para>
/// The rule is therefore allowlist-shaped: the origin's own host is always allowed (a playlist is
/// expected to reference its own server), and any other host must be publicly routable. Loopback,
/// link-local, private, carrier-grade-NAT, multicast and single-label names are refused even when the
/// origin itself is private: a server on the LAN can be compromised as easily as a public one, and
/// nothing in the playback path needs a second private host. A public CDN referenced by a private
/// server is allowed, which is the common "self-hosted Jellyfin with remote segments" case.
/// </para>
/// </remarks>
public static class HlsTargetPolicy
{
    private static readonly string[] LocalSuffixes =
    {
        ".localhost", ".local", ".localdomain", ".internal", ".intranet", ".lan", ".home.arpa",
    };

    /// <summary>True when <paramref name="target"/> may be fetched on behalf of <paramref name="origin"/>.</summary>
    public static bool IsAllowed(Uri target, Uri origin)
    {
        if (target is null || origin is null) return false;
        if (!IsHttp(target) || !IsHttp(origin)) return false;
        if (string.Equals(target.Host, origin.Host, StringComparison.OrdinalIgnoreCase)) return true;
        return !IsLocalHost(target.Host);
    }

    /// <summary>True for a host name or literal address that only exists on this machine's own network.</summary>
    public static bool IsLocalHost(string? host)
    {
        if (string.IsNullOrWhiteSpace(host)) return true;

        string value = host.Trim().Trim('[', ']');
        if (IPAddress.TryParse(value, out IPAddress? address)) return IsLocalAddress(address);

        foreach (string suffix in LocalSuffixes)
        {
            if (value.EndsWith(suffix, StringComparison.OrdinalIgnoreCase)) return true;
        }

        // A name with no dot can only resolve through the hosts file, mDNS or a DNS suffix: it names a
        // machine on this network under an alias, not a public endpoint.
        return value.IndexOf('.') < 0;
    }

    /// <summary>True for a loopback, link-local, private or otherwise non-public address.</summary>
    public static bool IsLocalAddress(IPAddress address)
    {
        if (address.AddressFamily == AddressFamily.InterNetworkV6)
        {
            if (address.IsIPv4MappedToIPv6) return IsLocalAddress(address.MapToIPv4());
            if (address.Equals(IPAddress.IPv6Any) || address.Equals(IPAddress.IPv6Loopback)) return true;
            if (address.IsIPv6LinkLocal || address.IsIPv6Multicast) return true;
            byte[] bytes = address.GetAddressBytes();
            return (bytes[0] & 0xFE) == 0xFC; // fc00::/7 unique-local
        }

        if (address.Equals(IPAddress.Any) || IPAddress.IsLoopback(address)) return true;

        byte[] v4 = address.GetAddressBytes();
        return v4[0] switch
        {
            10 => true,                                              // 10/8 private
            127 => true,                                             // 127/8 loopback
            169 when v4[1] == 254 => true,                           // 169.254/16 link-local
            172 when v4[1] >= 16 && v4[1] <= 31 => true,             // 172.16/12 private
            192 when v4[1] == 168 => true,                           // 192.168/16 private
            100 when v4[1] >= 64 && v4[1] <= 127 => true,            // 100.64/10 carrier-grade NAT
            >= 224 => true,                                          // multicast, reserved, broadcast
            _ => false,
        };
    }

    private static bool IsHttp(Uri uri)
        => uri.Scheme.Equals(Uri.UriSchemeHttp, StringComparison.OrdinalIgnoreCase)
           || uri.Scheme.Equals(Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase);
}

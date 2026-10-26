using System.Globalization;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace MediaHub.Sync;

/// <summary>What a caller knows about one network interface; the testable half of address enumeration.</summary>
public sealed record PairingAddressInput
{
    public required string InterfaceName { get; init; }

    public NetworkInterfaceType Type { get; init; } = NetworkInterfaceType.Unknown;

    public OperationalStatus Status { get; init; } = OperationalStatus.Up;

    /// <summary>Unicast addresses as text, so a test never has to construct a real interface.</summary>
    public IReadOnlyList<string> Addresses { get; init; } = [];
}

/// <summary>One address that could be put into a pairing link, with the reason it ranked where it did.</summary>
public sealed record PairingAddressCandidate
{
    public required string Address { get; init; }

    public required string InterfaceName { get; init; }

    /// <summary>Lower sorts first: wired, then wireless, then anything else.</summary>
    public int Priority { get; init; }

    /// <summary>Stable reason code describing the ranking (or the rejection).</summary>
    public string Reason { get; init; } = "";

    /// <summary>The URL a peer would have to call. Display only: nothing in this app ever binds it.</summary>
    public string ToUrl(int port) => string.Create(
        CultureInfo.InvariantCulture,
        $"https://{Address}:{port}/pairing");
}

/// <summary>Stable reasons for address selection and rejection.</summary>
public static class PairingAddressReasons
{
    /// <summary>Wired interface: preferred because it survives sleep and does not roam.</summary>
    public const string Wired = "wired-interface";

    /// <summary>Wi-Fi: usable, but the address can change between networks.</summary>
    public const string Wireless = "wireless-interface";

    /// <summary>Virtual, tunnel or otherwise unusual interface: last resort.</summary>
    public const string Other = "other-interface";

    /// <summary>127.0.0.0/8 is this machine talking to itself.</summary>
    public const string Loopback = "loopback-address";

    /// <summary>169.254.0.0/16 means DHCP failed; the address is not routable.</summary>
    public const string Apipa = "apipa-address";

    /// <summary>The interface reports itself as down.</summary>
    public const string InterfaceDown = "interface-down";

    /// <summary>Not an IPv4 unicast address.</summary>
    public const string NotIpv4 = "not-ipv4";

    /// <summary>Nothing usable was found; the caller must fall back to the code-only flow.</summary>
    public const string NoUsableAddress = "no-usable-address";

    /// <summary>The addresses could not be enumerated at all.</summary>
    public const string EnumerationFailed = "address-enumeration-failed";

    /// <summary>Every code above, in a stable order.</summary>
    public static IReadOnlyList<string> All { get; } =
    [
        Wired,
        Wireless,
        Other,
        Loopback,
        Apipa,
        InterfaceDown,
        NotIpv4,
        NoUsableAddress,
        EnumerationFailed,
    ];
}

/// <summary>The candidate list plus everything that was filtered out, for the UI and for the log.</summary>
public sealed record PairingAddressReport
{
    public IReadOnlyList<PairingAddressCandidate> Candidates { get; init; } = [];

    public IReadOnlyList<PairingAddressCandidate> Rejected { get; init; } = [];

    /// <summary>Why the candidate list is empty, or null when it is not.</summary>
    public string? Reason { get; init; }

    public bool IsEmpty => Candidates.Count == 0;

    public int Count => Candidates.Count;

    /// <summary>First candidate, or null.</summary>
    public PairingAddressCandidate? Primary => Candidates.Count == 0 ? null : Candidates[0];
}

/// <summary>Finds addresses that a peer on the same network could reach.</summary>
/// <remarks>
/// This is a display-only helper. The pairing listener always binds loopback: binding a real interface would make
/// Windows show a firewall prompt, and a pairing listener that survives on a public interface is a liability, not a
/// feature. Candidates therefore never become sockets here — they only become text in a link a person hands over.
/// </remarks>
public static class PairingAddressCandidates
{
    /// <summary>Enumerates this machine's interfaces. Never throws.</summary>
    public static PairingAddressReport Describe()
    {
        List<PairingAddressInput> inputs = [];
        try
        {
            foreach (NetworkInterface adapter in NetworkInterface.GetAllNetworkInterfaces())
            {
                List<string> addresses = [];
                try
                {
                    foreach (UnicastIPAddressInformation unicast in adapter.GetIPProperties().UnicastAddresses)
                    {
                        if (unicast.Address.AddressFamily == AddressFamily.InterNetwork)
                        {
                            addresses.Add(unicast.Address.ToString());
                        }
                    }
                }
                catch (NetworkInformationException)
                {
                    // An adapter that refuses to describe itself contributes nothing; the others still count.
                }

                inputs.Add(new PairingAddressInput
                {
                    InterfaceName = adapter.Name,
                    Type = adapter.NetworkInterfaceType,
                    Status = adapter.OperationalStatus,
                    Addresses = addresses,
                });
            }
        }
        catch (NetworkInformationException)
        {
            return new PairingAddressReport { Reason = PairingAddressReasons.EnumerationFailed };
        }

        return Describe(inputs);
    }

    /// <summary>Pure selection over injected interfaces, so the ranking is testable without a network.</summary>
    public static PairingAddressReport Describe(IEnumerable<PairingAddressInput> adapters)
    {
        ArgumentNullException.ThrowIfNull(adapters);

        List<(PairingAddressCandidate Candidate, IPAddress Parsed)> usable = [];
        List<PairingAddressCandidate> rejected = [];
        HashSet<string> seen = new(StringComparer.Ordinal);

        foreach (PairingAddressInput adapter in adapters)
        {
            string name = (adapter.InterfaceName ?? string.Empty).Trim();
            int priority = PriorityOf(adapter.Type);
            string reason = ReasonOf(adapter.Type);

            if (adapter.Type == NetworkInterfaceType.Loopback || adapter.Type == NetworkInterfaceType.Tunnel)
            {
                foreach (string address in adapter.Addresses)
                {
                    rejected.Add(new PairingAddressCandidate
                    {
                        Address = address,
                        InterfaceName = name,
                        Priority = priority,
                        Reason = adapter.Type == NetworkInterfaceType.Loopback
                            ? PairingAddressReasons.Loopback
                            : PairingAddressReasons.Other,
                    });
                }

                continue;
            }

            foreach (string text in adapter.Addresses)
            {
                // A v6-only or unparseable entry can never be dialled over IPv4, so it is reported instead of dropped:
                // the UI can then explain why an adapter contributed nothing rather than showing a bare empty list.
                if (!IPAddress.TryParse((text ?? string.Empty).Trim(), out IPAddress? address)
                    || address.AddressFamily != AddressFamily.InterNetwork)
                {
                    rejected.Add(new PairingAddressCandidate
                    {
                        Address = (text ?? string.Empty).Trim(),
                        InterfaceName = name,
                        Priority = priority,
                        Reason = PairingAddressReasons.NotIpv4,
                    });
                    continue;
                }

                string canonical = address.ToString();
                if (adapter.Status != OperationalStatus.Up)
                {
                    rejected.Add(new PairingAddressCandidate
                    {
                        Address = canonical,
                        InterfaceName = name,
                        Priority = priority,
                        Reason = PairingAddressReasons.InterfaceDown,
                    });
                    continue;
                }

                if (IPAddress.IsLoopback(address))
                {
                    rejected.Add(new PairingAddressCandidate
                    {
                        Address = canonical,
                        InterfaceName = name,
                        Priority = priority,
                        Reason = PairingAddressReasons.Loopback,
                    });
                    continue;
                }

                if (IsApipa(address))
                {
                    rejected.Add(new PairingAddressCandidate
                    {
                        Address = canonical,
                        InterfaceName = name,
                        Priority = priority,
                        Reason = PairingAddressReasons.Apipa,
                    });
                    continue;
                }

                if (!seen.Add(canonical))
                {
                    continue;
                }

                usable.Add((
                    new PairingAddressCandidate
                    {
                        Address = canonical,
                        InterfaceName = name,
                        Priority = priority,
                        Reason = reason,
                    },
                    address));
            }
        }

        usable.Sort(static (left, right) =>
        {
            int byPriority = left.Candidate.Priority.CompareTo(right.Candidate.Priority);
            if (byPriority != 0)
            {
                return byPriority;
            }

            int byAddress = CompareAddresses(left.Parsed, right.Parsed);
            if (byAddress != 0)
            {
                return byAddress;
            }

            return string.Compare(left.Candidate.InterfaceName, right.Candidate.InterfaceName, StringComparison.OrdinalIgnoreCase);
        });

        return new PairingAddressReport
        {
            Candidates = usable.Select(entry => entry.Candidate).ToArray(),
            Rejected = rejected,
            Reason = usable.Count == 0 ? PairingAddressReasons.NoUsableAddress : null,
        };
    }

    /// <summary>Wired beats wireless beats anything else.</summary>
    public static int PriorityOf(NetworkInterfaceType type) => type switch
    {
        NetworkInterfaceType.Ethernet
            or NetworkInterfaceType.GigabitEthernet
            or NetworkInterfaceType.FastEthernetFx
            or NetworkInterfaceType.FastEthernetT => 0,
        NetworkInterfaceType.Wireless80211 => 1,
        _ => 2,
    };

    /// <summary>Stable reason code for a ranked candidate on this interface kind.</summary>
    public static string ReasonOf(NetworkInterfaceType type) => type switch
    {
        NetworkInterfaceType.Ethernet
            or NetworkInterfaceType.GigabitEthernet
            or NetworkInterfaceType.FastEthernetFx
            or NetworkInterfaceType.FastEthernetT => PairingAddressReasons.Wired,
        NetworkInterfaceType.Wireless80211 => PairingAddressReasons.Wireless,
        _ => PairingAddressReasons.Other,
    };

    private static bool IsApipa(IPAddress address)
    {
        byte[] bytes = address.GetAddressBytes();
        return bytes[0] == 169 && bytes[1] == 254;
    }

    private static int CompareAddresses(IPAddress left, IPAddress right)
    {
        byte[] leftBytes = left.GetAddressBytes();
        byte[] rightBytes = right.GetAddressBytes();
        for (int index = 0; index < leftBytes.Length; index++)
        {
            int difference = leftBytes[index].CompareTo(rightBytes[index]);
            if (difference != 0)
            {
                return difference;
            }
        }

        return 0;
    }
}

namespace MediaHub.Providers.Smb;

/// <summary>Outcome of a reachability probe against a share.</summary>
/// <param name="Ok">True when the share answered and its root could be read.</param>
/// <param name="Failure">The category the source list uses to choose a status.</param>
/// <param name="Message">A sentence for the dialog, the source card or the browse page.</param>
public sealed record SmbProbeResult(bool Ok, SmbFailure Failure, string Message);

/// <summary>
/// Answers "can this share be browsed right now" without a provider instance, so the add dialog and
/// the restore path can use the same check the browse page will.
/// </summary>
public static class SmbProbe
{
    /// <summary>How long a host may take before the answer is "timed out" rather than "still trying".</summary>
    public static readonly TimeSpan Timeout = TimeSpan.FromSeconds(20);

    public static async Task<SmbProbeResult> ProbeAsync(
        SmbAddress address,
        string? userName,
        string? password,
        CancellationToken ct = default)
    {
        var share = new SmbShareConnection(address, userName, password, Timeout);

        (SmbFailure Failure, string Message) connected = await share.EnsureAsync(ct).ConfigureAwait(false);
        if (connected.Failure != SmbFailure.None) return new(false, connected.Failure, connected.Message);

        (SmbFailure Failure, string Message) readable = await SmbConnectivity
            .ProbeDirectoryAsync(address.UncRoot, share.HasCredentials, Timeout, ct)
            .ConfigureAwait(false);
        if (readable.Failure != SmbFailure.None)
        {
            // An address may point inside a share, and "access denied" on the folder looks identical
            // to "access denied" on the share. Opening the share itself tells the two apart: when it
            // works, the account is fine and the sentence must name a permission, not a password.
            if (readable.Failure == SmbFailure.CredentialsRequired &&
                !string.Equals(address.UncRoot, address.ConnectionTarget, StringComparison.OrdinalIgnoreCase))
            {
                (SmbFailure Failure, string Message) root = await SmbConnectivity
                    .ProbeDirectoryAsync(address.ConnectionTarget, share.HasCredentials, Timeout, ct)
                    .ConfigureAwait(false);
                if (root.Failure == SmbFailure.None)
                {
                    (SmbFailure Failure, string Message) denied = SmbErrors.PermissionDenied(address.UncRoot);
                    return new(false, denied.Failure, denied.Message);
                }
            }
            return new(false, readable.Failure, readable.Message);
        }

        return new(true, SmbFailure.None, "连接正常：" + address.UncRoot);
    }
}

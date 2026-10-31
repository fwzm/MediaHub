namespace MediaHub.Sync;

/// <summary>
/// Raised when a sync document does not satisfy the contract — malformed JSON, a missing required field, or a
/// version this build must not guess at.
/// </summary>
/// <remarks>
/// The message is for logs and developers. <see cref="Reason"/> is the stable, machine-readable half, of the
/// shape <c>missing-field:deviceId</c>, <c>invalid-field:minimumReaderVersion</c>,
/// <c>unsupported-schema-version:2</c> or <c>unsupported-minimum-reader-version:2</c>; the UI translates it
/// instead of parsing prose.
/// </remarks>
public sealed class SyncContractException : Exception
{
    /// <summary>Creates the exception with its stable reason and a developer-facing message.</summary>
    public SyncContractException(string reason, string message)
        : base(message)
        => Reason = reason;

    /// <summary>Machine-readable reason code.</summary>
    public string Reason { get; }
}

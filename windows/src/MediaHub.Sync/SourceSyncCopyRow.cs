namespace MediaHub.Sync;

/// <summary>One row of a kept-back list, ready to bind: the sentence, the way out and the identity to copy.</summary>
/// <remarks>
/// W6-95: the withheld list and the refused list bind the same template of facts, so they share one row shape instead
/// of two nearly identical records that drift apart. Keeping the copy payload here means the button means exactly one
/// thing; whether a row deserves a way out stays with the list that owns the wording.
/// </remarks>
public sealed record SourceSyncCopyRow
{
    /// <summary>The sentence the list shows for this entry; never blank.</summary>
    public required string Text { get; init; }

    /// <summary>Way out for this entry, empty when this list has none to offer.</summary>
    public string NextStep { get; init; } = "";

    /// <summary>Payload the copy button puts on the clipboard; empty when there is no identity to copy.</summary>
    public string CopyText { get; init; } = "";

    /// <summary>Whether <see cref="CopyText"/> holds a real peer identity.</summary>
    public bool CanCopy { get; init; }

    /// <summary>Button caption: one sentence, and the disabled case says why instead of staying silent.</summary>
    public string CopyLabel => CanCopy ? SourceSyncCopyText.CopyIdentity : SourceSyncCopyText.NoCopyableIdentity;
}

/// <summary>The one place the copy button words live, so both kept-back lists say the same thing.</summary>
public static class SourceSyncCopyText
{
    /// <summary>Caption of an enabled copy button.</summary>
    public const string CopyIdentity = "复制身份";

    /// <summary>Caption of a copy button with nothing to copy: a disabled button that does not explain itself reads as broken.</summary>
    public const string NoCopyableIdentity = "无身份可复制";
}

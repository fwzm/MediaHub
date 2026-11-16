namespace MediaHub.Player;

/// <summary>
/// Values shared by the two places that edit the subtitle look: the settings page (persistent
/// preferences) and the player's own subtitle panel (so a change can be judged on the picture).
/// </summary>
public static class SubtitleStyleOptions
{
    public static IReadOnlyList<(string Hex, string Label)> Colors { get; } = new[]
    {
        ("#FFFFFF", "白色"),
        ("#FFF3C4", "暖黄"),
        ("#C8F0FF", "浅蓝"),
        ("#FFE066", "黄色"),
        ("#B9F6CA", "浅绿"),
    };

    /// <summary>Restores the values a fresh profile starts with.</summary>
    public const double DefaultSize = 55;
    public const string DefaultFont = "Microsoft YaHei UI";
    public const string DefaultColor = "#FFFFFF";
    public const double DefaultBorderSize = 2.5;
    public const double DefaultPosition = 100;
}

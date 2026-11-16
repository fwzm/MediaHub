namespace MediaHub.Player;

/// <summary>One user-visible input binding: the keys, what they do, and where they apply.</summary>
public sealed record PlayerShortcut(string Keys, string Action, string Group);

/// <summary>
/// The single list of what the player accepts.
/// </summary>
/// <remarks>
/// It lives in the player assembly, not in the page, because it is rendered twice - the in-player
/// help panel and the settings page - and because it is asserted by tests. A help sheet maintained
/// next to the handlers is the only kind that does not drift away from them; one kept in a UI file
/// is a promise nobody checks.
/// </remarks>
public static class PlayerShortcutCatalog
{
    public const string Keyboard = "键盘";
    public const string Mouse = "鼠标与滚轮";
    public const string Buttons = "播放器按钮";

    public static IReadOnlyList<PlayerShortcut> All { get; } = new PlayerShortcut[]
    {
        new("空格", "播放 / 暂停", Keyboard),
        new("← / →", "后退 / 前进 5 秒", Keyboard),
        new("↑ / ↓", "指针在左半屏调亮度，右半屏调音量", Keyboard),
        new("M", "静音开关", Keyboard),
        new("I", "显示 / 隐藏媒体信息", Keyboard),
        new("F / F11", "全屏切换", Keyboard),
        new("Esc", "退出全屏 → 关闭侧栏 → 返回上一页", Keyboard),
        new("? / F1", "打开 / 关闭这份操作说明", Keyboard),

        new("滚轮（左半屏）", "调节亮度", Mouse),
        new("滚轮（右半屏）", "调节音量", Mouse),
        new("双击画面", "全屏切换", Mouse),
        new("拖动进度条", "定位，松开时才真正跳转", Mouse),

        new("上一集 / 下一集", "在剧集队列中切换（底栏按钮）", Buttons),
        new("音轨 / 字幕", "打开轨道与字幕面板（底栏右侧）", Buttons),
        new("媒体信息", "编码、色彩、硬解等实时信息（顶栏）", Buttons),
        new("截图", "保存当前画面（需在设置中开启）", Buttons),
    };
}

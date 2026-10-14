namespace MediaHub.App.Services;

/// <summary>
/// Minimal file-backed startup trace.
/// </summary>
/// <remarks>
/// Startup failures in an unpackaged WinUI app are easy to lose: there is no console, and a failure
/// before the first window exists produces no visible symptom at all. This trace is written
/// synchronously and flushed per line so the last entry survives a hard process exit.
/// </remarks>
public static class StartupTrace
{
    private static readonly object Gate = new();
    private static readonly string LogPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MediaHub", "startup.log");

    public static void Write(string stage)
    {
        try
        {
            lock (Gate)
            {
                Directory.CreateDirectory(Path.GetDirectoryName(LogPath)!);
                File.AppendAllText(LogPath, $"{DateTime.Now:HH:mm:ss.fff} {stage}{Environment.NewLine}");
            }
        }
        catch
        {
            // Tracing must never be the reason startup fails.
        }
    }

    public static void Reset()
    {
        try { lock (Gate) File.WriteAllText(LogPath, ""); } catch { }
    }
}

using Microsoft.Win32;

namespace MediaHub.App.Services;

/// <summary>
/// Registers and removes this application's ability to open video files.
/// </summary>
/// <remarks>
/// <para>
/// Everything is written under <c>HKCU\Software\Classes</c>, so nothing here needs elevation and an
/// uninstall can undo it completely. The registration deliberately does <b>not</b> claim the UserChoice
/// keys: writing those is both unsupported and, in practice, a hijack of the user's chosen default
/// player. Instead the application is offered as a candidate (<c>OpenWithProgids</c> and
/// <c>Applications\MediaHub.exe\SupportedTypes</c>), which makes it appear in "Open with" and lets the
/// user promote it deliberately.
/// </para>
/// <para>
/// The <c>Applications</c> form matters more than it looks: without it, a program that has never
/// claimed a file type can be invisible in the Open-with list even though its ProgId is registered.
/// </para>
/// </remarks>
public static class FileAssociationService
{
    private const string ProgId = "MediaHub.Video";
    private const string AppKey = @"Software\Classes\Applications\MediaHub.exe";
    private const string ProgIdKey = @"Software\Classes\" + ProgId;

    /// <summary>Extensions the client can play. Mirrors the local library's recognised set.</summary>
    public static readonly string[] Extensions =
    {
        ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".m4v", ".webm", ".flv", ".ts", ".mpg", ".mpeg",
        ".mp3", ".flac", ".m4a", ".aac", ".ogg", ".opus", ".wav",
    };

    /// <summary>True when this application is currently offered for the given extension.</summary>
    public static bool IsRegistered()
    {
        try
        {
            using RegistryKey? key = Registry.CurrentUser.OpenSubKey(AppKey);
            return key is not null;
        }
        catch { return false; }
    }

    /// <summary>
    /// Offers MediaHub as a handler for the recognised extensions. Returns the number registered.
    /// </summary>
    public static int Register(string executablePath)
    {
        if (string.IsNullOrWhiteSpace(executablePath) || !File.Exists(executablePath))
            throw new FileNotFoundException("可执行文件不存在", executablePath);

        int count = 0;
        string command = "\"" + executablePath + "\" \"%1\"";

        using (RegistryKey app = Registry.CurrentUser.CreateSubKey(AppKey, writable: true)!)
        {
            app.SetValue("FriendlyAppName", "MediaHub");
            using RegistryKey shellKey = app.CreateSubKey(@"shell\open\command", writable: true)!;
            shellKey.SetValue(null, command);
            using RegistryKey types = app.CreateSubKey("SupportedTypes", writable: true)!;
            foreach (string extension in Extensions) types.SetValue(extension, "");
        }

        using (RegistryKey progId = Registry.CurrentUser.CreateSubKey(ProgIdKey, writable: true)!)
        {
            progId.SetValue(null, "MediaHub 视频");
            progId.SetValue("FriendlyTypeName", "MediaHub 视频");
            using RegistryKey icon = progId.CreateSubKey("DefaultIcon", writable: true)!;
            icon.SetValue(null, "\"" + executablePath + "\",0");
            using RegistryKey openCommand = progId.CreateSubKey(@"shell\open\command", writable: true)!;
            openCommand.SetValue(null, command);
        }

        foreach (string extension in Extensions)
        {
            // OpenWithProgids adds a candidate without changing the user's default.
            using RegistryKey? key = Registry.CurrentUser.CreateSubKey(@"Software\Classes\" + extension + @"\OpenWithProgids", writable: true);
            if (key is null) continue;
            key.SetValue(ProgId, Array.Empty<byte>(), RegistryValueKind.Binary);
            count++;
        }

        return count;
    }

    /// <summary>Removes everything <see cref="Register"/> created. Returns the extensions cleaned.</summary>
    public static int Unregister()
    {
        int count = 0;

        foreach (string extension in Extensions)
        {
            try
            {
                using RegistryKey? key = Registry.CurrentUser.OpenSubKey(
                    @"Software\Classes\" + extension + @"\OpenWithProgids", writable: true);
                if (key is null) continue;
                if (key.GetValue(ProgId) is not null)
                {
                    key.DeleteValue(ProgId, throwOnMissingValue: false);
                    count++;
                }

                // Leaving an empty key behind is untidy and shows up in some registry tooling.
                if (key.ValueCount == 0 && key.SubKeyCount == 0)
                {
                    key.Close();
                    Registry.CurrentUser.DeleteSubKey(
                        @"Software\Classes\" + extension + @"\OpenWithProgids", throwOnMissingSubKey: false);
                }
            }
            catch (Exception) { /* best effort: one stubborn key must not abort the rest */ }
        }

        TryDeleteTree(AppKey);
        TryDeleteTree(ProgIdKey);
        return count;
    }

    private static void TryDeleteTree(string path)
    {
        try { Registry.CurrentUser.DeleteSubKeyTree(path, throwOnMissingSubKey: false); }
        catch (Exception) { }
    }
}

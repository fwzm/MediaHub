using System.Runtime.InteropServices;

namespace MediaHub.EngineProbe;

/// <summary>
/// Loads one libmpv build from an absolute path and resolves exports by name.
/// </summary>
/// <remarks>
/// The product binds libmpv through <c>[DllImport("libmpv-2.dll")]</c>, which resolves against the
/// loader search path and therefore can only ever see the DLL that happens to sit next to the App.
/// A probe has to compare two builds, so it loads explicitly instead: <see cref="NativeLibrary.Load(string)"/>
/// first (that is enough for a self-contained build), then - only when the caller supplied dependency
/// directories - the same call with <c>AddDllDirectory</c> registered user directories, and finally a
/// raw <c>LoadLibraryExW</c> with the same search flags. The mode that worked is recorded in
/// <see cref="LoadMode"/> so the report never has to guess how the DLL was found.
/// </remarks>
internal sealed class MpvLibrary : IDisposable
{
    private const uint LoadLibrarySearchDllLoadDir = 0x0000_0100;
    private const uint LoadLibrarySearchUserDirs = 0x0000_0400;
    private const uint LoadLibrarySearchDefaultDirs = 0x0000_1000;

    private readonly Dictionary<string, IntPtr> _exports = new(StringComparer.Ordinal);
    private IntPtr _handle;

    private MpvLibrary(IntPtr handle, string loadMode, IReadOnlyList<string> dependencyDirectories)
    {
        _handle = handle;
        LoadMode = loadMode;
        DependencyDirectories = dependencyDirectories;
    }

    /// <summary>How the DLL was ultimately opened: <c>native-library</c>, <c>native-library+user-dirs</c> or <c>load-library-ex+user-dirs</c>.</summary>
    public string LoadMode { get; }

    /// <summary>Additional directories registered as DLL search roots (empty when none were needed).</summary>
    public IReadOnlyList<string> DependencyDirectories { get; }

    public static MpvLibrary Load(string dllPath, IReadOnlyList<string> dependencyDirectories)
    {
        List<string> attempts = [];
        try
        {
            return new MpvLibrary(NativeLibrary.Load(dllPath), "native-library", dependencyDirectories);
        }
        catch (Exception ex) when (ex is DllNotFoundException or BadImageFormatException)
        {
            // A build that links FFmpeg as shared libraries needs its dependencies resolvable first;
            // that is the normal case for the LGPL candidate, not an error yet.
            attempts.Add("native-library: " + ex.Message);
        }

        if (dependencyDirectories.Count > 0)
        {
            RegisterDependencyDirectories(dependencyDirectories);
            try
            {
                return new MpvLibrary(NativeLibrary.Load(dllPath), "native-library+user-dirs", dependencyDirectories);
            }
            catch (Exception ex) when (ex is DllNotFoundException or BadImageFormatException)
            {
                attempts.Add("native-library+user-dirs: " + ex.Message);
            }

            IntPtr handle = LoadLibraryExW(
                dllPath,
                IntPtr.Zero,
                LoadLibrarySearchDllLoadDir | LoadLibrarySearchUserDirs | LoadLibrarySearchDefaultDirs);
            if (handle != IntPtr.Zero)
            {
                return new MpvLibrary(handle, "load-library-ex+user-dirs", dependencyDirectories);
            }

            attempts.Add("load-library-ex: win32 " + Marshal.GetLastWin32Error());
        }

        throw new InvalidOperationException(
            "engine DLL could not be loaded; tried " + string.Join(" | ", attempts));
    }

    /// <summary>Resolves one export and caches the pointer.</summary>
    public IntPtr Export(string name)
    {
        ObjectDisposedException.ThrowIf(_handle == IntPtr.Zero, this);
        if (_exports.TryGetValue(name, out IntPtr cached))
        {
            return cached;
        }

        IntPtr pointer;
        try
        {
            pointer = NativeLibrary.GetExport(_handle, name);
        }
        catch (EntryPointNotFoundException ex)
        {
            throw new MissingEntryPointException(name, LoadMode, ex);
        }

        if (pointer == IntPtr.Zero)
        {
            throw new MissingEntryPointException(name, LoadMode, null);
        }

        _exports[name] = pointer;
        return pointer;
    }

    /// <summary>True when the export exists; used for optional entry points such as <c>mpv_destroy</c>.</summary>
    public bool HasExport(string name)
    {
        if (_handle == IntPtr.Zero)
        {
            return false;
        }

        try
        {
            return NativeLibrary.TryGetExport(_handle, name, out IntPtr pointer) && pointer != IntPtr.Zero;
        }
        catch (Exception ex) when (ex is DllNotFoundException or BadImageFormatException)
        {
            return false;
        }
    }

    public T Bind<T>(string name) where T : Delegate
        => Marshal.GetDelegateForFunctionPointer<T>(Export(name));

    public void Dispose()
    {
        IntPtr handle = Interlocked.Exchange(ref _handle, IntPtr.Zero);
        if (handle != IntPtr.Zero)
        {
            NativeLibrary.Free(handle);
        }
    }

    private static void RegisterDependencyDirectories(IReadOnlyList<string> directories)
    {
        // AddDllDirectory only takes effect once the default search order includes user directories,
        // which is what the probe asks for here. System32 and the application directory stay in play.
        SetDefaultDllDirectories(LoadLibrarySearchDefaultDirs | LoadLibrarySearchUserDirs);
        foreach (string directory in directories)
        {
            if (Directory.Exists(directory))
            {
                AddDllDirectory(directory);
            }
        }
    }

    [DllImport("kernel32", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr LoadLibraryExW(string fileName, IntPtr file, uint flags);

    [DllImport("kernel32", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr AddDllDirectory(string newDirectory);

    [DllImport("kernel32", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetDefaultDllDirectories(uint directoryFlags);
}

/// <summary>Raised when a build does not export an entry point this probe requires.</summary>
internal sealed class MissingEntryPointException : Exception
{
    public MissingEntryPointException(string exportName, string loadMode, Exception? inner)
        : base("engine DLL has no export '" + exportName + "' (loaded via " + loadMode + ")", inner)
    {
        ExportName = exportName;
    }

    public string ExportName { get; }
}

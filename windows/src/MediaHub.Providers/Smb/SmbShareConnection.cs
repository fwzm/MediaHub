using System.Runtime.InteropServices;

namespace MediaHub.Providers.Smb;

/// <summary>
/// Opens a share for the current logon session using the Windows redirector — no third-party SMB
/// client is involved.
/// </summary>
/// <remarks>
/// <para>
/// <see cref="WNetAddConnection2"/> is used with a null local drive letter and
/// <c>CONNECT_TEMPORARY</c>, so nothing is mapped into Explorer and the connection disappears with
/// the process. That is the whole reason the redirector is preferred over an in-process SMB stack:
/// authentication, signing, dialect negotiation and reconnect are the operating system's problem.
/// </para>
/// <para>
/// A share that accepts the current Windows identity needs no call at all; the connection attempt is
/// skipped and the first directory read is the real check.
/// </para>
/// </remarks>
public sealed class SmbShareConnection
{
    private readonly SmbAddress _address;
    private readonly string? _userName;
    private readonly string? _password;
    private readonly TimeSpan _timeout;
    private volatile bool _connected;

    public SmbShareConnection(SmbAddress address, string? userName, string? password, TimeSpan timeout)
    {
        _address = address;
        _userName = string.IsNullOrWhiteSpace(userName) ? null : userName.Trim();
        _password = password;
        _timeout = timeout;
    }

    /// <summary>True when the share is opened with an explicit account rather than the logon identity.</summary>
    public bool HasCredentials => _userName is not null;

    /// <summary>
    /// Establishes the share connection once. Subsequent calls are free.
    /// </summary>
    /// <returns>A failure and a readable sentence, or <see cref="SmbFailure.None"/>.</returns>
    public async Task<(SmbFailure Failure, string Message)> EnsureAsync(CancellationToken ct = default)
    {
        if (_connected) return (SmbFailure.None, "");
        if (!HasCredentials)
        {
            // The logon identity is used implicitly; there is nothing to open and no credential to
            // validate here. A share that rejects it is reported by the directory probe.
            _connected = true;
            return (SmbFailure.None, "");
        }

        try
        {
            (SmbFailure Failure, string Message) result = await Task
                .Run(() => Connect(_address.ConnectionTarget, _userName, _password), ct)
                .WaitAsync(_timeout, ct)
                .ConfigureAwait(false);

            if (result.Failure == SmbFailure.None) _connected = true;
            return result;
        }
        catch (TimeoutException)
        {
            return TimeoutFailure();
        }
    }

    private (SmbFailure Failure, string Message) TimeoutFailure()
        => (SmbFailure.Timeout,
            "连接超时：主机在 " + (int)Math.Ceiling(_timeout.TotalSeconds) + " 秒内没有响应，请检查主机和网络后重试");

    private static (SmbFailure Failure, string Message) Connect(string target, string? userName, string? password)
    {
        var resource = new NativeNetResource
        {
            dwType = NativeResourcetypeDisk,
            dwDisplayType = NativeResourcetypeShare,
            lpRemoteName = target,
        };

        int code = WNetAddConnection2(ref resource, password, userName, NativeConnectTemporary);
        if (code == NativeErrorAlreadyAssigned) return (SmbFailure.None, "");
        return code == 0 ? (SmbFailure.None, "") : SmbErrors.Describe(code, target, credentialsSupplied: true);
    }

    private const uint NativeResourcetypeDisk = 1;
    private const uint NativeResourcetypeShare = 2;
    private const uint NativeConnectTemporary = 0x00000004;
    private const int NativeErrorAlreadyAssigned = 85;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct NativeNetResource
    {
        public uint dwScope;
        public uint dwType;
        public uint dwDisplayType;
        public uint dwUsage;
        public string? lpLocalName;
        public string? lpRemoteName;
        public string? lpComment;
        public string? lpProvider;
    }

    [DllImport("mpr.dll", CharSet = CharSet.Unicode, EntryPoint = "WNetAddConnection2W")]
    private static extern int WNetAddConnection2(ref NativeNetResource netResource, string? password,
        string? userName, uint flags);
}

/// <summary>
/// Reads the share root or a single file and reports the failure in the terms the UI shows.
/// </summary>
/// <remarks>
/// The reads are bounded here rather than by the caller: a share whose host silently drops packets
/// makes <see cref="Directory.EnumerateFileSystemEntries(string)"/> block for the TCP timeout, which
/// would freeze the browse page instead of telling the user the host is gone.
/// </remarks>
internal static class SmbConnectivity
{
    public static async Task<(SmbFailure Failure, string Message)> ProbeDirectoryAsync(
        string path, bool credentialsSupplied, TimeSpan timeout, CancellationToken ct = default)
    {
        try
        {
            return await Task.Run(() => ProbeDirectory(path, credentialsSupplied), ct)
                .WaitAsync(timeout, ct)
                .ConfigureAwait(false);
        }
        catch (TimeoutException)
        {
            return TimeoutFailure(timeout);
        }
    }

    public static async Task<(SmbFailure Failure, string Message)> ProbeFileAsync(
        string path, bool credentialsSupplied, TimeSpan timeout, CancellationToken ct = default)
    {
        try
        {
            return await Task.Run(() => ProbeFile(path, credentialsSupplied), ct)
                .WaitAsync(timeout, ct)
                .ConfigureAwait(false);
        }
        catch (TimeoutException)
        {
            return TimeoutFailure(timeout);
        }
    }

    public static (SmbFailure Failure, string Message) ProbeDirectory(string path, bool credentialsSupplied)
    {
        try
        {
            // One entry is enough to prove the share answers; enumerating a directory of thousands of
            // files before the real listing would double the round trips for nothing.
            using IEnumerator<string> entries = Directory.EnumerateFileSystemEntries(path).GetEnumerator();
            _ = entries.MoveNext();
            return (SmbFailure.None, "");
        }
        catch (OperationCanceledException) { throw; }
        catch (Exception ex)
        {
            return FromException(ex, path, credentialsSupplied, isFolder: true);
        }
    }

    public static (SmbFailure Failure, string Message) ProbeFile(string path, bool credentialsSupplied)
    {
        try
        {
            var info = new FileInfo(path);
            if (!info.Exists) return (SmbFailure.NotFound, "找不到该文件：" + Path.GetFileName(path));
            _ = info.Length;
            return (SmbFailure.None, "");
        }
        catch (OperationCanceledException) { throw; }
        catch (Exception ex)
        {
            return FromException(ex, path, credentialsSupplied, isFolder: false);
        }
    }

    private static (SmbFailure Failure, string Message) FromException(
        Exception ex, string path, bool credentialsSupplied, bool isFolder)
    {
        int code = SmbErrors.Win32Code(ex);
        if (code >= 0)
        {
            (SmbFailure Failure, string Message) mapped = SmbErrors.Describe(code, path, credentialsSupplied);
            if (mapped.Failure != SmbFailure.Other) return mapped;
        }

        if (ex is UnauthorizedAccessException)
        {
            return credentialsSupplied
                ? (SmbFailure.Authentication, "认证失败：该账号没有访问此共享的权限")
                : (SmbFailure.CredentialsRequired, "该共享需要账号和密码，请重新添加并在表单中填写凭据");
        }

        if (ex is DirectoryNotFoundException)
            return (SmbFailure.NotFound, "共享不存在或无法访问：" + path);

        if (ex is FileNotFoundException)
            return (SmbFailure.NotFound, "找不到该文件：" + Path.GetFileName(path));

        if (ex is IOException)
            return (SmbFailure.Network, "读取共享失败，请检查网络连接后重试");

        return (SmbFailure.Other, isFolder
            ? "无法读取共享目录，请检查该共享是否可用"
            : "无法读取该文件，请检查该共享是否可用");
    }

    private static (SmbFailure Failure, string Message) TimeoutFailure(TimeSpan timeout)
        => (SmbFailure.Timeout,
            "连接超时：共享主机在 " + (int)Math.Ceiling(timeout.TotalSeconds) + " 秒内没有响应，请检查主机和网络后重试");
}

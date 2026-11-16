using System.ComponentModel;

namespace MediaHub.Providers.Smb;

/// <summary>What went wrong when talking to a share, at the granularity the UI needs to react.</summary>
public enum SmbFailure
{
    None = 0,

    /// <summary>The share demands credentials and none were supplied.</summary>
    CredentialsRequired,

    /// <summary>Credentials were supplied and rejected, or the account is not allowed in.</summary>
    Authentication,

    /// <summary>The server or share does not exist, or the path does not.</summary>
    NotFound,

    /// <summary>The network is down, the host is unreachable, or the connection dropped.</summary>
    Network,

    /// <summary>The operation did not finish inside the client-side bound.</summary>
    Timeout,

    /// <summary>An existing connection to this share uses different credentials.</summary>
    Conflict,

    /// <summary>The share is readable, but this account may not open this folder or file.</summary>
    Permission,

    /// <summary>Anything the mapping does not recognise; the message still names the system error.</summary>
    Other,
}

/// <summary>
/// Turns Win32 / <see cref="System.IO"/> failures into one readable sentence plus an
/// <see cref="SmbFailure"/> the caller can act on.
/// </summary>
/// <remarks>
/// The redirector reports most of these through a Win32 code in the low word of
/// <see cref="Exception.HResult"/> (or a <see cref="Win32Exception.NativeErrorCode"/> nested inside).
/// A code is never surfaced alone: "系统错误 1326" tells a user nothing, while "账号或密码不正确"
/// tells them exactly which box to fix. Messages contain the share name only — never a user name,
/// a password or a credentialed path.
/// </remarks>
internal static class SmbErrors
{
    private const int ErrorSuccess = 0;
    private const int ErrorAccessDenied = 5;
    private const int ErrorBadNetPath = 53;
    private const int ErrorBadNetName = 67;
    private const int ErrorInvalidPassword = 86;
    private const int ErrorSemTimeout = 121;
    private const int ErrorNetNameDeleted = 64;
    private const int ErrorNoNetwork = 1203;
    private const int ErrorSessionCredentialConflict = 1219;
    private const int ErrorNetworkUnreachable = 1231;
    private const int ErrorHostUnreachable = 1232;
    private const int ErrorLogonFailure = 1326;
    private const int ErrorAccountRestriction = 1327;
    private const int ErrorLogonTypeNotGranted = 1385;
    private const int ErrorAccountLockedOut = 1909;
    private const int ErrorNoLogonServers = 1311;

    public static (SmbFailure Failure, string Message) Describe(int code, string target, bool credentialsSupplied)
    {
        switch (code)
        {
            case ErrorSuccess:
                return (SmbFailure.None, "");
            case ErrorLogonFailure:
            case ErrorInvalidPassword:
                return (SmbFailure.Authentication, "认证失败：账号或密码不正确，请检查后重试");
            case ErrorAccountRestriction:
                return (SmbFailure.Authentication, "认证失败：该账号受限（可能不允许空密码或网络登录）");
            case ErrorLogonTypeNotGranted:
                return (SmbFailure.Authentication, "认证失败：该账号不被允许进行网络登录");
            case ErrorAccountLockedOut:
                return (SmbFailure.Authentication, "认证失败：该账号已被锁定，请联系管理员");
            case ErrorAccessDenied when credentialsSupplied:
                return (SmbFailure.Authentication, "认证失败：账号或密码不正确，或该账号没有访问此共享的权限");
            case ErrorAccessDenied:
                return (SmbFailure.CredentialsRequired, "该共享需要账号和密码，请重新添加并在表单中填写凭据");
            case ErrorBadNetPath:
            case ErrorBadNetName:
            case 3:
            case 2:
                return (SmbFailure.NotFound, "共享不存在或无法访问：" + target);
            case ErrorSessionCredentialConflict:
                return (SmbFailure.Conflict, "该共享已用其他账号连接，请重启应用后重试");
            case ErrorSemTimeout:
                return (SmbFailure.Timeout, "连接超时：共享主机没有响应，请检查主机是否在线");
            case ErrorNetworkUnreachable:
                return (SmbFailure.Network, "网络不可达：请检查主机地址和网络连接");
            case ErrorHostUnreachable:
                return (SmbFailure.Network, "主机不可达：请检查主机是否开机、名称是否正确");
            case ErrorNoNetwork:
                return (SmbFailure.Network, "当前没有可用的网络连接");
            case ErrorNetNameDeleted:
                return (SmbFailure.Network, "网络连接已断开，请检查网络后重试");
            case ErrorNoLogonServers:
                return (SmbFailure.Network, "暂时联系不到登录服务器，请稍后重试");
            default:
                return (SmbFailure.Other, "无法访问该共享（系统错误 " + code + "）");
        }
    }

    /// <summary>
    /// The share opened but a path inside it did not. Used when the share root is readable, which
    /// rules out a missing credential and leaves a permission the account does not have.
    /// </summary>
    public static (SmbFailure Failure, string Message) PermissionDenied(string target)
        => (SmbFailure.Permission, "没有访问权限：" + target + "（共享可以打开，但当前账号不能访问这个位置）");

    /// <summary>
    /// Extracts the Win32 code a <see cref="System.IO"/> failure carries, searching the inner chain
    /// because <c>DirectoryNotFoundException</c> often wraps a <see cref="Win32Exception"/>.
    /// </summary>
    /// <returns><c>-1</c> when no code is present.</returns>
    public static int Win32Code(Exception exception)
    {
        for (Exception? current = exception; current is not null; current = current.InnerException)
        {
            if (current is Win32Exception win32) return win32.NativeErrorCode;

            // Win32 failures surface as 0x8007xxxx (FACILITY_WIN32, so 0x80070005 -> 5). Only that
            // facility yields a Win32 code; every other HRESULT carries a CLR or COM code whose low
            // word means something else entirely (InvalidOperationException is 0x80131509, which
            // would otherwise be "read" as Win32 error 5385).
            uint hr = unchecked((uint)current.HResult);
            if (hr is >= 0x80070000u and <= 0x8007FFFFu && (hr & 0xFFFF) != 0) return (int)(hr & 0xFFFF);
        }
        return -1;
    }
}

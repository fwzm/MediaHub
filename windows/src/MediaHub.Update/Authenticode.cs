using System.Runtime.InteropServices;

namespace MediaHub.Update;

/// <summary>
/// Asks Windows whether a file carries a code signature that chains to a trusted root.
/// </summary>
/// <remarks>
/// <para>
/// The update manifest carries the package's SHA-256, but the manifest and the package are fetched
/// from the same server: a digest proves the bytes arrived unchanged, not that the publisher built
/// them. Windows already knows which code-signing roots it trusts, so the same check the shell
/// applies before running a downloaded program is the check that decides whether an update may be
/// installed.
/// </para>
/// <para>
/// Revocation lookups are deliberately disabled: the check must work offline and must not appear to
/// hang the UI while a server is unreachable. A revoked-but-signed binary is a different problem from
/// an unsigned one, and refusing unsigned input is what this gate is for.
/// </para>
/// </remarks>
internal static class Authenticode
{
    private static readonly Guid GenericVerifyV2 = new("00AAC56B-CD44-11d0-8CC2-00C04FC295EE");

    private const uint WtdUiNone = 2;
    private const uint WtdRevokeNone = 0;
    private const uint WtdChoiceFile = 1;
    private const uint WtdStateActionVerify = 1;
    private const uint WtdStateActionClose = 2;
    private const uint WtdSaferFlag = 0x100;
    private const uint WtdCacheOnlyUrlRetrieval = 0x1000;

    /// <summary>True when the file is signed and the chain is trusted on this machine.</summary>
    public static bool Verify(string path, out string reason)
    {
        reason = "";
        if (!OperatingSystem.IsWindows())
        {
            reason = "code-signature verification requires Windows";
            return false;
        }

        var file = new WinTrustFileInfo
        {
            cbStruct = (uint)Marshal.SizeOf<WinTrustFileInfo>(),
            pcwszFilePath = path,
        };

        IntPtr filePtr = Marshal.AllocHGlobal(Marshal.SizeOf<WinTrustFileInfo>());
        try
        {
            Marshal.StructureToPtr(file, filePtr, fDeleteOld: false);
            var data = new WinTrustData
            {
                cbStruct = (uint)Marshal.SizeOf<WinTrustData>(),
                dwUIChoice = WtdUiNone,
                fdwRevocationChecks = WtdRevokeNone,
                dwUnionChoice = WtdChoiceFile,
                pFile = filePtr,
                dwStateAction = WtdStateActionVerify,
                dwProvFlags = WtdSaferFlag | WtdCacheOnlyUrlRetrieval,
            };

            int status = WinVerifyTrust(IntPtr.Zero, GenericVerifyV2, ref data);

            // Closing releases the state the verify call allocated; skipping it leaks per call.
            data.dwStateAction = WtdStateActionClose;
            try { _ = WinVerifyTrust(IntPtr.Zero, GenericVerifyV2, ref data); } catch { }

            if (status == 0) return true;
            reason = Describe(status);
            return false;
        }
        finally
        {
            Marshal.DestroyStructure<WinTrustFileInfo>(filePtr);
            Marshal.FreeHGlobal(filePtr);
        }
    }

    private static string Describe(int status)
    {
        unchecked
        {
            return status switch
            {
                (int)0x800B0100 => "no signature found (TRUST_E_NOSIGNATURE)",
                (int)0x800B0109 => "signature chains to an untrusted root (CERT_E_UNTRUSTEDROOT)",
                (int)0x800B0101 => "signing certificate has expired (CERT_E_EXPIRED)",
                (int)0x800B010C => "signing certificate was revoked (CERT_E_REVOKED)",
                (int)0x800B0110 => "certificate is not valid for code signing (CERT_E_WRONG_USAGE)",
                (int)0x80096010 => "image digest does not match the signature (TRUST_E_BAD_DIGEST)",
                (int)0x80092003 => "signature could not be read (CRYPT_E_FILE_ERROR)",
                _ => "signature is not trusted (0x" + status.ToString("X8", System.Globalization.CultureInfo.InvariantCulture) + ")",
            };
        }
    }

    [DllImport("wintrust.dll", ExactSpelling = true, PreserveSig = true, SetLastError = false)]
    private static extern int WinVerifyTrust(
        IntPtr hwnd, [MarshalAs(UnmanagedType.LPStruct)] Guid actionId, ref WinTrustData data);

    [StructLayout(LayoutKind.Sequential)]
    private struct WinTrustFileInfo
    {
        public uint cbStruct;
        [MarshalAs(UnmanagedType.LPWStr)] public string pcwszFilePath;
        public IntPtr hFile;
        public IntPtr pgKnownSubject;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct WinTrustData
    {
        public uint cbStruct;
        public IntPtr pPolicyCallbackData;
        public IntPtr pSIPClientData;
        public uint dwUIChoice;
        public uint fdwRevocationChecks;
        public uint dwUnionChoice;
        public IntPtr pFile;
        public uint dwStateAction;
        public IntPtr hWVTStateData;
        public IntPtr pwszURLReference;
        public uint dwProvFlags;
        public uint dwUIContext;
        public IntPtr pSignatureSettings;
    }
}

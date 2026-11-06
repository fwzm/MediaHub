using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;

namespace MediaHub.Sync;

/// <summary>
/// Seals secrets with Windows DPAPI, so the ciphertext is bound to this Windows account (default) or, when
/// explicitly asked for, to this machine.
/// </summary>
/// <remarks>
/// <para>
/// This type lives in MediaHub.Sync rather than in the WinUI project on purpose: the test project does not
/// reference the application, so a protector that only existed in App could never be exercised with real
/// DPAPI ciphertext. App already references Sync, so the application uses this same type.
/// </para>
/// <para>
/// The entropy label is a fixed constant, not a secret: it only prevents another program that happens to
/// call DPAPI under the same account from unsealing our blob by accident. The real binding is DPAPI itself.
/// </para>
/// </remarks>
public sealed class WindowsDpapiProtector : ISecretProtector
{
    /// <summary>Optional entropy passed to DPAPI alongside the payload.</summary>
    public const string EntropyLabel = "MediaHub.SourceSync.v1";

    private const int CryptProtectUiForbidden = 0x1;
    private const int CryptProtectLocalMachine = 0x4;

    private readonly bool _machineScope;
    private readonly byte[] _entropy = Encoding.UTF8.GetBytes(EntropyLabel);

    /// <param name="machineScope">When true the blob is readable by any account on this machine.</param>
    public WindowsDpapiProtector(bool machineScope = false)
    {
        if (!OperatingSystem.IsWindows())
        {
            throw new PlatformNotSupportedException("DPAPI is only available on Windows.");
        }

        _machineScope = machineScope;
    }

    /// <summary><c>dpapi-manager</c> for machine scope, <c>dpapi-user</c> otherwise.</summary>
    public string Scheme => _machineScope ? SecretEnvelope.SchemeDpapiMachine : SecretEnvelope.SchemeDpapiUser;

    /// <summary>Machine name for machine scope, user name otherwise.</summary>
    public string Scope => _machineScope ? Environment.MachineName : Environment.UserName;

    /// <summary>True when this protector seals for the machine rather than the current user.</summary>
    public bool IsMachineScope => _machineScope;

    /// <inheritdoc />
    public SecretEnvelope Protect(ReadOnlySpan<byte> plaintext)
    {
        if (plaintext.IsEmpty)
        {
            throw new SecretException(SecretError.DpapiBlobEmpty, "Refusing to protect an empty payload.");
        }

        byte[] plain = plaintext.ToArray();
        byte[] sealedBytes;
        try
        {
            sealedBytes = Transform(plain, protect: true);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(plain);
        }

        return new SecretEnvelope
        {
            Scheme = Scheme,
            Scope = Scope,
            PayloadBase64 = Convert.ToBase64String(sealedBytes),
            ProtectedAtEpochMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
        };
    }

    /// <inheritdoc />
    public byte[] Unprotect(SecretEnvelope envelope)
    {
        ArgumentNullException.ThrowIfNull(envelope);
        envelope.Validate();

        if (envelope.IsEmpty)
        {
            throw new SecretException(SecretError.EnvelopeNotSealed, "An empty secret block carries nothing to unseal.");
        }

        byte[] sealedBytes = envelope.ToPayloadBytes();
        try
        {
            return Transform(sealedBytes, protect: false);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(sealedBytes);
        }
    }

    private byte[] Transform(byte[] input, bool protect)
    {
        DATA_BLOB inputBlob = default;
        DATA_BLOB entropyBlob = default;
        DATA_BLOB outputBlob = default;
        GCHandle inputHandle = default;
        GCHandle entropyHandle = default;

        try
        {
            inputHandle = GCHandle.Alloc(input, GCHandleType.Pinned);
            entropyHandle = GCHandle.Alloc(_entropy, GCHandleType.Pinned);
            inputBlob = new DATA_BLOB { cbData = input.Length, pbData = inputHandle.AddrOfPinnedObject() };
            entropyBlob = new DATA_BLOB { cbData = _entropy.Length, pbData = entropyHandle.AddrOfPinnedObject() };

            int flags = CryptProtectUiForbidden | ((_machineScope && protect) ? CryptProtectLocalMachine : 0);
            bool ok = protect
                ? CryptProtectData(ref inputBlob, IntPtr.Zero, ref entropyBlob, IntPtr.Zero, IntPtr.Zero, flags, out outputBlob)
                : CryptUnprotectData(ref inputBlob, IntPtr.Zero, ref entropyBlob, IntPtr.Zero, IntPtr.Zero, flags, out outputBlob);

            if (!ok)
            {
                int error = Marshal.GetLastPInvokeError();
                string call = protect ? "CryptProtectData" : "CryptUnprotectData";
                string reason = protect ? SecretError.DpapiProtectFailed : SecretError.DpapiUnprotectFailed;
                throw new SecretException(reason, call + " failed with Win32 error 0x" + error.ToString("X8", System.Globalization.CultureInfo.InvariantCulture) + ".");
            }

            if (outputBlob.cbData <= 0 || outputBlob.pbData == IntPtr.Zero)
            {
                throw new SecretException(SecretError.DpapiBlobEmpty, "DPAPI returned an empty blob.");
            }

            byte[] result = new byte[outputBlob.cbData];
            Marshal.Copy(outputBlob.pbData, result, 0, outputBlob.cbData);
            return result;
        }
        finally
        {
            if (outputBlob.pbData != IntPtr.Zero)
            {
                ZeroUnmanaged(outputBlob.pbData, outputBlob.cbData);
                LocalFree(outputBlob.pbData);
            }

            if (inputHandle.IsAllocated)
            {
                inputHandle.Free();
            }

            if (entropyHandle.IsAllocated)
            {
                entropyHandle.Free();
            }
        }
    }

    private static void ZeroUnmanaged(IntPtr pointer, int length)
    {
        if (length <= 0)
        {
            return;
        }

        byte[] zeros = new byte[length];
        Marshal.Copy(zeros, 0, pointer, length);
        CryptographicOperations.ZeroMemory(zeros);
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct DATA_BLOB
    {
        public int cbData;
        public IntPtr pbData;
    }

    [DllImport("crypt32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CryptProtectData(
        ref DATA_BLOB pDataIn,
        IntPtr szDataDescr,
        ref DATA_BLOB pOptionalEntropy,
        IntPtr pvReserved,
        IntPtr pPromptStruct,
        int dwFlags,
        out DATA_BLOB pDataOut);

    [DllImport("crypt32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CryptUnprotectData(
        ref DATA_BLOB pDataIn,
        IntPtr ppszDataDescr,
        ref DATA_BLOB pOptionalEntropy,
        IntPtr pvReserved,
        IntPtr pPromptStruct,
        int dwFlags,
        out DATA_BLOB pDataOut);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr LocalFree(IntPtr hMem);
}

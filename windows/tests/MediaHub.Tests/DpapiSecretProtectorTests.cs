using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Pins the real DPAPI protector against the real Windows API: a blob sealed here opens here and nowhere
/// else, and every failure comes back as a stable code that never mentions the plaintext.
/// </summary>
public sealed class DpapiSecretProtectorTests
{
    private static bool NotWindows => !OperatingSystem.IsWindows();

    [Fact]
    public void ProtectThenUnprotect_ReturnsTheSameBytes()
    {
        if (NotWindows) { return; }

        var protector = new WindowsDpapiProtector();
        byte[] plaintext = Encoding.UTF8.GetBytes("webdav-password-42");

        SecretEnvelope envelope = protector.Protect(plaintext);
        byte[] opened = protector.Unprotect(envelope);

        Assert.Equal(plaintext, opened);
        Assert.Equal(SecretEnvelope.SchemeDpapiUser, envelope.Scheme);
        Assert.Equal(Environment.UserName, envelope.Scope);
        Assert.True(envelope.ProtectedAtEpochMs > 0);
        Assert.True(envelope.IsSealed);
    }

    [Fact]
    public void Protect_ProducesABlobNobodyCanReadAsPlaintext()
    {
        if (NotWindows) { return; }

        byte[] plaintext = Encoding.UTF8.GetBytes("S3cr3t-Token-Value-42");
        SecretEnvelope envelope = new WindowsDpapiProtector().Protect(plaintext);

        Assert.True(envelope.Validate().ToPayloadBytes().Length >= SecretEnvelope.MinSealedBytes);
        Assert.DoesNotContain(Convert.ToBase64String(plaintext), envelope.PayloadBase64, StringComparison.Ordinal);
        Assert.DoesNotContain("S3cr3t-Token-Value-42", envelope.ToString(), StringComparison.Ordinal);
    }

    [Fact]
    public void MachineScope_IsAnExplicitChoiceAndStillRoundTrips()
    {
        if (NotWindows) { return; }

        var protector = new WindowsDpapiProtector(machineScope: true);
        byte[] plaintext = Encoding.UTF8.GetBytes("machine-wide-secret");

        SecretEnvelope envelope = protector.Protect(plaintext);

        Assert.Equal(SecretEnvelope.SchemeDpapiMachine, envelope.Scheme);
        Assert.Equal(Environment.MachineName, envelope.Scope);
        Assert.Equal(plaintext, protector.Unprotect(envelope));
    }

    [Fact]
    public void Protect_RefusesAnEmptyPayload()
    {
        if (NotWindows) { return; }

        SecretException error = Assert.Throws<SecretException>(() => new WindowsDpapiProtector().Protect([]));

        Assert.Equal(SecretError.DpapiBlobEmpty, error.Reason);
    }

    [Fact]
    public void Unprotect_RefusesAnEmptyScheme()
    {
        if (NotWindows) { return; }

        Assert.Equal(
            SecretError.EnvelopeNotSealed,
            Assert.Throws<SecretException>(() => new WindowsDpapiProtector().Unprotect(SecretEnvelope.Empty())).Reason);
    }

    [Fact]
    public void Unprotect_RefusesATamperedPayload()
    {
        if (NotWindows) { return; }

        var protector = new WindowsDpapiProtector();
        SecretEnvelope envelope = protector.Protect(Encoding.UTF8.GetBytes("tamper-me"));

        char[] payload = envelope.PayloadBase64.ToCharArray();
        int index = payload.Length / 2;
        payload[index] = payload[index] == 'A' ? 'B' : 'A';

        SecretEnvelope tampered = envelope with { PayloadBase64 = new string(payload) };
        Assert.Equal(
            SecretError.DpapiUnprotectFailed,
            Assert.Throws<SecretException>(() => protector.Unprotect(tampered)).Reason);
    }

    [Fact]
    public void Unprotect_RefusesABlobSealedWithADifferentEntropy()
    {
        if (NotWindows) { return; }

        byte[] plaintext = Encoding.UTF8.GetBytes("sealed-somewhere-else");
        string foreignPayload = ForeignDpapi.SealWithEntropy(plaintext, Encoding.UTF8.GetBytes("SomeOtherApp.v1"));
        var foreign = new SecretEnvelope
        {
            Scheme = SecretEnvelope.SchemeDpapiUser,
            Scope = "someone-else",
            PayloadBase64 = foreignPayload,
            ProtectedAtEpochMs = 1,
        };

        Assert.Equal(
            SecretError.DpapiUnprotectFailed,
            Assert.Throws<SecretException>(() => new WindowsDpapiProtector().Unprotect(foreign)).Reason);
    }

    [Fact]
    public void Unprotect_IsRepeatable()
    {
        if (NotWindows) { return; }

        var protector = new WindowsDpapiProtector();
        byte[] plaintext = Encoding.UTF8.GetBytes("repeatable");
        SecretEnvelope envelope = protector.Protect(plaintext);

        Assert.Equal(plaintext, protector.Unprotect(envelope));
        Assert.Equal(plaintext, protector.Unprotect(envelope));
    }

    [Fact]
    public void Protect_TwoCallsProduceDifferentPayloads()
    {
        if (NotWindows) { return; }

        var protector = new WindowsDpapiProtector();
        byte[] plaintext = Encoding.UTF8.GetBytes("same-input");

        Assert.NotEqual(protector.Protect(plaintext).PayloadBase64, protector.Protect(plaintext).PayloadBase64);
    }

    /// <summary>A second, deliberately foreign DPAPI caller: a blob it seals is not ours to open.</summary>
    private static class ForeignDpapi
    {
        public static string SealWithEntropy(byte[] plaintext, byte[] entropy)
        {
            GCHandle inputHandle = GCHandle.Alloc(plaintext, GCHandleType.Pinned);
            GCHandle entropyHandle = GCHandle.Alloc(entropy, GCHandleType.Pinned);
            try
            {
                var input = new DATA_BLOB { cbData = plaintext.Length, pbData = inputHandle.AddrOfPinnedObject() };
                var entropyBlob = new DATA_BLOB { cbData = entropy.Length, pbData = entropyHandle.AddrOfPinnedObject() };
                if (!CryptProtectData(
                        ref input,
                        IntPtr.Zero,
                        ref entropyBlob,
                        IntPtr.Zero,
                        IntPtr.Zero,
                        0x1,
                        out DATA_BLOB output))
                {
                    throw new InvalidOperationException("foreign CryptProtectData failed: " + Marshal.GetLastPInvokeError());
                }

                try
                {
                    byte[] blob = new byte[output.cbData];
                    Marshal.Copy(output.pbData, blob, 0, output.cbData);
                    return Convert.ToBase64String(blob);
                }
                finally
                {
                    LocalFree(output.pbData);
                }
            }
            finally
            {
                inputHandle.Free();
                entropyHandle.Free();
            }
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

        [DllImport("kernel32.dll")]
        private static extern IntPtr LocalFree(IntPtr hMem);
    }
}

using System.Security.Cryptography;
using System.Text;

namespace MediaHub.Backup;

/// <summary>
/// Android .mhbackup 容器的加密参数（与 core/common/src/main/kotlin/com/mediahub/core/common/backup/BackupCrypto.kt 对齐）。
/// 手机导出的备份必须能在这里解开，反之亦然；因此参数、AAD 拼法、密文布局都按 Android 侧逐字节照搬。
/// </summary>
public static class MhBackupCrypto
{
    public const int FormatVersion = 2;
    public const int MinimumReaderVersion = 2;
    public const string Magic = "MHBK";
    public const string KdfAlgorithm = "PBKDF2WithHmacSHA256";
    public const int Pbkdf2Iterations = 600_000;
    public const int Pbkdf2SaltBytes = 32;
    public const int Pbkdf2KeyBits = 256;
    public const int GcmNonceBytes = 12;
    public const int GcmTagBits = 128;
    public const int GcmTagBytes = GcmTagBits / 8;
    public const int MinIterations = 10_000;
    public const int MaxIterations = 2_000_000;

    /// <summary>算法白名单：只接受 Android 写出的那一个 KDF 名字。</summary>
    public static bool IsSupportedKdfAlgorithm(string? algorithm) =>
        string.Equals(algorithm, KdfAlgorithm, StringComparison.Ordinal);

    public static byte[] RandomSalt() => RandomNumberGenerator.GetBytes(Pbkdf2SaltBytes);

    public static byte[] RandomNonce() => RandomNumberGenerator.GetBytes(GcmNonceBytes);

    /// <summary>PBKDF2-HMAC-SHA256 派生 256 位密钥。口令按 UTF-8 取字节（与 Java PBEKeySpec 的约定一致）。</summary>
    public static byte[] DeriveKey(ReadOnlySpan<char> password, byte[] salt, int iterations, int keyLengthBits)
    {
        if (salt.Length < Pbkdf2SaltBytes) throw new ArgumentException("salt too short", nameof(salt));
        if (iterations < MinIterations || iterations > MaxIterations) throw new ArgumentOutOfRangeException(nameof(iterations));
        if (keyLengthBits != Pbkdf2KeyBits) throw new ArgumentException("only 256-bit keys are supported", nameof(keyLengthBits));

        byte[] passwordBytes = Encoding.UTF8.GetBytes(password.ToString());
        try
        {
            return Rfc2898DeriveBytes.Pbkdf2(passwordBytes, salt, iterations, HashAlgorithmName.SHA256, keyLengthBits / 8);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(passwordBytes);
        }
    }

    /// <summary>与 Android aadFor 完全相同的稳定编码：magic|版本|算法|迭代数（UTF-8）。</summary>
    public static byte[] AadFor(string magic, int formatVersion, string kdfAlgorithm, int kdfIterations) =>
        Encoding.UTF8.GetBytes(magic + "|" + formatVersion + "|" + kdfAlgorithm + "|" + kdfIterations);

    /// <summary>
    /// AES-256-GCM 加密，返回 ciphertext||tag（16 字节 tag 追加在末尾）。
    /// Java 的 Cipher.doFinal 就是这种布局，.NET 的 AesGcm 分开输出，必须在此缝合成同一形状。
    /// </summary>
    public static byte[] Encrypt(byte[] plaintext, byte[] key, byte[] nonce, byte[]? aad)
    {
        if (nonce.Length != GcmNonceBytes) throw new ArgumentException("nonce must be 12 bytes", nameof(nonce));

        byte[] ciphertext = new byte[plaintext.Length];
        byte[] tag = new byte[GcmTagBytes];
        using (AesGcm aes = new(key, GcmTagBytes))
        {
            aes.Encrypt(nonce, plaintext, ciphertext, tag, aad);
        }

        byte[] result = new byte[ciphertext.Length + tag.Length];
        Buffer.BlockCopy(ciphertext, 0, result, 0, ciphertext.Length);
        Buffer.BlockCopy(tag, 0, result, ciphertext.Length, tag.Length);
        return result;
    }

    /// <summary>解密 ciphertext||tag；tag 不匹配时抛 CryptographicException（等价 Java 的 AEADBadTagException）。</summary>
    public static byte[] Decrypt(byte[] ciphertextWithTag, byte[] key, byte[] nonce, byte[]? aad)
    {
        if (nonce.Length != GcmNonceBytes) throw new ArgumentException("nonce must be 12 bytes", nameof(nonce));
        if (ciphertextWithTag.Length < GcmTagBytes) throw new CryptographicException("ciphertext too short");

        int ciphertextLength = ciphertextWithTag.Length - GcmTagBytes;
        byte[] plaintext = new byte[ciphertextLength];
        using (AesGcm aes = new(key, GcmTagBytes))
        {
            aes.Decrypt(
                nonce,
                ciphertextWithTag.AsSpan(0, ciphertextLength),
                ciphertextWithTag.AsSpan(ciphertextLength, GcmTagBytes),
                plaintext,
                aad);
        }

        return plaintext;
    }
}

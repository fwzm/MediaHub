using System.Security.Cryptography;
using System.Text;

namespace MediaHub.Backup;

public enum MhBackupImportStatus
{
    /// <summary>容器合法、口令正确、内容通过全部校验。</summary>
    Ok,
    /// <summary>不是备份文件（文件中不存在 MHBK 签名）。</summary>
    NotABackupFile,
    /// <summary>容器结构损坏 / 内容越界 / 校验不通过。</summary>
    Corrupted,
    /// <summary>备份由更新的阅读版本写出，本应用读不懂。</summary>
    VersionTooNew,
    /// <summary>口令错误，或密文被篡改（GCM tag 不匹配）。</summary>
    AuthenticationFailed,
}

public sealed record MhBackupImportResult(
    MhBackupImportStatus Status,
    string? Reason,
    MhBackupPayload? Payload,
    int FormatVersion)
{
    public bool IsOk => Status == MhBackupImportStatus.Ok;

    public static MhBackupImportResult Ok(MhBackupPayload payload, int formatVersion) =>
        new(MhBackupImportStatus.Ok, null, payload, formatVersion);

    public static MhBackupImportResult Failed(MhBackupImportStatus status, string reason, int formatVersion) =>
        new(status, reason, null, formatVersion);
}

/// <summary>导出被拒绝（内容不合法，或序列化后超过体积上限）。</summary>
public sealed class MhBackupExportException : InvalidOperationException
{
    public MhBackupExportException(string reason) : base(reason) => Reason = reason;

    public string Reason { get; }
}

/// <summary>
/// Android .mhbackup 容器的 Windows 读写实现（照 core/common/src/main/kotlin/com/mediahub/core/common/backup/BackupSerializer.kt）。
/// 目标不是「另做一个备份格式」，而是**同一个字节容器**：手机导出的文件在电脑上能解、电脑导出的在手机上能解。
/// 因此不读的参数、校验顺序、上限、分类都按 Android 侧原样照搬；只有人类可读的原因文案按本机语言重写。
/// </summary>
public static class MhBackupSerializer
{
    public const int MaxFileBytes = 10 * 1024 * 1024;
    public const int MinCiphertextBytes = MhBackupCrypto.GcmTagBytes;
    public const int MaxServers = 1000;
    public const int MaxProgressRecords = 20_000;
    public const int MaxEndpointsPerServer = 64;
    public const int MaxFavorites = 5_000;
    public const int MaxFavoriteTitleChars = 512;

    public static readonly IReadOnlyCollection<string> KnownSections = new HashSet<string>(StringComparer.Ordinal)
    {
        "servers", "progress", "preferences", "favorites",
    };

    // ---------------------------------------------------------------- 导出

    /// <summary>按 Android 容器格式写出备份字节；内容不合法或超过 10 MiB 时抛 MhBackupExportException。</summary>
    public static byte[] Export(MhBackupPayload payload, ReadOnlySpan<char> password)
    {
        ArgumentNullException.ThrowIfNull(payload);

        if (!ValidatePayload(payload, out string? invalid, out _))
            throw new MhBackupExportException(invalid ?? "备份内容不合法");

        MhBackupManifest manifest = payload.Manifest
            ?? throw new MhBackupExportException("备份缺少 manifest");
        if (manifest.FormatVersion != MhBackupCrypto.FormatVersion)
            throw new MhBackupExportException("只能导出格式版本 " + MhBackupCrypto.FormatVersion + " 的备份，当前为 " + manifest.FormatVersion);

        string payloadJson = MhBackupPayloadCodec.Encode(payload);
        byte[] plaintext = Encoding.UTF8.GetBytes(payloadJson);
        byte[] salt = MhBackupCrypto.RandomSalt();
        byte[] nonce = MhBackupCrypto.RandomNonce();
        byte[] key = MhBackupCrypto.DeriveKey(password, salt, MhBackupCrypto.Pbkdf2Iterations, MhBackupCrypto.Pbkdf2KeyBits);

        try
        {
            byte[] aad = MhBackupCrypto.AadFor(
                MhBackupCrypto.Magic,
                MhBackupCrypto.FormatVersion,
                MhBackupCrypto.KdfAlgorithm,
                MhBackupCrypto.Pbkdf2Iterations);

            byte[] ciphertext = MhBackupCrypto.Encrypt(plaintext, key, nonce, aad);

            MhBackupEnvelope envelope = new(
                MhBackupCrypto.Magic,
                MhBackupCrypto.FormatVersion,
                MhBackupCrypto.KdfAlgorithm,
                Convert.ToBase64String(salt),
                MhBackupCrypto.Pbkdf2Iterations,
                MhBackupCrypto.Pbkdf2KeyBits,
                Convert.ToBase64String(nonce),
                Convert.ToBase64String(ciphertext));

            byte[] bytes = Encoding.UTF8.GetBytes(MhBackupJson.EncodeEnvelope(envelope));
            if (bytes.Length > MaxFileBytes)
                throw new MhBackupExportException("备份内容超过 " + MaxFileBytes + " 字节上限，请减少来源或进度记录");

            return bytes;
        }
        finally
        {
            CryptographicOperations.ZeroMemory(plaintext);
            CryptographicOperations.ZeroMemory(key);
        }
    }

    // ---------------------------------------------------------------- 导入

    public static MhBackupImportResult Import(string text, ReadOnlySpan<char> password) =>
        Import(Encoding.UTF8.GetBytes(text ?? string.Empty), password);

    /// <summary>读取 Android 写出的备份字节；任何失败都以分类 + 中文原因返回，绝不把异常抛给调用方。</summary>
    public static MhBackupImportResult Import(byte[] data, ReadOnlySpan<char> password)
    {
        ArgumentNullException.ThrowIfNull(data);

        if (data.Length > MaxFileBytes)
            return MhBackupImportResult.Failed(MhBackupImportStatus.Corrupted, "备份文件超过 " + MaxFileBytes + " 字节上限", 0);

        string text = Encoding.UTF8.GetString(data);
        if (!text.Contains(MhBackupCrypto.Magic, StringComparison.Ordinal))
            return MhBackupImportResult.Failed(MhBackupImportStatus.NotABackupFile, "这个文件不是 MediaHub 备份（找不到 MHBK 签名）", 0);

        MhBackupEnvelope envelope;
        try
        {
            envelope = MhBackupJson.DecodeEnvelope(text);
        }
        catch (Exception)
        {
            return MhBackupImportResult.Failed(MhBackupImportStatus.Corrupted, "备份文件格式无法解析", 0);
        }

        MhBackupImportResult? envelopeFailure = ValidateEnvelope(envelope, out byte[] salt, out byte[] nonce, out byte[] ciphertext);
        if (envelopeFailure is not null) return envelopeFailure;

        byte[] key;
        try
        {
            key = MhBackupCrypto.DeriveKey(password, salt, envelope.KdfIterations, envelope.KdfKeyLengthBits);
        }
        catch (Exception)
        {
            return MhBackupImportResult.Failed(MhBackupImportStatus.Corrupted, "密钥派生失败", envelope.FormatVersion);
        }

        byte[] plaintext;
        try
        {
            byte[] aad = MhBackupCrypto.AadFor(
                MhBackupCrypto.Magic,
                envelope.FormatVersion,
                envelope.KdfAlgorithm ?? MhBackupCrypto.KdfAlgorithm,
                envelope.KdfIterations);
            plaintext = MhBackupCrypto.Decrypt(ciphertext, key, nonce, aad);
        }
        catch (CryptographicException)
        {
            return MhBackupImportResult.Failed(MhBackupImportStatus.AuthenticationFailed, "无法解密备份：密码不正确，或文件已损坏、被修改。", envelope.FormatVersion);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(key);
        }

        MhBackupPayload payload;
        try
        {
            payload = MhBackupPayloadCodec.Decode(Encoding.UTF8.GetString(plaintext));
        }
        catch (Exception)
        {
            return MhBackupImportResult.Failed(MhBackupImportStatus.Corrupted, "备份内容解析失败", envelope.FormatVersion);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(plaintext);
        }

        if (!ValidatePayload(payload, out string? invalid, out bool versionTooNew))
        {
            return versionTooNew
                ? MhBackupImportResult.Failed(MhBackupImportStatus.VersionTooNew, invalid ?? "备份格式过新", envelope.FormatVersion)
                : MhBackupImportResult.Failed(MhBackupImportStatus.Corrupted, invalid ?? "备份内容不合法", envelope.FormatVersion);
        }

        if (payload.Manifest!.FormatVersion != envelope.FormatVersion)
            return MhBackupImportResult.Failed(
                MhBackupImportStatus.Corrupted,
                "备份外层格式版本（" + envelope.FormatVersion + "）与内容声明的版本（" + payload.Manifest.FormatVersion + "）不一致",
                envelope.FormatVersion);

        return MhBackupImportResult.Ok(payload, envelope.FormatVersion);
    }

    // ---------------------------------------------------------------- 外层校验

    private static MhBackupImportResult? ValidateEnvelope(
        MhBackupEnvelope envelope,
        out byte[] salt,
        out byte[] nonce,
        out byte[] ciphertext)
    {
        salt = Array.Empty<byte>();
        nonce = Array.Empty<byte>();
        ciphertext = Array.Empty<byte>();

        if (!string.Equals(envelope.Magic, MhBackupCrypto.Magic, StringComparison.Ordinal))
            return MhBackupImportResult.Failed(MhBackupImportStatus.NotABackupFile, "这个文件不是 MediaHub 备份（签名不是 MHBK）", 0);

        if (envelope.FormatVersion > MhBackupCrypto.FormatVersion)
            return MhBackupImportResult.Failed(
                MhBackupImportStatus.VersionTooNew,
                "备份格式版本为 " + envelope.FormatVersion + "，本应用最多支持 " + MhBackupCrypto.FormatVersion,
                envelope.FormatVersion);

        if (envelope.FormatVersion < 1)
            return MhBackupImportResult.Failed(MhBackupImportStatus.Corrupted, "备份格式版本非法：" + envelope.FormatVersion, envelope.FormatVersion);

        if (!MhBackupCrypto.IsSupportedKdfAlgorithm(envelope.KdfAlgorithm))
            return MhBackupImportResult.Failed(MhBackupImportStatus.Corrupted, "备份使用了不支持的密钥派生算法", envelope.FormatVersion);

        if (envelope.KdfIterations < MhBackupCrypto.MinIterations || envelope.KdfIterations > MhBackupCrypto.MaxIterations)
            return MhBackupImportResult.Failed(MhBackupImportStatus.Corrupted, "备份的密钥派生迭代数越界", envelope.FormatVersion);

        if (envelope.KdfKeyLengthBits != MhBackupCrypto.Pbkdf2KeyBits)
            return MhBackupImportResult.Failed(MhBackupImportStatus.Corrupted, "备份的密钥长度不是 256 位", envelope.FormatVersion);

        if (!TryFromBase64(envelope.KdfSaltB64, out salt))
            return MhBackupImportResult.Failed(MhBackupImportStatus.Corrupted, "备份的盐值无法解码", envelope.FormatVersion);
        if (salt.Length != MhBackupCrypto.Pbkdf2SaltBytes)
            return MhBackupImportResult.Failed(MhBackupImportStatus.Corrupted, "备份的盐值长度不是 32 字节", envelope.FormatVersion);

        if (!TryFromBase64(envelope.NonceB64, out nonce))
            return MhBackupImportResult.Failed(MhBackupImportStatus.Corrupted, "备份的 nonce 无法解码", envelope.FormatVersion);
        if (nonce.Length != MhBackupCrypto.GcmNonceBytes)
            return MhBackupImportResult.Failed(MhBackupImportStatus.Corrupted, "备份的 nonce 长度不是 12 字节", envelope.FormatVersion);

        if (!TryFromBase64(envelope.CiphertextB64, out ciphertext))
            return MhBackupImportResult.Failed(MhBackupImportStatus.Corrupted, "备份的密文无法解码", envelope.FormatVersion);
        if (ciphertext.Length < MinCiphertextBytes)
            return MhBackupImportResult.Failed(MhBackupImportStatus.Corrupted, "备份的密文长度不足", envelope.FormatVersion);

        return null;
    }

    private static bool TryFromBase64(string? value, out byte[] bytes)
    {
        bytes = Array.Empty<byte>();
        if (string.IsNullOrEmpty(value)) return false;

        try
        {
            bytes = Convert.FromBase64String(value);
            return true;
        }
        catch (FormatException)
        {
            return false;
        }
    }

    // ---------------------------------------------------------------- 内容校验

    /// <summary>返回 true 表示合法；不合法时 invalid 给出原因，versionTooNew 标记「版本过新」这一分类。</summary>
    private static bool ValidatePayload(MhBackupPayload payload, out string? invalid, out bool versionTooNew)
    {
        invalid = null;
        versionTooNew = false;

        MhBackupManifest? manifest = payload.Manifest;
        if (manifest is null)
        {
            invalid = "备份缺少 manifest";
            return false;
        }

        if (manifest.FormatVersion < 1)
        {
            invalid = "备份 manifest 的格式版本非法：" + manifest.FormatVersion;
            return false;
        }

        if (manifest.FormatVersion > MhBackupCrypto.FormatVersion)
        {
            versionTooNew = true;
            invalid = "备份格式版本为 " + manifest.FormatVersion + "，本应用最多支持 " + MhBackupCrypto.FormatVersion;
            return false;
        }

        if (manifest.MinimumReaderVersion < 1)
        {
            invalid = "备份 manifest 的最低阅读版本非法：" + manifest.MinimumReaderVersion;
            return false;
        }

        if (manifest.MinimumReaderVersion > MhBackupCrypto.FormatVersion)
        {
            versionTooNew = true;
            invalid = "备份要求阅读版本 " + manifest.MinimumReaderVersion + "，本应用最多支持 " + MhBackupCrypto.FormatVersion;
            return false;
        }

        if (manifest.MinimumReaderVersion > manifest.FormatVersion)
        {
            invalid = "备份的最低阅读版本高于其格式版本";
            return false;
        }

        if (manifest.CreatedAtEpochMs < 0)
        {
            invalid = "备份的创建时间非法";
            return false;
        }

        if (manifest.IncludedSections is null)
        {
            invalid = "备份缺少 includedSections";
            return false;
        }

        if (manifest.RecordCounts is null)
        {
            invalid = "备份缺少 recordCounts";
            return false;
        }

        HashSet<string> sections = new(StringComparer.Ordinal);
        foreach (string section in manifest.IncludedSections)
        {
            if (!KnownSections.Contains(section))
            {
                invalid = "备份声明了未知分区：" + section;
                return false;
            }

            if (!sections.Add(section))
            {
                invalid = "备份的 includedSections 含重复分区：" + section;
                return false;
            }
        }

        foreach ((string section, int declared) in manifest.RecordCounts)
        {
            if (!KnownSections.Contains(section))
            {
                invalid = "备份的 recordCounts 含未知分区：" + section;
                return false;
            }

            if (declared < 0)
            {
                invalid = "备份的 recordCounts 含负数：" + section;
                return false;
            }
        }

        if (!sections.SetEquals(manifest.RecordCounts.Keys))
        {
            invalid = "备份的 recordCounts 与 includedSections 不一致";
            return false;
        }

        int actualServers = payload.Servers.Count;
        int actualProgress = payload.Progress.Count;
        int actualFavorites = payload.Favorites.Count;
        int actualPreferences = payload.Preferences is null ? 0 : 1;

        if (actualServers > MaxServers)
        {
            invalid = "备份来源数量超过上限（" + MaxServers + "）";
            return false;
        }

        if (actualProgress > MaxProgressRecords)
        {
            invalid = "备份进度数量超过上限（" + MaxProgressRecords + "）";
            return false;
        }

        if (actualFavorites > MaxFavorites)
        {
            invalid = "备份收藏数量超过上限（" + MaxFavorites + "）";
            return false;
        }

        if (!CountMatches(manifest, "servers", actualServers, out invalid) ||
            !CountMatches(manifest, "progress", actualProgress, out invalid) ||
            !CountMatches(manifest, "favorites", actualFavorites, out invalid) ||
            !CountMatches(manifest, "preferences", actualPreferences, out invalid))
        {
            return false;
        }

        return ValidateServers(payload, out invalid) &&
               ValidateProgress(payload, out invalid) &&
               ValidateFavorites(payload, out invalid) &&
               ValidatePreferences(payload, out invalid);
    }

    private static bool CountMatches(MhBackupManifest manifest, string section, int actual, out string? invalid)
    {
        invalid = null;
        if (!manifest.RecordCounts.TryGetValue(section, out int declared)) return true;
        if (declared == actual) return true;

        invalid = "备份分区 " + section + " 声明的数量（" + declared + "）与实际（" + actual + "）不符";
        return false;
    }

    private static bool ValidateServers(MhBackupPayload payload, out string? invalid)
    {
        invalid = null;
        HashSet<string> seenIds = new(StringComparer.Ordinal);

        foreach (MhBackupServerDto server in payload.Servers)
        {
            if (string.IsNullOrWhiteSpace(server.BackupId))
            {
                invalid = "备份里有来源缺少身份";
                return false;
            }

            if (!seenIds.Add(server.BackupId))
            {
                invalid = "备份里来源身份重复：" + server.BackupId;
                return false;
            }

            if (string.IsNullOrWhiteSpace(server.Name))
            {
                invalid = "备份里来源缺少名称：" + server.BackupId;
                return false;
            }

            if (string.IsNullOrWhiteSpace(server.Type))
            {
                invalid = "备份里来源缺少类型：" + server.BackupId;
                return false;
            }

            if (server.SortOrder < 0)
            {
                invalid = "备份里来源的排序为负：" + server.BackupId;
                return false;
            }

            List<MhBackupEndpointDto> endpoints = server.Endpoints;
            if (!string.Equals(server.Type, "LOCAL", StringComparison.Ordinal) && endpoints.Count == 0)
            {
                invalid = "备份里来源 " + server.Name + " 不是本地库却没有端点";
                return false;
            }

            if (endpoints.Count > MaxEndpointsPerServer)
            {
                invalid = "备份里来源 " + server.Name + " 的端点数量超过上限（" + MaxEndpointsPerServer + "）";
                return false;
            }

            foreach (MhBackupEndpointDto endpoint in endpoints)
            {
                if (endpoint.SortOrder < 0)
                {
                    invalid = "备份里来源 " + server.Name + " 的端点排序为负";
                    return false;
                }

                if (string.IsNullOrWhiteSpace(endpoint.Url))
                {
                    invalid = "备份里来源 " + server.Name + " 的端点缺少地址";
                    return false;
                }

                MhBackupUrlViolation? violation = MhBackupUrlGuard.Inspect(endpoint.Url);
                if (violation is not null)
                {
                    invalid = violation switch
                    {
                        MhBackupUrlViolation.UserInfo => "备份里来源 " + server.Name + " 的端点地址内嵌了凭据",
                        MhBackupUrlViolation.SensitiveQuery q => "备份里来源 " + server.Name + " 的端点地址带凭据参数：" + q.Key,
                        _ => "备份里来源 " + server.Name + " 的端点地址格式非法",
                    };
                    return false;
                }
            }

            if (!MhBackupUrlGuard.HasUnambiguousEndpointIdentity(server))
            {
                invalid = "备份里来源 " + server.Name + " 的端点身份不唯一";
                return false;
            }
        }

        return true;
    }

    private static bool ValidateProgress(MhBackupPayload payload, out string? invalid)
    {
        invalid = null;
        HashSet<string> seen = new(StringComparer.Ordinal);

        foreach (MhBackupProgressDto progress in payload.Progress)
        {
            if (string.IsNullOrWhiteSpace(progress.ServerBackupId))
            {
                invalid = "备份里有进度缺少来源身份";
                return false;
            }

            if (string.IsNullOrWhiteSpace(progress.ItemId))
            {
                invalid = "备份里有进度缺少条目 id";
                return false;
            }

            if (!seen.Add(progress.ServerBackupId + "/" + progress.ItemId))
            {
                invalid = "备份里有重复进度：" + progress.ServerBackupId + "/" + progress.ItemId;
                return false;
            }

            if (progress.PositionMs < 0)
            {
                invalid = "备份里有进度的位置为负";
                return false;
            }

            if (progress.DurationMs < 0)
            {
                invalid = "备份里有进度的时长为负";
                return false;
            }

            if (progress.UpdatedAtEpochMs < 0)
            {
                invalid = "备份里有进度的时间戳为负";
                return false;
            }
        }

        return true;
    }

    private static bool ValidateFavorites(MhBackupPayload payload, out string? invalid)
    {
        invalid = null;
        HashSet<string> seen = new(StringComparer.Ordinal);

        foreach (MhBackupFavoriteDto favorite in payload.Favorites)
        {
            if (string.IsNullOrWhiteSpace(favorite.ServerBackupId))
            {
                invalid = "备份里有收藏缺少来源身份";
                return false;
            }

            if (string.IsNullOrWhiteSpace(favorite.ItemId))
            {
                invalid = "备份里有收藏缺少条目 id";
                return false;
            }

            if (!seen.Add(favorite.ServerBackupId + "/" + favorite.ItemId))
            {
                invalid = "备份里有重复收藏：" + favorite.ServerBackupId + "/" + favorite.ItemId;
                return false;
            }

            if (favorite.AddedAtEpochMs < 0)
            {
                invalid = "备份里有收藏的时间戳为负";
                return false;
            }

            if (favorite.Title is not null && favorite.Title.Length > MaxFavoriteTitleChars)
            {
                invalid = "备份里有收藏标题超过 " + MaxFavoriteTitleChars + " 字符";
                return false;
            }
        }

        return true;
    }

    private static bool ValidatePreferences(MhBackupPayload payload, out string? invalid)
    {
        invalid = null;
        MhBackupPreferencesDto? preferences = payload.Preferences;
        if (preferences is null) return true;

        if (float.IsNaN(preferences.DefaultPlaybackSpeed) || float.IsInfinity(preferences.DefaultPlaybackSpeed) || preferences.DefaultPlaybackSpeed <= 0)
        {
            invalid = "备份偏好里的默认播放速度非法";
            return false;
        }

        if (preferences.SubtitleSizeSp <= 0)
        {
            invalid = "备份偏好里的字幕字号非法";
            return false;
        }

        if (preferences.MaxBitrateBps is <= 0)
        {
            invalid = "备份偏好里的码率上限非法";
            return false;
        }

        MhBackupSubtitleStyleDto style = preferences.SubtitleStyle;
        if (style.EdgeType is < 0 or > 2)
        {
            invalid = "备份偏好里的字幕描边类型越界";
            return false;
        }

        if (!IsFinite(style.TextScale) || style.TextScale < 0.6f || style.TextScale > 2.0f)
        {
            invalid = "备份偏好里的字幕文字缩放越界";
            return false;
        }

        if (!IsFinite(style.BottomPaddingFraction) || style.BottomPaddingFraction < 0f || style.BottomPaddingFraction > 0.4f)
        {
            invalid = "备份偏好里的字幕底边距越界";
            return false;
        }

        MhBackupPlayerGesturesDto gestures = preferences.Gestures;
        if (gestures.DoubleTapSeekBackwardSeconds is < 5 or > 60)
        {
            invalid = "备份偏好里的双击快退秒数越界";
            return false;
        }

        if (gestures.DoubleTapSeekForwardSeconds is < 5 or > 60)
        {
            invalid = "备份偏好里的双击快进秒数越界";
            return false;
        }

        if (!IsFinite(gestures.LongPressSpeedMin) || gestures.LongPressSpeedMin < 0.1f || gestures.LongPressSpeedMin > 0.5f)
        {
            invalid = "备份偏好里的长按速度下界越界";
            return false;
        }

        if (!IsFinite(gestures.LongPressSpeedMax) || gestures.LongPressSpeedMax < 2f || gestures.LongPressSpeedMax > 8f)
        {
            invalid = "备份偏好里的长按速度上界越界";
            return false;
        }

        if (!IsFinite(gestures.LongPressDefaultSpeed) || gestures.LongPressDefaultSpeed < 1f || gestures.LongPressDefaultSpeed > 4f)
        {
            invalid = "备份偏好里的长按默认速度越界";
            return false;
        }

        return true;
    }

    private static bool IsFinite(float value) => !float.IsNaN(value) && !float.IsInfinity(value);
}

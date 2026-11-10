using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using MediaHub.Backup;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// 跨平台备份容器的契约（S3）：手机写出的 .mhbackup 必须能在 Windows 上解开，反之亦然。
/// 这里的用例钉住三件事：容器形状/分类、凭据绝不落盘、以及威胁模型里的每一类坏文件都有确定分类。
/// 与 Android 的互操作由同一份黄金向量（backup-vectors.json）在两端共用来证明，不能只靠本端自洽。
/// </summary>
public sealed class BackupContainerTests
{
    private const string Password = "correct horse battery staple";

    // ------------------------------------------------------------ 样本构造

    private static MhBackupPayload SamplePayload() => new()
    {
        Manifest = new MhBackupManifest(
            FormatVersion: 2,
            MinimumReaderVersion: 2,
            AppVersion: "1.0.0-test",
            CreatedAtEpochMs: 1_790_000_000_000,
            IncludedSections: new List<string> { "favorites", "preferences", "progress", "servers" },
            RecordCounts: new Dictionary<string, int> { ["servers"] = 2, ["progress"] = 1, ["preferences"] = 1, ["favorites"] = 1 }),
        Servers = new List<MhBackupServerDto>
        {
            new()
            {
                BackupId = "srv-local", Name = "本地文件", Type = "LOCAL", IsDefault = false, SortOrder = 0,
            },
            new()
            {
                BackupId = "srv-emby", Name = "客厅 Emby", Type = "EMBY", Username = "mh", IsDefault = true, SortOrder = 1,
                Endpoints = new List<MhBackupEndpointDto>
                {
                    new() { Name = "主线路", Url = "https://emby.example.com:8443/base", IsPrimary = true, Enabled = true, SortOrder = 0 },
                    new() { Name = "备用", Url = "http://192.168.1.20:8096", IsPrimary = false, Enabled = true, SortOrder = 1 },
                },
            },
        },
        Progress = new List<MhBackupProgressDto>
        {
            new()
            {
                ServerBackupId = "srv-emby", ItemId = "item-1", PositionMs = 120_000, DurationMs = 600_000,
                IsPaused = false, UpdatedAtEpochMs = 1_790_000_001_000, ItemTitle = "Long Resume", ItemType = "Movie",
            },
        },
        Favorites = new List<MhBackupFavoriteDto>
        {
            new() { ServerBackupId = "srv-emby", ItemId = "item-1", Title = "Long Resume", AddedAtEpochMs = 1_790_000_002_000 },
        },
        Preferences = new MhBackupPreferencesDto
        {
            DefaultPlaybackSpeed = 1.25f,
            SubtitleStyle = new MhBackupSubtitleStyleDto { TextScale = 1.2f },
            Gestures = new MhBackupPlayerGesturesDto { DoubleTapSeekForwardSeconds = 30 },
        },
    };

    /// <summary>测试专用：跳过导出校验直接加密一份内容，用来覆盖导入侧的校验分支。</summary>
    private static byte[] EnvelopeFor(MhBackupPayload payload, string password = Password, int formatVersion = 2)
    {
        byte[] salt = MhBackupCrypto.RandomSalt();
        byte[] nonce = MhBackupCrypto.RandomNonce();
        byte[] key = MhBackupCrypto.DeriveKey(password, salt, MhBackupCrypto.Pbkdf2Iterations, MhBackupCrypto.Pbkdf2KeyBits);
        byte[] plaintext = Encoding.UTF8.GetBytes(MhBackupPayloadCodec.Encode(payload));
        byte[] aad = MhBackupCrypto.AadFor(MhBackupCrypto.Magic, formatVersion, MhBackupCrypto.KdfAlgorithm, MhBackupCrypto.Pbkdf2Iterations);
        byte[] ciphertext = MhBackupCrypto.Encrypt(plaintext, key, nonce, aad);

        MhBackupEnvelope envelope = new(
            MhBackupCrypto.Magic, formatVersion, MhBackupCrypto.KdfAlgorithm,
            Convert.ToBase64String(salt), MhBackupCrypto.Pbkdf2Iterations, MhBackupCrypto.Pbkdf2KeyBits,
            Convert.ToBase64String(nonce), Convert.ToBase64String(ciphertext));
        return Encoding.UTF8.GetBytes(MhBackupJson.EncodeEnvelope(envelope));
    }

    private static MhBackupPayload WithManifest(MhBackupPayload payload, MhBackupManifest manifest) => new()
    {
        Manifest = manifest,
        Servers = payload.Servers,
        Progress = payload.Progress,
        Favorites = payload.Favorites,
        Preferences = payload.Preferences,
    };

    private static MhBackupPayload WithEndpoints(MhBackupPayload payload, List<MhBackupEndpointDto> endpoints) => new()
    {
        Manifest = payload.Manifest,
        Progress = payload.Progress,
        Favorites = payload.Favorites,
        Preferences = payload.Preferences,
        Servers = payload.Servers
            .Select((server, index) => index == 1
                ? new MhBackupServerDto
                {
                    BackupId = server.BackupId, Name = server.Name, Type = server.Type, Username = server.Username,
                    Note = server.Note, IsDefault = server.IsDefault, SortOrder = server.SortOrder, Endpoints = endpoints,
                }
                : server)
            .ToList(),
    };
    private static MhBackupImportResult Import(byte[] bytes, string password = Password) =>
        MhBackupSerializer.Import(bytes, password.AsSpan());

    // ------------------------------------------------------------ 往返与容器形状

    [Fact]
    public void A_backup_exported_here_reads_back_with_every_whitelisted_section_intact()
    {
        byte[] bytes = MhBackupSerializer.Export(SamplePayload(), Password.AsSpan());
        string text = Encoding.UTF8.GetString(bytes);

        // 外层必须是 Android 认得的那套字段名与参数。
        JsonNode envelope = JsonNode.Parse(text)!.AsObject();
        Assert.Equal("MHBK", envelope["magic"]!.GetValue<string>());
        Assert.Equal(2, envelope["formatVersion"]!.GetValue<int>());
        Assert.Equal("PBKDF2WithHmacSHA256", envelope["kdfAlgorithm"]!.GetValue<string>());
        Assert.Equal(600_000, envelope["kdfIterations"]!.GetValue<int>());
        Assert.Equal(256, envelope["kdfKeyLengthBits"]!.GetValue<int>());
        Assert.Equal(32, Convert.FromBase64String(envelope["kdfSaltB64"]!.GetValue<string>()).Length);
        Assert.Equal(12, Convert.FromBase64String(envelope["nonceB64"]!.GetValue<string>()).Length);

        MhBackupImportResult result = Import(bytes);
        Assert.True(result.IsOk, result.Reason);
        Assert.Equal(MhBackupImportStatus.Ok, result.Status);
        Assert.Equal(2, result.FormatVersion);

        MhBackupPayload payload = result.Payload!;
        Assert.Equal("1.0.0-test", payload.Manifest!.AppVersion);
        Assert.Equal(2, payload.Servers.Count);
        MhBackupServerDto emby = payload.Servers[1];
        Assert.Equal("srv-emby", emby.BackupId);
        Assert.Equal("客厅 Emby", emby.Name);
        Assert.Equal("EMBY", emby.Type);
        Assert.Equal("mh", emby.Username);
        Assert.True(emby.IsDefault);
        Assert.Equal(2, emby.Endpoints.Count);
        Assert.Equal("https://emby.example.com:8443/base", emby.Endpoints[0].Url);
        Assert.True(emby.Endpoints[0].IsPrimary);
        Assert.Equal("srv-local", payload.Servers[0].Type == "LOCAL" ? payload.Servers[0].BackupId : "");

        MhBackupProgressDto progress = Assert.Single(payload.Progress);
        Assert.Equal("srv-emby", progress.ServerBackupId);
        Assert.Equal(120_000, progress.PositionMs);
        Assert.Equal("Long Resume", progress.ItemTitle);

        MhBackupFavoriteDto favorite = Assert.Single(payload.Favorites);
        Assert.Equal("item-1", favorite.ItemId);
        Assert.Equal(1_790_000_002_000, favorite.AddedAtEpochMs);

        Assert.NotNull(payload.Preferences);
        Assert.Equal(1.25f, payload.Preferences!.DefaultPlaybackSpeed);
        Assert.Equal(1.2f, payload.Preferences.SubtitleStyle.TextScale);
        Assert.Equal(30, payload.Preferences.Gestures.DoubleTapSeekForwardSeconds);
    }

    [Fact]
    public void Plaintext_never_appears_in_the_exported_bytes()
    {
        byte[] bytes = MhBackupSerializer.Export(SamplePayload(), Password.AsSpan());
        string text = Encoding.UTF8.GetString(bytes);

        // 明文是密文里的内容，而不是文件里的内容：名字、标题、口令都不该以可读形式出现。
        Assert.DoesNotContain("客厅 Emby", text);
        Assert.DoesNotContain("Long Resume", text);
        Assert.DoesNotContain(Password, text);
        Assert.DoesNotContain("emby.example.com", text);
    }

    // ------------------------------------------------------------ 口令与 AAD

    [Fact]
    public void A_wrong_password_is_reported_as_an_authentication_failure_not_as_a_corrupt_file()
    {
        byte[] bytes = MhBackupSerializer.Export(SamplePayload(), Password.AsSpan());

        MhBackupImportResult result = Import(bytes, "not the password");

        Assert.Equal(MhBackupImportStatus.AuthenticationFailed, result.Status);
        Assert.Contains("密码不正确", result.Reason);
        Assert.Null(result.Payload);
    }

    [Fact]
    public void The_aad_binds_the_envelope_version_so_downgrading_the_header_breaks_authentication()
    {
        byte[] bytes = MhBackupSerializer.Export(SamplePayload(), Password.AsSpan());
        JsonObject envelope = JsonNode.Parse(Encoding.UTF8.GetString(bytes))!.AsObject();
        envelope["formatVersion"] = 1;

        MhBackupImportResult result = Import(Encoding.UTF8.GetBytes(envelope.ToJsonString()));

        // 版本进了 AAD：改头部即改 AAD，GCM tag 必须拒绝。
        Assert.Equal(MhBackupImportStatus.AuthenticationFailed, result.Status);
    }

    [Fact]
    public void A_container_from_a_newer_app_is_rejected_as_version_too_new()
    {
        byte[] bytes = MhBackupSerializer.Export(SamplePayload(), Password.AsSpan());
        JsonObject envelope = JsonNode.Parse(Encoding.UTF8.GetString(bytes))!.AsObject();
        envelope["formatVersion"] = 3;

        MhBackupImportResult result = Import(Encoding.UTF8.GetBytes(envelope.ToJsonString()));

        Assert.Equal(MhBackupImportStatus.VersionTooNew, result.Status);
        Assert.Contains("最多支持 2", result.Reason);
    }

    // ------------------------------------------------------------ 坏文件分类

    [Fact]
    public void A_file_that_is_not_a_backup_is_named_as_such_instead_of_called_corrupt()
    {
        MhBackupImportResult result = Import(Encoding.UTF8.GetBytes("{\"hello\":\"world\"}"));

        Assert.Equal(MhBackupImportStatus.NotABackupFile, result.Status);
        Assert.Contains("MHBK", result.Reason);
    }

    [Fact]
    public void A_salt_of_the_wrong_length_is_corrupt_with_a_specific_reason()
    {
        byte[] bytes = MhBackupSerializer.Export(SamplePayload(), Password.AsSpan());
        JsonObject envelope = JsonNode.Parse(Encoding.UTF8.GetString(bytes))!.AsObject();
        envelope["kdfSaltB64"] = Convert.ToBase64String(new byte[16]);

        MhBackupImportResult result = Import(Encoding.UTF8.GetBytes(envelope.ToJsonString()));

        Assert.Equal(MhBackupImportStatus.Corrupted, result.Status);
        Assert.Contains("盐值长度", result.Reason);
    }

    [Fact]
    public void An_unsupported_kdf_algorithm_is_corrupt_and_never_attempted()
    {
        byte[] bytes = MhBackupSerializer.Export(SamplePayload(), Password.AsSpan());
        JsonObject envelope = JsonNode.Parse(Encoding.UTF8.GetString(bytes))!.AsObject();
        envelope["kdfAlgorithm"] = "PBKDF2WithHmacSHA1";

        MhBackupImportResult result = Import(Encoding.UTF8.GetBytes(envelope.ToJsonString()));

        Assert.Equal(MhBackupImportStatus.Corrupted, result.Status);
        Assert.Contains("密钥派生算法", result.Reason);
    }

    [Fact]
    public void A_file_larger_than_the_size_cap_is_refused_before_any_parsing()
    {
        byte[] oversized = new byte[MhBackupSerializer.MaxFileBytes + 1];
        oversized[0] = (byte)'M';

        MhBackupImportResult result = Import(oversized);

        Assert.Equal(MhBackupImportStatus.Corrupted, result.Status);
        Assert.Contains("上限", result.Reason);
    }

    // ------------------------------------------------------------ 导出拒绝：凭据与歧义

    [Fact]
    public void An_endpoint_carrying_a_credential_query_parameter_is_refused_at_export()
    {
        MhBackupPayload basePayload = SamplePayload();
        MhBackupPayload payload = WithEndpoints(basePayload, new List<MhBackupEndpointDto>
        {
            new() { Name = "主线路", Url = "https://emby.example.com/base?api_key=secret-value", IsPrimary = true, Enabled = true, SortOrder = 0 },
            basePayload.Servers[1].Endpoints[1],
        });

        MhBackupExportException error = Assert.Throws<MhBackupExportException>(() =>
            MhBackupSerializer.Export(payload, Password.AsSpan()));
        Assert.Contains("api_key", error.Reason);
    }

    [Fact]
    public void An_endpoint_with_embedded_userinfo_is_refused_at_export()
    {
        MhBackupPayload basePayload = SamplePayload();
        MhBackupPayload payload = WithEndpoints(basePayload, new List<MhBackupEndpointDto>
        {
            new() { Name = "主线路", Url = "https://user:pass@emby.example.com/base", IsPrimary = true, Enabled = true, SortOrder = 0 },
            basePayload.Servers[1].Endpoints[1],
        });

        MhBackupExportException error = Assert.Throws<MhBackupExportException>(() =>
            MhBackupSerializer.Export(payload, Password.AsSpan()));
        Assert.Contains("凭据", error.Reason);
    }

    [Fact]
    public void Two_primaries_with_different_addresses_at_the_same_rank_are_refused_as_ambiguous()
    {
        MhBackupPayload payload = WithEndpoints(SamplePayload(), new List<MhBackupEndpointDto>
        {
            new() { Name = "A", Url = "https://a.example.com", IsPrimary = true, Enabled = true, SortOrder = 0 },
            new() { Name = "B", Url = "https://b.example.com", IsPrimary = true, Enabled = true, SortOrder = 0 },
        });

        MhBackupExportException error = Assert.Throws<MhBackupExportException>(() =>
            MhBackupSerializer.Export(payload, Password.AsSpan()));
        Assert.Contains("身份不唯一", error.Reason);
    }

    [Fact]
    public void A_declared_record_count_that_does_not_match_the_payload_is_refused_at_export()
    {
        MhBackupPayload basePayload = SamplePayload();
        MhBackupPayload payload = WithManifest(basePayload, basePayload.Manifest! with { RecordCounts = new Dictionary<string, int>
        {
            ["servers"] = 9, ["progress"] = 1, ["preferences"] = 1, ["favorites"] = 1,
        } });

        MhBackupExportException error = Assert.Throws<MhBackupExportException>(() =>
            MhBackupSerializer.Export(payload, Password.AsSpan()));
        Assert.Contains("servers", error.Reason);
    }

    // ------------------------------------------------------------ 导入侧校验（不经导出守卫）

    [Fact]
    public void An_imported_payload_declaring_an_unknown_section_is_corrupt()
    {
        MhBackupPayload basePayload = SamplePayload();
        MhBackupPayload payload = WithManifest(basePayload, basePayload.Manifest! with
        {
            IncludedSections = new List<string> { "servers", "progress", "preferences", "favorites", "screenshots" },
            RecordCounts = new Dictionary<string, int>
            {
                ["servers"] = 2, ["progress"] = 1, ["preferences"] = 1, ["favorites"] = 1, ["screenshots"] = 0,
            },
        });

        MhBackupImportResult result = Import(EnvelopeFor(payload));

        Assert.Equal(MhBackupImportStatus.Corrupted, result.Status);
        Assert.Contains("screenshots", result.Reason);
    }

    [Fact]
    public void An_imported_payload_whose_counts_lie_is_corrupt()
    {
        MhBackupPayload basePayload = SamplePayload();
        MhBackupPayload payload = WithManifest(basePayload, basePayload.Manifest! with { RecordCounts = new Dictionary<string, int>
        {
            ["servers"] = 1, ["progress"] = 1, ["preferences"] = 1, ["favorites"] = 1,
        } });

        MhBackupImportResult result = Import(EnvelopeFor(payload));

        Assert.Equal(MhBackupImportStatus.Corrupted, result.Status);
        Assert.Contains("servers", result.Reason);
    }

    [Fact]
    public void An_imported_payload_from_a_newer_format_version_keeps_the_version_too_new_classification()
    {
        MhBackupPayload basePayload = SamplePayload();
        MhBackupPayload payload = WithManifest(basePayload, basePayload.Manifest! with { FormatVersion = 3, MinimumReaderVersion = 3 });

        MhBackupImportResult result = Import(EnvelopeFor(payload, formatVersion: 2));

        Assert.Equal(MhBackupImportStatus.VersionTooNew, result.Status);
    }

    // ------------------------------------------------------------ 地址守卫与身份

    [Theory]
    [InlineData("https://user:pass@host/base?x=1", "userinfo")]
    [InlineData("https://host/base?token=abc", "token")]
    [InlineData("https://host/base?%61pi_key=abc", "api_key")]
    [InlineData("https://host/base?X-Emby-Token=abc", "x-emby-token")]
    [InlineData("ftp://host/base", "(URL 格式)")]
    [InlineData("   ", "(URL 格式)")]
    public void The_url_guard_rejects_credentials_and_malformed_addresses(string url, string expected)
    {
        MhBackupUrlViolation? violation = MhBackupUrlGuard.Inspect(url);

        Assert.NotNull(violation);

        if (expected == "userinfo")
        {
            Assert.IsType<MhBackupUrlViolation.UserInfo>(violation);
            return;
        }

        MhBackupUrlViolation.SensitiveQuery sensitive = Assert.IsType<MhBackupUrlViolation.SensitiveQuery>(violation);
        Assert.Equal(expected, sensitive.Key);
    }
    [Fact]
    public void Identity_normalization_keeps_an_explicit_default_port_and_drops_fragment()
    {
        Assert.Equal("http://host:80/base?x=1", MhBackupUrlGuard.NormalizeForIdentity(" HTTP://Host:80/base/?x=1#frag "));
        Assert.Equal("https://host//base", MhBackupUrlGuard.NormalizeForIdentity("https://host//base//"));
        Assert.Equal("https://host:8443/a/b", MhBackupUrlGuard.NormalizeForIdentity("https://user:pass@HOST:8443/a/b#x"));
        Assert.Equal("not a url", MhBackupUrlGuard.NormalizeForIdentity("  not a url  "));
    }
}

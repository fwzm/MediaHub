using System.Text;
using System.Text.Json;
using MediaHub.Backup;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Cross-language contract for the .mhbackup container (W15).
/// </summary>
/// <remarks>
/// <para>
/// 手机导出的备份必须能在电脑上解开，电脑导出的必须能在手机上解开。因此本套件不写死任何密文：
/// 它读 <c>assets/backup-vectors.json</c>，同一份文件也被 Android 的
/// <c>com.mediahub.core.common.backup.BackupWindowsContractTest</c> 读走。任一端改了参数、AAD 拼法或
/// 密文布局，两套测试里必有一套变红，而不是各自自说自话地绿。
/// </para>
/// <para>
/// 文件由仓库外生成器 <c>D:/mh-verify/backup-vectors-gen</c> 用 Windows 权威实现产出；两份副本
/// （<c>core/common/src/test/resources/backup/backup-vectors.json</c> 与 <c>windows/tests/assets/backup-vectors.json</c>）
/// 必须逐字节相同。反向证据 <c>androidExport</c> 由 Kotlin 侧写入后合入同一文件。
/// </para>
/// </remarks>
public sealed class BackupCrossPlatformVectorTests
{
    private static readonly JsonDocument Vectors = JsonDocument.Parse(
        File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "assets", "backup-vectors.json")));

    private static JsonElement Root => Vectors.RootElement;

    // ------------------------------------------------------------- element helpers

    private static string S(JsonElement element, string name) => element.GetProperty(name).GetString()!;
    private static int I(JsonElement element, string name) => element.GetProperty(name).GetInt32();
    private static long L(JsonElement element, string name) => element.GetProperty(name).GetInt64();
    private static bool B(JsonElement element, string name) => element.GetProperty(name).GetBoolean();
    private static double D(JsonElement element, string name) => element.GetProperty(name).GetDouble();

    private static string StatusName(MhBackupImportStatus status) => status switch
    {
        MhBackupImportStatus.Ok => "ok",
        MhBackupImportStatus.NotABackupFile => "notABackup",
        MhBackupImportStatus.Corrupted => "corrupted",
        MhBackupImportStatus.VersionTooNew => "versionTooNew",
        _ => "authenticationFailed",
    };

    private static JsonElement Case(string id) =>
        Root.GetProperty("cases").EnumerateArray().First(c => S(c, "id") == id);

    // ------------------------------------------------------------- crypto

    [Fact]
    public void The_crypto_case_reproduces_the_ciphertext_from_the_same_password_salt_and_nonce()
    {
        JsonElement c = Root.GetProperty("cryptoCase");
        byte[] salt = Convert.FromBase64String(S(c, "saltB64"));
        byte[] nonce = Convert.FromBase64String(S(c, "nonceB64"));
        int iterations = I(c, "iterations");
        byte[] key = MhBackupCrypto.DeriveKey(S(c, "password"), salt, iterations, I(c, "keyLengthBits"));
        byte[] aad = MhBackupCrypto.AadFor(MhBackupCrypto.Magic, I(c, "formatVersion"), S(c, "kdfAlgorithm"), iterations);

        // AAD 是跨端最容易悄悄分叉的一处：拼法必须与 Android 完全相同。
        Assert.Equal(S(c, "aadUtf8"), Encoding.UTF8.GetString(aad));
        Assert.Equal(MhBackupCrypto.Magic, Root.GetProperty("cryptoCase").GetProperty("aadUtf8").GetString()!.Split('|')[0]);
        Assert.Equal(MhBackupCrypto.FormatVersion, I(c, "formatVersion"));
        Assert.Equal(MhBackupCrypto.KdfAlgorithm, S(c, "kdfAlgorithm"));
        Assert.Equal(MhBackupCrypto.Pbkdf2Iterations, iterations);
        Assert.Equal(MhBackupCrypto.Pbkdf2KeyBits, I(c, "keyLengthBits"));

        byte[] plaintext = Encoding.UTF8.GetBytes(S(c, "plaintextJson"));
        byte[] ciphertext = MhBackupCrypto.Encrypt(plaintext, key, nonce, aad);
        Assert.Equal(S(c, "ciphertextB64"), Convert.ToBase64String(ciphertext));
        Assert.Equal(S(c, "plaintextJson"), Encoding.UTF8.GetString(MhBackupCrypto.Decrypt(ciphertext, key, nonce, aad)));
    }

    // ------------------------------------------------------------- container cases

    [Fact]
    public void Every_container_case_lands_in_the_classification_the_vector_records()
    {
        foreach (JsonElement c in Root.GetProperty("cases").EnumerateArray())
        {
            string id = S(c, "id");
            byte[] bytes = c.TryGetProperty("envelopeJson", out JsonElement envelope)
                ? Encoding.UTF8.GetBytes(envelope.GetString()!)
                : Encoding.UTF8.GetBytes(S(c, "text"));
            MhBackupImportResult result = MhBackupSerializer.Import(bytes, S(c, "password"));

            Assert.True(S(c, "expectedStatus") == StatusName(result.Status),
                id + ": expected " + S(c, "expectedStatus") + " but got " + StatusName(result.Status) + " (" + result.Reason + ")");
            Assert.Equal(I(c, "expectedFormatVersion"), result.FormatVersion);
            if (c.TryGetProperty("expectedReasonContains", out JsonElement reason))
                Assert.Contains(reason.GetString()!, result.Reason);
        }
    }

    [Fact]
    public void The_ok_container_carries_every_whitelisted_field_the_vector_pins()
    {
        JsonElement c = Case("windows-export-ok");
        MhBackupImportResult result = MhBackupSerializer.Import(S(c, "envelopeJson"), S(c, "password"));

        Assert.True(result.IsOk, result.Reason);
        Assert.Equal(2, result.FormatVersion);
        AssertPayload(result.Payload!, Root.GetProperty("expectations"));
    }

    [Fact]
    public void The_fixed_salt_container_is_accepted_so_both_sides_share_one_deterministic_vector()
    {
        JsonElement c = Case("fixed-salt-ok");
        MhBackupImportResult result = MhBackupSerializer.Import(S(c, "envelopeJson"), S(c, "password"));

        Assert.True(result.IsOk, result.Reason);
        Assert.Equal(S(Root.GetProperty("expectations").GetProperty("server"), "backupId"), result.Payload!.Servers[0].BackupId);
    }

    // ------------------------------------------------------------- reverse direction

    [Fact]
    public void An_envelope_written_by_the_android_suite_is_read_here_with_the_same_fields()
    {
        Assert.True(Root.TryGetProperty("androidExport", out JsonElement android),
            "androidExport is missing: run gradlew.bat :core:common:testDebugUnitTest with MH_BACKUP_VECTOR_OUT set, then merge");

        string password = S(android, "password");
        byte[] salt = Convert.FromBase64String(S(android, "saltB64"));
        byte[] nonce = Convert.FromBase64String(S(android, "nonceB64"));
        int iterations = I(android, "iterations");
        byte[] key = MhBackupCrypto.DeriveKey(password, salt, iterations, MhBackupCrypto.Pbkdf2KeyBits);
        byte[] aad = MhBackupCrypto.AadFor(MhBackupCrypto.Magic, MhBackupCrypto.FormatVersion, MhBackupCrypto.KdfAlgorithm, iterations);
        byte[] ciphertext = Convert.FromBase64String(S(android, "ciphertextB64"));

        // 先证明 Android 侧确实是「把这些字节按这些参数加密」：解密得到它自报的 payloadJson。
        byte[] plaintext = MhBackupCrypto.Decrypt(ciphertext, key, nonce, aad);
        Assert.Equal(S(android, "payloadJson"), Encoding.UTF8.GetString(plaintext));
        Assert.Equal(S(android, "ciphertextB64"), Convert.ToBase64String(MhBackupCrypto.Encrypt(plaintext, key, nonce, aad)));

        // 再证明 Windows 的读取器能独立解开同一个容器、字段逐项相同。
        MhBackupImportResult result = MhBackupSerializer.Import(S(android, "envelopeJson"), password);
        Assert.True(result.IsOk, result.Reason);
        AssertPayload(result.Payload!, android.GetProperty("expect"));
    }

    // ------------------------------------------------------------- shared assertions

    private static void AssertPayload(MhBackupPayload payload, JsonElement e)
    {
        MhBackupManifest manifest = payload.Manifest!;
        Assert.Equal(S(e, "manifestAppVersion"), manifest.AppVersion);
        Assert.Equal(L(e, "manifestCreatedAtEpochMs"), manifest.CreatedAtEpochMs);
        Assert.Equal(
            e.GetProperty("includedSections").EnumerateArray().Select(x => x.GetString()).ToArray(),
            manifest.IncludedSections.ToArray());
        foreach (JsonProperty pair in e.GetProperty("recordCounts").EnumerateObject())
            Assert.Equal(pair.Value.GetInt32(), manifest.RecordCounts[pair.Name]);

        JsonElement server = e.GetProperty("server");
        MhBackupServerDto srv = Assert.Single(payload.Servers);
        Assert.Equal(S(server, "backupId"), srv.BackupId);
        Assert.Equal(S(server, "name"), srv.Name);
        Assert.Equal(S(server, "type"), srv.Type);
        Assert.Equal(S(server, "username"), srv.Username);
        Assert.Equal(B(server, "isDefault"), srv.IsDefault);
        Assert.Equal(I(server, "sortOrder"), srv.SortOrder);
        Assert.Equal(I(server, "endpointCount"), srv.Endpoints.Count);
        Assert.Equal(S(server, "primaryUrl"), srv.Endpoints.Single(ep => ep.IsPrimary).Url);

        JsonElement progress = e.GetProperty("progress");
        MhBackupProgressDto p = Assert.Single(payload.Progress);
        Assert.Equal(S(progress, "serverBackupId"), p.ServerBackupId);
        Assert.Equal(S(progress, "itemId"), p.ItemId);
        Assert.Equal(L(progress, "positionMs"), p.PositionMs);
        Assert.Equal(L(progress, "durationMs"), p.DurationMs);
        Assert.Equal(B(progress, "isPaused"), p.IsPaused);
        Assert.Equal(L(progress, "updatedAtEpochMs"), p.UpdatedAtEpochMs);
        Assert.Equal(S(progress, "itemTitle"), p.ItemTitle);
        Assert.Equal(S(progress, "itemType"), p.ItemType);

        JsonElement favorite = e.GetProperty("favorite");
        MhBackupFavoriteDto f = Assert.Single(payload.Favorites);
        Assert.Equal(S(favorite, "serverBackupId"), f.ServerBackupId);
        Assert.Equal(S(favorite, "itemId"), f.ItemId);
        Assert.Equal(S(favorite, "title"), f.Title);
        Assert.Equal(L(favorite, "addedAtEpochMs"), f.AddedAtEpochMs);

        JsonElement prefs = e.GetProperty("preferences");
        MhBackupPreferencesDto pref = payload.Preferences!;
        Assert.Equal(D(prefs, "defaultPlaybackSpeed"), (double)pref.DefaultPlaybackSpeed, 6);
        Assert.Equal(I(prefs, "subtitleSizeSp"), pref.SubtitleSizeSp);
        Assert.Equal(B(prefs, "autoPlayNextEpisode"), pref.AutoPlayNextEpisode);
    }
}

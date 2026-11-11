using System.Diagnostics;
using System.Globalization;
using System.Runtime.Versioning;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace MediaHub.Net.Security;

/// <summary>
/// Credentials for one configured media server, as persisted by an <see cref="ICredentialStore"/>.
/// </summary>
/// <remarks>
/// <para>
/// Stored as a DPAPI (<see cref="DataProtectionScope.CurrentUser"/>) protected file rather than as a
/// Windows Credential Manager entry. Four reasons decided that, in order of weight:
/// </para>
/// <list type="number">
/// <item>Credential Manager caps a credential blob at 2560 bytes, and the cap is a hard failure rather
/// than a truncation. One server entry already carries a password, a token, a user id and a remote
/// server id, and the client is expected to hold many sources, so the payload size cannot be assumed
/// to fit.</item>
/// <item>The format must survive its own upgrades: a versioned file can migrate field by field, while
/// the credential panel has nowhere to record a schema version.</item>
/// <item>It stays testable end to end without touching the user's credential panel: tests point the
/// store at a temporary directory, so running the suite cannot damage or reveal real credentials.</item>
/// <item>A per-installation entropy salt stored beside the vault binds the ciphertext to this
/// installation and Windows account, so a vault copied from elsewhere fails closed.</item>
/// </list>
/// <para>
/// The record is the on-disk payload shape as well as the in-memory model, so the JSON property names
/// below are pinned explicitly: renaming a C# property must not silently invalidate every existing
/// vault.
/// </para>
/// </remarks>
public sealed record ServerCredential
{
    /// <summary>Stable identifier of the configured source; also the vault entry key.</summary>
    [JsonPropertyName("serverId")]
    public required string ServerId { get; init; }

    [JsonPropertyName("userName")]
    public string? UserName { get; init; }

    [JsonPropertyName("password")]
    public string? Password { get; init; }

    /// <summary>Emby/Jellyfin access token. Travels in headers only, never in a URL (ADR-026).</summary>
    [JsonPropertyName("token")]
    public string? Token { get; init; }

    [JsonPropertyName("userId")]
    public string? UserId { get; init; }

    /// <summary>Server-side id of the account, needed to build the per-user API paths.</summary>
    [JsonPropertyName("remoteServerId")]
    public string? RemoteServerId { get; init; }

    [JsonPropertyName("savedAtUtc")]
    public DateTimeOffset SavedAtUtc { get; init; }

    /// <summary>Renders the credential with the secret fields reduced to whether they are present.</summary>
    /// <remarks>
    /// The compiler-generated record ToString() prints every property, secrets included, and ToString is
    /// what string interpolation, logging and exception messages reach for first. Overriding it is a
    /// security control, not a formatting preference. Lengths are not printed either: a length is a
    /// weak oracle, and no caller of ToString() is entitled to it.
    /// </remarks>
    public override string ToString()
        => string.Concat(
            "ServerCredential { ServerId = ", ServerId,
            ", UserName = ", UserName ?? "(none)",
            ", UserId = ", UserId ?? "(none)",
            ", RemoteServerId = ", RemoteServerId ?? "(none)",
            ", SavedAtUtc = ", SavedAtUtc.ToString("o", CultureInfo.InvariantCulture),
            ", Password = ", Presence(Password),
            ", Token = ", Presence(Token),
            " }");

    private static string Presence(string? secret) => string.IsNullOrEmpty(secret) ? "(none)" : "(present)";
}

/// <summary>Outcome of a credential load. Every failure mode has a distinct value because the caller's
/// reaction differs: re-login, re-login for one server, or stop trusting this vault entirely.</summary>
public enum VaultStatus
{
    /// <summary>The credential was found and decrypted.</summary>
    Ok = 0,

    /// <summary>No vault, or no entry for that server id. The caller asks the user to log in.</summary>
    Absent,

    /// <summary>The vault file is not a readable vault document (unparseable JSON, malformed structure,
    /// a bad ciphertext blob). A whole-file corruption is quarantined, and the caller asks the user to
    /// log in again.</summary>
    Corrupt,

    /// <summary>DPAPI returned bytes but they are not a credential payload. The entry is unusable; the
    /// caller asks the user to log in again for that server.</summary>
    Undecryptable,

    /// <summary>DPAPI refused to decrypt the blob: it was protected by another Windows account, another
    /// machine, or another installation's entropy salt. The credential is unrecoverable and the caller
    /// must say so rather than reuse anything.</summary>
    ForeignUser,

    /// <summary>The vault cannot be used right now or by this build: file system failure, unusable key
    /// material, or a format version written by a newer MediaHub. Nothing is destroyed for this status.</summary>
    Unavailable,
}

/// <summary>Result of a load: a status, the credential when one was usable, and a non-secret detail for
/// diagnostics. <see cref="Detail"/> is written for a human reading the diagnostics panel and must never
/// carry a password, a token or a ciphertext.</summary>
public sealed record VaultLoadResult(VaultStatus Status, ServerCredential? Credential, string? Detail);

/// <summary>Persistent per-server credentials.</summary>
/// <remarks>
/// Implementations must never let a vault condition reach the UI as an exception on the read path: a
/// user who cannot start the app because a credential file is unreadable is worse off than a user who is
/// asked to log in again.
/// </remarks>
public interface ICredentialStore
{
    /// <summary>Stores (or replaces) the credential for <see cref="ServerCredential.ServerId"/>.</summary>
    /// <exception cref="ArgumentException"><see cref="ServerCredential.ServerId"/> is empty.</exception>
    /// <exception cref="IOException">The vault cannot be written; the previous file is left intact.</exception>
    Task SaveAsync(ServerCredential credential, CancellationToken ct = default);

    /// <summary>Loads one credential. Never throws for a vault condition; the status says what happened.</summary>
    Task<VaultLoadResult> LoadAsync(string serverId, CancellationToken ct = default);

    /// <summary>Removes one entry, leaving every other entry readable.</summary>
    Task DeleteAsync(string serverId, CancellationToken ct = default);

    /// <summary>Lists the server ids present in the vault, in ordinal order.</summary>
    Task<IReadOnlyList<string>> ListServerIdsAsync(CancellationToken ct = default);

    /// <summary>Removes the whole vault (the explicit "forget this machine" action).</summary>
    Task ClearAsync(CancellationToken ct = default);
}

/// <summary>
/// Credential vault backed by DPAPI CurrentUser and a versioned JSON file.
/// </summary>
/// <remarks>
/// <para>
/// Layout, in <c>%LOCALAPPDATA%\MediaHub\credentials</c> by default:
/// <c>credentials.vault.json</c> (version, then one encrypted blob per server id) and
/// <c>entropy.bin</c> (this installation's salt). Server ids are stored in clear text on purpose: they
/// are identifiers rather than secrets, and keeping them readable is what lets the client list and
/// forget entries of a vault it cannot decrypt.
/// </para>
/// <para>
/// Failure policy: a vault the build cannot parse is quarantined (renamed next to the vault, never
/// deleted) and reported as <see cref="VaultStatus.Corrupt"/>; a vault written by a newer build is
/// reported as <see cref="VaultStatus.Unavailable"/> and left untouched, so a downgrade cannot destroy
/// credentials. A single unreadable entry is reported without discarding the rest of the vault.
/// </para>
/// <para>
/// Thread safety is per instance (the app has exactly one store). The gate covers the whole
/// read-modify-write cycle, so a concurrent save cannot lose another save's entry, and the atomic
/// replace means a reader never observes a partially written file.
/// </para>
/// </remarks>
[SupportedOSPlatform("windows")]
public sealed class DpapiCredentialStore : ICredentialStore
{
    /// <summary>Format version this build writes and the highest it understands.</summary>
    private const int CurrentFormatVersion = 1;

    private const int SaltLength = 32;
    private const string VaultFileName = "credentials.vault.json";
    private const string EntropyFileName = "entropy.bin";

    /// <summary>
    /// Domain separator mixed into the DPAPI entropy together with the salt. A new format version gets a
    /// new string, so a v1 blob can never be silently reinterpreted as a newer payload shape.
    /// </summary>
    private const string EntropyDomain = "MediaHub.CredentialVault.v1";

    private static readonly JsonSerializerOptions Json = new()
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly string _directory;
    private readonly string _entropyPath;
    private volatile VaultStatus _lastStatus = VaultStatus.Absent;

    /// <param name="directory">
    /// Vault directory, or null for <c>%LOCALAPPDATA%\MediaHub\credentials</c>. Nothing is written at
    /// construction time: the directory and the salt appear on the first save.
    /// </param>
    public DpapiCredentialStore(string? directory = null)
    {
        string root = string.IsNullOrWhiteSpace(directory)
            ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MediaHub", "credentials")
            : directory;

        _directory = Path.GetFullPath(root);
        VaultPath = Path.Combine(_directory, VaultFileName);
        _entropyPath = Path.Combine(_directory, EntropyFileName);
    }

    /// <summary>Full path of the vault file, whether or not it exists yet.</summary>
    public string VaultPath { get; }

    /// <summary>Status of the most recent operation, for the diagnostics panel. Advisory: it is the last
    /// outcome of this instance, and concurrent callers race for it by definition.</summary>
    public VaultStatus LastStatus => _lastStatus;

    /// <summary>Raised when an operation hit a vault problem worth telling the user about
    /// (Corrupt, Undecryptable, ForeignUser, Unavailable). Absence is normal and is not raised.</summary>
    public event EventHandler<VaultLoadResult>? VaultProblem;

    /// <inheritdoc />
    public async Task SaveAsync(ServerCredential credential, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(credential);
        if (string.IsNullOrWhiteSpace(credential.ServerId))
            throw new ArgumentException("ServerId identifies the vault entry and cannot be empty.", nameof(credential));

        // The vault is the component that knows when the write happened. Persisting MinValue because a
        // caller built the record without a timestamp would put an impossible date into the vault and
        // into the UI, so a default is stamped here instead.
        ServerCredential stored = credential.SavedAtUtc == default
            ? credential with { SavedAtUtc = DateTimeOffset.UtcNow }
            : credential;

        await _gate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            VaultDocument document = DocumentForWrite();
            byte[] salt = GetOrCreateSalt();
            byte[] blob = ProtectedData.Protect(
                Encoding.UTF8.GetBytes(JsonSerializer.Serialize(stored, Json)),
                EntropyFor(salt, stored.ServerId),
                DataProtectionScope.CurrentUser);

            document.Entries!.RemoveAll(e => e is not null && string.Equals(e.ServerId, stored.ServerId, StringComparison.Ordinal));
            document.Entries!.Add(new VaultEntry { ServerId = stored.ServerId, Blob = Convert.ToBase64String(blob) });
            WriteDocument(document);
            _lastStatus = VaultStatus.Ok;
        }
        catch (Exception ex) when (IsWriteFailure(ex))
        {
            // The write did not happen. Recording Ok here would make the diagnostics panel lie, so the
            // status is corrected before the failure is handed to the caller.
            _lastStatus = VaultStatus.Unavailable;
            throw;
        }
        finally
        {
            _gate.Release();
        }
    }

    /// <inheritdoc />
    public async Task<VaultLoadResult> LoadAsync(string serverId, CancellationToken ct = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(serverId);

        VaultLoadResult result;
        await _gate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            try
            {
                result = LoadCore(serverId);
            }
            catch (Exception ex) when (IsInfrastructureFailure(ex))
            {
                result = new VaultLoadResult(VaultStatus.Unavailable, null, InfrastructureDetail(ex));
            }

            _lastStatus = result.Status;
        }
        finally
        {
            _gate.Release();
        }

        // Raised outside the gate: a subscriber that calls back into the store would otherwise deadlock,
        // and this store never promises re-entrancy.
        if (IsProblem(result.Status)) RaiseProblem(result);
        return result;
    }

    /// <inheritdoc />
    public async Task DeleteAsync(string serverId, CancellationToken ct = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(serverId);

        VaultLoadResult? problem = null;
        await _gate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            (VaultDocument? document, VaultDocumentProblem kind, string? detail) = ReadDocument();
            if (kind == VaultDocumentProblem.FutureVersion)
            {
                // Deleting rewrites the document, which would drop every entry this build cannot read — the same
                // destruction Save refuses. It is reported as an error because a silent no-op would leave the
                // credential on disk after the user asked to forget it.
                _lastStatus = VaultStatus.Unavailable;
                throw new InvalidOperationException(detail);
            }

            if (kind != VaultDocumentProblem.None)
            {
                problem = ProblemResult(kind, detail);
                _lastStatus = problem.Status;
            }
            else
            {
                // Deletion never decrypts: a user who cannot read a vault (another account, another
                // installation) must still be able to remove an entry from it.
                int removed = document!.Entries!.RemoveAll(e => e is not null && string.Equals(e.ServerId, serverId, StringComparison.Ordinal));
                if (removed == 0)
                {
                    _lastStatus = VaultStatus.Absent;
                }
                else
                {
                    WriteDocument(document);
                    _lastStatus = VaultStatus.Ok;
                }
            }
        }
        catch (Exception ex) when (IsWriteFailure(ex))
        {
            _lastStatus = VaultStatus.Unavailable;
            throw;
        }
        finally
        {
            _gate.Release();
        }

        // Raised after the gate is released, and only for a real problem: a missing vault is a normal state
        // ("this server was never configured"), not something to report.
        if (problem is not null && IsProblem(problem.Status)) RaiseProblem(problem);
    }

    /// <inheritdoc />
    public async Task<IReadOnlyList<string>> ListServerIdsAsync(CancellationToken ct = default)
    {
        IReadOnlyList<string> ids = Array.Empty<string>();
        VaultLoadResult? problem = null;

        await _gate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            (VaultDocument? document, VaultDocumentProblem kind, string? detail) = ReadDocument();
            if (kind == VaultDocumentProblem.None)
            {
                // Identifiers, not secrets, so they are listed from the document without decrypting: the
                // settings page must be able to show (and clear) entries of a vault this account cannot open.
                ids = document!.Entries!
                    .Select(e => e!.ServerId)
                    .OrderBy(id => id, StringComparer.Ordinal)
                    .ToArray();
                _lastStatus = VaultStatus.Ok;
            }
            else
            {
                problem = ProblemResult(kind, detail);
                _lastStatus = problem.Status;
            }
        }
        catch (Exception ex) when (IsInfrastructureFailure(ex))
        {
            problem = new VaultLoadResult(VaultStatus.Unavailable, null, InfrastructureDetail(ex));
            _lastStatus = VaultStatus.Unavailable;
        }
        finally
        {
            _gate.Release();
        }

        if (problem is not null) RaiseProblem(problem);
        return ids;
    }

    /// <inheritdoc />
    public async Task ClearAsync(CancellationToken ct = default)
    {
        await _gate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            // The salt deliberately survives a clear: it is per installation rather than per vault, and
            // keeping it avoids re-keying on the next save. A vault of any format version is removed here
            // because this is the explicit "forget this machine" action, unlike the routine paths above
            // which refuse to touch a format this build cannot read.
            bool existed = File.Exists(VaultPath);
            File.Delete(VaultPath);
            _lastStatus = existed ? VaultStatus.Ok : VaultStatus.Absent;
        }
        catch (Exception ex) when (IsWriteFailure(ex))
        {
            _lastStatus = VaultStatus.Unavailable;
            throw;
        }
        finally
        {
            _gate.Release();
        }
    }

    private VaultLoadResult LoadCore(string serverId)
    {
        (VaultDocument? document, VaultDocumentProblem kind, string? detail) = ReadDocument();
        if (kind != VaultDocumentProblem.None) return ProblemResult(kind, detail);

        VaultEntry? entry = document!.Entries!.Find(e => e is not null && string.Equals(e.ServerId, serverId, StringComparison.Ordinal));
        if (entry is null) return new VaultLoadResult(VaultStatus.Absent, null, null);

        if (string.IsNullOrEmpty(entry.Blob))
            return new VaultLoadResult(VaultStatus.Corrupt, null, "保管库中该服务器的密文为空，凭据无法读取。");

        byte[] blob;
        try
        {
            blob = Convert.FromBase64String(entry.Blob);
        }
        catch (FormatException)
        {
            return new VaultLoadResult(VaultStatus.Corrupt, null, "保管库中该服务器的密文不是有效的 Base64，凭据无法读取。");
        }

        byte[] salt;
        try
        {
            salt = GetOrCreateSalt();
        }
        catch (InvalidDataException ex)
        {
            // A salt of the wrong size cannot be the one that protected this entry, and regenerating it
            // would silently orphan every credential the installation still holds.
            return new VaultLoadResult(VaultStatus.ForeignUser, null, ex.Message);
        }

        byte[] plain;
        try
        {
            plain = ProtectedData.Unprotect(blob, EntropyFor(salt, entry.ServerId), DataProtectionScope.CurrentUser);
        }
        catch (CryptographicException)
        {
            // DPAPI refuses in situations that are indistinguishable from here: the blob belongs to
            // another Windows account or machine, or this installation's salt is not the one it was
            // protected with (a copied vault). Either way the secret is unrecoverable, so the caller is
            // told instead of being handed anything to retry with.
            return new VaultLoadResult(VaultStatus.ForeignUser, null,
                "该服务器的凭据无法用当前 Windows 账户解密：它由其他账户或其他安装写入，无法恢复，请重新登录。");
        }

        ServerCredential? credential;
        try
        {
            credential = JsonSerializer.Deserialize<ServerCredential>(plain, Json);
        }
        catch (JsonException)
        {
            return new VaultLoadResult(VaultStatus.Undecryptable, null, "该服务器的凭据已解密，但内容不是有效的凭据载荷，请重新登录。");
        }

        return credential is null
            ? new VaultLoadResult(VaultStatus.Undecryptable, null, "该服务器的凭据已解密，但内容为空，请重新登录。")
            : new VaultLoadResult(VaultStatus.Ok, credential, null);
    }

    /// <summary>
    /// Reads the vault document, classifying anything that is not a document this build can use.
    /// </summary>
    /// <remarks>
    /// File system failures are rethrown rather than classified here: the read path turns them into
    /// <see cref="VaultStatus.Unavailable"/> at its boundary, while the write path must let the caller see
    /// the real exception because a failed save is an explicit action's failure, not a background read.
    /// </remarks>
    private (VaultDocument? Document, VaultDocumentProblem Kind, string? Detail) ReadDocument()
    {
        byte[] raw;
        try
        {
            raw = File.ReadAllBytes(VaultPath);
        }
        catch (FileNotFoundException)
        {
            return (null, VaultDocumentProblem.Missing, null);
        }
        catch (DirectoryNotFoundException)
        {
            return (null, VaultDocumentProblem.Missing, null);
        }

        VaultDocument? document;
        try
        {
            document = JsonSerializer.Deserialize<VaultDocument>(raw, Json);
        }
        catch (JsonException ex)
        {
            // Only the exception type travels: a serializer message can quote the content it failed on
            // (ADR-W003 red line 7, cause sanitisation).
            return (null, VaultDocumentProblem.Corrupt, "保管库文件不是有效的 JSON（" + ex.GetType().Name + "）。");
        }

        if (document is null)
            return (null, VaultDocumentProblem.Corrupt, "保管库文件为空。");

        if (document.Version > CurrentFormatVersion)
            return (null, VaultDocumentProblem.FutureVersion,
                "保管库由更新版本的 MediaHub 写入（version=" + document.Version.ToString(CultureInfo.InvariantCulture)
                + "，当前支持 version=" + CurrentFormatVersion.ToString(CultureInfo.InvariantCulture) + "），本版本不会改动它。");

        if (document.Version < CurrentFormatVersion)
            return (null, VaultDocumentProblem.Corrupt,
                "保管库缺少有效的版本号（version=" + document.Version.ToString(CultureInfo.InvariantCulture) + "）。");

        if (document.Entries is null)
            return (null, VaultDocumentProblem.Corrupt, "保管库缺少 entries 列表。");

        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (VaultEntry? entry in document.Entries)
        {
            if (entry is null || string.IsNullOrWhiteSpace(entry.ServerId) || entry.Blob is null)
                return (null, VaultDocumentProblem.Corrupt, "保管库包含结构不完整的条目。");
            if (!seen.Add(entry.ServerId))
                return (null, VaultDocumentProblem.Corrupt, "保管库包含重复的服务器标识。");
        }

        return (document, VaultDocumentProblem.None, null);
    }

    /// <summary>Reads the document for a read-modify-write cycle.</summary>
    /// <remarks>
    /// A vault this build cannot parse is quarantined and replaced rather than blocking the write: the only
    /// way out of a corrupt vault is to log in again, and that login has to be able to save. A vault from a
    /// newer build is the opposite case and is refused, because rewriting it would drop entries this build
    /// cannot even read.
    /// </remarks>
    private VaultDocument DocumentForWrite()
    {
        (VaultDocument? document, VaultDocumentProblem kind, string? detail) = ReadDocument();
        switch (kind)
        {
            case VaultDocumentProblem.None:
                return document!;
            case VaultDocumentProblem.Missing:
                return NewDocument();
            case VaultDocumentProblem.FutureVersion:
                throw new InvalidOperationException(detail);
            default:
                TryQuarantine();
                return NewDocument();
        }
    }

    /// <summary>Maps a document-level problem to the caller-visible result, quarantining a corrupt file.</summary>
    private VaultLoadResult ProblemResult(VaultDocumentProblem kind, string? detail)
    {
        if (kind == VaultDocumentProblem.Missing) return new VaultLoadResult(VaultStatus.Absent, null, null);
        if (kind == VaultDocumentProblem.FutureVersion) return new VaultLoadResult(VaultStatus.Unavailable, null, detail);

        string? quarantined = TryQuarantine();
        string message = detail ?? "保管库无法读取。";
        message += quarantined is null
            ? " 该文件无法隔离，请手动检查后重新登录。"
            : " 原文件已隔离为 " + Path.GetFileName(quarantined) + "，请重新登录。";
        return new VaultLoadResult(VaultStatus.Corrupt, null, message);
    }

    /// <summary>
    /// Moves an unreadable vault aside instead of deleting it.
    /// </summary>
    /// <remarks>
    /// The bytes are the only remaining evidence of what went wrong, and the next successful save will
    /// overwrite the vault path. Renaming within the same directory keeps the operation cheap and atomic.
    /// </remarks>
    private string? TryQuarantine()
    {
        string stamp = DateTime.UtcNow.ToString("yyyyMMdd'T'HHmmssfff'Z'", CultureInfo.InvariantCulture);
        for (int attempt = 0; attempt < 100; attempt++)
        {
            string suffix = attempt == 0 ? "" : "-" + attempt.ToString(CultureInfo.InvariantCulture);
            string target = VaultPath + ".corrupt-" + stamp + suffix;
            try
            {
                File.Move(VaultPath, target);
                return target;
            }
            catch (IOException) when (File.Exists(target))
            {
                // Two quarantines inside the same millisecond; take the next free suffix.
            }
            catch (IOException)
            {
                return null;
            }
            catch (UnauthorizedAccessException)
            {
                return null;
            }
        }

        return null;
    }

    /// <summary>
    /// Returns this installation's salt, creating it on first use.
    /// </summary>
    /// <remarks>
    /// <para>
    /// The salt is not a secret: it is readable by this Windows account and does not slow down anyone who
    /// already runs as this user. What it does is bind every blob to this installation, so a vault file
    /// copied from another installation fails to decrypt instead of opening. It is deliberately not derived
    /// from anything that can change (a password, a machine id), because a salt derived from changeable
    /// data would make the vault unreadable the moment that data changed.
    /// </para>
    /// <para>
    /// A salt file of the wrong size throws rather than being regenerated: regenerating would strand every
    /// credential the installation still holds, which is exactly the silent data loss this vault exists to
    /// avoid.
    /// </para>
    /// </remarks>
    private byte[] GetOrCreateSalt()
    {
        byte[]? existing = TryReadSalt();
        if (existing is not null) return existing;

        byte[] fresh = RandomNumberGenerator.GetBytes(SaltLength);
        Directory.CreateDirectory(_directory);

        string temp = _entropyPath + ".tmp-" + Guid.NewGuid().ToString("N");
        try
        {
            File.WriteAllBytes(temp, fresh);
            try
            {
                // No overwrite: if another instance created the salt first, its file wins and this one is
                // adopted by re-reading below. Replacing it would strand the blobs it already encrypted.
                File.Move(temp, _entropyPath);
            }
            catch (IOException) when (File.Exists(_entropyPath))
            {
                // Lost the race; adopt the winner's salt.
            }
        }
        finally
        {
            TryDelete(temp);
        }

        return TryReadSalt()
            ?? throw new InvalidDataException("无法创建本机熵盐文件：" + _entropyPath);
    }

    private byte[]? TryReadSalt()
    {
        if (!File.Exists(_entropyPath)) return null;

        byte[] salt = File.ReadAllBytes(_entropyPath);
        if (salt.Length != SaltLength)
            throw new InvalidDataException(
                "本机熵盐文件长度异常（" + salt.Length.ToString(CultureInfo.InvariantCulture)
                + " 字节，应为 " + SaltLength.ToString(CultureInfo.InvariantCulture)
                + " 字节），已保存的凭据无法解密。");

        return salt;
    }

    /// <summary>
    /// Builds the DPAPI optional entropy for one entry: the installation salt plus the entry's server id.
    /// </summary>
    /// <remarks>
    /// Binding the blob to its server id means a blob swapped between entries fails closed rather than
    /// presenting one server's token to another origin.
    /// </remarks>
    private static byte[] EntropyFor(byte[] salt, string serverId)
    {
        byte[] purpose = Encoding.UTF8.GetBytes(EntropyDomain + "|" + serverId);
        byte[] entropy = new byte[salt.Length + purpose.Length];
        salt.CopyTo(entropy, 0);
        purpose.CopyTo(entropy, salt.Length);
        return entropy;
    }

    /// <summary>
    /// Writes the document through a temporary file and an atomic replace.
    /// </summary>
    /// <remarks>
    /// The vault is the only copy of credentials that cannot be re-derived, so a crash, a full disk or a
    /// concurrent reader must never be able to observe a half-written file.
    /// </remarks>
    private void WriteDocument(VaultDocument document)
    {
        // Stamped on the way out rather than defaulted inside the DTO, so that a document arriving without a
        // version field stays distinguishable from a version 1 document.
        document.Version = CurrentFormatVersion;
        document.Entries ??= new();

        byte[] bytes = JsonSerializer.SerializeToUtf8Bytes(document, Json);
        Directory.CreateDirectory(_directory);

        string temp = VaultPath + ".tmp-" + Guid.NewGuid().ToString("N");
        try
        {
            using (var stream = new FileStream(temp, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                stream.Write(bytes, 0, bytes.Length);
                stream.Flush(flushToDisk: true);
            }

            File.Move(temp, VaultPath, overwrite: true);
        }
        finally
        {
            TryDelete(temp);
        }
    }

    private void RaiseProblem(VaultLoadResult result)
    {
        EventHandler<VaultLoadResult>? handler = VaultProblem;
        if (handler is null) return;

        try
        {
            handler(this, result);
        }
        catch (Exception ex)
        {
            // A diagnostics subscriber is not allowed to turn a recoverable vault problem into an
            // exception thrown from credential loading, which runs on the UI thread during startup.
            Debug.WriteLine("VaultProblem subscriber threw " + ex.GetType().Name);
        }
    }

    /// <summary>
    /// Exceptions the read path turns into <see cref="VaultStatus.Unavailable"/> instead of letting reach
    /// the UI. Deliberately an allow-list: anything else is a defect in this class, and silently swallowing
    /// it would hide the defect.
    /// </summary>
    private static bool IsInfrastructureFailure(Exception ex)
        => ex is IOException or UnauthorizedAccessException or System.Security.SecurityException
            or NotSupportedException or ArgumentException;

    /// <summary>Write-path failures: the operations that have no result value, so the caller needs the
    /// exception. InvalidOperationException covers a vault this build refuses to rewrite.</summary>
    private static bool IsWriteFailure(Exception ex)
        => ex is IOException or UnauthorizedAccessException or System.Security.SecurityException
            or NotSupportedException or CryptographicException or InvalidOperationException;

    private static string InfrastructureDetail(Exception ex)
        // Only the exception type travels: file system messages can contain a path, and a serializer
        // message can quote content.
        => "保管库暂时不可用（" + ex.GetType().Name + "），请检查目录权限后重试。";

    private static VaultDocument NewDocument() => new()
    {
        Version = CurrentFormatVersion,
        Entries = new(),
    };

    private static bool IsProblem(VaultStatus status)
        => status is VaultStatus.Corrupt or VaultStatus.Undecryptable or VaultStatus.ForeignUser or VaultStatus.Unavailable;

    private static void TryDelete(string path)
    {
        try
        {
            File.Delete(path);
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }

    private enum VaultDocumentProblem
    {
        None = 0,
        Missing,
        Corrupt,
        FutureVersion,
    }

    /// <remarks>
    /// Private on purpose: the document shape is an on-disk contract, and the only thing that may depend on
    /// it is this file. The JSON names are pinned so a C# rename cannot invalidate existing vaults.
    /// </remarks>
    private sealed class VaultDocument
    {
        /// <remarks>
        /// Deliberately without an initial value: a document that arrives without a version field must read as
        /// "not a vault this build wrote" (Corrupt) rather than being adopted as version 1. The version is
        /// stamped by <see cref="WriteDocument"/> on the way out.
        /// </remarks>
        [JsonPropertyName("version")]
        public int Version { get; set; }

        [JsonPropertyName("entries")]
        public List<VaultEntry?>? Entries { get; set; }
    }

    private sealed class VaultEntry
    {
        [JsonPropertyName("serverId")]
        public string ServerId { get; set; } = "";

        /// <summary>DPAPI-protected UTF-8 credential JSON, Base64.</summary>
        [JsonPropertyName("blob")]
        public string Blob { get; set; } = "";
    }
}

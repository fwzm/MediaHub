using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using MediaHub.Net.Security;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// The vault is the only copy of credentials that cannot be re-derived, so these tests cover the failure
/// modes that lose or leak data rather than only the happy path: quarantine instead of deletion, fail-closed
/// on a vault from elsewhere, and a read path that never throws at the UI.
/// </summary>
public sealed class CredentialVaultTests : IDisposable
{
    private readonly string _directory;

    public CredentialVaultTests()
    {
        // A per-test directory keeps the suite away from the real %LOCALAPPDATA% vault and makes every test
        // independent of the others' file state.
        _directory = Path.Combine(Path.GetTempPath(), "mediahub-vault-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_directory);
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_directory)) Directory.Delete(_directory, recursive: true);
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }

    [Fact]
    public async Task Save_then_load_round_trips_every_field()
    {
        var store = new DpapiCredentialStore(_directory);
        var saved = new ServerCredential
        {
            ServerId = "emby-home",
            UserName = "alice",
            Password = "p@ss wörd 汉字",
            Token = "token-0123456789abcdef",
            UserId = "user-guid",
            RemoteServerId = "remote-server-guid",
            SavedAtUtc = new DateTimeOffset(2026, 10, 14, 3, 4, 5, TimeSpan.FromHours(8)),
        };

        await store.SaveAsync(saved);
        VaultLoadResult result = await store.LoadAsync("emby-home");

        Assert.Equal(VaultStatus.Ok, result.Status);
        Assert.NotNull(result.Credential);
        Assert.Equal(saved.ServerId, result.Credential!.ServerId);
        Assert.Equal(saved.UserName, result.Credential.UserName);
        Assert.Equal(saved.Password, result.Credential.Password);
        Assert.Equal(saved.Token, result.Credential.Token);
        Assert.Equal(saved.UserId, result.Credential.UserId);
        Assert.Equal(saved.RemoteServerId, result.Credential.RemoteServerId);
        Assert.Equal(saved.SavedAtUtc, result.Credential.SavedAtUtc);
        Assert.Equal(saved, result.Credential);
    }

    [Fact]
    public async Task Saved_at_is_stamped_when_the_caller_leaves_it_at_the_default()
    {
        var store = new DpapiCredentialStore(_directory);
        DateTimeOffset before = DateTimeOffset.UtcNow;

        await store.SaveAsync(new ServerCredential { ServerId = "srv", Password = "p" });
        VaultLoadResult result = await store.LoadAsync("srv");

        Assert.Equal(VaultStatus.Ok, result.Status);
        Assert.NotEqual(default, result.Credential!.SavedAtUtc);
        Assert.InRange(result.Credential.SavedAtUtc, before.AddSeconds(-1), DateTimeOffset.UtcNow.AddSeconds(1));
    }

    [Fact]
    public async Task Vault_file_is_a_versioned_document_that_holds_no_plaintext_secret()
    {
        var store = new DpapiCredentialStore(_directory);
        await store.SaveAsync(new ServerCredential
        {
            ServerId = "srv",
            UserName = "alice",
            Password = "hunter2",
            Token = "tok-abc",
        });

        string raw = await File.ReadAllTextAsync(store.VaultPath);
        using JsonDocument document = JsonDocument.Parse(raw);
        Assert.Equal(1, document.RootElement.GetProperty("version").GetInt32());
        JsonElement entries = document.RootElement.GetProperty("entries");
        Assert.Equal(1, entries.GetArrayLength());

        // The id stays readable on purpose: listing and forgetting must work on a vault this account cannot
        // decrypt, and an id is an identifier rather than a secret.
        Assert.Equal("srv", entries[0].GetProperty("serverId").GetString());

        // Everything secret exists only inside the DPAPI blob.
        Assert.DoesNotContain("hunter2", raw);
        Assert.DoesNotContain("tok-abc", raw);
        Assert.DoesNotContain("alice", raw);
    }

    [Fact]
    public async Task Payloads_beyond_the_credential_manager_limit_round_trip()
    {
        // 2560 bytes is the hard cap on a single Windows Credential Manager blob. A long-lived token plus the
        // profile fields is exactly the case this vault exists for, so the vault must have no such cap.
        string hugeToken = new('A', 4000);
        string hugePassword = new('汉', 500);
        var store = new DpapiCredentialStore(_directory);

        await store.SaveAsync(new ServerCredential { ServerId = "srv", Token = hugeToken, Password = hugePassword });
        VaultLoadResult result = await store.LoadAsync("srv");

        Assert.Equal(VaultStatus.Ok, result.Status);
        Assert.Equal(hugeToken, result.Credential!.Token);
        Assert.Equal(hugePassword, result.Credential.Password);
    }

    [Fact]
    public async Task Servers_are_isolated_and_listed_in_ordinal_order()
    {
        var store = new DpapiCredentialStore(_directory);
        await store.SaveAsync(new ServerCredential { ServerId = "zeta", Password = "pz" });
        await store.SaveAsync(new ServerCredential { ServerId = "alpha", Password = "pa" });
        await store.SaveAsync(new ServerCredential { ServerId = "mid", Password = "pm" });

        Assert.Equal(new[] { "alpha", "mid", "zeta" }, await store.ListServerIdsAsync());
        Assert.Equal("pz", (await store.LoadAsync("zeta")).Credential!.Password);
        Assert.Equal("pa", (await store.LoadAsync("alpha")).Credential!.Password);
        Assert.Equal("pm", (await store.LoadAsync("mid")).Credential!.Password);
    }

    [Fact]
    public async Task Saving_the_same_server_twice_replaces_it()
    {
        var store = new DpapiCredentialStore(_directory);
        await store.SaveAsync(new ServerCredential { ServerId = "srv", Password = "old", Token = "old-token" });
        await store.SaveAsync(new ServerCredential { ServerId = "srv", Password = "new" });

        IReadOnlyList<string> ids = await store.ListServerIdsAsync();
        Assert.Equal(new[] { "srv" }, ids);

        VaultLoadResult result = await store.LoadAsync("srv");
        Assert.Equal("new", result.Credential!.Password);
        Assert.Null(result.Credential.Token);
    }

    [Fact]
    public async Task Delete_removes_one_entry_and_leaves_the_others_decryptable()
    {
        var store = new DpapiCredentialStore(_directory);
        await store.SaveAsync(new ServerCredential { ServerId = "a", Password = "pa" });
        await store.SaveAsync(new ServerCredential { ServerId = "b", Password = "pb" });

        await store.DeleteAsync("a");
        Assert.Equal(VaultStatus.Ok, store.LastStatus);

        Assert.Equal(VaultStatus.Absent, (await store.LoadAsync("a")).Status);
        Assert.Equal(new[] { "b" }, await store.ListServerIdsAsync());

        // A fresh instance proves the file itself is still readable, not just this instance's state.
        var reopened = new DpapiCredentialStore(_directory);
        VaultLoadResult remaining = await reopened.LoadAsync("b");
        Assert.Equal(VaultStatus.Ok, remaining.Status);
        Assert.Equal("pb", remaining.Credential!.Password);
    }

    [Fact]
    public async Task Deleting_an_unknown_server_reports_absent_and_touches_nothing()
    {
        var store = new DpapiCredentialStore(_directory);
        await store.SaveAsync(new ServerCredential { ServerId = "a", Password = "pa" });
        byte[] before = await File.ReadAllBytesAsync(store.VaultPath);

        await store.DeleteAsync("never-configured");

        Assert.Equal(VaultStatus.Absent, store.LastStatus);
        Assert.Equal(before, await File.ReadAllBytesAsync(store.VaultPath));
    }

    [Fact]
    public async Task Deleting_from_a_vault_that_does_not_exist_does_not_create_one()
    {
        var store = new DpapiCredentialStore(_directory);
        await store.DeleteAsync("a");
        Assert.Equal(VaultStatus.Absent, store.LastStatus);
        Assert.Empty(Directory.GetFiles(_directory));
    }

    [Fact]
    public async Task A_corrupt_vault_is_reported_quarantined_and_never_thrown()
    {
        var store = new DpapiCredentialStore(_directory);
        byte[] garbage = Encoding.UTF8.GetBytes("{ not json at all");
        await File.WriteAllBytesAsync(store.VaultPath, garbage);

        VaultLoadResult result = await store.LoadAsync("srv");

        Assert.Equal(VaultStatus.Corrupt, result.Status);
        Assert.Null(result.Credential);
        Assert.Equal(VaultStatus.Corrupt, store.LastStatus);
        Assert.False(File.Exists(store.VaultPath));

        string[] quarantined = Directory.GetFiles(_directory).Where(f => f.Contains(".corrupt-", StringComparison.Ordinal)).ToArray();
        Assert.Single(quarantined);
        Assert.Equal(garbage, await File.ReadAllBytesAsync(quarantined[0]));
        Assert.Contains(Path.GetFileName(quarantined[0]), result.Detail!);

        // The next read is a normal "not configured yet": the app asks the user to log in rather than failing.
        Assert.Equal(VaultStatus.Absent, (await store.LoadAsync("srv")).Status);

        // And that login must be able to write.
        await store.SaveAsync(new ServerCredential { ServerId = "srv", Password = "fresh" });
        VaultLoadResult afterLogin = await store.LoadAsync("srv");
        Assert.Equal(VaultStatus.Ok, afterLogin.Status);
        Assert.Equal("fresh", afterLogin.Credential!.Password);
    }

    [Fact]
    public async Task One_unreadable_entry_does_not_discard_the_rest_of_the_vault()
    {
        var store = new DpapiCredentialStore(_directory);
        await store.SaveAsync(new ServerCredential { ServerId = "good", Password = "kept" });

        JsonNode document = JsonNode.Parse(await File.ReadAllTextAsync(store.VaultPath))!;
        string goodBlob = document["entries"]![0]!["blob"]!.GetValue<string>();
        await File.WriteAllTextAsync(
            store.VaultPath,
            $$"""{"version":1,"entries":[{"serverId":"good","blob":"{{goodBlob}}"},{"serverId":"bad","blob":"@@@"}]}""");

        VaultLoadResult bad = await store.LoadAsync("bad");
        Assert.Equal(VaultStatus.Corrupt, bad.Status);
        Assert.Null(bad.Credential);

        // An entry-level problem is not a reason to quarantine the whole vault and lose the other servers.
        Assert.True(File.Exists(store.VaultPath));
        VaultLoadResult good = await store.LoadAsync("good");
        Assert.Equal(VaultStatus.Ok, good.Status);
        Assert.Equal("kept", good.Credential!.Password);
    }

    [Theory]
    [InlineData("""{"entries":[]}""")]
    [InlineData("""{"version":0,"entries":[]}""")]
    [InlineData("""{"version":1}""")]
    [InlineData("""{"version":1,"entries":null}""")]
    public async Task A_vault_without_the_required_structure_is_corrupt(string content)
    {
        // A version that is merely absent must not be defaulted into "version 1": the version field is what makes
        // a future format change migratable, so a document without one is not a vault this build wrote.
        var store = new DpapiCredentialStore(_directory);
        await File.WriteAllTextAsync(store.VaultPath, content);

        VaultLoadResult result = await store.LoadAsync("srv");

        Assert.Equal(VaultStatus.Corrupt, result.Status);
        Assert.Null(result.Credential);
        Assert.NotNull(result.Detail);
        Assert.False(File.Exists(store.VaultPath));
    }

    [Fact]
    public async Task A_vault_with_duplicate_server_ids_is_corrupt()
    {
        var store = new DpapiCredentialStore(_directory);
        await File.WriteAllTextAsync(
            store.VaultPath,
            """{"version":1,"entries":[{"serverId":"dup","blob":"AAAA"},{"serverId":"dup","blob":"BBBB"}]}""");

        VaultLoadResult result = await store.LoadAsync("dup");

        Assert.Equal(VaultStatus.Corrupt, result.Status);
        Assert.Contains("重复", result.Detail!);
        Assert.False(File.Exists(store.VaultPath));
    }

    [Fact]
    public async Task A_vault_from_a_newer_build_is_unavailable_and_is_never_rewritten()
    {
        var store = new DpapiCredentialStore(_directory);
        const string future = """{"version":99,"entries":[{"serverId":"srv","blob":"AAAA"}]}""";
        await File.WriteAllTextAsync(store.VaultPath, future);

        VaultLoadResult loaded = await store.LoadAsync("srv");
        Assert.Equal(VaultStatus.Unavailable, loaded.Status);
        Assert.Null(loaded.Credential);
        Assert.Contains("99", loaded.Detail!);
        Assert.DoesNotContain(Directory.GetFiles(_directory), f => f.Contains(".corrupt-", StringComparison.Ordinal));
        Assert.Equal(future, await File.ReadAllTextAsync(store.VaultPath));

        // Writing would replace a document this build cannot read, which is how a downgrade would silently
        // destroy credentials saved by a newer install.
        await Assert.ThrowsAsync<InvalidOperationException>(
            () => store.SaveAsync(new ServerCredential { ServerId = "srv", Password = "p" }));
        Assert.Equal(VaultStatus.Unavailable, store.LastStatus);
        Assert.Equal(future, await File.ReadAllTextAsync(store.VaultPath));

        await Assert.ThrowsAsync<InvalidOperationException>(() => store.DeleteAsync("srv"));
        Assert.Equal(future, await File.ReadAllTextAsync(store.VaultPath));
    }

    [Fact]
    public async Task A_vault_from_another_installation_is_foreign_user()
    {
        var origin = new DpapiCredentialStore(_directory);
        await origin.SaveAsync(new ServerCredential { ServerId = "srv", Password = "secret" });

        // Same Windows account, different installation: the salt in the second directory is not the one that
        // protected the blob, which is exactly what a vault copied between machines looks like.
        string otherDirectory = Path.Combine(_directory, "other-installation");
        Directory.CreateDirectory(otherDirectory);
        var foreign = new DpapiCredentialStore(otherDirectory);
        File.Copy(origin.VaultPath, foreign.VaultPath);

        VaultLoadResult result = await foreign.LoadAsync("srv");

        Assert.Equal(VaultStatus.ForeignUser, result.Status);
        Assert.Null(result.Credential);
        Assert.Contains("无法恢复", result.Detail!);

        // Not quarantined: the bytes are still the user's credentials and this build has no basis to judge them.
        Assert.True(File.Exists(foreign.VaultPath));
    }

    [Fact]
    public async Task Wrong_entropy_is_foreign_user_and_the_salt_is_not_regenerated()
    {
        var store = new DpapiCredentialStore(_directory);
        await store.SaveAsync(new ServerCredential { ServerId = "srv", Password = "secret" });

        string saltPath = Assert.Single(
            Directory.GetFiles(_directory),
            f => !string.Equals(f, store.VaultPath, StringComparison.OrdinalIgnoreCase));
        byte[] wrongSalt = RandomNumberGenerator.GetBytes(32);
        await File.WriteAllBytesAsync(saltPath, wrongSalt);
        byte[] vaultBefore = await File.ReadAllBytesAsync(store.VaultPath);

        VaultLoadResult result = await store.LoadAsync("srv");

        Assert.Equal(VaultStatus.ForeignUser, result.Status);
        Assert.Null(result.Credential);

        // Re-keying the installation would strand every credential already stored in it, so a failed read must
        // leave both files exactly as it found them.
        Assert.Equal(wrongSalt, await File.ReadAllBytesAsync(saltPath));
        Assert.Equal(vaultBefore, await File.ReadAllBytesAsync(store.VaultPath));
    }

    [Fact]
    public async Task A_vault_this_account_cannot_read_can_still_be_listed_and_forgotten()
    {
        var store = new DpapiCredentialStore(_directory);
        await store.SaveAsync(new ServerCredential { ServerId = "a", Password = "pa" });
        await store.SaveAsync(new ServerCredential { ServerId = "b", Password = "pb" });

        string saltPath = Assert.Single(
            Directory.GetFiles(_directory),
            f => !string.Equals(f, store.VaultPath, StringComparison.OrdinalIgnoreCase));
        await File.WriteAllBytesAsync(saltPath, RandomNumberGenerator.GetBytes(32));

        // Listing and deleting read identifiers only, so a vault the user cannot decrypt is still manageable
        // from the settings page instead of becoming unreachable rubbish.
        Assert.Equal(new[] { "a", "b" }, await store.ListServerIdsAsync());
        await store.DeleteAsync("a");
        Assert.Equal(VaultStatus.Ok, store.LastStatus);
        Assert.Equal(new[] { "b" }, await store.ListServerIdsAsync());
    }

    [Fact]
    public async Task Vault_problems_are_raised_but_absence_is_not()
    {
        var store = new DpapiCredentialStore(_directory);
        var reported = new List<VaultLoadResult>();
        store.VaultProblem += (_, e) => reported.Add(e);

        await store.LoadAsync("srv");
        Assert.Empty(reported);

        await File.WriteAllTextAsync(store.VaultPath, "not a vault");
        await store.LoadAsync("srv");
        Assert.Single(reported);
        Assert.Equal(VaultStatus.Corrupt, reported[0].Status);
    }

    [Fact]
    public async Task A_throwing_subscriber_cannot_break_credential_loading()
    {
        var store = new DpapiCredentialStore(_directory);
        store.VaultProblem += (_, _) => throw new InvalidOperationException("subscriber bug");
        await File.WriteAllTextAsync(store.VaultPath, "not a vault");

        VaultLoadResult result = await store.LoadAsync("srv");

        Assert.Equal(VaultStatus.Corrupt, result.Status);
    }

    [Fact]
    public async Task Concurrent_saves_preserve_every_entry()
    {
        const int count = 24;
        var store = new DpapiCredentialStore(_directory);

        await Task.WhenAll(Enumerable.Range(0, count).Select(i =>
            store.SaveAsync(new ServerCredential { ServerId = "s" + i, Password = "p" + i })));

        IReadOnlyList<string> ids = await store.ListServerIdsAsync();
        Assert.Equal(count, ids.Count);
        for (int i = 0; i < count; i++)
        {
            VaultLoadResult result = await store.LoadAsync("s" + i);
            Assert.Equal(VaultStatus.Ok, result.Status);
            Assert.Equal("p" + i, result.Credential!.Password);
        }
    }

    [Fact]
    public async Task Concurrent_saves_and_loads_never_observe_a_half_written_vault()
    {
        const int count = 12;
        var store = new DpapiCredentialStore(_directory);

        Task[] writers = Enumerable.Range(0, count)
            .Select(i => store.SaveAsync(new ServerCredential { ServerId = "c" + i, Token = "t" + i }))
            .ToArray();
        Task<VaultLoadResult>[] readers = Enumerable.Range(0, count)
            .Select(i => store.LoadAsync("c" + i))
            .ToArray();

        await Task.WhenAll(writers.Concat<Task>(readers));

        foreach (Task<VaultLoadResult> reader in readers)
        {
            VaultLoadResult result = await reader;
            // A reader may arrive before its writer, but a torn file or a lost entry would show up as Corrupt.
            Assert.True(
                result.Status is VaultStatus.Ok or VaultStatus.Absent,
                "unexpected status " + result.Status + " during concurrent access: " + result.Detail);
        }

        for (int i = 0; i < count; i++)
        {
            Assert.Equal(VaultStatus.Ok, (await store.LoadAsync("c" + i)).Status);
        }
    }

    [Fact]
    public async Task Clear_forgets_everything_and_is_idempotent()
    {
        var store = new DpapiCredentialStore(_directory);
        await store.SaveAsync(new ServerCredential { ServerId = "a", Password = "pa" });
        await store.SaveAsync(new ServerCredential { ServerId = "b", Password = "pb" });

        string saltPath = Assert.Single(
            Directory.GetFiles(_directory),
            f => !string.Equals(f, store.VaultPath, StringComparison.OrdinalIgnoreCase));
        byte[] salt = await File.ReadAllBytesAsync(saltPath);

        await store.ClearAsync();
        Assert.False(File.Exists(store.VaultPath));
        Assert.Equal(VaultStatus.Ok, store.LastStatus);
        Assert.Empty(await store.ListServerIdsAsync());
        Assert.Equal(VaultStatus.Absent, (await store.LoadAsync("a")).Status);

        // The salt is per installation rather than per vault, so it survives the clear.
        Assert.Equal(salt, await File.ReadAllBytesAsync(saltPath));

        await store.ClearAsync();
        Assert.Equal(VaultStatus.Absent, store.LastStatus);

        await store.SaveAsync(new ServerCredential { ServerId = "a", Password = "again" });
        Assert.Equal(VaultStatus.Ok, (await store.LoadAsync("a")).Status);
    }

    [Fact]
    public async Task Clear_removes_a_vault_of_any_format_version()
    {
        var store = new DpapiCredentialStore(_directory);
        await File.WriteAllTextAsync(store.VaultPath, """{"version":99,"entries":[]}""");

        await store.ClearAsync();

        Assert.False(File.Exists(store.VaultPath));
        Assert.Equal(VaultStatus.Ok, store.LastStatus);
    }

    [Fact]
    public async Task Loading_a_server_that_was_never_configured_touches_nothing()
    {
        var store = new DpapiCredentialStore(_directory);

        Assert.Equal(VaultStatus.Absent, (await store.LoadAsync("nope")).Status);

        // Notably no entropy salt: a read must not create key material, or an installation that never logged in
        // would leave credential-related files behind.
        Assert.Empty(Directory.GetFiles(_directory));
    }

    [Fact]
    public async Task Saving_creates_the_vault_directory()
    {
        string nested = Path.Combine(_directory, "nested", "credentials");
        var store = new DpapiCredentialStore(nested);
        Assert.False(Directory.Exists(nested));

        await store.SaveAsync(new ServerCredential { ServerId = "srv", Password = "p" });

        Assert.True(File.Exists(store.VaultPath));
        Assert.Equal(VaultStatus.Ok, (await store.LoadAsync("srv")).Status);
    }

    [Fact]
    public async Task A_vault_path_that_cannot_be_read_is_unavailable_rather_than_an_exception()
    {
        var store = new DpapiCredentialStore(_directory);
        Directory.CreateDirectory(store.VaultPath);

        VaultLoadResult result = await store.LoadAsync("srv");

        Assert.Equal(VaultStatus.Unavailable, result.Status);
        Assert.Null(result.Credential);
        Assert.NotNull(result.Detail);
        Assert.Equal(VaultStatus.Unavailable, store.LastStatus);
    }

    [Fact]
    public async Task Credentials_without_an_identifier_are_refused()
    {
        var store = new DpapiCredentialStore(_directory);

        await Assert.ThrowsAsync<ArgumentNullException>(() => store.SaveAsync(null!));
        await Assert.ThrowsAsync<ArgumentException>(() => store.SaveAsync(new ServerCredential { ServerId = "   " }));
        await Assert.ThrowsAsync<ArgumentException>(() => store.LoadAsync(""));
        await Assert.ThrowsAsync<ArgumentException>(() => store.DeleteAsync(" "));

        Assert.Empty(Directory.GetFiles(_directory));
    }

    [Fact]
    public void ToString_keeps_the_identifier_and_drops_the_secrets()
    {
        var credential = new ServerCredential
        {
            ServerId = "srv",
            UserName = "alice",
            Password = "hunter2",
            Token = "tok-abc",
            UserId = "u1",
            RemoteServerId = "r1",
        };

        string text = credential.ToString();

        Assert.DoesNotContain("hunter2", text);
        Assert.DoesNotContain("tok-abc", text);
        Assert.Contains("srv", text);
        Assert.Contains("(present)", text);
    }

    [Fact]
    public void The_default_directory_lives_under_local_app_data_and_construction_writes_nothing()
    {
        string expected = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "MediaHub",
            "credentials",
            "credentials.vault.json");

        var store = new DpapiCredentialStore();

        Assert.Equal(expected, store.VaultPath);
        Assert.Equal(VaultStatus.Absent, store.LastStatus);
    }

    [Fact]
    public async Task LastStatus_reports_the_most_recent_operation()
    {
        var store = new DpapiCredentialStore(_directory);
        Assert.Equal(VaultStatus.Absent, store.LastStatus);

        await store.SaveAsync(new ServerCredential { ServerId = "srv", Password = "p" });
        Assert.Equal(VaultStatus.Ok, store.LastStatus);

        await store.LoadAsync("srv");
        Assert.Equal(VaultStatus.Ok, store.LastStatus);

        await File.WriteAllTextAsync(store.VaultPath, "not a vault");
        await store.LoadAsync("srv");
        Assert.Equal(VaultStatus.Corrupt, store.LastStatus);
    }
}

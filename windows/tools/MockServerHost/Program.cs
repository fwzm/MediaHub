using System.Text.Json;
using MediaHub.Domain;
using MediaHub.Net.Security;
using MediaHub.TestSupport;

namespace MediaHub.Tools.MockServerHost;

/// <summary>
/// Runs the mock Emby/Jellyfin server outside the test runner so the desktop client can be driven
/// against it.
/// </summary>
/// <remarks>
/// <para>
/// The automated tests prove that the provider code speaks the protocol correctly. They cannot prove
/// that the <i>application</i> can log in, list libraries and start playback, because that path runs
/// through WinUI. This host exists so the real client can be pointed at a real socket and the whole
/// user-visible flow observed.
/// </para>
/// <para>
/// With <c>--seed</c> it also writes the client's settings and DPAPI credential vault for the mock
/// server, which removes the need to type a password into the UI from an automation script. The
/// credential is written through the same <see cref="DpapiCredentialStore"/> the application uses, so
/// the seed exercises the real storage format rather than a shortcut around it.
/// </para>
/// <para>This is a development tool. It is not part of the shipped client.</para>
/// </remarks>
internal static class Program
{
    private static async Task<int> Main(string[] args)
    {
        ServerType type = args.Contains("--jellyfin", StringComparer.OrdinalIgnoreCase) ? ServerType.Jellyfin : ServerType.Emby;
        bool seed = args.Contains("--seed", StringComparer.OrdinalIgnoreCase);
        int itemCount = ReadInt(args, "--items", 40);

        // Serve the committed fixture rather than the synthetic placeholder bytes, so the desktop
        // client's playback path is exercised against a file the engine can actually demux. With
        // placeholder bytes the engine fails with "unrecognized file format", which proves the relay
        // delivered something but says nothing about whether playback works.
        byte[]? media = null;
        string? fixture = FindFixture();
        if (fixture is not null) media = await File.ReadAllBytesAsync(fixture);
        Console.WriteLine("media bytes : " + (media is null ? "placeholder (fixture not found)" : fixture + " (" + media.Length + " bytes)"));

        var options = new MockServerOptions
        {
            ServerId = "gui-test-server",
            MediaBytes = media,
            ServerName = "GUI Test Server",
            UserName = "tester",
            Password = "secret",
            Token = "gui-test-token",
            UserId = "user-1",
            ItemCount = itemCount,
        };

        await using MockMediaServer server = await MockMediaServer.StartAsync(type, options);
        string serverId = type.ToString().ToLowerInvariant() + ":" + options.ServerId;

        Console.WriteLine("mock server : " + type);
        Console.WriteLine("base url    : " + server.BaseUri);
        Console.WriteLine("api root    : " + server.ApiRootPath);
        Console.WriteLine("server id   : " + serverId);
        Console.WriteLine("user/pass   : " + options.UserName + " / " + options.Password);
        Console.WriteLine("items       : " + itemCount);

        if (seed)
        {
            SeedSettings(serverId, type, server.BaseUri.ToString(), options);
            await SeedVaultAsync(serverId, options);
            Console.WriteLine("seeded      : settings.json + DPAPI vault for this server");
        }

        Console.WriteLine();
        Console.WriteLine("Ready. Start MediaHub, then open 媒体源 → this server should show 已连接.");
        Console.WriteLine("Press Ctrl+C to stop.");
        Console.WriteLine();

        // Live request log. It answers the question a security review actually asks: which process
        // contacted the server, and what did it carry? A request that arrives with no session header
        // on the media route is the observable signature of the relay doing its job.
        _ = Task.Run(async () =>
        {
            int printed = 0;
            while (true)
            {
                await Task.Delay(400);
                IReadOnlyList<RecordedRequest> all = server.Requests;
                for (int i = printed; i < all.Count; i++)
                {
                    RecordedRequest r = all[i];
                    string token = r.Header("X-Emby-Token") ?? r.Header("Authorization") ?? "(none)";
                    bool carriedSession = !token.Contains("(none)", StringComparison.Ordinal);
                    string range = r.Header("Range") ?? "-";
                    Console.WriteLine(DateTime.Now.ToString("HH:mm:ss.fff")
                        + "  " + r.Method + " " + r.PathAndQuery
                        + "  session=" + (carriedSession ? "yes" : "NO")
                        + "  range=" + range);
                }
                printed = all.Count;
            }
        });

        var stop = new TaskCompletionSource();
        Console.CancelKeyPress += (_, e) => { e.Cancel = true; stop.TrySetResult(); };
        await stop.Task;
        return 0;
    }

    /// <summary>
    /// Locates the committed media fixture by walking up from the build output.
    /// </summary>
    /// <remarks>
    /// A hard-coded relative path silently degrades to the placeholder whenever the output layout
    /// changes, which turns a real playback check into a meaningless one without failing.
    /// </remarks>
    private static string? FindFixture()
    {
        var directory = new DirectoryInfo(AppContext.BaseDirectory);
        for (int depth = 0; depth < 10 && directory is not null; depth++)
        {
            string candidate = Path.Combine(directory.FullName, "tests", "assets", "sample-av.mp4");
            if (File.Exists(candidate)) return candidate;
            directory = directory.Parent;
        }
        return null;
    }

    private static int ReadInt(string[] args, string name, int fallback)
    {
        int index = Array.FindIndex(args, a => a.Equals(name, StringComparison.OrdinalIgnoreCase));
        return index >= 0 && index + 1 < args.Length && int.TryParse(args[index + 1], out int value) ? value : fallback;
    }

    private static string SettingsPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MediaHub", "settings.json");

    private static void SeedSettings(string serverId, ServerType type, string url, MockServerOptions options)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(SettingsPath)!);

        // Only the non-secret part is written here; the password and token go to the vault below.
        var settings = new Dictionary<string, object?>();
        if (File.Exists(SettingsPath))
        {
            using JsonDocument existing = JsonDocument.Parse(File.ReadAllText(SettingsPath));
            foreach (JsonProperty property in existing.RootElement.EnumerateObject())
            {
                settings[property.Name] = JsonSerializer.Deserialize<object>(property.Value.GetRawText());
            }
        }

        var servers = new List<Dictionary<string, object?>>();
        if (settings.TryGetValue("Servers", out object? raw) && raw is JsonElement element && element.ValueKind == JsonValueKind.Array)
        {
            foreach (JsonElement item in element.EnumerateArray())
            {
                var entry = JsonSerializer.Deserialize<Dictionary<string, object?>>(item.GetRawText());
                if (entry is not null) servers.Add(entry);
            }
        }
        servers.RemoveAll(s => s.TryGetValue("ServerId", out object? id) && (id?.ToString() == serverId));

        servers.Add(new Dictionary<string, object?>
        {
            ["ServerId"] = serverId,
            ["Type"] = type.ToString(),
            ["DisplayName"] = options.ServerName,
            ["Url"] = url,
            ["UserName"] = options.UserName,
            ["RemoteServerId"] = options.ServerId,
        });

        settings["Servers"] = servers;
        File.WriteAllText(SettingsPath, JsonSerializer.Serialize(settings, new JsonSerializerOptions { WriteIndented = true }));
    }

    private static async Task SeedVaultAsync(string serverId, MockServerOptions options)
    {
        var vault = new DpapiCredentialStore();
        await vault.SaveAsync(new ServerCredential
        {
            ServerId = serverId,
            UserName = options.UserName,
            Password = options.Password,
            Token = options.Token,
            UserId = options.UserId,
            RemoteServerId = options.ServerId,
            SavedAtUtc = DateTimeOffset.UtcNow,
        });
        VaultLoadResult check = await vault.LoadAsync(serverId);
        Console.WriteLine("vault check : " + check.Status
            + (check.Credential is null ? "" : " (user=" + check.Credential.UserName + ", token set=" + (check.Credential.Token is { Length: > 0 }) + ")"));
    }
}

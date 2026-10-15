using System.Text.Json;
using MediaHub.Domain;
using MediaHub.Net.Security;

namespace MediaHub.Tools.SeedSource;

/// <summary>
/// Registers a media source in the client's own configuration and credential vault.
/// </summary>
/// <remarks>
/// <para>
/// Exists so an end-to-end run can start from a connected state without typing a password into a
/// dialog from an automation script. It writes through the same settings file and the same
/// <see cref="DpapiCredentialStore"/> the application uses, so the client still performs a real
/// login against the real server on startup - the only thing skipped is the keystrokes.
/// </para>
/// <para>Development tool. Not part of the shipped client.</para>
/// </remarks>
internal static class Program
{
    private static async Task<int> Main(string[] args)
    {
        if (args.Length < 5)
        {
            Console.Error.WriteLine("usage: SeedSource <type> <url> <user> <password> <remoteServerId> [token] [userId]");
            return 2;
        }

        string type = args[0];
        string url = args[1];
        string user = args[2];
        string password = args[3];
        string remoteServerId = args[4];
        string? token = args.Length > 5 ? args[5] : null;
        string? userId = args.Length > 6 ? args[6] : null;

        ServerType serverType = type.ToLowerInvariant() switch
        {
            "jellyfin" => ServerType.Jellyfin,
            "emby" => ServerType.Emby,
            "webdav" => ServerType.WebDav,
            _ => throw new ArgumentException("unknown server type: " + type),
        };

        string serverId = serverType.ToString().ToLowerInvariant() + ":" + remoteServerId;
        string settingsPath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MediaHub", "settings.json");
        Directory.CreateDirectory(Path.GetDirectoryName(settingsPath)!);

        // Preserve everything the user already configured; only the Servers array is replaced.
        var settings = new Dictionary<string, object?>();
        if (File.Exists(settingsPath))
        {
            using JsonDocument existing = JsonDocument.Parse(File.ReadAllText(settingsPath));
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

        servers.RemoveAll(s => s.TryGetValue("ServerId", out object? id) && id?.ToString() == serverId);
        servers.Add(new Dictionary<string, object?>
        {
            ["ServerId"] = serverId,
            ["Type"] = serverType.ToString(),
            ["DisplayName"] = type + " test server",
            ["Url"] = url,
            ["UserName"] = user,
            ["RemoteServerId"] = remoteServerId,
        });
        settings["Servers"] = servers;

        File.WriteAllText(settingsPath, JsonSerializer.Serialize(settings, new JsonSerializerOptions { WriteIndented = true }));

        var vault = new DpapiCredentialStore();
        await vault.SaveAsync(new ServerCredential
        {
            ServerId = serverId,
            UserName = user,
            Password = password,
            Token = token,
            UserId = userId,
            RemoteServerId = remoteServerId,
            SavedAtUtc = DateTimeOffset.UtcNow,
        });

        VaultLoadResult check = await vault.LoadAsync(serverId);
        Console.WriteLine("serverId   : " + serverId);
        Console.WriteLine("settings   : " + settingsPath);
        Console.WriteLine("vault      : " + check.Status + " user=" + check.Credential?.UserName + " token=" + (check.Credential?.Token is { Length: > 0 }));
        return 0;
    }
}

using System.Globalization;

namespace MediaHub.SyncCli;

/// <summary>
/// Hand-written argument parser. No NuGet, no <c>System.CommandLine</c>: the contract is small
/// enough that the option table below *is* the documentation, and the tests can pin every code.
/// </summary>
/// <remarks>
/// <para>
/// Accepted shapes are <c>--name value</c> and <c>--name=value</c>. A flag (<c>--apply</c>,
/// <c>--json</c>) never takes a value, not even an empty one: silently ignoring <c>--apply=false</c>
/// would turn a preview into a write. Every rejection carries a stable code so callers never have to
/// match on prose.
/// </para>
/// <para>
/// <c>baseline</c>, <c>pair</c> and <c>transfer</c> are groups: the second token is the verb, and it may not be an
/// option (<c>missing-subcommand:baseline</c>). Their invocation is labelled <c>baseline-show</c>,
/// <c>pair-forget</c> and so on, so an option rejected for a group command still yields one flat
/// printable code (<c>option-not-allowed-for-command:--apply:pair-link</c>).
/// </para>
/// </remarks>
public static class CliParser
{
    /// <summary>Usage text printed for <c>--help</c> and on a usage error.</summary>
    public const string UsageText =
        "MediaHub.SyncCli <command> [options]\n" +
        "\n" +
        "Envelope commands:\n" +
        "  export       --root <dir> [--out <file>] [--passphrase <pw>] [--secrets <json>]\n" +
        "               [--deletions <json>] [--device <id>]\n" +
        "  import       --root <dir> --in <file> [--passphrase <pw>] [--apply] [--baseline <file>]\n" +
        "               [--device <id>]                       (preview unless --apply is given)\n" +
        "  plan         --root <dir> --in <file> [--baseline <file>] [--device <id>]\n" +
        "  secret-check --root <dir> --id <syncSourceId> --expect <base64 plaintext>\n" +
        "\n" +
        "Baseline commands (local bookkeeping, no network):\n" +
        "  baseline show    --root <dir>\n" +
        "  baseline forget  --root <dir> --device <id> [--source <syncSourceId>] [--apply]\n" +
        "                                                      (preview unless --apply is given)\n" +
        "\n" +
        "Pairing commands (offline: this CLI never listens on a socket):\n" +
        "  pair link        [--device <id>]\n" +
        "  pair verify      --link <mediahub://pair?...>\n" +
        "  pair list        --root <dir>\n" +
        "  pair forget      --root <dir> --device <id> [--apply]\n" +
        "                                                      (preview unless --apply is given)\n" +
        "\n" +
        "Transfer commands (loopback only: 127.0.0.1, ::1 or localhost, never a LAN address):\n" +
        "  transfer serve     --root <dir> --device <id> [--port <n>] [--apply] [--once]\n" +
        "                                                      (preview unless --apply is given;\n" +
        "                                                       serves one connection, then exits)\n" +
        "  transfer send      --to 127.0.0.1:<port> --root <dir> --device <id>\n" +
        "  transfer roundtrip --root <dir> --device-a <id> --device-b <id> [--apply]\n" +
        "                                                      (two devices under <root>/a and <root>/b)\n" +
        "\n" +
        "Global:\n" +
        "  --json       stdout carries exactly one JSON document; progress goes to stderr\n" +
        "  --help, -h   this text\n" +
        "\n" +
        "Exit codes: 0 success, 1 usage, 2 input problem, 3 needs approval, 4 archive/credential error\n";

    private static readonly string[] ExportValueOptions = ["--root", "--out", "--passphrase", "--secrets", "--deletions", "--device"];
    private static readonly string[] ImportValueOptions = ["--root", "--in", "--passphrase", "--baseline", "--device"];
    private static readonly string[] PlanValueOptions = ["--root", "--in", "--baseline", "--device"];
    private static readonly string[] SecretCheckValueOptions = ["--root", "--id", "--expect"];
    private static readonly string[] BaselineShowValueOptions = ["--root"];
    private static readonly string[] BaselineForgetValueOptions = ["--root", "--device", "--source"];
    private static readonly string[] PairLinkValueOptions = ["--device"];
    private static readonly string[] PairVerifyValueOptions = ["--link"];
    private static readonly string[] PairListValueOptions = ["--root"];
    private static readonly string[] PairForgetValueOptions = ["--root", "--device"];
    private static readonly string[] TransferServeValueOptions = ["--root", "--device", "--port"];
    private static readonly string[] TransferSendValueOptions = ["--to", "--root", "--device"];
    private static readonly string[] TransferRoundtripValueOptions = ["--root", "--device-a", "--device-b"];

    /// <summary>Parses a raw argument vector.</summary>
    public static CliParseResult Parse(IReadOnlyList<string>? args)
    {
        if (args is null || args.Count == 0)
        {
            return Error("missing-command");
        }

        string first = args[0];
        if (first is "--help" or "-h" or "help")
        {
            return new CliParseResult { IsHelp = true };
        }

        if (first is "--version" or "version")
        {
            return new CliParseResult { IsVersion = true };
        }

        if (first is not ("export" or "import" or "plan" or "secret-check" or "baseline" or "pair" or "transfer"))
        {
            return Error("unknown-command:" + first);
        }

        // baseline/pair stay None until the verb slot is read, because the verb selects the options.
        SyncCliCommand command = first switch
        {
            "export" => SyncCliCommand.Export,
            "import" => SyncCliCommand.Import,
            "plan" => SyncCliCommand.Plan,
            "secret-check" => SyncCliCommand.SecretCheck,
            _ => SyncCliCommand.None,
        };

        // The group verbs are read before the option loop, because the verb decides which options
        // exist. --help in the verb slot is still a help request, not a missing verb.
        int start = 1;
        string commandName = first;
        if (first is "baseline" or "pair" or "transfer")
        {
            if (args.Count > 1 && args[1] is "--help" or "-h")
            {
                return new CliParseResult { IsHelp = true };
            }

            if (args.Count < 2 || args[1].StartsWith("--", StringComparison.Ordinal))
            {
                return Error("missing-subcommand:" + first);
            }

            string sub = args[1];
            command = (first, sub) switch
            {
                ("baseline", "show") => SyncCliCommand.BaselineShow,
                ("baseline", "forget") => SyncCliCommand.BaselineForget,
                ("pair", "link") => SyncCliCommand.PairLink,
                ("pair", "verify") => SyncCliCommand.PairVerify,
                ("pair", "list") => SyncCliCommand.PairList,
                ("pair", "forget") => SyncCliCommand.PairForget,
                ("transfer", "serve") => SyncCliCommand.TransferServe,
                ("transfer", "send") => SyncCliCommand.TransferSend,
                ("transfer", "roundtrip") => SyncCliCommand.TransferRoundtrip,
                _ => SyncCliCommand.None,
            };

            if (command == SyncCliCommand.None)
            {
                return Error("unknown-subcommand:" + first + ":" + sub);
            }

            commandName = first + "-" + sub;
            start = 2;
        }

        string[] allowed = command switch
        {
            SyncCliCommand.Export => ExportValueOptions,
            SyncCliCommand.Import => ImportValueOptions,
            SyncCliCommand.Plan => PlanValueOptions,
            SyncCliCommand.SecretCheck => SecretCheckValueOptions,
            SyncCliCommand.BaselineShow => BaselineShowValueOptions,
            SyncCliCommand.BaselineForget => BaselineForgetValueOptions,
            SyncCliCommand.PairLink => PairLinkValueOptions,
            SyncCliCommand.PairVerify => PairVerifyValueOptions,
            SyncCliCommand.PairList => PairListValueOptions,
            SyncCliCommand.PairForget => PairForgetValueOptions,
            SyncCliCommand.TransferServe => TransferServeValueOptions,
            SyncCliCommand.TransferSend => TransferSendValueOptions,
            _ => TransferRoundtripValueOptions,
        };

        Dictionary<string, string> values = new(StringComparer.Ordinal);
        bool json = false;
        bool apply = false;
        bool once = false;

        for (int i = start; i < args.Count; i++)
        {
            string token = args[i];
            if (token is "--help" or "-h")
            {
                return new CliParseResult { IsHelp = true };
            }

            if (!token.StartsWith("--", StringComparison.Ordinal))
            {
                return Error("unexpected-argument:" + token);
            }

            string name = token;
            string? inlineValue = null;
            int equals = token.IndexOf('=', StringComparison.Ordinal);
            if (equals > 0)
            {
                name = token[..equals];
                inlineValue = token[(equals + 1)..];
            }

            if (name == "--json")
            {
                if (inlineValue is not null)
                {
                    return Error("flag-takes-no-value:" + name);
                }

                json = true;
                continue;
            }

            if (name == "--apply")
            {
                if (inlineValue is not null)
                {
                    return Error("flag-takes-no-value:" + name);
                }

                if (command is not (SyncCliCommand.Import or SyncCliCommand.BaselineForget or
                    SyncCliCommand.PairForget or SyncCliCommand.TransferServe or SyncCliCommand.TransferRoundtrip))
                {
                    return Error("option-not-allowed-for-command:" + name + ":" + commandName);
                }

                apply = true;
                continue;
            }

            if (name == "--once")
            {
                if (inlineValue is not null)
                {
                    return Error("flag-takes-no-value:" + name);
                }

                // Only serve has a "how many connections" question, and its answer is always one.
                if (command != SyncCliCommand.TransferServe)
                {
                    return Error("option-not-allowed-for-command:" + name + ":" + commandName);
                }

                once = true;
                continue;
            }

            if (Array.IndexOf(allowed, name) < 0)
            {
                return Error(IsKnownOption(name)
                    ? "option-not-allowed-for-command:" + name + ":" + commandName
                    : "unknown-option:" + name);
            }

            string? value = inlineValue;
            if (value is null)
            {
                if (i + 1 >= args.Count)
                {
                    return Error("missing-value:" + name);
                }

                value = args[++i];
            }

            if (string.IsNullOrWhiteSpace(value))
            {
                return Error("empty-value:" + name);
            }

            if (values.ContainsKey(name))
            {
                return Error("duplicate-option:" + name);
            }

            values[name] = value;
        }

        string root = values.GetValueOrDefault("--root") ?? "";
        if (RequiresRoot(command) && root.Length == 0)
        {
            return Error("missing-required-option:--root");
        }

        string? inPath = values.GetValueOrDefault("--in");
        if (command is SyncCliCommand.Import or SyncCliCommand.Plan && inPath is null)
        {
            return Error("missing-required-option:--in");
        }

        string? syncSourceId = values.GetValueOrDefault("--id");
        string? expect = values.GetValueOrDefault("--expect");
        if (command == SyncCliCommand.SecretCheck)
        {
            if (syncSourceId is null)
            {
                return Error("missing-required-option:--id");
            }

            if (expect is null)
            {
                return Error("missing-required-option:--expect");
            }
        }

        string? deviceId = values.GetValueOrDefault("--device");
        if (command is SyncCliCommand.BaselineForget or SyncCliCommand.PairForget && deviceId is null)
        {
            return Error("missing-required-option:--device");
        }

        string? link = values.GetValueOrDefault("--link");
        if (command == SyncCliCommand.PairVerify && link is null)
        {
            return Error("missing-required-option:--link");
        }

        string? target = values.GetValueOrDefault("--to");
        string? deviceA = values.GetValueOrDefault("--device-a");
        string? deviceB = values.GetValueOrDefault("--device-b");
        switch (command)
        {
            case SyncCliCommand.TransferServe when deviceId is null:
                return Error("missing-required-option:--device");
            case SyncCliCommand.TransferSend when target is null:
                return Error("missing-required-option:--to");
            case SyncCliCommand.TransferSend when deviceId is null:
                return Error("missing-required-option:--device");
            case SyncCliCommand.TransferRoundtrip when deviceA is null:
                return Error("missing-required-option:--device-a");
            case SyncCliCommand.TransferRoundtrip when deviceB is null:
                return Error("missing-required-option:--device-b");
        }

        int? port = null;
        string? portText = values.GetValueOrDefault("--port");
        if (portText is not null)
        {
            // A port is 16 bits and a host order, not a hex string: reject anything else here so the
            // runner never has to guess and socket code never sees an out-of-range value.
            if (!int.TryParse(portText, NumberStyles.None, CultureInfo.InvariantCulture, out int parsedPort) ||
                parsedPort is < 0 or > 65535)
            {
                return Error("invalid-port:" + portText);
            }

            port = parsedPort;
        }

        string? passphrase = values.GetValueOrDefault("--passphrase");
        string? secretsJson = values.GetValueOrDefault("--secrets");
        if (passphrase is not null && secretsJson is not null)
        {
            // Archive red line: an envelope that carries credentials is never encrypted into a file
            // that can be copied between machines (ADR-S005 / ADR-S006 D5).
            return Error("secrets-and-passphrase-are-mutually-exclusive");
        }

        return new CliParseResult
        {
            Invocation = new CliInvocation
            {
                Command = command,
                Root = root,
                OutPath = values.GetValueOrDefault("--out"),
                InPath = inPath,
                BaselinePath = values.GetValueOrDefault("--baseline"),
                Passphrase = passphrase,
                SecretsJson = secretsJson,
                DeletionsJson = values.GetValueOrDefault("--deletions"),
                DeviceId = deviceId,
                SyncSourceId = syncSourceId,
                SourceId = values.GetValueOrDefault("--source"),
                Link = link,
                ExpectPlaintextBase64 = expect,
                Target = target,
                Port = port,
                DeviceA = deviceA,
                DeviceB = deviceB,
                Once = once,
                Apply = apply,
                Json = json,
            },
        };
    }

    /// <summary>Commands whose work happens inside a device directory; <c>pair link/verify</c> need none.</summary>
    private static bool RequiresRoot(SyncCliCommand command) => command is not (
        SyncCliCommand.PairLink or SyncCliCommand.PairVerify or
        SyncCliCommand.TransferServe or SyncCliCommand.TransferSend or SyncCliCommand.TransferRoundtrip);

    private static bool IsKnownOption(string name) => name is
        "--root" or "--out" or "--in" or "--passphrase" or "--secrets" or "--deletions" or
        "--device" or "--baseline" or "--id" or "--source" or "--link" or "--expect" or
        "--to" or "--port" or "--device-a" or "--device-b" or
        "--apply" or "--once" or "--json";

    private static CliParseResult Error(string code) => new() { Error = code };
}

namespace MediaHub.SyncCli;

/// <summary>
/// Hand-written argument parser. No NuGet, no <c>System.CommandLine</c>: the contract is small
/// enough that the option table below *is* the documentation, and the tests can pin every code.
/// </summary>
/// <remarks>
/// Accepted shapes are <c>--name value</c> and <c>--name=value</c>. A flag (<c>--apply</c>,
/// <c>--json</c>) never takes a value, not even an empty one: silently ignoring <c>--apply=false</c>
/// would turn a preview into a write. Every rejection carries a stable code so callers never have to
/// match on prose.
/// </remarks>
public static class CliParser
{
    /// <summary>Usage text printed for <c>--help</c> and on a usage error.</summary>
    public const string UsageText =
        "MediaHub.SyncCli <command> --root <dir> [options]\n" +
        "\n" +
        "Commands:\n" +
        "  export       --root <dir> [--out <file>] [--passphrase <pw>] [--secrets <json>]\n" +
        "               [--deletions <json>] [--device <id>]\n" +
        "  import       --root <dir> --in <file> [--passphrase <pw>] [--apply] [--baseline <file>]\n" +
        "               [--device <id>]                       (preview unless --apply is given)\n" +
        "  plan         --root <dir> --in <file> [--baseline <file>] [--device <id>]\n" +
        "  secret-check --root <dir> --id <syncSourceId> --expect <base64 plaintext>\n" +
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

        SyncCliCommand command = first switch
        {
            "export" => SyncCliCommand.Export,
            "import" => SyncCliCommand.Import,
            "plan" => SyncCliCommand.Plan,
            "secret-check" => SyncCliCommand.SecretCheck,
            _ => SyncCliCommand.None,
        };

        if (command == SyncCliCommand.None)
        {
            return Error("unknown-command:" + first);
        }

        string[] allowed = command switch
        {
            SyncCliCommand.Export => ExportValueOptions,
            SyncCliCommand.Import => ImportValueOptions,
            SyncCliCommand.Plan => PlanValueOptions,
            _ => SecretCheckValueOptions,
        };

        Dictionary<string, string> values = new(StringComparer.Ordinal);
        bool json = false;
        bool apply = false;

        for (int i = 1; i < args.Count; i++)
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

                if (command != SyncCliCommand.Import)
                {
                    return Error("option-not-allowed-for-command:" + name + ":" + first);
                }

                apply = true;
                continue;
            }

            if (Array.IndexOf(allowed, name) < 0)
            {
                return Error(IsKnownOption(name)
                    ? "option-not-allowed-for-command:" + name + ":" + first
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
        if (root.Length == 0)
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
                DeviceId = values.GetValueOrDefault("--device"),
                SyncSourceId = syncSourceId,
                ExpectPlaintextBase64 = expect,
                Apply = apply,
                Json = json,
            },
        };
    }

    private static bool IsKnownOption(string name) => name is
        "--root" or "--out" or "--in" or "--passphrase" or "--secrets" or "--deletions" or
        "--device" or "--baseline" or "--id" or "--expect" or "--apply" or "--json";

    private static CliParseResult Error(string code) => new() { Error = code };
}

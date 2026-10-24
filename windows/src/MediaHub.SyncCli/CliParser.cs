using System.Globalization;
using MediaHub.Sync;

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
        "  --authenticate     transfer only: both ends prove they hold a shared secret (W6-24);\n" +
        "                     off by default, so the bytes stay exactly W6-16's\n" +
        "  --secret-env NAME  with --authenticate: read the secret from environment variable NAME\n" +
        "                     (the value is never an argument and is never printed)\n" +
        "  --tls              transfer only: wrap the frames in TLS 1.3 with an ephemeral self-signed\n" +
        "                     certificate (W6-26); off by default, so the bytes stay exactly W6-16's\n" +
        "  --expect-fingerprint HEX\n" +
        "                     with --tls on 'transfer send': pin the listener's SHA-256 public-key\n" +
        "                     fingerprint (64 hex characters, either case) - 'transfer serve' prints\n" +
        "                     its own as 'certificate fingerprint: ...', and roundtrip pins itself\n" +
        "  --confirm-delivery with 'transfer send': after the last frame, wait for the peer to close the\n" +
        "                     connection and fail with delivery-unconfirmed if it does not (W6-30);\n" +
        "                     off by default, so a send never waits and never adds a byte\n" +
        "  --send-receipt     with 'transfer serve': after the envelope decoded, write one receipt byte\n" +
        "                     (0x04) so the sender knows it was understood (W6-37); off by default\n" +
        "  --await-receipt    with 'transfer send': wait for that byte after the last frame and fail with\n" +
        "                     receipt-missing, receipt-timeout or receipt-invalid when it does not come;\n" +
        "                     off by default, so a send never waits and never reads back\n" +
        "  --budget-ms N      with 'transfer serve': the whole wait for one peer, in milliseconds\n" +
        "                     (100..600000, default 15000); running out is transfer-timeout (W6-50)\n" +
        "  --bind ADDR        with 'transfer serve': the local address to bind (default 127.0.0.1);\n" +
        "                     a non-loopback ADDR still needs --allow-non-loopback (W6-51)\n" +
        "  --allow-non-loopback\n" +
        "                     with 'transfer serve' / 'transfer send': accept one explicitly named\n" +
        "                     non-loopback unicast address - a LAN address literal, never a wildcard,\n" +
        "                     broadcast, multicast or host name. Without it only 127.0.0.1, ::1 and\n" +
        "                     localhost are accepted, exactly as before W6-51, so the option is the\n" +
        "                     consent a firewall prompt would otherwise have to ask for\n" +
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
    private static readonly string[] TransferServeValueOptions =
        ["--root", "--device", "--port", "--budget-ms", "--bind", "--secret-env", "--expect-fingerprint"];
    private static readonly string[] TransferSendValueOptions =
        ["--to", "--root", "--device", "--secret-env", "--expect-fingerprint"];
    private static readonly string[] TransferRoundtripValueOptions =
        ["--root", "--device-a", "--device-b", "--secret-env", "--expect-fingerprint"];

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
        bool authenticate = false;
        bool tls = false;
        bool confirmDelivery = false;
        bool sendReceipt = false;
        bool awaitReceipt = false;
        bool allowExplicitHost = false;

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

            if (name == "--authenticate")
            {
                if (inlineValue is not null)
                {
                    return Error("flag-takes-no-value:" + name);
                }

                // Only the three socket verbs have a peer to authenticate.
                if (command is not (SyncCliCommand.TransferServe or SyncCliCommand.TransferSend or
                    SyncCliCommand.TransferRoundtrip))
                {
                    return Error("option-not-allowed-for-command:" + name + ":" + commandName);
                }

                authenticate = true;
                continue;
            }

            if (name == "--tls")
            {
                if (inlineValue is not null)
                {
                    return Error("flag-takes-no-value:" + name);
                }

                // TLS only makes sense where there is a socket and a peer certificate to pin.
                if (command is not (SyncCliCommand.TransferServe or SyncCliCommand.TransferSend or
                    SyncCliCommand.TransferRoundtrip))
                {
                    return Error("option-not-allowed-for-command:" + name + ":" + commandName);
                }

                tls = true;
                continue;
            }

            if (name == "--confirm-delivery")
            {
                if (inlineValue is not null)
                {
                    return Error("flag-takes-no-value:" + name);
                }

                // Only the dialling half has a peer whose close it could wait for; serve and roundtrip
                // own the listener, so their own close is not evidence of anything.
                if (command != SyncCliCommand.TransferSend)
                {
                    return Error("option-not-allowed-for-command:" + name + ":" + commandName);
                }

                confirmDelivery = true;
                continue;
            }

            if (name == "--send-receipt")
            {
                if (inlineValue is not null)
                {
                    return Error("flag-takes-no-value:" + name);
                }

                // Only the receiving half can answer: a receipt says "I decoded your envelope", and the dialling half
                // has nothing to decode.
                if (command != SyncCliCommand.TransferServe)
                {
                    return Error("option-not-allowed-for-command:" + name + ":" + commandName);
                }

                sendReceipt = true;
                continue;
            }

            if (name == "--await-receipt")
            {
                if (inlineValue is not null)
                {
                    return Error("flag-takes-no-value:" + name);
                }

                // Only the sending half can wait for one; serve and roundtrip write receipts from their own side.
                if (command != SyncCliCommand.TransferSend)
                {
                    return Error("option-not-allowed-for-command:" + name + ":" + commandName);
                }

                awaitReceipt = true;
                continue;
            }

            if (name == "--allow-non-loopback")
            {
                if (inlineValue is not null)
                {
                    return Error("flag-takes-no-value:" + name);
                }

                // Only the two verbs that name exactly one peer address can be given consent for it: serve
                // binds one address and send dials one. Roundtrip invents both of its own addresses, so a
                // consent flag there would have nothing to consent to (W6-51).
                if (command is not (SyncCliCommand.TransferServe or SyncCliCommand.TransferSend))
                {
                    return Error("option-not-allowed-for-command:" + name + ":" + commandName);
                }

                allowExplicitHost = true;
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

        string? secretEnv = values.GetValueOrDefault("--secret-env");
        string? expectFingerprint = values.GetValueOrDefault("--expect-fingerprint");
        if (command is SyncCliCommand.TransferServe or SyncCliCommand.TransferSend or SyncCliCommand.TransferRoundtrip)
        {
            // The secret itself never travels on the command line, so both halves of the pair are
            // required: --authenticate without --secret-env would have nothing to authenticate with,
            // and --secret-env alone names a secret that nothing reads.
            if (authenticate && secretEnv is null)
            {
                return Error("missing-required-option:--secret-env");
            }

            if (!authenticate && secretEnv is not null)
            {
                return Error("missing-required-option:--authenticate");
            }

            if (!tls && expectFingerprint is not null)
            {
                return Error("missing-required-option:--tls");
            }

            // A pin is what makes TLS mean anything on the dialling side: an unpinned session accepts
            // whatever certificate answered, which authenticates nothing. Only transfer send dials a
            // listener it did not just create, so only it needs one: serve is the side presenting the
            // certificate, and roundtrip pins the listener it bound a moment earlier.
            if (tls && command == SyncCliCommand.TransferSend && expectFingerprint is null)
            {
                return Error("missing-required-option:--expect-fingerprint");
            }

            if (expectFingerprint is not null
                && command is SyncCliCommand.TransferServe or SyncCliCommand.TransferRoundtrip)
            {
                return Error("option-not-allowed-for-command:--expect-fingerprint:" + commandName);
            }
        }

        if (expectFingerprint is not null && !PairingFingerprint.TryParse(expectFingerprint, out _))
        {
            // A pin that cannot be parsed would silently never match, so it is refused as a usage
            // error instead of surfacing later as an unexplained handshake failure.
            return Error("invalid-fingerprint:" + expectFingerprint);
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

        int? budgetMilliseconds = null;
        string? budgetText = values.GetValueOrDefault("--budget-ms");
        if (budgetText is not null)
        {
            // The budget is the whole wait for one peer: a value under a tenth of a second would fail
            // before a handshake could finish, and a value over ten minutes would hang a script with no
            // way to tell "slow peer" from "no peer". Same shape as --port: refuse here rather than let
            // the runner clamp or guess.
            if (!int.TryParse(budgetText, NumberStyles.None, CultureInfo.InvariantCulture, out int parsedBudget) ||
                parsedBudget is < 100 or > 600000)
            {
                return Error("invalid-budget-ms:" + budgetText);
            }

            budgetMilliseconds = parsedBudget;
        }

        // --bind is only reachable for 'transfer serve': every other command's value-option list omits it,
        // so the loop above already refused it with option-not-allowed-for-command. Nothing to re-check
        // here, and the runner still substitutes the loopback default when it is absent.
        string? bindAddress = values.GetValueOrDefault("--bind");

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
                BudgetMilliseconds = budgetMilliseconds,
                BindAddress = bindAddress,
                AllowExplicitHost = allowExplicitHost,
                DeviceA = deviceA,
                DeviceB = deviceB,
                Once = once,
                Authenticate = authenticate,
                SecretEnv = secretEnv,
                Tls = tls,
                ExpectFingerprint = expectFingerprint,
                ConfirmDelivery = confirmDelivery,
                SendReceipt = sendReceipt,
                AwaitReceipt = awaitReceipt,
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
        "--to" or "--port" or "--budget-ms" or "--bind" or "--allow-non-loopback" or "--device-a" or "--device-b" or
        "--authenticate" or "--secret-env" or "--tls" or "--expect-fingerprint" or "--confirm-delivery" or
        "--send-receipt" or "--await-receipt" or
        "--apply" or "--once" or "--json";

    private static CliParseResult Error(string code) => new() { Error = code };
}

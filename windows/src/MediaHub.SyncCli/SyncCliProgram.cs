namespace MediaHub.SyncCli;

/// <summary>
/// Entry point shared by the executable and the tests. Nothing here writes to <see cref="Console"/>:
/// the writers are injected so a test can capture stdout and stderr exactly as the process would.
/// </summary>
public static class SyncCliProgram
{
    /// <summary>Reported by <c>--version</c>.</summary>
    public const string Version = "0.1.0";

    /// <summary>Runs one command line and returns the process exit code.</summary>
    public static int Run(
        string[]? args,
        TextWriter stdout,
        TextWriter stderr,
        Func<DateTimeOffset>? clock = null)
    {
        ArgumentNullException.ThrowIfNull(stdout);
        ArgumentNullException.ThrowIfNull(stderr);
        string[] effective = args ?? [];
        CliParseResult parsed = CliParser.Parse(effective);

        if (parsed.IsHelp)
        {
            stdout.WriteLine(CliParser.UsageText);
            return CliExitCode.Success;
        }

        if (parsed.IsVersion)
        {
            stdout.WriteLine("MediaHub.SyncCli " + Version);
            return CliExitCode.Success;
        }

        if (!parsed.Succeeded)
        {
            string code = parsed.Error ?? "invalid-arguments";
            bool json = Array.IndexOf(effective, "--json") >= 0;
            CliOutput.WriteError(stdout, stderr, json, code, "run with --help for usage");
            if (!json)
            {
                stderr.WriteLine(CliParser.UsageText);
            }

            return CliExitCode.UsageError;
        }

        return new SyncCliRunner(stdout, stderr, clock).Execute(parsed.Invocation!);
    }
}

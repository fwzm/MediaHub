namespace MediaHub.EngineProbe;

/// <summary>Command line of <c>MediaHub.EngineProbe.exe</c>.</summary>
internal sealed class ProbeOptions
{
    /// <summary>Seconds of real playback the probe has to observe; the pass floor is one second less.</summary>
    public const double DefaultSeconds = 10;

    /// <summary>
    /// How far below <see cref="Seconds"/> still counts as "the clock advanced". The clock is sampled
    /// from a wall-clock event loop, so a sample landing at 9.998 s for a 10 s target is normal; more
    /// than one second short is not.
    /// </summary>
    public const double ProgressToleranceSeconds = 1.0;

    public required string DllPath { get; init; }

    public required string MediaPath { get; init; }

    public double Seconds { get; init; } = DefaultSeconds;

    public string? JsonPath { get; init; }

    public bool JsonToStdout { get; init; }

    public IReadOnlyList<string> DependencyDirectories { get; init; } = [];

    public string Label { get; init; } = "";

    public double SuccessFloorSeconds => Math.Max(0, Seconds - ProgressToleranceSeconds);

    public static string Usage =>
        """
        usage: MediaHub.EngineProbe --dll <absolute path to libmpv-2.dll> --media <file> [options]

          --dll <path>      absolute path of the libmpv build to probe (required)
          --media <file>    media file to open (required)
          --seconds <n>     seconds of playback to observe (default 10); the pass floor is n-1
          --deps <dirs>     ';'-separated directories holding the DLL's own dependencies; repeatable.
                            Only needed for builds that link FFmpeg/other libraries as shared DLLs.
          --json <path>     write a machine readable report to <path>; bare --json prints it to stdout
          --label <name>    flavour label copied into the report (for example gpl / lgpl)
          --help            print this text

        exit codes: 0 = loaded and progressed, 1 = usage, 2 = DLL could not be loaded,
                    3 = load/decode failed or the clock did not advance, 4 = probe self-timeout.
        """;

    public static bool TryParse(string[] args, out ProbeOptions? options, out string? error)
    {
        options = null;
        error = null;

        string? dll = null;
        string? media = null;
        string? json = null;
        string? label = null;
        bool jsonToStdout = false;
        double seconds = DefaultSeconds;
        List<string> dependencies = [];

        for (int i = 0; i < args.Length; i++)
        {
            string argument = args[i];
            switch (argument)
            {
                case "--help":
                case "-h":
                case "/?":
                    error = "help";
                    return false;

                case "--dll":
                    if (!TryTakeValue(args, ref i, out dll, out error))
                    {
                        return false;
                    }

                    break;

                case "--media":
                    if (!TryTakeValue(args, ref i, out media, out error))
                    {
                        return false;
                    }

                    break;

                case "--label":
                    if (!TryTakeValue(args, ref i, out label, out error))
                    {
                        return false;
                    }

                    break;

                case "--seconds":
                    if (!TryTakeValue(args, ref i, out string? secondsText, out error))
                    {
                        return false;
                    }

                    if (!double.TryParse(secondsText, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out seconds) ||
                        seconds <= 0 ||
                        seconds > 120)
                    {
                        error = "--seconds must be a number in (0, 120]";
                        return false;
                    }

                    break;

                case "--deps":
                    if (!TryTakeValue(args, ref i, out string? dependenciesText, out error))
                    {
                        return false;
                    }

                    foreach (string directory in (dependenciesText ?? "").Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
                    {
                        if (!dependencies.Contains(directory, StringComparer.OrdinalIgnoreCase))
                        {
                            dependencies.Add(directory);
                        }
                    }

                    break;

                case "--json":
                    // A bare --json means "stdout"; a following token that is not another switch is a path.
                    if (i + 1 < args.Length && !args[i + 1].StartsWith("--", StringComparison.Ordinal))
                    {
                        json = args[++i];
                    }
                    else
                    {
                        jsonToStdout = true;
                    }

                    break;

                default:
                    error = "unknown argument: " + argument;
                    return false;
            }
        }

        if (string.IsNullOrWhiteSpace(dll))
        {
            error = "--dll is required";
            return false;
        }

        if (string.IsNullOrWhiteSpace(media))
        {
            error = "--media is required";
            return false;
        }

        if (!Path.IsPathFullyQualified(dll))
        {
            error = "--dll must be an absolute path (the probe never searches the loader path)";
            return false;
        }

        if (!File.Exists(dll))
        {
            error = "--dll does not exist: " + dll;
            return false;
        }

        if (!File.Exists(media))
        {
            error = "--media does not exist: " + media;
            return false;
        }

        options = new ProbeOptions
        {
            DllPath = Path.GetFullPath(dll),
            MediaPath = Path.GetFullPath(media),
            Seconds = seconds,
            JsonPath = json is null ? null : Path.GetFullPath(json),
            JsonToStdout = jsonToStdout,
            DependencyDirectories = dependencies,
            Label = string.IsNullOrWhiteSpace(label) ? Path.GetFileNameWithoutExtension(dll) : label.Trim()
        };
        return true;
    }

    private static bool TryTakeValue(string[] args, ref int index, out string? value, out string? error)
    {
        error = null;
        if (index + 1 >= args.Length || args[index + 1].StartsWith("--", StringComparison.Ordinal))
        {
            value = null;
            error = "missing value for " + args[index];
            return false;
        }

        value = args[++index];
        return true;
    }
}

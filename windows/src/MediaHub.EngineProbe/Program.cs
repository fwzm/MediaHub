using System.Text;

namespace MediaHub.EngineProbe;

/// <summary>
/// Headless libmpv probe used by windows/tools/acceptance/engine/engine-probe.ps1.
/// </summary>
/// <remarks>
/// The point of loading libmpv by absolute path is that the DLL under test is never copied over the
/// one the product ships with: the probe can point at a candidate build without mutating the repo.
/// </remarks>
internal static class Program
{
    private const int ExitSuccess = 0;
    private const int ExitUsage = 1;
    private const int ExitDllLoad = 2;
    private const int ExitSelfTimeout = 4;
    private const double SelfTimeoutSeconds = 120;
    private const double HardExitGraceSeconds = 20;

    private static int Main(string[] args)
    {
        Console.OutputEncoding = Encoding.UTF8;

        if (!ProbeOptions.TryParse(args, out ProbeOptions? options, out string? error) || options is null)
        {
            if (string.Equals(error, "help", StringComparison.Ordinal))
            {
                Console.WriteLine(ProbeOptions.Usage);
                return ExitSuccess;
            }

            Console.Error.WriteLine("参数错误：" + error);
            Console.Error.WriteLine(ProbeOptions.Usage);
            return ExitUsage;
        }

        Deadline deadline = new(TimeSpan.FromSeconds(SelfTimeoutSeconds));
        StartWatchdog(deadline);

        ProbeReport report = ProbeReport.Create(options);
        MpvLibrary? library = null;
        try
        {
            try
            {
                library = MpvLibrary.Load(options.DllPath, options.DependencyDirectories);
            }
            catch (Exception exception) when (exception is not OutOfMemoryException)
            {
                report.LoadMode = "failed";
                report.Fail("无法加载 libmpv：" + exception.Message);
                report.Verdict = "FAIL";
                report.Reason = report.Failure ?? "无法加载 libmpv";
                report.ExitCode = ExitDllLoad;
                Emit(report, options);
                return report.ExitCode;
            }

            report.LoadMode = library.LoadMode;
            new ProbeRunner(options, new MpvApi(library)).Run(report, deadline);
            report.Evaluate();
            Emit(report, options);
            return report.ExitCode;
        }
        finally
        {
            library?.Dispose();
        }
    }

    private static void Emit(ProbeReport report, ProbeOptions options)
    {
        if (options.JsonToStdout)
        {
            Console.WriteLine(report.ToJson());
        }
        else
        {
            foreach (string line in report.ToLines())
            {
                Console.WriteLine(line);
            }
        }

        if (options.JsonPath is { Length: > 0 } path)
        {
            try
            {
                string? directory = Path.GetDirectoryName(path);
                if (!string.IsNullOrEmpty(directory))
                {
                    Directory.CreateDirectory(directory);
                }

                File.WriteAllText(path, report.ToJson() + Environment.NewLine, new UTF8Encoding(false));
                Console.WriteLine("json           : " + path);
            }
            catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
            {
                Console.Error.WriteLine("无法写入 JSON 报告：" + exception.Message);
            }
        }

        Console.Out.Flush();
    }

    /// <summary>
    /// A watchdog rather than only a deadline: if libmpv wedges inside a native call, nothing in
    /// managed code can interrupt it, so the process has to give up on its own.
    /// </summary>
    private static void StartWatchdog(Deadline deadline)
    {
        Thread watchdog = new(() =>
        {
            try
            {
                Thread.Sleep(TimeSpan.FromSeconds(SelfTimeoutSeconds));
                deadline.MarkTimedOut();
                Console.Error.WriteLine(
                    "probe self-timeout after " + SelfTimeoutSeconds.ToString("0", System.Globalization.CultureInfo.InvariantCulture)
                    + " s; waiting " + HardExitGraceSeconds.ToString("0", System.Globalization.CultureInfo.InvariantCulture)
                    + " s for mpv_terminate_destroy before a hard exit");
                Thread.Sleep(TimeSpan.FromSeconds(HardExitGraceSeconds));
                Console.Error.WriteLine("probe hard exit after self-timeout");
                Environment.Exit(ExitSelfTimeout);
            }
            catch (ThreadInterruptedException)
            {
                // Shutting down normally; nothing to do.
            }
        })
        {
            IsBackground = true,
            Name = "probe-watchdog"
        };

        watchdog.Start();
    }
}

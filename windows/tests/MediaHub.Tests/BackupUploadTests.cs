using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json.Nodes;
using MediaHub.Backup;
using Xunit;

namespace MediaHub.Tests;

/// <summary>WebDAV 上传（备份容器 -> 远端）的契约测试：协议细节与命令行两个层面。</summary>
/// <remarks>
/// 不连真服务器：用一个按顺序回放响应的 HttpMessageHandler，同时记录每次请求的方法、地址、
/// 认证头、正文与内容类型。命令行层通过 Run 的可选 handler 参数注入同一个桩。
/// </remarks>
public sealed class BackupUploadTests : IDisposable
{
    private static readonly Uri Target = new("https://dav.example/backups/windows.mhbackup");

    private readonly string _directory = Path.Combine(
        Path.GetTempPath(), "mh-backup-upload-" + Guid.NewGuid().ToString("N"));

    public BackupUploadTests() => Directory.CreateDirectory(_directory);

    public void Dispose()
    {
        try
        {
            Directory.Delete(_directory, recursive: true);
        }
        catch (IOException)
        {
            // 临时目录清理失败不影响断言结论。
        }
    }

    [Fact]
    public async Task PutSendsTheExactBytesWithBasicAuthorizationAndOctetStream()
    {
        byte[] content = Encoding.UTF8.GetBytes("MHBK-upload-test-body");
        var stub = new StubHandler().Then(HttpStatusCode.Created);

        MhBackupWebDavUploader.Result result = await MhBackupWebDavUploader.UploadAsync(
            content, Target, "user-A", "p@ss word", createCollection: false, handler: stub);

        Assert.True(result.Ok);
        Assert.Equal(201, result.StatusCode);
        Assert.Equal(Target, result.Location);
        CapturedRequest request = Assert.Single(stub.Requests);
        Assert.Equal("PUT", request.Method);
        Assert.Equal(Target, request.Uri);
        Assert.Equal("application/octet-stream", request.ContentType);
        Assert.Equal(content, request.Body);
        Assert.Equal("Basic " + Convert.ToBase64String(Encoding.UTF8.GetBytes("user-A:p@ss word")), request.Authorization);
    }

    [Fact]
    public async Task WithoutAUserNameNoAuthorizationHeaderIsSent()
    {
        var stub = new StubHandler().Then(HttpStatusCode.NoContent);

        MhBackupWebDavUploader.Result result = await MhBackupWebDavUploader.UploadAsync(
            Encoding.UTF8.GetBytes("x"), Target, userName: null, password: "ignored", createCollection: false, handler: stub);

        Assert.True(result.Ok);
        Assert.Null(Assert.Single(stub.Requests).Authorization);
    }

    [Fact]
    public async Task UnauthorizedIsReportedAsAnAuthenticationFailureInsteadOfARawCode()
    {
        var stub = new StubHandler().Then(HttpStatusCode.Unauthorized);

        MhBackupWebDavUploader.Result result = await MhBackupWebDavUploader.UploadAsync(
            Encoding.UTF8.GetBytes("x"), Target, "user-A", "wrong", createCollection: false, handler: stub);

        Assert.False(result.Ok);
        Assert.Equal(401, result.StatusCode);
        Assert.Equal(MhBackupWebDavUploader.Reasons.AuthenticationFailed, result.Reason);
    }

    [Fact]
    public async Task ConflictWithoutMkdirStopsAfterThePutAndSaysTheCollectionIsMissing()
    {
        var stub = new StubHandler().Then(HttpStatusCode.Conflict);

        MhBackupWebDavUploader.Result result = await MhBackupWebDavUploader.UploadAsync(
            Encoding.UTF8.GetBytes("x"), Target, "user-A", "p", createCollection: false, handler: stub);

        Assert.False(result.Ok);
        Assert.Equal(MhBackupWebDavUploader.Reasons.CollectionMissing, result.Reason);
        Assert.Single(stub.Requests);
        Assert.Equal("PUT", stub.Requests[0].Method);
    }

    [Fact]
    public async Task MkdirCreatesTheLastCollectionOnlyThenRetriesThePut()
    {
        var stub = new StubHandler()
            .Then(HttpStatusCode.Conflict)
            .Then(HttpStatusCode.Created)
            .Then(HttpStatusCode.Created);

        MhBackupWebDavUploader.Result result = await MhBackupWebDavUploader.UploadAsync(
            Encoding.UTF8.GetBytes("x"), Target, "user-A", "p", createCollection: true, handler: stub);

        Assert.True(result.Ok);
        Assert.Equal(new[] { "PUT", "MKCOL", "PUT" }, stub.Requests.Select(r => r.Method).ToArray());
        Assert.Equal(new Uri("https://dav.example/backups/"), stub.Requests[1].Uri);
        Assert.All(stub.Requests, r => Assert.NotNull(r.Authorization));
    }

    [Fact]
    public async Task AFailedMkcolIsReportedInsteadOfSilentlyRetryingForever()
    {
        var stub = new StubHandler()
            .Then(HttpStatusCode.Conflict)
            .Then(HttpStatusCode.Forbidden);

        MhBackupWebDavUploader.Result result = await MhBackupWebDavUploader.UploadAsync(
            Encoding.UTF8.GetBytes("x"), Target, "user-A", "p", createCollection: true, handler: stub);

        Assert.False(result.Ok);
        Assert.Equal(403, result.StatusCode);
        Assert.Equal(MhBackupWebDavUploader.Reasons.CollectionMissing, result.Reason);
        Assert.Equal(2, stub.Requests.Count);
    }

    [Fact]
    public async Task QuotaAndUnsupportedTypeHaveTheirOwnReasons()
    {
        var quota = new StubHandler().Then((HttpStatusCode)507);
        var quotaResult = await MhBackupWebDavUploader.UploadAsync(
            Encoding.UTF8.GetBytes("x"), Target, "u", "p", false, quota);
        Assert.Equal(MhBackupWebDavUploader.Reasons.QuotaExceeded, quotaResult.Reason);

        var unsupported = new StubHandler().Then(HttpStatusCode.UnsupportedMediaType);
        var unsupportedResult = await MhBackupWebDavUploader.UploadAsync(
            Encoding.UTF8.GetBytes("x"), Target, "u", "p", false, unsupported);
        Assert.Equal(MhBackupWebDavUploader.Reasons.NotAcceptable, unsupportedResult.Reason);
    }

    [Fact]
    public async Task EmptyContentAndNonHttpTargetsAreRejectedWithoutSendingAnything()
    {
        var stub = new StubHandler();
        var empty = await MhBackupWebDavUploader.UploadAsync(Array.Empty<byte>(), Target, "u", "p", false, stub);
        Assert.Equal(MhBackupWebDavUploader.Reasons.EmptyContent, empty.Reason);

        var ftp = await MhBackupWebDavUploader.UploadAsync(
            Encoding.UTF8.GetBytes("x"), new Uri("ftp://dav.example/a.mhbackup"), "u", "p", false, stub);
        Assert.Equal(MhBackupWebDavUploader.Reasons.NotHttp, ftp.Reason);
        Assert.Empty(stub.Requests);
    }

    [Fact]
    public async Task ANetworkFailureBecomesATimeoutReasonRatherThanAnException()
    {
        var stub = new StubHandler().ThenThrow(new TaskCanceledException("timeout"));

        MhBackupWebDavUploader.Result result = await MhBackupWebDavUploader.UploadAsync(
            Encoding.UTF8.GetBytes("x"), Target, "u", "p", false, stub);

        Assert.False(result.Ok);
        Assert.Equal(MhBackupWebDavUploader.Reasons.Timeout, result.Reason);
    }

    [Fact]
    public void UploadCommandPutsTheLocalFileAndPrintsItsDigest()
    {
        string file = WriteBackupFile();
        var stub = new StubHandler().Then(HttpStatusCode.Created);

        int exit = Run(stub, out string output, out string error, "upload", "--file", file, "--url", Target.AbsoluteUri, "--user", "user-A");

        Assert.Equal(0, exit);
        Assert.Equal(string.Empty, error);
        Assert.Contains("已上传备份：" + file, output);
        Assert.Contains("HTTP 状态：201", output);
        Assert.Contains("SHA-256：" + Sha256(file), output);
        Assert.Contains("备份不含密码或令牌", output);
        Assert.Single(stub.Requests);
    }

    [Fact]
    public void UploadCommandJsonNamesTheRemoteAddressStatusAndDigest()
    {
        string file = WriteBackupFile();
        var stub = new StubHandler().Then(HttpStatusCode.NoContent);

        int exit = Run(stub, out string output, out _, "upload", "--file", file, "--url", Target.AbsoluteUri, "--json");

        Assert.Equal(0, exit);
        JsonNode node = JsonNode.Parse(output)!;
        Assert.Equal("upload", node["command"]!.GetValue<string>());
        Assert.True(node["ok"]!.GetValue<bool>());
        Assert.Equal(204, node["status"]!.GetValue<int>());
        Assert.Equal(Target.AbsoluteUri, node["url"]!.GetValue<string>());
        Assert.Equal(new FileInfo(file).Length, node["bytes"]!.GetValue<long>());
        Assert.Equal(Sha256(file), node["sha256"]!.GetValue<string>());
    }

    [Fact]
    public void RemoteFailuresExitWithNineAndSayWhy()
    {
        string file = WriteBackupFile();
        var stub = new StubHandler().Then(HttpStatusCode.Unauthorized);

        int exit = Run(stub, out _, out string error, "upload", "--file", file, "--url", Target.AbsoluteUri);

        Assert.Equal(MhBackupCliCommand.ExitRemote, exit);
        Assert.Contains("上传失败（HTTP 401）", error);
        Assert.Contains("账号或口令不对", error);
    }

    [Fact]
    public void UploadUsageErrorsDoNotTouchTheNetwork()
    {
        var stub = new StubHandler();
        Assert.Equal(MhBackupCliCommand.ExitUsage, Run(stub, out _, out string noFile, "upload", "--url", Target.AbsoluteUri));
        Assert.Contains("--file", noFile);
        Assert.Equal(MhBackupCliCommand.ExitUsage, Run(stub, out _, out string noUrl, "upload", "--file", "x"));
        Assert.Contains("--url", noUrl);
        Assert.Equal(MhBackupCliCommand.ExitUsage, Run(stub, out _, out string badUrl, "upload", "--file", "x", "--url", "not a uri"));
        Assert.Contains("不是合法 URI", badUrl);
        Assert.Empty(stub.Requests);
    }

    [Fact]
    public void AMissingLocalFileIsAnIoErrorNotARemoteOne()
    {
        var stub = new StubHandler();
        int exit = Run(stub, out _, out string error, "upload",
            "--file", Path.Combine(_directory, "nope.mhbackup"), "--url", Target.AbsoluteUri);

        Assert.Equal(MhBackupCliCommand.ExitIo, exit);
        Assert.Contains("读写文件失败", error);
        Assert.Empty(stub.Requests);
    }


    [Fact]
    public async Task NoOverwriteSendsIfNoneMatchStar()
    {
        var stub = new StubHandler().Then(HttpStatusCode.Created);

        MhBackupWebDavUploader.Result result = await MhBackupWebDavUploader.UploadAsync(
            Encoding.UTF8.GetBytes("x"), Target, "user-A", "p", createCollection: false, handler: stub, noOverwrite: true);

        Assert.True(result.Ok);
        Assert.Equal("*", Assert.Single(stub.Requests).IfNoneMatch);
    }

    [Fact]
    public async Task APlainUploadSendsNoConditionalHeader()
    {
        var stub = new StubHandler().Then(HttpStatusCode.Created);

        MhBackupWebDavUploader.Result result = await MhBackupWebDavUploader.UploadAsync(
            Encoding.UTF8.GetBytes("x"), Target, "user-A", "p", createCollection: false, handler: stub);

        Assert.True(result.Ok);
        Assert.Null(Assert.Single(stub.Requests).IfNoneMatch);
    }

    [Fact]
    public async Task ARefusedConditionalWriteSaysTheRemoteFileAlreadyExists()
    {
        var stub = new StubHandler().Then(HttpStatusCode.PreconditionFailed);

        MhBackupWebDavUploader.Result result = await MhBackupWebDavUploader.UploadAsync(
            Encoding.UTF8.GetBytes("x"), Target, "user-A", "p", createCollection: false, handler: stub, noOverwrite: true);

        Assert.False(result.Ok);
        Assert.Equal(412, result.StatusCode);
        Assert.Equal(MhBackupWebDavUploader.Reasons.AlreadyExists, result.Reason);
    }

    [Fact]
    public async Task AnUnconditionalWriteThatHitsPreconditionFailedSaysSomethingElse()
    {
        var stub = new StubHandler().Then(HttpStatusCode.PreconditionFailed);

        MhBackupWebDavUploader.Result result = await MhBackupWebDavUploader.UploadAsync(
            Encoding.UTF8.GetBytes("x"), Target, "user-A", "p", createCollection: false, handler: stub);

        Assert.False(result.Ok);
        Assert.Equal(MhBackupWebDavUploader.Reasons.PreconditionFailed, result.Reason);
    }

    [Fact]
    public void CommandLineNoOverwriteSendsTheHeaderAndReportsTheExistingFile()
    {
        var stub = new StubHandler().Then(HttpStatusCode.PreconditionFailed);

        int exit = Run(stub, out _, out string error, "upload", "--file", WriteBackupFile(), "--url", Target.AbsoluteUri, "--no-overwrite");

        Assert.Equal(MhBackupCliCommand.ExitRemote, exit);
        Assert.Contains("已有同名文件", error);
        Assert.Equal("*", Assert.Single(stub.Requests).IfNoneMatch);
    }

    [Fact]
    public void CommandLineNoOverwriteSaysSoOnSuccessAndInJson()
    {
        var stub = new StubHandler().Then(HttpStatusCode.Created);

        int exit = Run(stub, out string output, out _, "upload", "--file", WriteBackupFile(), "--url", Target.AbsoluteUri, "--no-overwrite");

        Assert.Equal(MhBackupCliCommand.ExitOk, exit);
        Assert.Contains("覆盖保护：已开启", output);
    }

    [Fact]
    public void UsageTextMentionsTheNoOverwriteSwitch()
    {
        var stub = new StubHandler();

        int exit = Run(stub, out string help, out _, "--help");

        Assert.Equal(MhBackupCliCommand.ExitOk, exit);
        Assert.Contains("--no-overwrite", help);
        Assert.Contains("If-None-Match", help);
        Assert.Empty(stub.Requests);
    }
    private string WriteBackupFile()
    {
        string path = Path.Combine(_directory, "windows.mhbackup");
        File.WriteAllBytes(path, Encoding.UTF8.GetBytes("MHBK-upload-test-body"));
        return path;
    }

    private static string Sha256(string path)
        => Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(File.ReadAllBytes(path)));

    private static int Run(StubHandler stub, out string output, out string error, params string[] args)
    {
        var outWriter = new StringWriter();
        var errWriter = new StringWriter();
        int exit = MhBackupCliCommand.Run(args, outWriter, errWriter, name =>
            string.Equals(name, MhBackupCliCommand.DefaultRemotePasswordVariable, StringComparison.Ordinal)
                ? "p@ss word"
                : null, stub);
        output = outWriter.ToString();
        error = errWriter.ToString();
        return exit;
    }

    private sealed record CapturedRequest(string Method, Uri Uri, string? Authorization, string? ContentType, byte[]? Body, string? IfNoneMatch);

    /// <summary>按顺序回放预设响应，并记录真正发出的请求。</summary>
    private sealed class StubHandler : HttpMessageHandler
    {
        private readonly Queue<Func<HttpRequestMessage, HttpResponseMessage>> _steps = new();
        private readonly Queue<Exception> _throws = new();

        public List<CapturedRequest> Requests { get; } = new();

        public StubHandler Then(HttpStatusCode status)
        {
            _steps.Enqueue(_ => new HttpResponseMessage(status));
            return this;
        }

        public StubHandler ThenThrow(Exception failure)
        {
            _throws.Enqueue(failure);
            return this;
        }

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            byte[]? body = request.Content is null ? null : await request.Content.ReadAsByteArrayAsync(cancellationToken);
            string? ifNoneMatch = request.Headers.TryGetValues("If-None-Match", out IEnumerable<string>? values)
                ? string.Join(",", values)
                : null;
            Requests.Add(new CapturedRequest(
                request.Method.Method,
                request.RequestUri!,
                request.Headers.Authorization?.ToString(),
                request.Content?.Headers.ContentType?.MediaType,
                body,
                ifNoneMatch));

            if (_throws.Count > 0) throw _throws.Dequeue();
            if (_steps.Count == 0) throw new InvalidOperationException("unexpected " + request.Method.Method + " " + request.RequestUri);
            return _steps.Dequeue()(request);
        }
    }
}

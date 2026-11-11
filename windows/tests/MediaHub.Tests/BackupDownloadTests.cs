using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json.Nodes;
using MediaHub.Backup;
using Xunit;

namespace MediaHub.Tests;

/// <summary>WebDAV 下载（远端备份容器 -> 本机文件）的契约测试。</summary>
/// <remarks>
/// 不连真服务器：桩 handler 按顺序回放响应并记录请求方法、地址与认证头。
/// 上限用极小值（几十字节）验证「先看声明长度、再在读取中计数」两条路径，避免测试真的分配 10 MiB。
/// </remarks>
public sealed class BackupDownloadTests : IDisposable
{
    private static readonly Uri Source = new("https://dav.example/backups/windows.mhbackup");

    private readonly string _directory = Path.Combine(
        Path.GetTempPath(), "mh-backup-download-" + Guid.NewGuid().ToString("N"));

    public BackupDownloadTests() => Directory.CreateDirectory(_directory);

    public void Dispose()
    {
        try { Directory.Delete(_directory, recursive: true); } catch (IOException) { }
    }

    [Fact]
    public async Task GetSendsBasicAuthorizationAndReturnsTheExactBytes()
    {
        byte[] content = Encoding.UTF8.GetBytes("MHBK-download-body");
        var stub = new StubHandler().Then(Response(HttpStatusCode.OK, content));

        MhBackupWebDavDownloader.Result result = await MhBackupWebDavDownloader.DownloadAsync(
            Source, "user-A", "p@ss word", handler: stub);

        Assert.True(result.Ok);
        Assert.Equal(200, result.StatusCode);
        Assert.Equal(content, result.Content);
        Assert.Equal(content.LongLength, result.DeclaredLength);
        Captured request = Assert.Single(stub.Requests);
        Assert.Equal("GET", request.Method);
        Assert.Equal(Source, request.Uri);
        Assert.Equal(
            "Basic " + Convert.ToBase64String(Encoding.UTF8.GetBytes("user-A:p@ss word")),
            request.Authorization);
    }

    [Fact]
    public async Task WithoutAUserNameNoAuthorizationHeaderIsSent()
    {
        var stub = new StubHandler().Then(Response(HttpStatusCode.OK, Encoding.UTF8.GetBytes("x")));

        MhBackupWebDavDownloader.Result result = await MhBackupWebDavDownloader.DownloadAsync(
            Source, userName: null, password: "ignored", handler: stub);

        Assert.True(result.Ok);
        Assert.Null(Assert.Single(stub.Requests).Authorization);
    }

    [Fact]
    public async Task UnauthorizedIsReportedAsAnAuthenticationFailure()
    {
        var stub = new StubHandler().Then(Response(HttpStatusCode.Unauthorized, null));

        MhBackupWebDavDownloader.Result result = await MhBackupWebDavDownloader.DownloadAsync(
            Source, "user-A", "wrong", handler: stub);

        Assert.False(result.Ok);
        Assert.Equal(401, result.StatusCode);
        Assert.Equal(MhBackupWebDavDownloader.Reasons.AuthenticationFailed, result.Reason);
        Assert.Null(result.Content);
    }

    [Fact]
    public async Task NotFoundIsNamedInsteadOfBeingReportedAsARawCode()
    {
        var stub = new StubHandler().Then(Response(HttpStatusCode.NotFound, null));

        MhBackupWebDavDownloader.Result result = await MhBackupWebDavDownloader.DownloadAsync(
            Source, "user-A", "p", handler: stub);

        Assert.Equal(MhBackupWebDavDownloader.Reasons.NotFound, result.Reason);
        Assert.Equal(404, result.StatusCode);
    }

    [Fact]
    public async Task ADeclaredLengthOverTheLimitIsRefusedBeforeTheBodyIsRead()
    {
        var stub = new StubHandler().Then(Response(HttpStatusCode.OK, Encoding.UTF8.GetBytes("0123456789"), declared: 4096));

        MhBackupWebDavDownloader.Result result = await MhBackupWebDavDownloader.DownloadAsync(
            Source, "user-A", "p", maxBytes: 1024, handler: stub);

        Assert.False(result.Ok);
        Assert.Equal(MhBackupWebDavDownloader.Reasons.TooLarge, result.Reason);
        Assert.Null(result.Content);
        Assert.Equal(4096, result.DeclaredLength);
    }

    [Fact]
    public async Task AChunkedBodyOverTheLimitIsCutOffWhileReading()
    {
        byte[] oversized = new byte[65];
        var stub = new StubHandler().Then(Response(HttpStatusCode.OK, oversized, declared: null));

        MhBackupWebDavDownloader.Result result = await MhBackupWebDavDownloader.DownloadAsync(
            Source, "user-A", "p", maxBytes: 64, handler: stub);

        Assert.False(result.Ok);
        Assert.Equal(MhBackupWebDavDownloader.Reasons.TooLarge, result.Reason);
        Assert.Null(result.Content);
    }

    [Fact]
    public async Task ABodyShorterThanTheDeclaredLengthIsReportedAsTruncated()
    {
        var stub = new StubHandler().Then(Response(HttpStatusCode.OK, new byte[20], declared: 100));

        MhBackupWebDavDownloader.Result result = await MhBackupWebDavDownloader.DownloadAsync(
            Source, "user-A", "p", handler: stub);

        Assert.False(result.Ok);
        Assert.Equal(MhBackupWebDavDownloader.Reasons.Truncated, result.Reason);
        Assert.Equal(100, result.DeclaredLength);
    }

    [Fact]
    public async Task AnEmptyBodyIsNamedInsteadOfReturningAnEmptyContainer()
    {
        var stub = new StubHandler().Then(Response(HttpStatusCode.OK, Array.Empty<byte>()));

        MhBackupWebDavDownloader.Result result = await MhBackupWebDavDownloader.DownloadAsync(
            Source, "user-A", "p", handler: stub);

        Assert.False(result.Ok);
        Assert.Equal(MhBackupWebDavDownloader.Reasons.Empty, result.Reason);
    }

    [Fact]
    public async Task ADirectoryUrlIsRejectedWithoutTouchingTheNetwork()
    {
        var stub = new StubHandler();

        MhBackupWebDavDownloader.Result result = await MhBackupWebDavDownloader.DownloadAsync(
            new Uri("https://dav.example/backups/"), "user-A", "p", handler: stub);

        Assert.False(result.Ok);
        Assert.Equal(MhBackupWebDavDownloader.Reasons.DirectoryTarget, result.Reason);
        Assert.Empty(stub.Requests);
    }

    [Fact]
    public async Task ANonHttpUrlIsRejectedWithoutTouchingTheNetwork()
    {
        var stub = new StubHandler();

        MhBackupWebDavDownloader.Result result = await MhBackupWebDavDownloader.DownloadAsync(
            new Uri("file:///C:/tmp/x.mhbackup"), "user-A", "p", handler: stub);

        Assert.Equal(MhBackupWebDavDownloader.Reasons.NotHttp, result.Reason);
        Assert.Empty(stub.Requests);
    }

    [Fact]
    public async Task ServerErrorsAreNamedAsServerErrors()
    {
        var stub = new StubHandler().Then(Response(HttpStatusCode.ServiceUnavailable, null));

        MhBackupWebDavDownloader.Result result = await MhBackupWebDavDownloader.DownloadAsync(
            Source, "user-A", "p", handler: stub);

        Assert.Equal(MhBackupWebDavDownloader.Reasons.ServerError, result.Reason);
        Assert.Equal(503, result.StatusCode);
    }

    [Fact]
    public void CommandLineDownloadWritesTheFileAndPrintsTheDigest()
    {
        byte[] content = Encoding.UTF8.GetBytes("MHBK-command-line");
        string outPath = Path.Combine(_directory, "from-remote.mhbackup");
        var stub = new StubHandler().Then(Response(HttpStatusCode.OK, content));
        var output = new StringWriter();
        var error = new StringWriter();

        int code = MhBackupCliCommand.Run(
            new[] { "download", "--url", Source.AbsoluteUri, "--out", outPath, "--json" },
            output, error, _ => null, stub);

        Assert.Equal(0, code);
        Assert.Equal(content, File.ReadAllBytes(outPath));
        JsonNode json = JsonNode.Parse(output.ToString())!;
        Assert.Equal("download", json["command"]!.GetValue<string>());
        Assert.True(json["ok"]!.GetValue<bool>());
        Assert.Equal(outPath, json["path"]!.GetValue<string>());
        Assert.Equal(content.LongLength, json["bytes"]!.GetValue<long>());
        Assert.Equal(
            Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(content)),
            json["sha256"]!.GetValue<string>());
    }

    [Fact]
    public void CommandLineDownloadFailureUsesExit9AndLeavesNoFile()
    {
        string outPath = Path.Combine(_directory, "never-written.mhbackup");
        var stub = new StubHandler().Then(Response(HttpStatusCode.Unauthorized, null));
        var output = new StringWriter();
        var error = new StringWriter();

        int code = MhBackupCliCommand.Run(
            new[] { "download", "--url", Source.AbsoluteUri, "--out", outPath, "--user", "u", "--json" },
            output, error, _ => "wrong", stub);

        Assert.Equal(9, code);
        Assert.False(File.Exists(outPath));
        Assert.Empty(Directory.GetFiles(_directory));
        Assert.Contains("账号或口令不对", error.ToString());
    }

    [Fact]
    public void CommandLineDownloadUsageErrorsNeverTouchTheNetwork()
    {
        var stub = new StubHandler();

        Assert.Equal(2, MhBackupCliCommand.Run(
            new[] { "download", "--url", Source.AbsoluteUri }, new StringWriter(), new StringWriter(), _ => null, stub));
        Assert.Equal(2, MhBackupCliCommand.Run(
            new[] { "download", "--out", Path.Combine(_directory, "x.mhbackup") }, new StringWriter(), new StringWriter(), _ => null, stub));
        Assert.Equal(2, MhBackupCliCommand.Run(
            new[] { "download", "--url", "not a uri", "--out", Path.Combine(_directory, "y.mhbackup") }, new StringWriter(), new StringWriter(), _ => null, stub));

        Assert.Empty(stub.Requests);
    }

    [Fact]
    public void CommandLineDownloadOverTheLimitFailsAndLeavesNoPartialFile()
    {
        string outPath = Path.Combine(_directory, "too-big.mhbackup");
        var stub = new StubHandler().Then(Response(HttpStatusCode.OK, new byte[32], declared: 4096));
        var error = new StringWriter();

        int code = MhBackupCliCommand.Run(
            new[] { "download", "--url", Source.AbsoluteUri, "--out", outPath, "--max-bytes", "1024" },
            new StringWriter(), error, _ => null, stub);

        Assert.Equal(9, code);
        Assert.False(File.Exists(outPath));
        Assert.Empty(Directory.GetFiles(_directory));
        Assert.Contains("上限", error.ToString());
    }

    private static HttpResponseMessage Response(HttpStatusCode status, byte[]? body, long? declared = -1)
    {
        var response = new HttpResponseMessage(status);
        if (body is null) return response;
        HttpContent content = declared is null
            ? new StreamContent(new MemoryStream(body, writable: false))
            : new ByteArrayContent(body);
        if (declared is >= 0 and long announced) content.Headers.ContentLength = announced;
        response.Content = content;
        return response;
    }

    private sealed class StubHandler : HttpMessageHandler
    {
        private readonly Queue<Func<HttpRequestMessage, HttpResponseMessage>> _steps = new();

        public List<Captured> Requests { get; } = new();

        public StubHandler Then(HttpResponseMessage response)
        {
            _steps.Enqueue(_ => response);
            return this;
        }

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Requests.Add(new Captured(request.Method.Method, request.RequestUri!, request.Headers.Authorization?.ToString()));
            if (_steps.Count == 0) throw new InvalidOperationException("unexpected " + request.Method.Method + " " + request.RequestUri);
            return Task.FromResult(_steps.Dequeue()(request));
        }
    }

    private sealed record Captured(string Method, Uri Uri, string? Authorization);
}

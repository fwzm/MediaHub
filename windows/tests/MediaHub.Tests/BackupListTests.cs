using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json.Nodes;
using MediaHub.Backup;
using Xunit;

namespace MediaHub.Tests;

/// <summary>W22：远端备份目录的只读浏览（PROPFIND Depth: 1）。全部用例走桩 Handler，不联网。</summary>
public sealed class BackupListTests
{
    private static readonly Uri Directory_ = new("https://dav.example/backups/");

    private static string Multistatus(params string[] items)
    {
        var builder = new StringBuilder();
        builder.Append("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
        builder.Append("<d:multistatus xmlns:d=\"DAV:\">");
        foreach (string item in items) builder.Append(item);
        builder.Append("</d:multistatus>");
        return builder.ToString();
    }

    private static string Response(string href, string? displayName = null, bool collection = false, long? size = null, string? modified = null)
    {
        var prop = new StringBuilder();
        if (displayName is not null) prop.Append("<d:displayname>").Append(displayName).Append("</d:displayname>");
        if (collection) prop.Append("<d:resourcetype><d:collection/></d:resourcetype>");
        else prop.Append("<d:resourcetype/>");
        if (size is long bytes) prop.Append("<d:getcontentlength>").Append(bytes).Append("</d:getcontentlength>");
        if (modified is not null) prop.Append("<d:getlastmodified>").Append(modified).Append("</d:getlastmodified>");
        return "<d:response><d:href>" + href + "</d:href><d:propstat><d:prop>" + prop + "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>";
    }

    private static readonly string Self = Response("/backups/", collection: true);

    private static MhBackupWebDavLister.Result List(string xml, ListStubHandler handler)
        => MhBackupWebDavLister.ListAsync(Directory_, handler: handler).GetAwaiter().GetResult();

    [Fact]
    public async Task ItSendsOnePropfindWithDepthOneAndBasicAuth()
    {
        var handler = new ListStubHandler();
        handler.Then(HttpStatusCode.MultiStatus, Multistatus(Self, Response("/backups/a.mhbackup", size: 12)));

        var result = await MhBackupWebDavLister.ListAsync(Directory_, "bob", "secret", handler);

        Assert.True(result.Ok);
        ListStubHandler.Captured request = Assert.Single(handler.Requests);
        Assert.Equal("PROPFIND", request.Method);
        Assert.Equal("1", request.Depth);
        Assert.Equal("Basic " + Convert.ToBase64String(Encoding.UTF8.GetBytes("bob:secret")), request.Authorization);
        Assert.Contains("propfind", request.Body, StringComparison.Ordinal);
        Assert.Equal(207, result.StatusCode);
    }

    [Fact]
    public void ItDecodesNamesAndReadsSizeAndDate()
    {
        var handler = new ListStubHandler();
        handler.Then(HttpStatusCode.MultiStatus, Multistatus(
            Self,
            Response("/backups/%E4%B8%AD%E6%96%87.mhbackup", size: 2048, modified: "Thu, 29 Oct 2026 00:00:00 GMT"),
            Response("/backups/plain.mhbackup", displayName: "显示名", size: 7)));

        var result = List("", handler);
        Assert.True(result.Ok);
        Assert.Equal(2, result.Entries.Count);
        Assert.Equal("中文.mhbackup", result.Entries[0].Name);
        Assert.Equal(2048, result.Entries[0].Size);
        Assert.Equal(new DateTimeOffset(2026, 10, 29, 0, 0, 0, TimeSpan.Zero), result.Entries[0].LastModifiedUtc);
        Assert.Equal("显示名", result.Entries[1].Name);
        Assert.Equal(7, result.Entries[1].Size);
    }

    [Fact]
    public void AWeekdayTypoInTheRemoteDateDoesNotLoseTheDate()
    {
        var handler = new ListStubHandler();
        handler.Then(HttpStatusCode.MultiStatus, Multistatus(
            Self,
            Response("/backups/a.mhbackup", size: 1, modified: "Wed, 29 Oct 2026 00:00:00 GMT")));

        var result = List("", handler);
        Assert.True(result.Ok);
        Assert.Equal(new DateTimeOffset(2026, 10, 29, 0, 0, 0, TimeSpan.Zero), result.Entries[0].LastModifiedUtc);
    }

    [Fact]
    public void ItMarksCollectionsAndNeverReturnsTheDirectoryItself()
    {
        var handler = new ListStubHandler();
        handler.Then(HttpStatusCode.MultiStatus, Multistatus(
            Self,
            Response("/backups/2026/", collection: true),
            Response("/backups/x.mhbackup", size: 1)));

        var result = List("", handler);
        Assert.Equal(2, result.Entries.Count);
        Assert.True(result.Entries[0].IsCollection);
        Assert.Equal("2026", result.Entries[0].Name);
        Assert.False(result.Entries[1].IsCollection);
    }

    [Fact]
    public async Task ItMapsTheAuthenticationFailure()
    {
        var handler = new ListStubHandler();
        handler.Then(HttpStatusCode.Unauthorized);

        var result = await MhBackupWebDavLister.ListAsync(Directory_, "bob", "wrong", handler);

        Assert.False(result.Ok);
        Assert.Equal(401, result.StatusCode);
        Assert.Equal(MhBackupWebDavLister.Reasons.AuthenticationFailed, result.Reason);
    }

    [Fact]
    public async Task ItSaysWhenTheServerCannotListAtAll()
    {
        var handler = new ListStubHandler();
        handler.Then(HttpStatusCode.MethodNotAllowed);

        var result = await MhBackupWebDavLister.ListAsync(Directory_, handler: handler);

        Assert.False(result.Ok);
        Assert.Equal(MhBackupWebDavLister.Reasons.NotSupported, result.Reason);
    }

    [Fact]
    public async Task AnHtmlErrorPageIsReportedAsCorruptedInsteadOfAnEmptyDirectory()
    {
        var handler = new ListStubHandler();
        handler.Then(HttpStatusCode.MultiStatus, "<html><body>not webdav</body></html>");

        var result = await MhBackupWebDavLister.ListAsync(Directory_, handler: handler);

        Assert.False(result.Ok);
        Assert.Equal(MhBackupWebDavLister.Reasons.Corrupted, result.Reason);
        Assert.Empty(result.Entries);
    }

    [Fact]
    public void AnEmptyCollectionIsOkWithNoEntries()
    {
        var handler = new ListStubHandler();
        handler.Then(HttpStatusCode.MultiStatus, Multistatus(Self));

        var result = List("", handler);
        Assert.True(result.Ok);
        Assert.Empty(result.Entries);
    }

    [Fact]
    public void TheCommandLineListsOnlyBackupFilesByDefault()
    {
        var handler = new ListStubHandler();
        handler.Then(HttpStatusCode.MultiStatus, Multistatus(
            Self,
            Response("/backups/2026/", collection: true),
            Response("/backups/notes.txt", size: 3),
            Response("/backups/phone.mhbackup", size: 4096)));

        var report = Run(new[] { "list", "--url", Directory_.ToString() }, handler);

        Assert.Equal(0, report.Code);
        Assert.Contains("phone.mhbackup", report.Out, StringComparison.Ordinal);
        Assert.DoesNotContain("notes.txt", report.Out, StringComparison.Ordinal);
        Assert.DoesNotContain("[目录]", report.Out, StringComparison.Ordinal);
        Assert.Contains("其中 1 个 .mhbackup", report.Out, StringComparison.Ordinal);
        Assert.Contains("加 --all 显示全部条目", report.Out, StringComparison.Ordinal);
    }

    [Fact]
    public void TheCommandLineCanShowEverythingWithAll()
    {
        var handler = new ListStubHandler();
        handler.Then(HttpStatusCode.MultiStatus, Multistatus(
            Self,
            Response("/backups/2026/", collection: true),
            Response("/backups/notes.txt", size: 3)));

        var report = Run(new[] { "list", "--url", Directory_.ToString(), "--all" }, handler);

        Assert.Equal(0, report.Code);
        Assert.Contains("[目录] 2026", report.Out, StringComparison.Ordinal);
        Assert.Contains("notes.txt", report.Out, StringComparison.Ordinal);
        Assert.DoesNotContain("加 --all", report.Out, StringComparison.Ordinal);
    }

    [Fact]
    public void TheCommandLineJsonCarriesTheCountsAndRows()
    {
        var handler = new ListStubHandler();
        handler.Then(HttpStatusCode.MultiStatus, Multistatus(
            Self,
            Response("/backups/2026/", collection: true),
            Response("/backups/laptop.mhbackup", size: 99, modified: "Thu, 29 Oct 2026 00:00:00 GMT")));

        var report = Run(new[] { "list", "--url", Directory_.ToString(), "--json" }, handler);
        JsonNode payload = JsonNode.Parse(report.Out.Trim())!;

        Assert.Equal("list", payload["command"]!.GetValue<string>());
        Assert.True(payload["ok"]!.GetValue<bool>());
        Assert.Equal(1, payload["directories"]!.GetValue<int>());
        Assert.Equal(1, payload["files"]!.GetValue<int>());
        Assert.Equal(1, payload["backupFiles"]!.GetValue<int>());
        JsonNode row = payload["entries"]!.AsArray()[0]!;
        Assert.Equal("laptop.mhbackup", row["name"]!.GetValue<string>());
        Assert.Equal(99, row["bytes"]!.GetValue<long>());
    }

    [Fact]
    public void ARemoteFailureKeepsItsOwnExitCode()
    {
        var handler = new ListStubHandler();
        handler.Then(HttpStatusCode.Forbidden);

        var report = Run(new[] { "list", "--url", Directory_.ToString() }, handler);

        Assert.Equal(9, report.Code);
        Assert.Contains("账号或口令不对", report.Err, StringComparison.Ordinal);
    }

    [Fact]
    public void UsageErrorsNeverReachTheNetwork()
    {
        var handler = new ListStubHandler();

        Assert.Equal(2, Run(new[] { "list" }, handler).Code);
        Assert.Equal(2, Run(new[] { "list", "--url", "not a uri" }, handler).Code);
        Assert.Empty(handler.Requests);
    }

    [Fact]
    public void TheRemotePasswordComesFromItsOwnVariable()
    {
        var handler = new ListStubHandler();
        handler.Then(HttpStatusCode.MultiStatus, Multistatus(Self));

        var report = Run(
            new[] { "list", "--url", Directory_.ToString(), "--user", "bob" },
            handler,
            new Dictionary<string, string?>(StringComparer.OrdinalIgnoreCase) { ["MH_WEBDAV_PASSWORD"] = "secret" });

        Assert.Equal(0, report.Code);
        ListStubHandler.Captured request = Assert.Single(handler.Requests);
        Assert.Equal("Basic " + Convert.ToBase64String(Encoding.UTF8.GetBytes("bob:secret")), request.Authorization);
    }

    private static (int Code, string Out, string Err) Run(
        string[] args,
        ListStubHandler handler,
        Dictionary<string, string?>? environment = null)
    {
        var output = new StringWriter();
        var error = new StringWriter();
        int code = MhBackupCliCommand.Run(
            args,
            output,
            error,
            name => environment is not null && environment.TryGetValue(name, out string? value) ? value : null,
            handler);
        return (code, output.ToString(), error.ToString());
    }

    internal sealed class ListStubHandler : HttpMessageHandler
    {
        private readonly Queue<Func<HttpRequestMessage, HttpResponseMessage>> _steps = new();

        public List<Captured> Requests { get; } = new();

        public void Then(HttpStatusCode status, string? body = null)
            => _steps.Enqueue(_ => body is null
                ? new HttpResponseMessage(status)
                : new HttpResponseMessage(status) { Content = new StringContent(body, Encoding.UTF8, "application/xml") });

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            string body = request.Content is null ? string.Empty : await request.Content.ReadAsStringAsync(cancellationToken);
            string? depth = request.Headers.TryGetValues("Depth", out IEnumerable<string>? values) ? values.FirstOrDefault() : null;
            string? authorization = request.Headers.Authorization?.ToString();
            Requests.Add(new Captured(request.Method.Method, request.RequestUri!, depth, authorization, body));
            if (_steps.Count == 0) throw new InvalidOperationException("桩 Handler 收到了计划之外的请求：" + request.RequestUri);
            return _steps.Dequeue()(request);
        }

        public sealed record Captured(string Method, Uri Uri, string? Depth, string? Authorization, string Body);
    }
}

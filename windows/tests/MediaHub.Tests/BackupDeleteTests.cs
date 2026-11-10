using System.Net;
using System.Net.Http;
using System.Text.Json;
using MediaHub.Backup;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// W23：WebDAV 备份删除的确认语义。删除不可逆，所以这些用例盯的是「没被允许时一个请求都不发」「允许后先确认存在再删」「远端各种拒绝各有稳定原因与退出码」。
/// </summary>
public sealed class BackupDeleteTests
{
    private const string BackupUrl = "https://dav.example/backups/media-20261030-1145.mhbackup";

    [Fact]
    public async Task APreviewSendsNothingAndSaysWhy()
    {
        var handler = new DeleteStubHandler();

        MhBackupWebDavDeleter.Result result = await MhBackupWebDavDeleter.DeleteAsync(new Uri(BackupUrl), handler: handler);

        Assert.False(result.Deleted);
        Assert.False(result.Ok);
        Assert.False(result.CheckedExistence);
        Assert.Equal(MhBackupWebDavDeleter.Reasons.ConfirmRequired, result.Reason);
        Assert.Empty(handler.Requests);
    }

    [Fact]
    public async Task ConfirmationChecksExistenceBeforeDeleting()
    {
        var handler = new DeleteStubHandler()
            .Then(HttpStatusCode.OK)
            .Then(HttpStatusCode.NoContent);

        MhBackupWebDavDeleter.Result result = await MhBackupWebDavDeleter.DeleteAsync(
            new Uri(BackupUrl), "user-A", "pass-A", confirm: true, handler: handler);

        Assert.True(result.Deleted);
        Assert.True(result.CheckedExistence);
        Assert.Equal(204, result.StatusCode);
        Assert.Equal(new[] { "HEAD", "DELETE" }, handler.Requests.Select(request => request.Method));
        Assert.All(handler.Requests, request => Assert.Equal("Basic " + Convert.ToBase64String("user-A:pass-A"u8.ToArray()), request.Authorization));
    }

    [Fact]
    public async Task AMissingFileIsNotReportedAsDeleted()
    {
        var handler = new DeleteStubHandler().Then(HttpStatusCode.NotFound);

        MhBackupWebDavDeleter.Result result = await MhBackupWebDavDeleter.DeleteAsync(
            new Uri(BackupUrl), confirm: true, handler: handler);

        Assert.False(result.Deleted);
        Assert.True(result.CheckedExistence);
        Assert.Equal(MhBackupWebDavDeleter.Reasons.NotFound, result.Reason);
        Assert.Equal(new[] { "HEAD" }, handler.Requests.Select(request => request.Method));
    }

    [Fact]
    public async Task ADeleteThatLosesTheRaceStillReportsNotFound()
    {
        var handler = new DeleteStubHandler()
            .Then(HttpStatusCode.OK)
            .Then(HttpStatusCode.NotFound);

        MhBackupWebDavDeleter.Result result = await MhBackupWebDavDeleter.DeleteAsync(
            new Uri(BackupUrl), confirm: true, handler: handler);

        Assert.False(result.Deleted);
        Assert.Equal(MhBackupWebDavDeleter.Reasons.NotFound, result.Reason);
        Assert.Equal(2, handler.Requests.Count);
    }

    [Fact]
    public async Task AnUnauthorizedCheckStopsBeforeDelete()
    {
        var handler = new DeleteStubHandler().Then(HttpStatusCode.Unauthorized);

        MhBackupWebDavDeleter.Result result = await MhBackupWebDavDeleter.DeleteAsync(
            new Uri(BackupUrl), "user-A", "wrong", confirm: true, handler: handler);

        Assert.False(result.Deleted);
        Assert.Equal(MhBackupWebDavDeleter.Reasons.AuthenticationFailed, result.Reason);
        Assert.Equal(new[] { "HEAD" }, handler.Requests.Select(request => request.Method));
    }

    [Fact]
    public async Task AnUnauthorizedDeleteIsReportedAsAuthentication()
    {
        var handler = new DeleteStubHandler()
            .Then(HttpStatusCode.OK)
            .Then(HttpStatusCode.Forbidden);

        MhBackupWebDavDeleter.Result result = await MhBackupWebDavDeleter.DeleteAsync(
            new Uri(BackupUrl), confirm: true, handler: handler);

        Assert.False(result.Deleted);
        Assert.Equal(MhBackupWebDavDeleter.Reasons.AuthenticationFailed, result.Reason);
        Assert.Equal(403, result.StatusCode);
    }

    [Fact]
    public async Task AServerThatRefusesDeleteIsReportedAsNotSupported()
    {
        var handler = new DeleteStubHandler()
            .Then(HttpStatusCode.OK)
            .Then((HttpStatusCode)405);

        MhBackupWebDavDeleter.Result result = await MhBackupWebDavDeleter.DeleteAsync(
            new Uri(BackupUrl), confirm: true, handler: handler);

        Assert.False(result.Deleted);
        Assert.Equal(MhBackupWebDavDeleter.Reasons.NotSupported, result.Reason);
    }

    [Fact]
    public async Task AServerErrorKeepsItsOwnReason()
    {
        var handler = new DeleteStubHandler()
            .Then(HttpStatusCode.OK)
            .Then(HttpStatusCode.InternalServerError);

        MhBackupWebDavDeleter.Result result = await MhBackupWebDavDeleter.DeleteAsync(
            new Uri(BackupUrl), confirm: true, handler: handler);

        Assert.False(result.Deleted);
        Assert.Equal(MhBackupWebDavDeleter.Reasons.ServerError, result.Reason);
    }

    [Fact]
    public async Task OnlyBackupFilesAreDeletedWithoutTheAnyFlag()
    {
        var refused = new DeleteStubHandler();
        MhBackupWebDavDeleter.Result refusedResult = await MhBackupWebDavDeleter.DeleteAsync(
            new Uri("https://dav.example/backups/notes.txt"), confirm: true, handler: refused);

        Assert.False(refusedResult.Deleted);
        Assert.Equal(MhBackupWebDavDeleter.Reasons.NotABackupFile, refusedResult.Reason);
        Assert.Empty(refused.Requests);

        var allowed = new DeleteStubHandler()
            .Then(HttpStatusCode.OK)
            .Then(HttpStatusCode.NoContent);
        MhBackupWebDavDeleter.Result allowedResult = await MhBackupWebDavDeleter.DeleteAsync(
            new Uri("https://dav.example/backups/notes.txt"), confirm: true, allowNonBackupFile: true, handler: allowed);

        Assert.True(allowedResult.Deleted);
        Assert.Equal(new[] { "HEAD", "DELETE" }, allowed.Requests.Select(request => request.Method));
    }

    [Theory]
    [InlineData("https://dav.example/backups/", MhBackupWebDavDeleter.Reasons.DirectoryTarget)]
    [InlineData("file:///D:/backups/media.mhbackup", MhBackupWebDavDeleter.Reasons.NotHttp)]
    public async Task DestinationsThatAreNotASingleHttpFileAreRefusedBeforeAnyRequest(string url, string expected)
    {
        var handler = new DeleteStubHandler();
        MhBackupWebDavDeleter.Result result = await MhBackupWebDavDeleter.DeleteAsync(
            new Uri(url), confirm: true, allowNonBackupFile: true, handler: handler);

        Assert.False(result.Deleted);
        Assert.Equal(expected, result.Reason);
        Assert.Empty(handler.Requests);
    }

    [Fact]
    public async Task AHeadBlockedServerFallsBackToPropfindDepthZero()
    {
        var handler = new DeleteStubHandler()
            .Then((HttpStatusCode)405)
            .Then(HttpStatusCode.MultiStatus)
            .Then(HttpStatusCode.NoContent);

        MhBackupWebDavDeleter.Result result = await MhBackupWebDavDeleter.DeleteAsync(
            new Uri(BackupUrl), confirm: true, handler: handler);

        Assert.True(result.Deleted);
        Assert.Equal(new[] { "HEAD", "PROPFIND", "DELETE" }, handler.Requests.Select(request => request.Method));
        Assert.Equal("0", handler.Requests[1].Depth);
    }

    [Fact]
    public void TheCommandLinePreviewDoesNotTouchTheRemote()
    {
        var handler = new DeleteStubHandler();
        var output = new StringWriter();
        var error = new StringWriter();

        int code = MhBackupCliCommand.Run(new[] { "delete", "--url", BackupUrl }, output, error, name => null, handler);

        Assert.Equal(MhBackupCliCommand.ExitOk, code);
        Assert.Empty(handler.Requests);
        string text = output.ToString();
        Assert.Contains("预览：不会删除任何东西", text);
        Assert.Contains("加 --confirm 才会先确认文件存在、再发 DELETE。", text);
    }

    [Fact]
    public void TheCommandLineConfirmsBeforeDeleting()
    {
        var handler = new DeleteStubHandler()
            .Then(HttpStatusCode.OK)
            .Then(HttpStatusCode.NoContent);
        var output = new StringWriter();
        var error = new StringWriter();

        int code = MhBackupCliCommand.Run(
            new[] { "delete", "--url", BackupUrl, "--user", "user-A", "--confirm" }, output, error,
            name => name == MhBackupCliCommand.DefaultRemotePasswordVariable ? "pass-A" : null, handler);

        Assert.Equal(MhBackupCliCommand.ExitOk, code);
        Assert.Equal(new[] { "HEAD", "DELETE" }, handler.Requests.Select(request => request.Method));
        Assert.Contains("已删除远端备份", output.ToString());
    }

    [Fact]
    public void TheCommandLineReportsAuthenticationFailureWithExitNine()
    {
        var handler = new DeleteStubHandler().Then(HttpStatusCode.Unauthorized);
        var output = new StringWriter();
        var error = new StringWriter();

        int code = MhBackupCliCommand.Run(
            new[] { "delete", "--url", BackupUrl, "--confirm", "--json" }, output, error, name => "wrong", handler);

        Assert.Equal(MhBackupCliCommand.ExitRemote, code);
        Assert.Contains("账号或口令不对", error.ToString());
        using JsonDocument json = JsonDocument.Parse(output.ToString());
        Assert.False(json.RootElement.GetProperty("deleted").GetBoolean());
        Assert.Equal(401, json.RootElement.GetProperty("status").GetInt32());
    }

    [Fact]
    public void TheCommandLineRefusesUsageErrorsWithoutAnyRequest()
    {
        var handler = new DeleteStubHandler();

        Assert.Equal(MhBackupCliCommand.ExitUsage, MhBackupCliCommand.Run(new[] { "delete" }, new StringWriter(), new StringWriter(), name => null, handler));
        Assert.Equal(MhBackupCliCommand.ExitUsage, MhBackupCliCommand.Run(new[] { "delete", "--url", "https://dav.example/backups/notes.txt" }, new StringWriter(), new StringWriter(), name => null, handler));
        Assert.Equal(MhBackupCliCommand.ExitUsage, MhBackupCliCommand.Run(new[] { "delete", "--url", "file:///D:/backups/x.mhbackup" }, new StringWriter(), new StringWriter(), name => null, handler));
        Assert.Empty(handler.Requests);
    }

    [Fact]
    public void TheCommandLineJsonPreviewSaysModePreview()
    {
        var output = new StringWriter();

        int code = MhBackupCliCommand.Run(new[] { "delete", "--url", BackupUrl, "--json" }, output, new StringWriter(), name => null, new DeleteStubHandler());

        Assert.Equal(MhBackupCliCommand.ExitOk, code);
        using JsonDocument json = JsonDocument.Parse(output.ToString());
        Assert.Equal("preview", json.RootElement.GetProperty("mode").GetString());
        Assert.False(json.RootElement.GetProperty("deleted").GetBoolean());
        Assert.Equal(BackupUrl, json.RootElement.GetProperty("url").GetString());
    }

    [Fact]
    public void TheCommandLineJsonDeleteReportsCheckedExistence()
    {
        var handler = new DeleteStubHandler()
            .Then(HttpStatusCode.OK)
            .Then(HttpStatusCode.NoContent);
        var output = new StringWriter();

        int code = MhBackupCliCommand.Run(new[] { "delete", "--url", BackupUrl, "--confirm", "--json" }, output, new StringWriter(), name => null, handler);

        Assert.Equal(MhBackupCliCommand.ExitOk, code);
        using JsonDocument json = JsonDocument.Parse(output.ToString());
        Assert.True(json.RootElement.GetProperty("deleted").GetBoolean());
        Assert.True(json.RootElement.GetProperty("checkedExistence").GetBoolean());
        Assert.Equal(204, json.RootElement.GetProperty("status").GetInt32());
    }

    private sealed record CapturedRequest(string Method, Uri Uri, string? Authorization, string? Depth);

    private sealed class DeleteStubHandler : HttpMessageHandler
    {
        private readonly Queue<Func<HttpRequestMessage, HttpResponseMessage>> _queue = new();

        public List<CapturedRequest> Requests { get; } = new();

        public DeleteStubHandler Then(HttpStatusCode status)
        {
            _queue.Enqueue(_ => new HttpResponseMessage(status));
            return this;
        }

        public DeleteStubHandler ThenThrow(Exception failure)
        {
            _queue.Enqueue(_ => throw failure);
            return this;
        }

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Requests.Add(new CapturedRequest(
                request.Method.Method,
                request.RequestUri!,
                request.Headers.Authorization?.ToString(),
                request.Headers.TryGetValues("Depth", out IEnumerable<string>? depth) ? string.Join(",", depth) : null));

            Func<HttpRequestMessage, HttpResponseMessage> next = _queue.Count > 0
                ? _queue.Dequeue()
                : _ => new HttpResponseMessage(HttpStatusCode.OK);
            HttpResponseMessage response = next(request);
            response.RequestMessage = request;
            return Task.FromResult(response);
        }
    }
}

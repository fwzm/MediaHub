using System.ComponentModel;
using MediaHub.Domain;
using MediaHub.Net;
using MediaHub.Providers;
using MediaHub.Providers.Smb;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// The SMB share source: address parsing, redirector error translation, and the local-file chain.
/// </summary>
/// <remarks>
/// The browse/playback cases run against a temporary directory used as the share root. That is the
/// point of the design: the share adds a connection and an error vocabulary in front of
/// <see cref="LocalMediaProvider"/>, so a file on a share and a file on <c>C:</c> travel the exact
/// same playback path. A real share is exercised by the acceptance run; here the shape of that path
/// is pinned without needing a network.
/// </remarks>
public sealed class SmbSourceTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "mh-smb-" + Guid.NewGuid().ToString("N"));

    public SmbSourceTests() => Directory.CreateDirectory(_root);

    public void Dispose()
    {
        try { Directory.Delete(_root, recursive: true); } catch (IOException) { }
    }

    // ---------------------------------------------------------------- address

    [Theory]
    [InlineData(@"\\NAS\Media")]
    [InlineData("//NAS/Media")]
    [InlineData("smb://NAS/Media")]
    [InlineData("cifs://NAS/Media/")]
    public void The_same_share_typed_four_ways_is_one_address(string input)
    {
        Assert.True(SmbAddress.TryParse(input, out SmbAddress address, out string error), error);
        Assert.Equal("nas", address.Host);
        Assert.Equal("Media", address.Share);
        Assert.Equal(@"\\nas\Media", address.UncRoot);
        Assert.Equal(@"\\nas\Media", address.ConnectionTarget);
    }

    [Fact]
    public void A_subfolder_survives_parsing_and_becomes_the_browse_root()
    {
        Assert.True(SmbAddress.TryParse(@"\\nas\media\Shows\Season 01", out SmbAddress address, out string error), error);

        Assert.Equal(@"\\nas\media\Shows\Season 01", address.UncRoot);
        // The connection is made to the share, not to the folder: a credential logon has no subfolder.
        Assert.Equal(@"\\nas\media", address.ConnectionTarget);
        Assert.Equal(@"nas\media\Shows\Season 01", address.DisplayName);
    }

    [Theory]
    [InlineData("", "请填写共享地址")]
    [InlineData(@"\\nas", "共享名")]
    [InlineData(@"\\nas\", "共享名")]
    [InlineData(@"\\nas\media\..\other", "不合法")]
    [InlineData(@"\\nas\media\.", "不合法")]
    [InlineData("https://nas/media", "smb://")]
    [InlineData(@"nas\media", "开头")]
    [InlineData(@"\\na*s\media", "主机名不合法")]
    [InlineData(@"\\nas:445\media", "主机名不合法")]
    public void An_unusable_address_is_rejected_with_a_sentence_that_says_what_to_fix(string input, string expected)
    {
        Assert.False(SmbAddress.TryParse(input, out _, out string error));
        Assert.Contains(expected, error);
    }

    // ---------------------------------------------------------------- error translation

    [Theory]
    [InlineData(1326, "账号或密码")]   // ERROR_LOGON_FAILURE
    [InlineData(86, "账号或密码")]     // ERROR_INVALID_PASSWORD
    [InlineData(1327, "账号受限")]
    [InlineData(1385, "网络登录")]
    [InlineData(1909, "锁定")]
    public void A_rejected_logon_names_the_credential_and_never_the_secret(int code, string expected)
    {
        (SmbFailure failure, string message) = SmbErrors.Describe(code, @"\\nas\media", credentialsSupplied: true);

        Assert.Equal(SmbFailure.Authentication, failure);
        Assert.Contains(expected, message);
        Assert.DoesNotContain("nas", message);
    }

    [Fact]
    public void Access_denied_means_different_things_with_and_without_a_supplied_account()
    {
        (SmbFailure withPassword, string messageWithPassword) =
            SmbErrors.Describe(5, @"\\nas\media", credentialsSupplied: true);
        (SmbFailure anonymous, string messageAnonymous) =
            SmbErrors.Describe(5, @"\\nas\media", credentialsSupplied: false);

        // A wrong password and a passwordless anonymous logon look identical to the redirector. The
        // only difference the user can act on is whether they already filled the box.
        Assert.Equal(SmbFailure.Authentication, withPassword);
        Assert.Equal(SmbFailure.CredentialsRequired, anonymous);
        Assert.Contains("需要账号和密码", messageAnonymous);
    }

    [Theory]
    [InlineData(53)]
    [InlineData(67)]
    [InlineData(3)]
    [InlineData(2)]
    public void A_missing_share_names_the_target_the_user_typed(int code)
    {
        (SmbFailure failure, string message) = SmbErrors.Describe(code, @"\\nas\gone", credentialsSupplied: false);

        Assert.Equal(SmbFailure.NotFound, failure);
        Assert.Contains(@"\\nas\gone", message);
    }

    [Theory]
    [InlineData(121, SmbFailure.Timeout)]
    [InlineData(1231, SmbFailure.Network)]
    [InlineData(1232, SmbFailure.Network)]
    [InlineData(1203, SmbFailure.Network)]
    [InlineData(64, SmbFailure.Network)]
    [InlineData(1311, SmbFailure.Network)]
    [InlineData(1219, SmbFailure.Conflict)]
    public void Network_and_conflict_codes_stay_distinguishable(int code, SmbFailure expected)
    {
        (SmbFailure failure, string message) = SmbErrors.Describe(code, @"\\nas\media", credentialsSupplied: true);

        Assert.Equal(expected, failure);
        Assert.NotEmpty(message);
    }

    [Fact]
    public void A_folder_inside_a_readable_share_is_a_permission_not_a_missing_password()
    {
        (SmbFailure failure, string message) = SmbErrors.PermissionDenied(@"\\nas\media\private");

        Assert.Equal(SmbFailure.Permission, failure);
        Assert.Contains("没有访问权限", message);
        Assert.Contains(@"\\nas\media\private", message);
        Assert.DoesNotContain("密码", message);
    }

    [Fact]
    public void An_unmapped_code_is_reported_with_the_number_instead_of_inventing_a_cause()
    {
        (SmbFailure failure, string message) = SmbErrors.Describe(999, @"\\nas\media", credentialsSupplied: true);

        Assert.Equal(SmbFailure.Other, failure);
        Assert.Contains("999", message);
    }

    [Fact]
    public void The_win32_code_is_read_through_the_inner_chain()
    {
        Assert.Equal(5, SmbErrors.Win32Code(new UnauthorizedAccessException("x", new Win32Exception(5))));
        // A plain managed exception carries no low word, which is what makes -1 a safe "unknown".
        Assert.Equal(-1, SmbErrors.Win32Code(new InvalidOperationException()));
    }

    // ---------------------------------------------------------------- browsing

    [Fact]
    public async Task A_share_lists_folders_and_video_files_in_the_local_shape()
    {
        Directory.CreateDirectory(Path.Combine(_root, "Season 01"));
        await File.WriteAllBytesAsync(Path.Combine(_root, "movie.mp4"), [0, 0, 0]);
        await File.WriteAllTextAsync(Path.Combine(_root, "notes.txt"), "x");

        SmbMediaProvider provider = Provider(_root);
        PagedResult<MediaItem> page = await provider.ListFolderAsync(null, new PageRequest(0, 50));

        MediaItem folder = Assert.Single(page.Items, i => i.Type == MediaType.Folder);
        Assert.Equal("Season 01", folder.Name);
        MediaItem video = Assert.Single(page.Items, i => i.Type == MediaType.Video);
        // The name drops the extension, as local folders do; the path keeps it.
        Assert.Equal("movie", video.Name);
        Assert.Equal(Path.Combine(_root, "movie.mp4"), video.Path);
        // A non-media file in the share is not offered, exactly as in a local folder.
        Assert.DoesNotContain(page.Items, i => i.Name == "notes.txt");
    }

    [Fact]
    public async Task An_empty_share_is_an_empty_listing_rather_than_a_failure()
    {
        SmbMediaProvider provider = Provider(_root);

        PagedResult<MediaItem> page = await provider.ListFolderAsync(null, new PageRequest(0, 50));

        Assert.Empty(page.Items);
    }

    [Fact]
    public async Task A_folder_that_is_not_there_reports_not_found_with_the_path()
    {
        SmbMediaProvider provider = Provider(_root);
        var folder = new MediaItem
        {
            ServerId = "smb:test",
            Id = Path.Combine(_root, "gone"),
            Path = Path.Combine(_root, "gone"),
            Name = "gone",
            Type = MediaType.Folder,
        };

        ProviderException ex = await Assert.ThrowsAsync<ProviderException>(
            () => provider.ListFolderAsync(folder, new PageRequest(0, 50)));

        Assert.Equal(ProviderErrorCode.NotFound, ex.Code);
        Assert.Contains("gone", ex.Message);
    }

    // ---------------------------------------------------------------- playback

    [Fact]
    public async Task Playback_resolves_to_a_file_uri_so_the_engine_uses_the_local_chain()
    {
        string file = Path.Combine(_root, "movie.mp4");
        await File.WriteAllBytesAsync(file, new byte[64]);

        SmbMediaProvider provider = Provider(_root);
        var item = new MediaItem
        {
            ServerId = "smb:test",
            Id = file,
            Path = file,
            Name = "movie.mp4",
            Type = MediaType.Video,
        };

        PlaybackSource source = await provider.ResolvePlaybackAsync(item, new PlaybackOptions());

        Assert.Equal(PlaybackMode.DirectPlay, source.Mode);
        Assert.True(source.Url.IsFile);
        Assert.Equal(new Uri(file), source.Url);
        // No headers and no origin: a share is read by the file system, not by an HTTP client.
        Assert.Empty(source.Headers);
        Assert.Empty(provider.StreamRequestHeaders);
        Assert.Null(provider.StreamHeaderOrigin);
    }

    [Fact]
    public async Task A_file_that_is_not_there_is_not_found_rather_than_a_network_error()
    {
        SmbMediaProvider provider = Provider(_root);
        var item = new MediaItem
        {
            ServerId = "smb:test",
            Id = Path.Combine(_root, "gone.mp4"),
            Path = Path.Combine(_root, "gone.mp4"),
            Name = "gone.mp4",
            Type = MediaType.Video,
        };

        ProviderException ex = await Assert.ThrowsAsync<ProviderException>(
            () => provider.ResolvePlaybackAsync(item, new PlaybackOptions()));

        Assert.Equal(ProviderErrorCode.NotFound, ex.Code);
        Assert.Contains("gone.mp4", ex.Message);
    }

    [Fact]
    public async Task Progress_is_local_only_so_nothing_is_reported_and_resume_is_unknown()
    {
        SmbMediaProvider provider = Provider(_root);

        await provider.ReportProgressAsync(new PlaybackProgress
        {
            ServerId = "smb:test",
            ItemId = Path.Combine(_root, "movie.mp4"),
            PositionTicks = 10,
        });

        Assert.Null(await provider.GetResumePositionAsync(Path.Combine(_root, "movie.mp4")));
        Assert.Empty(await provider.GetContinueWatchingAsync(10));
    }

    // ---------------------------------------------------------------- factory

    [Fact]
    public void The_factory_offers_browse_playback_and_progress_for_a_share()
    {
        var factory = new SmbMediaProviderFactory();
        MediaServer server = Server(@"\\nas\media");

        ProviderHandle handle = factory.Create(server, NetworkPolicy.FollowSystem);

        Assert.Equal(ServerType.Smb, handle.Descriptor.Type);
        Assert.NotNull(handle.Browse);
        Assert.NotNull(handle.Playback);
        Assert.NotNull(handle.Progress);
        // Progress must stay advertised: it is what makes the coordinator write the local snapshot
        // that the resume fallback later reads.
        Assert.True(handle.Descriptor.DeclaredCapabilities.HasFlag(ProviderCapability.Progress));
    }

    [Fact]
    public void An_address_without_a_share_is_refused_when_the_handle_is_built()
    {
        var factory = new SmbMediaProviderFactory();

        ProviderException ex = Assert.Throws<ProviderException>(
            () => factory.Create(Server(@"\\nas"), NetworkPolicy.FollowSystem));

        Assert.Equal(ProviderErrorCode.Connection, ex.Code);
        Assert.Contains("共享名", ex.Message);
    }

    // ---------------------------------------------------------------- helpers

    private static SmbMediaProvider Provider(string root)
        => new("smb:test", new SmbAddress("nas", "media", root, @"nas\media"), userName: null, password: null);

    private static MediaServer Server(string url)
        => new("smb:nas", ServerType.Smb, "nas", [new ServerEndpoint(url)]);
}

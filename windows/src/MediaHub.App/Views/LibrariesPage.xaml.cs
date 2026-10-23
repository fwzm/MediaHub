using MediaHub.App.Services;
using MediaHub.Domain;
using MediaHub.Providers;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;

namespace MediaHub.App.Views;

/// <summary>One library tile, tagged with the source it belongs to.</summary>
public sealed record LibraryCard(string ServerId, string LibraryId, string Name, string SourceLabel, string KindLabel)
{
    /// <summary>
    /// Stable, unique automation identifier for this tile.
    /// </summary>
    /// <remarks>
    /// <para>
    /// Before this existed, two tiles from different sources shared the same accessible name - both a
    /// local folder entry and a WebDAV entry read as "文件夹浏览" - so an automation script could only
    /// reach one of them by guessing, and the WebDAV path never got exercised at all.
    /// </para>
    /// <para>
    /// The form is <c>role|source|item</c>. A pipe is used as the separator because a source id
    /// legitimately contains colons (<c>webdav:127.0.0.1:8181</c>), so splitting on a colon would be
    /// ambiguous. A source with no catalogue - a browse-only source - has the fixed item token
    /// <c>root</c>, which is what makes "open this source's root folder" addressable.
    /// </para>
    /// <para>
    /// Both components are escaped. Neither carries a credential: a source id is type plus host or a
    /// server-reported id, and a library id is a server-side collection identifier. Nothing here is
    /// derived from a password, a token or a credentialed URL, and none of it may become so - the
    /// identifier ends up in UIA dumps and screenshots.
    /// </para>
    /// </remarks>
    public string AutomationId => string.IsNullOrEmpty(LibraryId)
        ? "browse|" + Uri.EscapeDataString(ServerId) + "|root"
        : "library|" + Uri.EscapeDataString(ServerId) + "|" + Uri.EscapeDataString(LibraryId);
}

public sealed partial class LibrariesPage : Page
{
    private bool _loading;

    public LibrariesPage()
    {
        InitializeComponent();
        Loaded += OnLoaded;
    }

    private async void OnLoaded(object sender, RoutedEventArgs e) => await LoadAsync();

    private void OnReloadClick(object sender, RoutedEventArgs e) => _ = LoadAsync();

    private async Task LoadAsync()
    {
        if (_loading) return;
        _loading = true;
        BusyRing.IsActive = true;
        StatusBar.IsOpen = false;

        try
        {
            // A source that implements Library contributes its catalogues; a source that implements
            // only Browse contributes its root folder. Both are ways of reaching content, and a source
            // exposing only the second used to be invisible here - which is why a connected WebDAV
            // source could not be opened from anywhere in the shell.
            //
            // Sources that are offline or need a login are still listed when they can be browsed. An
            // unreachable server that simply disappears from this page is indistinguishable from a
            // deleted one, and the user has nowhere to go to find out what happened or to retry - the
            // folder browser is exactly that place. Only enumerating catalogues requires a live
            // session, so that part is skipped for them.
            var entries = App.Services.Servers.Servers
                .Where(e => e.Handle is { } h && (h.Library is not null || h.Browse is not null))
                .ToList();

            // A refresh used to only re-render already-known statuses: a source that had gone offline
            // stayed offline here until the user found the 媒体源 page and reconnected by hand, so the
            // refresh button could not recover anything. Retrying the connection makes this button the
            // recovery path it looks like. Sources that are fine are not re-checked, so the common case
            // costs nothing.
            foreach (ServerEntry stale in entries.Where(x => x.Status != ServerStatus.Online && x.Type != ServerType.Local).ToList())
            {
                await App.Services.Servers.RetestAsync(stale.ServerId);
            }
            if (entries.Count == 0)
            {
                LibraryGrid.ItemsSource = Array.Empty<LibraryCard>();
                StatusBar.Severity = InfoBarSeverity.Informational;
                StatusBar.Title = "没有可用的媒体库";
                StatusBar.Message = "请先在“媒体源”中添加并登录 Emby、Jellyfin 或 WebDAV 服务器。";
                StatusBar.IsOpen = true;
                return;
            }

            var cards = new List<LibraryCard>();
            var failures = new List<string>();

            // Sources are queried independently so one unreachable server cannot blank the page.
            foreach (ServerEntry entry in entries)
            {
                ProviderHandle handle = entry.Handle!;
                bool live = entry.Status == ServerStatus.Online;
                if (handle.Library is null || !live)
                {
                    if (handle.Browse is null)
                    {
                        failures.Add(entry.DisplayName + "：" + entry.StatusText);
                        continue;
                    }

                    // Browse-only source, or a source we cannot enumerate right now: offer its root
                    // folder. An empty library id is the marker the click handler reads to open the
                    // folder browser instead of a library page.
                    cards.Add(new LibraryCard(
                        entry.ServerId,
                        "",
                        entry.DisplayName,
                        entry.DisplayName,
                        live ? "文件夹浏览" : "文件夹浏览 · " + ShortStatus(entry.Status)));
                    continue;
                }

                try
                {
                    IReadOnlyList<MediaLibrary> libraries = await handle.Library.GetLibrariesAsync();
                    cards.AddRange(libraries.Select(l => new LibraryCard(
                        handle.ServerId,
                        l.Id,
                        l.Name,
                        handle.Server.DisplayName,
                        DescribeKind(l.Type))));
                }
                catch (ProviderException ex)
                {
                    failures.Add(handle.Server.DisplayName + "：" + ex.Message);
                    if (handle.Browse is not null)
                    {
                        cards.Add(new LibraryCard(
                            entry.ServerId,
                            "",
                            entry.DisplayName,
                            entry.DisplayName,
                            "文件夹浏览 · " + ShortStatus(entry.Status)));
                    }
                }
            }

            LibraryGrid.ItemsSource = cards;

            if (failures.Count > 0)
            {
                StatusBar.Severity = InfoBarSeverity.Warning;
                StatusBar.Title = failures.Count + " 个媒体源未能加载";
                StatusBar.Message = string.Join("；", failures);
                StatusBar.IsOpen = true;
            }
        }
        finally
        {
            BusyRing.IsActive = false;
            _loading = false;
        }
    }

    /// <summary>Two or three characters describing why a source cannot be listed in full.</summary>
    private static string ShortStatus(ServerStatus status) => status switch
    {
        ServerStatus.Unreachable => "离线",
        ServerStatus.AuthRequired => "需要登录",
        ServerStatus.AuthFailed => "认证失败",
        ServerStatus.Checking => "连接中",
        _ => "未连接",
    };

    private static string DescribeKind(LibraryType type) => type switch
    {
        LibraryType.Movies => "电影",
        LibraryType.TvShows => "剧集",
        LibraryType.Music => "音乐",
        LibraryType.Photos => "照片",
        LibraryType.Mixed => "混合内容",
        LibraryType.Folder => "文件夹",
        _ => "媒体库",
    };

    private void OnLibraryClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is not LibraryCard card) return;

        // An empty library id means the source has no catalogue to open, only a tree to walk.
        if (string.IsNullOrEmpty(card.LibraryId))
        {
            App.MainWindow?.NavigateShell(typeof(BrowsePage),
                new Services.BrowseNavigation(card.ServerId, null, null, card.Name));
            return;
        }

        App.MainWindow?.NavigateShell(typeof(LibraryPage),
            new Services.LibraryNavigation(card.ServerId, card.LibraryId, card.Name));
    }
}

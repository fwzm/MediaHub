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
            var handles = App.Services.Servers.OnlineHandles
                .Where(h => h.Library is not null || h.Browse is not null)
                .ToList();
            if (handles.Count == 0)
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
            foreach (ProviderHandle handle in handles)
            {
                if (handle.Library is null)
                {
                    // Browse-only source: offer its root folder. An empty library id is the marker the
                    // click handler reads to open the folder browser instead of a library page.
                    cards.Add(new LibraryCard(
                        handle.ServerId,
                        "",
                        handle.Server.DisplayName,
                        handle.Server.DisplayName,
                        "文件夹浏览"));
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

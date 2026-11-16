using MediaHub.Domain;
using System.Collections.ObjectModel;
using MediaHub.App.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace MediaHub.App.Views;

public sealed partial class LocalLibraryPage : Page
{
    private readonly ObservableCollection<LocalMediaEntry> _items = new();

    public LocalLibraryPage()
    {
        InitializeComponent();
        MediaGrid.ItemsSource = _items;
        SkeletonPanel.ItemsSource = new object[8];
        App.Services.LocalLibrary.ItemDiscovered += OnItemDiscovered;
        App.Services.LocalLibrary.ScanCompleted += OnScanCompleted;
        Loaded += OnLoaded;
        Unloaded += OnUnloaded;
    }

    private async void OnLoaded(object sender, RoutedEventArgs e)
    {
        RefreshFolders();
        if (App.Services.Settings.Current.LocalFolders.Count > 0 && _items.Count == 0)
        {
            await ScanAsync();
        }
        UpdateState();
    }

    private void OnUnloaded(object sender, RoutedEventArgs e)
    {
        App.Services.LocalLibrary.ItemDiscovered -= OnItemDiscovered;
        App.Services.LocalLibrary.ScanCompleted -= OnScanCompleted;
    }

    private void OnItemDiscovered(object? sender, LocalMediaEntry entry)
    {
        // Discovery runs on a worker thread; the observable collection must be touched on the UI thread.
        DispatcherQueue.TryEnqueue(() =>
        {
            _items.Add(entry);
            // The first file is proof the scan is working; placeholders would only cover real tiles.
            if (_items.Count == 1)
            {
                HideSkeleton();
                UpdateState();
            }
        });
    }

    private void OnScanCompleted(object? sender, EventArgs e)
        => DispatcherQueue.TryEnqueue(() =>
        {
            ScanRing.IsActive = false;
            RescanButton.IsEnabled = true;
            HideSkeleton();
            UpdateState();
        });

    private void RefreshFolders()
    {
        var folders = App.Services.Settings.Current.LocalFolders;
        FolderList.ItemsSource = folders.Select(f => new FolderRow(f, System.IO.Directory.Exists(f))).ToList();
    }

    /// <summary>
    /// Distinguishes "no folder yet" from "folders, but nothing playable in them". They used to share
    /// one banner, which told a user who had already added a folder to go add one.
    /// </summary>
    private void UpdateState()
    {
        bool scanning = ScanRing.IsActive;
        bool hasFolders = App.Services.Settings.Current.LocalFolders.Count > 0;

        if (_items.Count > 0 || scanning)
        {
            EmptyPanel.Visibility = Visibility.Collapsed;
            return;
        }

        StateTitle.Text = hasFolders ? "这些文件夹里没有找到视频" : "还没有添加本地文件夹";
        EmptyText.Text = hasFolders
            ? "支持常见视频与字幕格式。可以添加其他文件夹，或直接打开单个文件。"
            : "点击“添加文件夹”选择包含视频的目录，或直接“打开文件…”播放单个视频。";
        EmptyPanel.Visibility = Visibility.Visible;
    }

    private void ShowSkeleton()
    {
        EmptyPanel.Visibility = Visibility.Collapsed;
        SkeletonPanel.Visibility = Visibility.Visible;
        UiMotion.Pulse(SkeletonPanel);
    }

    private void HideSkeleton()
    {
        if (SkeletonPanel.Visibility != Visibility.Visible) return;
        UiMotion.Settle(SkeletonPanel);
        SkeletonPanel.Visibility = Visibility.Collapsed;
    }

    private async Task ScanAsync()
    {
        ScanRing.IsActive = true;
        RescanButton.IsEnabled = false;
        _items.Clear();
        if (App.Services.Settings.Current.LocalFolders.Count > 0) ShowSkeleton();
        await App.Services.LocalLibrary.ScanAsync();
    }

    private async void OnRescanClick(object sender, RoutedEventArgs e) => await ScanAsync();

    private async void OnAddFolderClick(object sender, RoutedEventArgs e)
    {
        string? folder = await FilePickerService.PickFolderAsync();
        if (folder is null) return;

        App.MainWindow?.AddLocalFolder(folder);
        RefreshFolders();
        UpdateState();
        await ScanAsync();
    }

    private async void OnOpenFileClick(object sender, RoutedEventArgs e)
    {
        // Audio included: the file picker is explicit user intent, so the narrower library-scan
        // extension set would only hide files the engine can play.
        string? file = await FilePickerService.PickFileAsync(LocalLibraryService.SupportedMediaExtensions);
        if (file is null) return;
        PlayLocal(file);
    }

    private void OnMediaItemClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is LocalMediaEntry entry) PlayLocal(entry.Path);
    }

    private static void PlayLocal(string path)
    {
        PlaybackHistoryEntry? entry = App.Services.PlaybackHistory.Find(path);
        var request = new PlaybackRequest
        {
            LocalPath = path,
            Title = System.IO.Path.GetFileNameWithoutExtension(path),
            DisplayUrl = path,
            StartPositionTicks = App.Services.Settings.Current.ResumePlayback ? (entry?.ResumePositionTicks ?? 0) : 0,
            DeclaredDuration = entry is { DurationTicks: > 0 } ? TimeSpan.FromTicks(entry.DurationTicks) : null,
            ExternalSubtitles = LocalLibraryService.FindSiblingSubtitles(path),
        };
        App.MainWindow?.EnterPlayer(request);
    }

    private sealed record FolderRow(string Path, bool Exists)
    {
        public string Status => Exists ? "" : "（目录不可用）";
    }
}

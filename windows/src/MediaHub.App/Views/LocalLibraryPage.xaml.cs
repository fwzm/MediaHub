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
        UpdateEmptyHint();
    }

    private void OnUnloaded(object sender, RoutedEventArgs e)
    {
        App.Services.LocalLibrary.ItemDiscovered -= OnItemDiscovered;
        App.Services.LocalLibrary.ScanCompleted -= OnScanCompleted;
    }

    private void OnItemDiscovered(object? sender, LocalMediaEntry entry)
    {
        // Discovery runs on a worker thread; the observable collection must be touched on the UI thread.
        DispatcherQueue.TryEnqueue(() => _items.Add(entry));
    }

    private void OnScanCompleted(object? sender, EventArgs e)
        => DispatcherQueue.TryEnqueue(() =>
        {
            ScanRing.IsActive = false;
            RescanButton.IsEnabled = true;
            UpdateEmptyHint();
        });

    private void RefreshFolders()
    {
        var folders = App.Services.Settings.Current.LocalFolders;
        FolderList.ItemsSource = folders.Select(f => new FolderRow(f, System.IO.Directory.Exists(f))).ToList();
    }

    private void UpdateEmptyHint()
        => EmptyHint.IsOpen = App.Services.Settings.Current.LocalFolders.Count == 0;

    private async Task ScanAsync()
    {
        ScanRing.IsActive = true;
        RescanButton.IsEnabled = false;
        _items.Clear();
        await App.Services.LocalLibrary.ScanAsync();
    }

    private async void OnRescanClick(object sender, RoutedEventArgs e) => await ScanAsync();

    private async void OnAddFolderClick(object sender, RoutedEventArgs e)
    {
        string? folder = await FilePickerService.PickFolderAsync();
        if (folder is null) return;

        App.MainWindow?.AddLocalFolder(folder);
        RefreshFolders();
        UpdateEmptyHint();
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
            StartPositionTicks = App.Services.Settings.Current.ResumePlayback ? (entry?.PositionTicks ?? 0) : 0,
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

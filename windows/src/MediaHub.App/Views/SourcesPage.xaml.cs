using MediaHub.App.Services;
using MediaHub.Domain;
using MediaHub.Net;
using MediaHub.Providers;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace MediaHub.App.Views;

public sealed partial class SourcesPage : Page
{
    public SourcesPage()
    {
        InitializeComponent();
        Loaded += OnLoaded;
        Unloaded += OnUnloaded;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        ServerList.ItemsSource = App.Services.Servers.Servers;
        App.Services.Servers.Changed += OnServersChanged;
    }

    private void OnUnloaded(object sender, RoutedEventArgs e)
        => App.Services.Servers.Changed -= OnServersChanged;

    private void OnServersChanged(object? sender, EventArgs e) => DispatcherQueue.TryEnqueue(() =>
    {
        if (ServerList.ItemsSource is null) ServerList.ItemsSource = App.Services.Servers.Servers;
    });

    private void ShowStatus(InfoBarSeverity severity, string title, string message)
    {
        StatusBar.Severity = severity;
        StatusBar.Title = title;
        StatusBar.Message = message;
        StatusBar.IsOpen = true;
    }

    // ---------------------------------------------------------------- add

    private async void OnAddClick(object sender, RoutedEventArgs e)
    {
        var providers = App.Services.Servers.AvailableProviders;
        if (providers.Count == 0)
        {
            ShowStatus(InfoBarSeverity.Warning, "没有可用的媒体源类型", "当前版本未包含任何媒体服务器数据源。");
            return;
        }

        var typeBox = new ComboBox { MinWidth = 220, HorizontalAlignment = HorizontalAlignment.Stretch };
        foreach (ProviderDescriptor descriptor in providers)
        {
            typeBox.Items.Add(new ComboBoxItem { Content = descriptor.DisplayName, Tag = descriptor.Type });
        }
        typeBox.SelectedIndex = 0;

        var urlBox = new TextBox
        {
            PlaceholderText = "http://192.168.1.10:8096 或 https://media.example.com/emby",
            HorizontalAlignment = HorizontalAlignment.Stretch,
        };
        var userBox = new TextBox { PlaceholderText = "用户名", HorizontalAlignment = HorizontalAlignment.Stretch };
        var passwordBox = new PasswordBox { PlaceholderText = "密码", HorizontalAlignment = HorizontalAlignment.Stretch };
        var hint = new TextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            Style = (Style)Application.Current.Resources["MediaHubCaption"],
        };

        var testButton = new Button { Content = "测试连接" };
        var panel = new StackPanel { Spacing = 8, Width = 420 };
        panel.Children.Add(new TextBlock { Text = "媒体源类型" });
        panel.Children.Add(typeBox);
        panel.Children.Add(new TextBlock { Text = "服务器地址" });
        panel.Children.Add(urlBox);
        panel.Children.Add(new TextBlock { Text = "账号" });
        panel.Children.Add(userBox);
        panel.Children.Add(new TextBlock { Text = "密码" });
        panel.Children.Add(passwordBox);
        panel.Children.Add(testButton);
        panel.Children.Add(hint);

        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = "添加媒体源",
            Content = panel,
            PrimaryButtonText = "登录并添加",
            CloseButtonText = "取消",
            DefaultButton = ContentDialogButton.Primary,
        };

        testButton.Click += async (_, _) =>
        {
            testButton.IsEnabled = false;
            hint.Text = "正在连接…";
            BusyRing.IsActive = true;
            try
            {
                (bool ok, string message) = await App.Services.Servers
                    .TestConnectionAsync(urlBox.Text, SelectedType(typeBox), NetworkPolicy.FollowSystem);
                hint.Text = message;
            }
            finally
            {
                BusyRing.IsActive = false;
                testButton.IsEnabled = true;
            }
        };

        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;

        BusyRing.IsActive = true;
        try
        {
            (bool ok, string message) = await App.Services.Servers.AddAsync(
                urlBox.Text, SelectedType(typeBox), userBox.Text, passwordBox.Password, NetworkPolicy.FollowSystem);
            ShowStatus(ok ? InfoBarSeverity.Success : InfoBarSeverity.Error,
                ok ? "已添加媒体源" : "添加失败", message);
        }
        finally
        {
            BusyRing.IsActive = false;
        }
    }

    private static ServerType SelectedType(ComboBox box)
        => box.SelectedItem is ComboBoxItem { Tag: ServerType type } ? type : ServerType.Emby;

    // ---------------------------------------------------------------- item actions

    private async void OnTestClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not ServerEntry entry) return;
        BusyRing.IsActive = true;
        try
        {
            (bool ok, string message) = await App.Services.Servers.RetestAsync(entry.ServerId);
            ShowStatus(ok ? InfoBarSeverity.Success : InfoBarSeverity.Warning, entry.DisplayName, message);
        }
        finally
        {
            BusyRing.IsActive = false;
        }
    }

    private async void OnReconnectClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not ServerEntry entry) return;
        if (entry.Type == ServerType.Local)
        {
            ShowStatus(InfoBarSeverity.Informational, "本地媒体源", "本地媒体源不需要登录。请在“本地媒体”中添加文件夹。");
            return;
        }

        var passwordBox = new PasswordBox { PlaceholderText = "密码", HorizontalAlignment = HorizontalAlignment.Stretch };
        var userBox = new TextBox
        {
            PlaceholderText = "用户名",
            Text = entry.UserName ?? "",
            HorizontalAlignment = HorizontalAlignment.Stretch,
        };
        var panel = new StackPanel { Spacing = 8, Width = 380 };
        panel.Children.Add(new TextBlock { Text = "账号" });
        panel.Children.Add(userBox);
        panel.Children.Add(new TextBlock { Text = "密码" });
        panel.Children.Add(passwordBox);

        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = "重新登录：" + entry.DisplayName,
            Content = panel,
            PrimaryButtonText = "登录",
            CloseButtonText = "取消",
            DefaultButton = ContentDialogButton.Primary,
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;

        BusyRing.IsActive = true;
        try
        {
            (bool ok, string message) = await App.Services.Servers.ReconnectAsync(entry.ServerId, passwordBox.Password);
            ShowStatus(ok ? InfoBarSeverity.Success : InfoBarSeverity.Error,
                ok ? "重新登录成功" : "重新登录失败", message);
        }
        finally
        {
            BusyRing.IsActive = false;
        }
    }

    private async void OnDeleteClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not ServerEntry entry) return;

        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = "删除媒体源",
            Content = "将删除“" + entry.DisplayName + "”及其在本机保存的账号凭据。此操作不可撤销。",
            PrimaryButtonText = "删除",
            CloseButtonText = "取消",
            DefaultButton = ContentDialogButton.Close,
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;

        await App.Services.Servers.DeleteAsync(entry.ServerId);
        ShowStatus(InfoBarSeverity.Success, "已删除", entry.DisplayName + " 及其凭据已从本机移除。");
    }

    private async void OnRefreshClick(object sender, RoutedEventArgs e)
    {
        BusyRing.IsActive = true;
        RefreshButton.IsEnabled = false;
        try
        {
            foreach (ServerEntry entry in App.Services.Servers.Servers.ToList())
            {
                if (entry.Type == ServerType.Local) continue;
                await App.Services.Servers.RetestAsync(entry.ServerId);
            }
            ShowStatus(InfoBarSeverity.Informational, "检测完成", "已重新检测全部媒体源。");
        }
        finally
        {
            BusyRing.IsActive = false;
            RefreshButton.IsEnabled = true;
        }
    }
}

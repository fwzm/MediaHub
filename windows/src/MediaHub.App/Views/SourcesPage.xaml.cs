using MediaHub.App.Services;
using MediaHub.Domain;
using MediaHub.Net;
using MediaHub.Providers;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
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
        SyncEmptyState();
    }

    private void OnUnloaded(object sender, RoutedEventArgs e)
        => App.Services.Servers.Changed -= OnServersChanged;

    private void OnServersChanged(object? sender, EventArgs e) => DispatcherQueue.TryEnqueue(() =>
    {
        if (ServerList.ItemsSource is null) ServerList.ItemsSource = App.Services.Servers.Servers;
        SyncEmptyState();
    });

    private void SyncEmptyState()
        => EmptyPanel.Visibility = App.Services.Servers.Servers.Count == 0
            ? Visibility.Visible
            : Visibility.Collapsed;

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

        var urlLabel = new TextBlock { Text = "服务器地址" };
        var urlBox = new TextBox
        {
            PlaceholderText = "http://192.168.1.10:8096 或 https://media.example.com/emby",
            HorizontalAlignment = HorizontalAlignment.Stretch,
        };
        var userLabel = new TextBlock { Text = "账号" };
        var userBox = new TextBox { PlaceholderText = "用户名", HorizontalAlignment = HorizontalAlignment.Stretch };
        var passwordLabel = new TextBlock { Text = "密码" };
        var passwordBox = new PasswordBox { PlaceholderText = "密码", HorizontalAlignment = HorizontalAlignment.Stretch };
        // The dialog is built in code, so these fields have no x:Name to fall back on. Without explicit
        // ids a driver can only reach "the first Edit in the window", which is a guess that silently
        // starts filling the shell's search box the moment the layout changes.
        AutomationProperties.SetAutomationId(typeBox, "sources.type");
        AutomationProperties.SetAutomationId(urlBox, "sources.url");
        AutomationProperties.SetAutomationId(userBox, "sources.user");
        AutomationProperties.SetAutomationId(passwordBox, "sources.password");
        var hint = new TextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            Style = (Style)Application.Current.Resources["MediaHubCaption"],
        };
        // The probe result is the only place a failure reason appears inside this dialog, so a driver
        // (and a screen reader) needs an id for it rather than "some TextBlock in the dialog".
        AutomationProperties.SetAutomationId(hint, "sources.hint");

        var testButton = new Button { Content = "测试连接" };
        var panel = new StackPanel { Spacing = 8, Width = 420 };
        panel.Children.Add(new TextBlock { Text = "媒体源类型" });
        panel.Children.Add(typeBox);
        panel.Children.Add(urlLabel);
        panel.Children.Add(urlBox);
        panel.Children.Add(userLabel);
        panel.Children.Add(userBox);
        panel.Children.Add(passwordLabel);
        panel.Children.Add(passwordBox);
        panel.Children.Add(testButton);
        panel.Children.Add(hint);

        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = "添加媒体源",
            // The dialog has no built-in scrolling. At the default window size the fixed-height body
            // clipped the password box — it was not merely off-screen, it was not in the UI tree, so a
            // user could not fill the form at all without maximising the window first.
            Content = new ScrollViewer
            {
                Content = panel,
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                MaxHeight = 480,
            },
            PrimaryButtonText = "登录并添加",
            CloseButtonText = "取消",
            DefaultButton = ContentDialogButton.Primary,
        };

        // A plain address has no account, so asking for one would be a field that cannot be filled
        // correctly. The form follows the selected kind instead of showing credentials for every source.
        void SyncType()
        {
            ServerType type = SelectedType(typeBox);
            bool isOneDrive = type == ServerType.OneDrive;
            bool needsLogin = type is not (ServerType.Http or ServerType.Smb or ServerType.OneDrive);

            // A share can be anonymous (the current Windows identity) or protected, so the fields stay
            // available but are marked optional. Hiding them would make a password-protected share
            // impossible to add; demanding them would block the anonymous case.
            bool optionalLogin = type == ServerType.Smb;
            Visibility login = optionalLogin || needsLogin ? Visibility.Visible : Visibility.Collapsed;
            userLabel.Visibility = login;
            userBox.Visibility = login;
            passwordLabel.Visibility = login;
            passwordBox.Visibility = login;
            userLabel.Text = optionalLogin ? "账号（可选）" : "账号";
            passwordLabel.Text = optionalLogin ? "密码（可选）" : "密码";
            passwordBox.PlaceholderText = optionalLogin ? "留空使用当前 Windows 账号" : "密码";

            // OneDrive is identified by the account that signs in, not by an address; leaving an empty
            // URL box on screen would invite typing one and imply it is used for something.
            urlLabel.Visibility = isOneDrive ? Visibility.Collapsed : Visibility.Visible;
            urlBox.Visibility = isOneDrive ? Visibility.Collapsed : Visibility.Visible;

            string addressHint = type switch
            {
                ServerType.Http => "https://example.com/video.mp4 或 https://example.com/list.m3u8",
                ServerType.Smb => @"\\192.168.1.10\media 或 smb://nas/media",
                _ => "http://192.168.1.10:8096 或 https://media.example.com/emby",
            };
            urlBox.PlaceholderText = addressHint;
            // The placeholder is a visual hint only; help text is what a screen reader announces and
            // what a driver can read back, so the two are kept in step.
            AutomationProperties.SetHelpText(urlBox, addressHint);
            dialog.PrimaryButtonText = needsLogin || isOneDrive ? "登录并添加" : "添加";

            hint.Text = !isOneDrive
                ? ""
                : App.Services.Servers.OneDriveConfigured
                    ? "点击“登录并添加”会在系统浏览器打开 Microsoft 登录页；授权完成后自动添加。密码只输入在浏览器里。"
                    : App.Services.Servers.OneDriveConfigHelp;
        }

        typeBox.SelectionChanged += (_, _) => SyncType();
        SyncType();

        testButton.Click += async (_, _) =>
        {
            testButton.IsEnabled = false;
            hint.Text = "正在连接…";
            BusyRing.IsActive = true;
            try
            {
                ServerType type = SelectedType(typeBox);
                (bool ok, string message) = await App.Services.Servers
                    .TestConnectionAsync(urlBox.Text, type, NetworkPolicy.FollowSystem, default,
                        userBox.Text, passwordBox.Password);

                // A share and an OAuth source have no address to probe for a TLS handshake, so there is
                // no certificate to read; probing one would just spend eight seconds failing.
                if (!ok && type is not (ServerType.Smb or ServerType.OneDrive))
                {
                    // A failed probe on an HTTPS address is usually a certificate the platform does not
                    // trust. Reading it here keeps the answer inside the open dialog (a second
                    // ContentDialog cannot be shown while this one is up) and tells the user exactly
                    // what the next step will ask them to confirm.
                    PresentedCertificate? certificate = await ServerManager.InspectCertificateAsync(
                        urlBox.Text, type, TimeSpan.FromSeconds(8));
                    if (certificate is not null)
                    {
                        hint.Text = "该服务器使用了本机不信任的证书：\n" + certificate.Summary +
                                    "\n\n仍然可以添加，添加时会让你选择：信任该指纹（推荐），或仅对该服务器跳过证书校验。";
                        return;
                    }
                }

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
            ServerType type = SelectedType(typeBox);

            // The browser is the sign-in surface, so the dialog is already gone and the only feedback
            // left is the status bar. Say what is happening before the wait starts, not after.
            if (type == ServerType.OneDrive)
            {
                if (!App.Services.Servers.OneDriveConfigured)
                {
                    ShowStatus(InfoBarSeverity.Warning, "OneDrive 尚未配置",
                        App.Services.Servers.OneDriveConfigHelp);
                    return;
                }

                ShowStatus(InfoBarSeverity.Informational, "等待浏览器授权",
                    "已在系统浏览器打开 Microsoft 登录页，请完成授权。密码只输入在浏览器中。");
            }

            ServerAddResult result = await App.Services.Servers.AddAsync(
                urlBox.Text, type, userBox.Text, passwordBox.Password, NetworkPolicy.FollowSystem);

            if (!result.Ok && result.UntrustedCertificate is PresentedCertificate certificate)
            {
                // The first attempt stopped at the TLS handshake, so the password was never written to
                // the socket. The retry only happens after the user has chosen how this one server is to
                // be trusted: by fingerprint (recommended) or, explicitly, not at all.
                CertificateChoice choice = await ConfirmCertificateAsync(certificate);
                if (choice == CertificateChoice.Cancel)
                {
                    ShowStatus(InfoBarSeverity.Warning, "未添加媒体源",
                        "已取消。服务器未添加，账号与令牌都没有发送。");
                    return;
                }

                NetworkPolicy retryPolicy = choice == CertificateChoice.Pin
                    ? NetworkPolicy.FollowSystem with { TrustedCertificateSha256 = certificate.Sha256 }
                    : NetworkPolicy.FollowSystem with { AllowUntrustedCertificate = true };

                result = await App.Services.Servers.AddAsync(
                    urlBox.Text, type, userBox.Text, passwordBox.Password, retryPolicy);

                if (result.Ok && choice == CertificateChoice.SkipValidation)
                {
                    ShowStatus(InfoBarSeverity.Warning, "已添加媒体源（该服务器不校验证书）",
                        result.Message + " 该服务器的令牌未经证书校验传输，可能被中间人截获。");
                    return;
                }
            }

            ShowStatus(result.Ok ? InfoBarSeverity.Success : InfoBarSeverity.Error,
                result.Ok ? "已添加媒体源" : "添加失败", result.Message);
        }
        finally
        {
            BusyRing.IsActive = false;
        }
    }

    /// <summary>How the user chose to handle a certificate the platform does not trust.</summary>
    private enum CertificateChoice
    {
        Cancel = 0,
        Pin,
        SkipValidation,
    }

    /// <summary>
    /// Shows the certificate a server presented and asks how this one server should be trusted.
    /// </summary>
    /// <remarks>
    /// Two options, both scoped to this server and persisted with it; there is no global switch. The
    /// "do not validate" option states the consequence in the same dialog, because a user who picks it
    /// is accepting that the token can be read by whoever intercepts the connection. The default button
    /// is Cancel: pressing Enter must never decide a security question.
    /// </remarks>
    private async Task<CertificateChoice> ConfirmCertificateAsync(PresentedCertificate certificate)
    {
        var details = new TextBlock
        {
            Text = "主机：" + certificate.Host + "\n" + certificate.Summary,
            TextWrapping = TextWrapping.Wrap,
            IsTextSelectionEnabled = true,
            FontFamily = new Microsoft.UI.Xaml.Media.FontFamily("Consolas"),
        };
        var recommended = new TextBlock
        {
            Text = "“信任该指纹”只为该服务器保存这张证书的指纹，不会关闭全局证书校验；服务器更换证书后需要重新确认。",
            TextWrapping = TextWrapping.Wrap,
            Style = (Style)Application.Current.Resources["MediaHubCaption"],
        };
        var risk = new TextBlock
        {
            Text = "“该台不校验”会跳过证书校验，只在这台服务器上生效：任何能截获这条连接的人都可以冒充服务器，"
                   + "并读到这台服务器的令牌与账号。仅在你完全掌控网络、且服务器证书会频繁更换时选择。",
            TextWrapping = TextWrapping.Wrap,
            Style = (Style)Application.Current.Resources["MediaHubCaption"],
        };
        var panel = new StackPanel { Spacing = 10, Width = 470 };
        panel.Children.Add(details);
        panel.Children.Add(recommended);
        panel.Children.Add(risk);

        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = "信任该服务器的证书？",
            Content = panel,
            PrimaryButtonText = "信任该指纹（推荐）",
            SecondaryButtonText = "该台不校验",
            CloseButtonText = "取消",
            DefaultButton = ContentDialogButton.Close,
        };

        return await dialog.ShowAsync() switch
        {
            ContentDialogResult.Primary => CertificateChoice.Pin,
            ContentDialogResult.Secondary => CertificateChoice.SkipValidation,
            _ => CertificateChoice.Cancel,
        };
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

        // An OAuth source has no password to ask for; its sign-in is the browser page, so the dialog
        // would be a field the user cannot fill meaningfully. Go straight to the browser flow.
        if (entry.Type == ServerType.OneDrive)
        {
            BusyRing.IsActive = true;
            try
            {
                if (!App.Services.Servers.OneDriveConfigured)
                {
                    ShowStatus(InfoBarSeverity.Warning, "OneDrive 尚未配置", App.Services.Servers.OneDriveConfigHelp);
                    return;
                }

                ShowStatus(InfoBarSeverity.Informational, "等待浏览器授权",
                    "已在系统浏览器打开 Microsoft 登录页，请完成授权。");
                (bool ok, string message) = await App.Services.Servers.ReconnectAsync(entry.ServerId, "");
                ShowStatus(ok ? InfoBarSeverity.Success : InfoBarSeverity.Error,
                    ok ? "重新登录成功" : "重新登录失败", message);
            }
            finally
            {
                BusyRing.IsActive = false;
            }
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

        BusyRing.IsActive = true;
        try
        {
            await App.Services.Servers.DeleteAsync(entry.ServerId);
            ShowStatus(InfoBarSeverity.Success, "已删除", entry.DisplayName + " 及其凭据已从本机移除。");
        }
        catch (Exception ex)
        {
            // An exception escaping an async void handler terminates the process. This is the only
            // destructive action on the page, so it reports its own failure instead of taking the app
            // down with it, and the busy indicator is always reset.
            ShowStatus(InfoBarSeverity.Error, "删除失败", ex.Message);
        }
        finally
        {
            BusyRing.IsActive = false;
        }
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

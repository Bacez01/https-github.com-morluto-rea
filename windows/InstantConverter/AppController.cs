using System;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Threading;
using WinForms = System.Windows.Forms;

namespace InstantConverter;

/// <summary>Wires the bubble, the selection/clipboard watchers and the tray icon.</summary>
internal sealed class AppController : IDisposable
{
    private readonly AppSettings _settings = AppSettings.Load();
    private readonly RateService _rates;
    private readonly BubbleWindow _bubble;
    private readonly WinForms.NotifyIcon _tray;
    private readonly WinForms.ContextMenuStrip _menu;
    private readonly WinForms.ToolStripMenuItem _toggleItem;
    private readonly WinForms.ToolStripMenuItem _rateItem;
    private readonly DispatcherTimer _rateTimer;
    private SelectionWatcher? _watcher;
    private DateTime _suppressClipboardUntil = DateTime.MinValue;

    public AppController()
    {
        _rates = new RateService(_settings);
        _bubble = new BubbleWindow(_settings, _rates);
        _bubble.CopyResult = text =>
        {
            Suppress(TimeSpan.FromSeconds(1));
            ClipboardHelper.TrySetText(text);
        };

        // ---- menu (tray icon and right-click on the bubble)
        _menu = new WinForms.ContextMenuStrip();
        _toggleItem = new WinForms.ToolStripMenuItem(L.MenuHide, null, (_, _) => ToggleBubble());
        var autoCopy = new WinForms.ToolStripMenuItem(L.MenuAutoCopy) { CheckOnClick = true, Checked = _settings.AutoCopy };
        autoCopy.CheckedChanged += (_, _) => { _settings.AutoCopy = autoCopy.Checked; _settings.Save(); };
        var watchClip = new WinForms.ToolStripMenuItem(L.MenuWatchClipboard) { CheckOnClick = true, Checked = _settings.WatchClipboard };
        watchClip.CheckedChanged += (_, _) => { _settings.WatchClipboard = watchClip.Checked; _settings.Save(); };
        var startup = new WinForms.ToolStripMenuItem(L.MenuStartup) { CheckOnClick = true };
        startup.CheckedChanged += (_, _) => StartupManager.Set(startup.Checked);
        _rateItem = new WinForms.ToolStripMenuItem("") { Enabled = false };
        var refresh = new WinForms.ToolStripMenuItem(L.MenuRefresh, null, async (_, _) =>
        {
            bool ok = await _rates.RefreshAsync(force: true);
            UpdateRateItem();
            if (!ok) _tray!.ShowBalloonTip(3000, L.AppName, L.RateRefreshFailed, WinForms.ToolTipIcon.Warning);
        });
        var quit = new WinForms.ToolStripMenuItem(L.MenuQuit, null, (_, _) => Application.Current.Shutdown());
        _menu.Items.AddRange(new WinForms.ToolStripItem[]
        {
            _toggleItem, new WinForms.ToolStripSeparator(),
            autoCopy, watchClip, startup, new WinForms.ToolStripSeparator(),
            _rateItem, refresh, new WinForms.ToolStripSeparator(), quit,
        });
        _menu.Opening += (_, _) =>
        {
            startup.Checked = StartupManager.IsEnabled;
            _toggleItem.Text = _bubble.IsVisible ? L.MenuHide : L.MenuShow;
            UpdateRateItem();
        };

        _tray = new WinForms.NotifyIcon
        {
            Icon = IconFactory.Create(),
            Text = L.AppName,
            ContextMenuStrip = _menu,
            Visible = true,
        };
        _tray.MouseClick += (_, e) =>
        {
            if (e.Button == WinForms.MouseButtons.Left) ToggleBubble();
        };
        _bubble.MenuRequested += () => _menu.Show(WinForms.Cursor.Position);

        // ---- bubble and watchers
        _bubble.SourceInitialized += (_, _) =>
        {
            var source = HwndSource.FromHwnd(_bubble.Handle);
            source?.AddHook(WndProc);
            Native.AddClipboardFormatListener(_bubble.Handle);
        };
        _bubble.Show();
        _watcher = new SelectionWatcher(_bubble, _settings, Suppress);

        // ---- rates
        _rateTimer = new DispatcherTimer { Interval = TimeSpan.FromHours(1) };
        _rateTimer.Tick += async (_, _) => await _rates.RefreshAsync();
        _rateTimer.Start();
        _ = _rates.RefreshAsync();

        // ---- first run: start with Windows and explain how it works
        if (_settings.FirstRun)
        {
            _settings.FirstRun = false;
            _settings.Save();
            StartupManager.Set(true);
            _tray.ShowBalloonTip(8000, L.WelcomeTitle, L.WelcomeText, WinForms.ToolTipIcon.Info);
        }
        else
        {
            StartupManager.Refresh();
        }
    }

    private void Suppress(TimeSpan duration)
    {
        // Clipboard changes made by the app itself must not trigger a conversion.
        _suppressClipboardUntil = DateTime.UtcNow + duration;
    }

    private IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == Native.WM_CLIPBOARDUPDATE) _ = OnClipboardChangedAsync();
        return IntPtr.Zero;
    }

    /// <summary>Something was copied (Ctrl+C): convert it when it holds an amount.</summary>
    private async Task OnClipboardChangedAsync()
    {
        if (!_settings.WatchClipboard || DateTime.UtcNow < _suppressClipboardUntil || !_bubble.IsVisible) return;
        await Task.Delay(60);
        if (DateTime.UtcNow < _suppressClipboardUntil) return;
        var text = ClipboardHelper.TryGetText();
        if (text == null || text.Length > 200 || !BubbleWindow.IsConvertible(text, automatic: true)) return;
        _bubble.RememberClipboard(text);
        _bubble.ShowConversion(text, explicitRequest: false);
    }

    private void ToggleBubble()
    {
        if (_bubble.IsVisible)
        {
            _bubble.Collapse();
            _bubble.Hide();
        }
        else
        {
            _bubble.Show();
        }
    }

    private void UpdateRateItem()
    {
        var conv = _rates.Converter();
        string when = _settings.RateUpdatedUtc is { } t
            ? L.RateUpdated(t.ToLocalTime().ToString("g"))
            : L.RateDefault;
        _rateItem.Text = conv.FormatRate(Core.Currency.USD) + " (" + when + ")";
    }

    public void Dispose()
    {
        _watcher?.Dispose();
        _watcher = null;
        if (_bubble.Handle != IntPtr.Zero) Native.RemoveClipboardFormatListener(_bubble.Handle);
        _tray.Visible = false;
        _tray.Dispose();
        _menu.Dispose();
    }
}

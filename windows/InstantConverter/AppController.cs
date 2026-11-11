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
    private readonly WinForms.ToolStripMenuItem[] _rateItems;
    private readonly WinForms.ToolStripMenuItem _rateWhen;
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
        var screenshots = new WinForms.ToolStripMenuItem(L.MenuScreenshots) { CheckOnClick = true, Checked = _settings.ReadScreenshots };
        screenshots.CheckedChanged += (_, _) => { _settings.ReadScreenshots = screenshots.Checked; _settings.Save(); };
        var startup = new WinForms.ToolStripMenuItem(L.MenuStartup) { CheckOnClick = true };
        startup.CheckedChanged += (_, _) => StartupManager.Set(startup.Checked);
        _rateItems = new WinForms.ToolStripMenuItem[RateService.Downloaded.Length];
        for (int i = 0; i < _rateItems.Length; i++) _rateItems[i] = new WinForms.ToolStripMenuItem("") { Enabled = false };
        _rateWhen = new WinForms.ToolStripMenuItem("") { Enabled = false };
        var clearHistory = new WinForms.ToolStripMenuItem(L.MenuClearHistory, null, (_, _) =>
        {
            _settings.History.Clear();
            _settings.Save();
            _bubble.Collapse();
        });
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
            autoCopy, watchClip, screenshots, startup, new WinForms.ToolStripSeparator(),
            _rateItems[0], _rateItems[1], _rateItems[2], _rateWhen, refresh, new WinForms.ToolStripSeparator(),
            clearHistory, quit,
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

    /// <summary>
    /// Something was copied: a screenshot (Win+Shift+S) is read with OCR, a
    /// text (Ctrl+C) is converted when it holds an amount.
    /// </summary>
    private async Task OnClipboardChangedAsync()
    {
        if (DateTime.UtcNow < _suppressClipboardUntil || !_bubble.IsVisible) return;
        await Task.Delay(60);
        if (DateTime.UtcNow < _suppressClipboardUntil) return;

        if (_settings.ReadScreenshots && await TryConvertScreenshotAsync()) return;
        if (!_settings.WatchClipboard) return;

        var text = ClipboardHelper.TryGetText();
        if (text == null || text.Length > 200 || !BubbleWindow.IsConvertible(text, automatic: true)) return;
        _bubble.RememberClipboard(text);
        _bubble.ShowConversion(text, explicitRequest: false);
    }

    private bool _readingScreenshot;

    /// <summary>Returns true when the clipboard held an image (handled here).</summary>
    private async Task<bool> TryConvertScreenshotAsync()
    {
        // Text copied from Word, a web page... may come with a picture: the text wins.
        if (ClipboardHelper.TryGetText() is { Length: > 0 }) return false;
        using var image = ScreenshotReader.TryGetClipboardImage();
        if (image == null) return false;
        if (_readingScreenshot || ScreenshotReader.IsFullScreen(image)) return true;
        _readingScreenshot = true;
        try
        {
            var lines = await ScreenshotReader.ReadLinesAsync(image);
            if (lines == null)
            {
                _bubble.ShowMessage(L.Screenshot, L.NoOcr, L.NoOcrHint);
                return true;
            }
            var found = Core.AmountParser.FindInScreenshot(lines);
            if (found is { } f)
                _bubble.ShowScreenshotConversion(f.Line);
            else
                _bubble.ShowMessage(L.Screenshot, L.NoAmountInScreenshot, L.NoAmountInScreenshotHint);
        }
        catch (Exception ex)
        {
            Program.Log(ex);
        }
        finally
        {
            _readingScreenshot = false;
        }
        return true;
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
        for (int i = 0; i < _rateItems.Length; i++) _rateItems[i].Text = conv.FormatRate(RateService.Downloaded[i]);
        _rateWhen.Text = "(" + (_settings.RateUpdatedUtc is { } t
            ? L.RateUpdated(t.ToLocalTime().ToString("g"))
            : L.RateDefault) + ")";
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

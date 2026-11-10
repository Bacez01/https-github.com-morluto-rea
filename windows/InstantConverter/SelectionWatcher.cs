using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using System.Windows.Automation;
using System.Windows.Threading;

namespace InstantConverter;

/// <summary>
/// Watches the mouse system-wide (low-level hook):
/// - a click outside the expanded bubble collapses it;
/// - the end of a text selection (drag or double-click) in another app reads
///   the selected text and shows its conversion.
///
/// Reading the selection:
/// - browsers: presses Ctrl+C for the user (auto-copy) then restores the
///   clipboard. Browsers don't expose page selections reliably otherwise.
/// - other apps (Word, PDF readers...): asks UI Automation for the selection.
/// </summary>
internal sealed class SelectionWatcher : IDisposable
{
    private static readonly HashSet<string> Browsers = new(StringComparer.OrdinalIgnoreCase)
    {
        "chrome", "msedge", "firefox", "opera", "opera_gx", "brave", "vivaldi", "arc",
        "chromium", "waterfox", "librewolf", "floorp", "yandex", "whale", "thorium", "zen",
    };

    private readonly BubbleWindow _bubble;
    private readonly AppSettings _settings;
    private readonly Dispatcher _dispatcher;
    private readonly Action<TimeSpan> _suppressClipboard;
    private readonly Native.LowLevelMouseProc _proc; // kept alive: the hook calls it
    private readonly DispatcherTimer _debounce;
    private IntPtr _hook;

    private Native.POINT _downPt;
    private Native.POINT _lastDownPt;
    private uint _lastDownTime;
    private bool _downInside;
    private bool _doubleClick;
    private bool _checking;
    private Native.POINT _pendingPt;

    public SelectionWatcher(BubbleWindow bubble, AppSettings settings, Action<TimeSpan> suppressClipboard)
    {
        _bubble = bubble;
        _settings = settings;
        _dispatcher = Dispatcher.CurrentDispatcher;
        _suppressClipboard = suppressClipboard;
        _debounce = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(150) };
        _debounce.Tick += async (_, _) =>
        {
            _debounce.Stop();
            await CheckSelectionAsync(_pendingPt);
        };
        _proc = HookProc;
        _hook = Native.SetWindowsHookEx(Native.WH_MOUSE_LL, _proc, Native.GetModuleHandle(null), 0);
    }

    private IntPtr HookProc(int nCode, IntPtr wParam, IntPtr lParam)
    {
        if (nCode >= 0)
        {
            try
            {
                var info = Marshal.PtrToStructure<Native.MSLLHOOKSTRUCT>(lParam);
                int msg = (int)wParam;
                if (msg is Native.WM_LBUTTONDOWN or Native.WM_RBUTTONDOWN or Native.WM_MBUTTONDOWN)
                {
                    bool inside = _bubble.ContainsScreenPoint(info.pt);
                    // Click outside the expanded bubble: close it and tidy it into its corner.
                    if (!inside && _bubble.IsExpanded) _dispatcher.BeginInvoke(_bubble.Collapse);
                    if (msg == Native.WM_LBUTTONDOWN)
                    {
                        _doubleClick = info.time - _lastDownTime <= Native.GetDoubleClickTime() &&
                                       Math.Abs(info.pt.X - _lastDownPt.X) < 6 &&
                                       Math.Abs(info.pt.Y - _lastDownPt.Y) < 6;
                        _lastDownTime = info.time;
                        _lastDownPt = info.pt;
                        _downPt = info.pt;
                        _downInside = inside;
                    }
                }
                else if (msg == Native.WM_LBUTTONUP && !_downInside)
                {
                    bool dragged = Math.Abs(info.pt.X - _downPt.X) > 6 || Math.Abs(info.pt.Y - _downPt.Y) > 6;
                    if (dragged || _doubleClick)
                    {
                        var pt = info.pt;
                        _dispatcher.BeginInvoke(() =>
                        {
                            _pendingPt = pt;
                            _debounce.Stop();
                            _debounce.Start();
                        });
                    }
                }
            }
            catch
            {
                // Never let an error break the system mouse hook.
            }
        }
        return Native.CallNextHookEx(_hook, nCode, wParam, lParam);
    }

    private async Task CheckSelectionAsync(Native.POINT pt)
    {
        if (_checking || !_bubble.IsVisible) return;
        _checking = true;
        try
        {
            var hwnd = Native.GetForegroundWindow();
            if (hwnd == IntPtr.Zero || hwnd == _bubble.Handle) return;
            Native.GetWindowThreadProcessId(hwnd, out uint pid);
            if (pid == (uint)Environment.ProcessId) return;
            string process = ProcessName(pid);

            string? text = null;
            if (Browsers.Contains(process))
            {
                if (_settings.AutoCopy) text = await ClipboardHelper.CopySelectionAsync(_suppressClipboard);
            }
            else
            {
                var task = Task.Run(() => UiaSelection(pt));
                if (await Task.WhenAny(task, Task.Delay(900)) == task) text = task.Result;
            }

            if (!string.IsNullOrWhiteSpace(text) && text.Length <= 200)
                _bubble.ShowConversion(text, explicitRequest: false);
        }
        catch
        {
            // Ignore: the next selection will try again.
        }
        finally
        {
            _checking = false;
        }
    }

    private static string ProcessName(uint pid)
    {
        try
        {
            using var p = Process.GetProcessById((int)pid);
            return p.ProcessName;
        }
        catch
        {
            return "";
        }
    }

    /// <summary>Selected text through UI Automation, or null.</summary>
    private static string? UiaSelection(Native.POINT pt)
    {
        try
        {
            var text = FromElement(AutomationElement.FocusedElement);
            if (!string.IsNullOrEmpty(text)) return text;

            var el = AutomationElement.FromPoint(new System.Windows.Point(pt.X, pt.Y));
            var walker = TreeWalker.ControlViewWalker;
            for (int i = 0; el != null && i < 8; i++)
            {
                text = FromElement(el);
                if (!string.IsNullOrEmpty(text)) return text;
                el = walker.GetParent(el);
            }
        }
        catch
        {
            // Element vanished or app doesn't support UI Automation.
        }
        return null;
    }

    private static string? FromElement(AutomationElement? el)
    {
        if (el == null) return null;
        if (el.TryGetCurrentPattern(TextPattern.Pattern, out var p) && p is TextPattern tp)
        {
            var ranges = tp.GetSelection();
            if (ranges.Length > 0) return ranges[0].GetText(300);
        }
        return null;
    }

    public void Dispose()
    {
        if (_hook != IntPtr.Zero)
        {
            Native.UnhookWindowsHookEx(_hook);
            _hook = IntPtr.Zero;
        }
    }
}

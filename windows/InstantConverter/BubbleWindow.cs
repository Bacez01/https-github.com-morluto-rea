using System;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Effects;
using System.Windows.Threading;
using InstantConverter.Core;

namespace InstantConverter;

/// <summary>
/// The floating bubble, always on top.
/// - Collapsed: a small round "CFA" bubble parked in a screen corner. It can be
///   dragged and snaps to the nearest corner when released.
/// - Expanded: grows slightly into a card with the amount in CFA francs.
/// - A click anywhere outside collapses it back into its corner (see SelectionWatcher).
/// The window never takes the focus, so the selection in the browser is kept.
/// </summary>
internal sealed class BubbleWindow : Window
{
    private static readonly Color Accent = Color.FromRgb(0x1B, 0x7F, 0x5A);
    private static readonly Regex OwnResult = new(@"(?i)f?cfa|xof");
    private const double Margin_ = 4;

    private readonly AppSettings _settings;
    private readonly RateService _rates;
    private readonly Border _bubble;
    private readonly Border _card;
    private readonly TextBlock _tvSource;
    private readonly TextBlock _tvResult;
    private readonly TextBlock _tvRate;
    private readonly ScaleTransform _cardScale = new(1, 1);

    private bool _expanded;
    private string? _lastResult;
    private string? _lastClipboard;
    private Rect _area = SystemParameters.WorkArea;

    private bool _mouseDown;
    private bool _dragging;
    private Point _downScreen;
    private double _downLeft;
    private double _downTop;
    private DispatcherTimer? _snapTimer;
    private DispatcherTimer? _copiedTimer;

    public event Action? MenuRequested;

    public IntPtr Handle { get; private set; }
    public bool IsExpanded => _expanded;
    public Action<string>? CopyResult { get; set; }

    public BubbleWindow(AppSettings settings, RateService rates)
    {
        _settings = settings;
        _rates = rates;

        WindowStyle = WindowStyle.None;
        AllowsTransparency = true;
        Background = Brushes.Transparent;
        Topmost = true;
        ShowInTaskbar = false;
        ShowActivated = false;
        ResizeMode = ResizeMode.NoResize;
        SizeToContent = SizeToContent.WidthAndHeight;
        Title = L.AppName;
        WindowStartupLocation = WindowStartupLocation.Manual;
        Left = _area.Right - 80;
        Top = _area.Bottom - 80;

        var accent = new SolidColorBrush(Accent);
        _bubble = new Border
        {
            Width = 54,
            Height = 54,
            CornerRadius = new CornerRadius(27),
            Background = accent,
            BorderBrush = Brushes.White,
            BorderThickness = new Thickness(2),
            Margin = new Thickness(8),
            Effect = Shadow(),
            Opacity = 0.95,
            Cursor = Cursors.Hand,
            ToolTip = L.AppName,
            Child = new TextBlock
            {
                Text = "CFA",
                Foreground = Brushes.White,
                FontWeight = FontWeights.Bold,
                FontSize = 13,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
            },
        };

        _tvSource = new TextBlock { Foreground = new SolidColorBrush(Color.FromRgb(0xD8, 0xF3, 0xE8)), FontSize = 13 };
        _tvResult = new TextBlock { Foreground = Brushes.White, FontWeight = FontWeights.Bold, FontSize = 22 };
        _tvRate = new TextBlock
        {
            Foreground = new SolidColorBrush(Color.FromRgb(0xB5, 0xE3, 0xCF)),
            FontSize = 11,
            Margin = new Thickness(0, 2, 0, 0),
        };
        var stack = new StackPanel();
        stack.Children.Add(_tvSource);
        stack.Children.Add(_tvResult);
        stack.Children.Add(_tvRate);

        _card = new Border
        {
            CornerRadius = new CornerRadius(22),
            Background = accent,
            BorderBrush = Brushes.White,
            BorderThickness = new Thickness(2),
            Padding = new Thickness(18, 12, 18, 12),
            Margin = new Thickness(8),
            MinWidth = 170,
            Effect = Shadow(),
            Child = stack,
            Visibility = Visibility.Collapsed,
            RenderTransform = _cardScale,
            Cursor = Cursors.Hand,
            ToolTip = L.ClickToCopy,
        };

        var grid = new Grid();
        grid.Children.Add(_bubble);
        grid.Children.Add(_card);
        Content = grid;

        MouseLeftButtonDown += OnMouseDown;
        MouseMove += OnMouseMove;
        MouseLeftButtonUp += OnMouseUp;
        MouseRightButtonUp += (_, e) =>
        {
            e.Handled = true;
            MenuRequested?.Invoke();
        };

        SourceInitialized += (_, _) =>
        {
            Handle = new WindowInteropHelper(this).Handle;
            var ex = (long)Native.GetWindowLongPtr(Handle, Native.GWL_EXSTYLE);
            Native.SetWindowLongPtr(Handle, Native.GWL_EXSTYLE,
                (IntPtr)(ex | Native.WS_EX_NOACTIVATE | Native.WS_EX_TOOLWINDOW));
        };
        Loaded += (_, _) => MoveToCorner(animate: false);
        // The window size follows its content: keep it glued to its corner.
        SizeChanged += (_, _) =>
        {
            if (!_dragging && _snapTimer == null) MoveToCorner(animate: false);
        };
        SystemParameters.StaticPropertyChanged += (_, e) =>
        {
            if (e.PropertyName == nameof(SystemParameters.WorkArea))
                Dispatcher.BeginInvoke(() => { _area = SystemParameters.WorkArea; MoveToCorner(false); });
        };
    }

    private static Effect Shadow() => new DropShadowEffect { BlurRadius = 10, ShadowDepth = 2, Opacity = 0.35 };

    // ------------------------------------------------------------------ geometry

    /// <summary>True when the screen point (physical pixels) is over the bubble.</summary>
    public bool ContainsScreenPoint(Native.POINT p)
    {
        if (Handle == IntPtr.Zero || !IsVisible) return false;
        Native.GetWindowRect(Handle, out var r);
        return p.X >= r.Left && p.X < r.Right && p.Y >= r.Top && p.Y < r.Bottom;
    }

    private Point CursorDip()
    {
        var px = System.Windows.Forms.Cursor.Position;
        var dpi = VisualTreeHelper.GetDpi(this);
        return new Point(px.X / dpi.DpiScaleX, px.Y / dpi.DpiScaleY);
    }

    private Rect WorkAreaAtCursor()
    {
        var px = System.Windows.Forms.Cursor.Position;
        var wa = System.Windows.Forms.Screen.FromPoint(px).WorkingArea;
        var dpi = VisualTreeHelper.GetDpi(this);
        return new Rect(wa.Left / dpi.DpiScaleX, wa.Top / dpi.DpiScaleY, wa.Width / dpi.DpiScaleX, wa.Height / dpi.DpiScaleY);
    }

    private Point CornerPosition()
    {
        UpdateLayout();
        double w = ActualWidth, h = ActualHeight;
        double x = _settings.CornerRight ? _area.Right - w - Margin_ : _area.Left + Margin_;
        double y = _settings.CornerBottom ? _area.Bottom - h - Margin_ : _area.Top + Margin_;
        return new Point(x, y);
    }

    private void MoveToCorner(bool animate)
    {
        _snapTimer?.Stop();
        _snapTimer = null;
        var target = CornerPosition();
        if (!animate)
        {
            Left = target.X;
            Top = target.Y;
            return;
        }
        double startX = Left, startY = Top;
        int step = 0;
        const int steps = 14;
        _snapTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(15) };
        _snapTimer.Tick += (_, _) =>
        {
            step++;
            double t = 1 - Math.Pow(1 - (double)step / steps, 3); // ease-out
            Left = startX + (target.X - startX) * t;
            Top = startY + (target.Y - startY) * t;
            if (step >= steps)
            {
                _snapTimer?.Stop();
                _snapTimer = null;
            }
        };
        _snapTimer.Start();
    }

    // ------------------------------------------------------------------ mouse

    private void OnMouseDown(object sender, MouseButtonEventArgs e)
    {
        _mouseDown = true;
        _dragging = false;
        _downScreen = CursorDip();
        _downLeft = Left;
        _downTop = Top;
        CaptureMouse();
        e.Handled = true;
    }

    private void OnMouseMove(object sender, MouseEventArgs e)
    {
        if (!_mouseDown) return;
        var p = CursorDip();
        double dx = p.X - _downScreen.X, dy = p.Y - _downScreen.Y;
        if (!_dragging && !_expanded && (Math.Abs(dx) > 4 || Math.Abs(dy) > 4))
        {
            _dragging = true;
            _snapTimer?.Stop();
            _snapTimer = null;
        }
        if (_dragging)
        {
            Left = _downLeft + dx;
            Top = _downTop + dy;
        }
    }

    private void OnMouseUp(object sender, MouseButtonEventArgs e)
    {
        if (!_mouseDown) return;
        _mouseDown = false;
        ReleaseMouseCapture();
        e.Handled = true;

        if (_dragging)
        {
            _dragging = false;
            _area = WorkAreaAtCursor();
            _settings.CornerRight = Left + ActualWidth / 2 > _area.Left + _area.Width / 2;
            _settings.CornerBottom = Top + ActualHeight / 2 > _area.Top + _area.Height / 2;
            _settings.Save();
            MoveToCorner(animate: true);
        }
        else if (_expanded)
        {
            if (_lastResult != null)
            {
                CopyResult?.Invoke(_lastResult);
                FlashCopied();
            }
            else
            {
                Collapse();
            }
        }
        else
        {
            OnBubbleClicked();
        }
    }

    private void FlashCopied()
    {
        var previous = _tvRate.Text;
        _tvRate.Text = L.Copied;
        _copiedTimer?.Stop();
        _copiedTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1.5) };
        _copiedTimer.Tick += (_, _) =>
        {
            _copiedTimer?.Stop();
            if (_tvRate.Text == L.Copied) _tvRate.Text = previous;
        };
        _copiedTimer.Start();
    }

    // ------------------------------------------------------------------ content

    /// <summary>Text that can be converted, ignoring what this app copied itself.</summary>
    public static bool IsConvertible(string? text, bool automatic)
    {
        if (string.IsNullOrWhiteSpace(text) || OwnResult.IsMatch(text)) return false;
        return (automatic ? AmountParser.ParseAutomatic(text) : AmountParser.Parse(text, requireCurrency: false)) != null;
    }

    /// <summary>Clicking the bubble: a freshly copied amount wins, else the last result.</summary>
    private void OnBubbleClicked()
    {
        var clip = ClipboardHelper.TryGetText();
        if (clip != _lastClipboard && IsConvertible(clip, automatic: false))
        {
            _lastClipboard = clip;
            ShowConversion(clip!, explicitRequest: true);
        }
        else if (string.IsNullOrEmpty(_tvResult.Text))
        {
            ShowIdleCard();
        }
        else
        {
            Expand();
        }
    }

    public void RememberClipboard(string text) => _lastClipboard = text;

    /// <summary>
    /// Shows the conversion of <paramref name="text"/>. Automatic requests
    /// (selection, copy) stay silent when the text holds no amount.
    /// </summary>
    public void ShowConversion(string text, bool explicitRequest)
    {
        if (!IsVisible) return;
        var parsed = explicitRequest
            ? AmountParser.Parse(text, requireCurrency: false)
            : (OwnResult.IsMatch(text) ? null : AmountParser.ParseAutomatic(text));

        if (parsed == null)
        {
            if (!explicitRequest) return;
            _tvSource.Text = Ellipsize(text);
            _tvResult.Text = L.NoAmount;
            _tvResult.FontSize = 18;
            _tvRate.Text = L.NoAmountHint;
            _lastResult = null;
        }
        else
        {
            var conv = _rates.Converter();
            if (parsed.Currency is { } cur)
            {
                var xof = conv.FormatXof(conv.ToXof(parsed.Value, cur));
                _tvSource.Text = conv.FormatSource(parsed.Value, cur);
                _tvResult.Text = xof;
                _tvResult.FontSize = 22;
                _tvRate.Text = conv.FormatRate(cur);
                _lastResult = xof;
            }
            else
            {
                var eur = conv.FormatXof(conv.ToXof(parsed.Value, Currency.EUR));
                var usd = conv.FormatXof(conv.ToXof(parsed.Value, Currency.USD));
                _tvSource.Text = L.UnknownCurrency;
                _tvResult.Text = conv.FormatSource(parsed.Value, Currency.EUR) + " → " + eur + "\n" +
                                 conv.FormatSource(parsed.Value, Currency.USD) + " → " + usd;
                _tvResult.FontSize = 16;
                _tvRate.Text = conv.FormatRate(Currency.EUR) + " · " + conv.FormatRate(Currency.USD);
                _lastResult = eur;
            }
        }
        Expand();
    }

    private void ShowIdleCard()
    {
        _tvSource.Text = L.AppName;
        _tvResult.Text = L.IdleTitle;
        _tvResult.FontSize = 16;
        _tvRate.Text = L.IdleHint;
        _lastResult = null;
        Expand();
    }

    private static string Ellipsize(string s)
    {
        var t = s.Replace('\n', ' ').Replace('\r', ' ').Trim();
        return t.Length > 40 ? t[..39] + "…" : t;
    }

    // ------------------------------------------------------------------ expand / collapse

    private void Expand()
    {
        _bubble.Visibility = Visibility.Collapsed;
        _card.Visibility = Visibility.Visible;
        _expanded = true;
        MoveToCorner(animate: false);

        // Grow from the corner the bubble is parked in.
        _card.RenderTransformOrigin = new Point(_settings.CornerRight ? 1 : 0, _settings.CornerBottom ? 1 : 0);
        var ease = new BackEase { Amplitude = 0.35, EasingMode = EasingMode.EaseOut };
        var grow = new DoubleAnimation(0.6, 1, TimeSpan.FromMilliseconds(220)) { EasingFunction = ease };
        _cardScale.BeginAnimation(ScaleTransform.ScaleXProperty, grow);
        _cardScale.BeginAnimation(ScaleTransform.ScaleYProperty, grow);
        _card.BeginAnimation(OpacityProperty, new DoubleAnimation(0.4, 1, TimeSpan.FromMilliseconds(160)));
    }

    public void Collapse()
    {
        if (!_expanded) return;
        _expanded = false;
        var shrink = new DoubleAnimation(1, 0.6, TimeSpan.FromMilliseconds(120));
        var fade = new DoubleAnimation(1, 0, TimeSpan.FromMilliseconds(120));
        fade.Completed += (_, _) =>
        {
            if (_expanded) return; // re-opened meanwhile
            _card.Visibility = Visibility.Collapsed;
            _card.BeginAnimation(OpacityProperty, null);
            _cardScale.BeginAnimation(ScaleTransform.ScaleXProperty, null);
            _cardScale.BeginAnimation(ScaleTransform.ScaleYProperty, null);
            _bubble.Visibility = Visibility.Visible;
            MoveToCorner(animate: false);
        };
        _cardScale.BeginAnimation(ScaleTransform.ScaleXProperty, shrink);
        _cardScale.BeginAnimation(ScaleTransform.ScaleYProperty, shrink);
        _card.BeginAnimation(OpacityProperty, fade);
    }
}

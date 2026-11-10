using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Threading.Tasks;
using Windows.Globalization;
using Windows.Media.Ocr;
using WinBitmapDecoder = Windows.Graphics.Imaging.BitmapDecoder;
using WinPixelFormat = Windows.Graphics.Imaging.BitmapPixelFormat;
using WinAlphaMode = Windows.Graphics.Imaging.BitmapAlphaMode;

namespace InstantConverter;

/// <summary>
/// Reads the text of a screenshot (Win+Shift+S, Snipping Tool...) with the OCR
/// engine built into Windows 10/11. Works offline, nothing is sent anywhere.
/// </summary>
internal static class ScreenshotReader
{
    private static OcrEngine? _engine;

    /// <summary>The screenshot currently in the clipboard, or null.</summary>
    public static Bitmap? TryGetClipboardImage()
    {
        try
        {
            if (!System.Windows.Forms.Clipboard.ContainsImage()) return null;
            return System.Windows.Forms.Clipboard.GetImage() as Bitmap;
        }
        catch
        {
            return null;
        }
    }

    /// <summary>A whole-screen capture (Print Screen) is not a request to convert a price.</summary>
    public static bool IsFullScreen(Bitmap image)
    {
        foreach (var screen in System.Windows.Forms.Screen.AllScreens)
        {
            var b = screen.Bounds;
            if (image.Width >= b.Width * 0.9 && image.Height >= b.Height * 0.9) return true;
        }
        return false;
    }

    /// <summary>Lines of text found in the image, or null when no OCR language is installed.</summary>
    public static async Task<IReadOnlyList<string>?> ReadLinesAsync(Bitmap image)
    {
        _engine ??= OcrEngine.TryCreateFromUserProfileLanguages()
                    ?? TryLanguage("fr-FR") ?? TryLanguage("en-US");
        if (_engine == null) return null;

        using var prepared = Prepare(image);
        using var ms = new MemoryStream();
        prepared.Save(ms, ImageFormat.Png);
        ms.Position = 0;
        using var stream = ms.AsRandomAccessStream();
        var decoder = await WinBitmapDecoder.CreateAsync(stream);
        using var softwareBitmap = await decoder.GetSoftwareBitmapAsync(WinPixelFormat.Bgra8, WinAlphaMode.Premultiplied);
        var result = await _engine.RecognizeAsync(softwareBitmap);

        var lines = new List<string>();
        foreach (var line in result.Lines) lines.Add(line.Text);
        return lines;
    }

    private static OcrEngine? TryLanguage(string tag)
    {
        try
        {
            var lang = new Language(tag);
            return OcrEngine.IsLanguageSupported(lang) ? OcrEngine.TryCreateFromLanguage(lang) : null;
        }
        catch
        {
            return null;
        }
    }

    /// <summary>
    /// Opaque copy of the image, enlarged when small (OCR reads small text
    /// much better at 2x) and kept under the engine's size limit.
    /// </summary>
    private static Bitmap Prepare(Bitmap source)
    {
        double scale = source.Width < 1200 && source.Height < 1200 ? 2.0 : 1.0;
        double max = OcrEngine.MaxImageDimension;
        double longest = Math.Max(source.Width, source.Height) * scale;
        if (longest > max) scale *= max / longest;

        int w = Math.Max(1, (int)(source.Width * scale));
        int h = Math.Max(1, (int)(source.Height * scale));
        var bmp = new Bitmap(w, h, PixelFormat.Format24bppRgb);
        using var g = Graphics.FromImage(bmp);
        g.Clear(Color.White);
        g.InterpolationMode = InterpolationMode.HighQualityBicubic;
        g.DrawImage(source, 0, 0, w, h);
        return bmp;
    }
}

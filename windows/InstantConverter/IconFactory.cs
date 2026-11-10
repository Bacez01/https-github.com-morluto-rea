using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;

namespace InstantConverter;

/// <summary>Draws the green "CFA" icon used in the notification area.</summary>
internal static class IconFactory
{
    public static Icon Create()
    {
        using var bmp = new Bitmap(32, 32);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
            g.Clear(Color.Transparent);
            using var fill = new SolidBrush(Color.FromArgb(0x1B, 0x7F, 0x5A));
            g.FillEllipse(fill, 0, 0, 31, 31);
            using var font = new Font("Segoe UI", 9.5f, FontStyle.Bold, GraphicsUnit.Pixel);
            using var fmt = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
            g.DrawString("CFA", font, Brushes.White, new RectangleF(0, 0, 32, 32), fmt);
        }
        return Icon.FromHandle(bmp.GetHicon());
    }
}

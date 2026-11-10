using System;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;

namespace InstantConverter;

internal static class ClipboardHelper
{
    public static string? TryGetText()
    {
        for (int i = 0; i < 5; i++)
        {
            try
            {
                return Clipboard.ContainsText() ? Clipboard.GetText() : null;
            }
            catch
            {
                // The clipboard is briefly locked by another app.
                Thread.Sleep(30);
            }
        }
        return null;
    }

    public static bool TrySetText(string text)
    {
        for (int i = 0; i < 5; i++)
        {
            try
            {
                Clipboard.SetDataObject(text, true);
                return true;
            }
            catch
            {
                Thread.Sleep(30);
            }
        }
        return false;
    }

    private static void TryClear()
    {
        try { Clipboard.Clear(); } catch { }
    }

    /// <summary>
    /// Presses Ctrl+C in the foreground app to read the current selection,
    /// then puts back what the clipboard held before (text only). Returns
    /// null when nothing was copied. Skipped when the clipboard holds
    /// something other than text (image, files) so it is never lost.
    /// </summary>
    public static async Task<string?> CopySelectionAsync(Action<TimeSpan> suppressClipboardEvents)
    {
        if (Native.IsDown(Native.VK_SHIFT) || Native.IsDown(Native.VK_CONTROL) || Native.IsDown(Native.VK_MENU) ||
            Native.IsDown(Native.VK_LWIN) || Native.IsDown(Native.VK_RWIN) || Native.IsDown(Native.VK_LBUTTON))
            return null;

        string? previous;
        try
        {
            if (Clipboard.ContainsText()) previous = Clipboard.GetText();
            else if (Native.CountClipboardFormats() > 0) return null;
            else previous = null;
        }
        catch
        {
            return null;
        }

        uint seq = Native.GetClipboardSequenceNumber();
        suppressClipboardEvents(TimeSpan.FromSeconds(2));
        SendCtrlC();

        bool changed = false;
        for (int i = 0; i < 24 && !changed; i++)
        {
            await Task.Delay(25);
            changed = Native.GetClipboardSequenceNumber() != seq;
        }
        if (!changed) return null;
        await Task.Delay(40);

        var copied = TryGetText();
        if (previous != null) TrySetText(previous);
        else TryClear();
        suppressClipboardEvents(TimeSpan.FromMilliseconds(800));
        return copied;
    }

    private static void SendCtrlC()
    {
        static Native.INPUT Key(ushort vk, bool up) => new()
        {
            type = Native.INPUT_KEYBOARD,
            U = new Native.InputUnion { ki = new Native.KEYBDINPUT { wVk = vk, dwFlags = up ? Native.KEYEVENTF_KEYUP : 0 } },
        };
        var inputs = new[]
        {
            Key(Native.VK_CONTROL, false), Key(Native.VK_C, false),
            Key(Native.VK_C, true), Key(Native.VK_CONTROL, true),
        };
        Native.SendInput((uint)inputs.Length, inputs, System.Runtime.InteropServices.Marshal.SizeOf<Native.INPUT>());
    }
}

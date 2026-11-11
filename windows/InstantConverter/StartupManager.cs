using System;
using Microsoft.Win32;

namespace InstantConverter;

/// <summary>"Start with Windows" through the per-user Run registry key (no admin rights needed).</summary>
internal static class StartupManager
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "InstantConverter2";

    public static bool IsEnabled
    {
        get
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey);
            return key?.GetValue(ValueName) is string;
        }
    }

    public static void Set(bool enabled)
    {
        try
        {
            using var key = Registry.CurrentUser.CreateSubKey(RunKey);
            if (enabled && Environment.ProcessPath is { } exe)
                key.SetValue(ValueName, "\"" + exe + "\"");
            else
                key.DeleteValue(ValueName, throwOnMissingValue: false);
        }
        catch
        {
            // Policy may forbid it; the menu just stays unchecked.
        }
    }

    /// <summary>Keeps the registered path right if the exe was moved.</summary>
    public static void Refresh()
    {
        if (IsEnabled) Set(true);
    }
}

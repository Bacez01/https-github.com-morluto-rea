using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;

namespace InstantConverter;

/// <summary>Preferences, cached rates and history, stored in %APPDATA%\InstantConverter2\settings.json.</summary>
internal sealed class AppSettings
{
    public bool CornerRight { get; set; } = true;
    public bool CornerBottom { get; set; } = true;
    public bool AutoCopy { get; set; } = true;
    public bool WatchClipboard { get; set; } = true;
    public bool ReadScreenshots { get; set; } = true;
    public bool FirstRun { get; set; } = true;
    /// <summary>Value of one unit in XOF, by currency code (USD, GBP, CNY).</summary>
    public Dictionary<string, double> XofRates { get; set; } = new();
    public List<HistoryEntry> History { get; set; } = new();
    public DateTime? RateUpdatedUtc { get; set; }

    public static string Folder =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "InstantConverter2");

    private static string FilePath => Path.Combine(Folder, "settings.json");

    public static AppSettings Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(FilePath)) ?? new AppSettings();
        }
        catch
        {
            // Corrupted file: start again with defaults.
        }
        return new AppSettings();
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(Folder);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch
        {
            // Not critical.
        }
    }
}

/// <summary>One past conversion.</summary>
internal sealed class HistoryEntry
{
    public DateTime Time { get; set; }
    public string Source { get; set; } = "";
    public string Result { get; set; } = "";

    public override string ToString() => Time.ToLocalTime().ToString("HH:mm") + "   " + Source + " → " + Result;
}

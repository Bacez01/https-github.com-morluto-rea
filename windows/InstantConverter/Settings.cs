using System;
using System.IO;
using System.Text.Json;

namespace InstantConverter;

/// <summary>Preferences and cached rate, stored in %APPDATA%\InstantConverter\settings.json.</summary>
internal sealed class AppSettings
{
    public bool CornerRight { get; set; } = true;
    public bool CornerBottom { get; set; } = true;
    public bool AutoCopy { get; set; } = true;
    public bool WatchClipboard { get; set; } = true;
    public bool ReadScreenshots { get; set; } = true;
    public bool FirstRun { get; set; } = true;
    public double UsdToXof { get; set; } = 565.0;
    public DateTime? RateUpdatedUtc { get; set; }

    public static string Folder =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "InstantConverter");

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

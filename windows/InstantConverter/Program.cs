using System;
using System.IO;
using System.Threading;
using System.Windows;

namespace InstantConverter;

internal static class Program
{
    [STAThread]
    public static void Main()
    {
        // Only one bubble at a time.
        using var mutex = new Mutex(true, "InstantConverter.SingleInstance", out bool first);
        if (!first) return;

        System.Windows.Forms.Application.EnableVisualStyles();
        var app = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
        app.DispatcherUnhandledException += (_, e) =>
        {
            Log(e.Exception);
            e.Handled = true; // keep the bubble alive
        };

        AppController? controller = null;
        app.Startup += (_, _) => controller = new AppController();
        app.Exit += (_, _) => controller?.Dispose();
        app.Run();
    }

    private static void Log(Exception ex)
    {
        try
        {
            Directory.CreateDirectory(AppSettings.Folder);
            File.AppendAllText(Path.Combine(AppSettings.Folder, "error.log"), DateTime.Now + "  " + ex + Environment.NewLine);
        }
        catch
        {
        }
    }
}

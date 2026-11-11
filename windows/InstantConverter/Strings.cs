using System.Globalization;

namespace InstantConverter;

/// <summary>User interface texts: French on a French Windows, English otherwise.</summary>
internal static class L
{
    private static readonly bool Fr = CultureInfo.CurrentUICulture.TwoLetterISOLanguageName == "fr";
    private static string T(string fr, string en) => Fr ? fr : en;

    public static string AppName => T("Convertisseur Instantané 2", "Instant Converter 2");
    public static string NoAmount => T("Aucun montant trouvé", "No amount found");
    public static string NoAmountHint => T("Sélectionnez un prix en €, $, £ ou ¥", "Select a price in €, $, £ or ¥");
    public static string UnknownCurrency => T("Devise non précisée", "Currency not specified");
    public static string IdleTitle => T("Sélectionnez un montant", "Select an amount");
    public static string IdleHint => T("ou copiez-le puis cliquez sur la bulle", "or copy it, then click the bubble");
    public static string Copied => T("Copié ✓", "Copied ✓");
    public static string ClickToCopy => T("Cliquez pour copier le résultat", "Click to copy the result");

    public static string MenuShow => T("Afficher la bulle", "Show the bubble");
    public static string MenuHide => T("Masquer la bulle", "Hide the bubble");
    public static string MenuAutoCopy => T("Résultat immédiat dans les navigateurs (copie auto)", "Instant result in browsers (auto-copy)");
    public static string MenuWatchClipboard => T("Convertir ce que je copie (Ctrl+C)", "Convert what I copy (Ctrl+C)");
    public static string MenuScreenshots => T("Convertir les captures d'écran (Win+Maj+S)", "Convert screenshots (Win+Shift+S)");
    public static string Screenshot => T("Capture d'écran", "Screenshot");
    public static string NoAmountInScreenshot => T("Aucun montant lu", "No amount found");
    public static string NoAmountInScreenshotHint => T("Capturez une zone qui contient un prix", "Capture an area that shows a price");
    public static string NoOcr => T("Lecture de texte indisponible", "Text recognition unavailable");
    public static string NoOcrHint => T("Ajoutez la langue Français dans Paramètres › Heure et langue", "Add a language in Settings › Time & language");
    public static string MenuStartup => T("Lancer au démarrage de Windows", "Start with Windows");
    public static string MenuRefresh => T("Mettre à jour les taux", "Update the exchange rates");
    public static string HistoryShow => T("Historique ▾", "History ▾");
    public static string HistoryHide => T("Masquer l'historique ▴", "Hide history ▴");
    public static string MenuClearHistory => T("Effacer l'historique", "Clear history");
    public static string MenuQuit => T("Quitter", "Quit");
    public static string RateDefault => T("taux approximatif", "approximate rate");
    public static string RateFixed => T("parité fixe", "fixed parity");
    public static string RateUpdated(string when) => T("mis à jour le " + when, "updated " + when);
    public static string RateRefreshFailed => T("Impossible de mettre à jour les taux (pas de connexion ?)", "Could not update the rates (no connection?)");

    public static string WelcomeTitle => T("La bulle est prête", "The bubble is ready");
    public static string WelcomeText => T(
        "Sélectionnez un prix en €, $, £ ou ¥ sur une page web, ou faites une capture d'écran (Win+Maj+S) : la bulle affiche le montant en FCFA. Clic droit sur la bulle pour les options.",
        "Select a price in €, $, £ or ¥ on a web page, or take a screenshot (Win+Shift+S): the bubble shows it in FCFA. Right-click the bubble for options.");
}

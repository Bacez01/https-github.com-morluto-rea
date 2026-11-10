using System;
using System.Net.Http;
using System.Text.Json;
using System.Threading.Tasks;
using InstantConverter.Core;

namespace InstantConverter;

/// <summary>
/// EUR → XOF is a fixed parity. USD → XOF comes from a free public API
/// (no key) and is cached so the app keeps working offline.
/// </summary>
internal sealed class RateService
{
    private const string ApiUrl = "https://open.er-api.com/v6/latest/USD";
    private static readonly TimeSpan MaxAge = TimeSpan.FromHours(6);
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(10) };

    private readonly AppSettings _settings;

    public RateService(AppSettings settings) => _settings = settings;

    public CurrencyConverter Converter() => new(_settings.UsdToXof > 0 ? _settings.UsdToXof : 565.0);

    /// <summary>Returns true when a fresh rate was stored.</summary>
    public async Task<bool> RefreshAsync(bool force = false)
    {
        if (!force && _settings.RateUpdatedUtc is { } last && DateTime.UtcNow - last < MaxAge) return false;
        try
        {
            var body = await Http.GetStringAsync(ApiUrl).ConfigureAwait(true);
            using var doc = JsonDocument.Parse(body);
            var root = doc.RootElement;
            if (root.GetProperty("result").GetString() != "success") return false;
            var rate = root.GetProperty("rates").GetProperty("XOF").GetDouble();
            if (rate <= 0) return false;
            _settings.UsdToXof = rate;
            _settings.RateUpdatedUtc = DateTime.UtcNow;
            _settings.Save();
            return true;
        }
        catch
        {
            return false;
        }
    }
}

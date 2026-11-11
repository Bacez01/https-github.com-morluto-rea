using System;
using System.Collections.Generic;
using System.Net.Http;
using System.Text.Json;
using System.Threading.Tasks;
using InstantConverter.Core;

namespace InstantConverter;

/// <summary>
/// EUR → XOF is a fixed parity. USD, GBP and CNY → XOF come from a free public
/// API (no key) and are cached so the app keeps working offline.
/// </summary>
internal sealed class RateService
{
    private const string ApiUrl = "https://open.er-api.com/v6/latest/USD";
    private static readonly TimeSpan MaxAge = TimeSpan.FromHours(6);
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(10) };
    public static readonly Currency[] Downloaded = { Currency.USD, Currency.GBP, Currency.CNY };

    private readonly AppSettings _settings;

    public RateService(AppSettings settings) => _settings = settings;

    public CurrencyConverter Converter()
    {
        var rates = new Dictionary<Currency, double>();
        foreach (var c in Downloaded)
            if (_settings.XofRates.TryGetValue(c.ToString(), out var r) && r > 0) rates[c] = r;
        return new CurrencyConverter(rates);
    }

    /// <summary>Returns true when fresh rates were stored.</summary>
    public async Task<bool> RefreshAsync(bool force = false)
    {
        if (!force && _settings.RateUpdatedUtc is { } last && DateTime.UtcNow - last < MaxAge) return false;
        try
        {
            var body = await Http.GetStringAsync(ApiUrl).ConfigureAwait(true);
            using var doc = JsonDocument.Parse(body);
            var root = doc.RootElement;
            if (root.GetProperty("result").GetString() != "success") return false;
            var rates = root.GetProperty("rates");
            // Rates are given per US dollar: 1 unit = XOF / (units per dollar).
            var xofPerUsd = rates.GetProperty("XOF").GetDouble();
            if (xofPerUsd <= 0) return false;
            foreach (var c in Downloaded)
            {
                double perUsd = c == Currency.USD ? 1.0
                    : rates.TryGetProperty(c.ToString(), out var v) ? v.GetDouble() : 0;
                if (perUsd > 0) _settings.XofRates[c.ToString()] = xofPerUsd / perUsd;
            }
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

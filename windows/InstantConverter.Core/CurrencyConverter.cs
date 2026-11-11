using System;
using System.Collections.Generic;
using System.Globalization;

namespace InstantConverter.Core;

/// <summary>
/// Conversion of euros, dollars, pounds sterling and Chinese yuan into CFA
/// francs (XOF). The rates give the value of one unit in XOF; the euro always
/// uses the fixed parity.
/// </summary>
public sealed class CurrencyConverter
{
    /// <summary>Fixed parity guaranteed by the CFA franc / euro peg.</summary>
    public const double EurToXof = 655.957;

    /// <summary>Approximate rates used until the first download.</summary>
    public static readonly IReadOnlyDictionary<Currency, double> DefaultRates = new Dictionary<Currency, double>
    {
        [Currency.USD] = 565.0,
        [Currency.GBP] = 760.0,
        [Currency.CNY] = 79.0,
    };

    private readonly IReadOnlyDictionary<Currency, double> _rates;
    private readonly CultureInfo _culture;

    public CurrencyConverter(IReadOnlyDictionary<Currency, double> xofRates, CultureInfo? culture = null)
    {
        _rates = xofRates;
        _culture = culture ?? CultureInfo.CurrentCulture;
    }

    public double Rate(Currency c)
    {
        if (c == Currency.EUR) return EurToXof;
        return _rates.TryGetValue(c, out var r) && r > 0 ? r : DefaultRates[c];
    }

    public double ToXof(double value, Currency c) => value * Rate(c);

    /// <summary>"32 791 FCFA"</summary>
    public string FormatXof(double value) =>
        Math.Round(value, MidpointRounding.AwayFromZero).ToString("#,0", _culture) + " FCFA";

    /// <summary>"49,99 €", "120 $", "15 £", "199 ¥"</summary>
    public string FormatSource(double value, Currency c)
    {
        bool hasCents = Math.Abs(value - Math.Round(value)) > 0.004;
        return value.ToString(hasCents ? "#,0.00" : "#,0", _culture) + " " + Symbol(c);
    }

    /// <summary>"1 € = 655,957 FCFA", "1 ¥ (yuan) = 79 FCFA"</summary>
    public string FormatRate(Currency c)
    {
        var unit = c == Currency.CNY ? "¥ (yuan)" : Symbol(c);
        var format = Rate(c) < 100 ? "#,0.##" : "#,0.###";
        return "1 " + unit + " = " + Rate(c).ToString(format, _culture) + " FCFA";
    }

    public static string Symbol(Currency c) => c switch
    {
        Currency.EUR => "€",
        Currency.USD => "$",
        Currency.GBP => "£",
        _ => "¥",
    };
}

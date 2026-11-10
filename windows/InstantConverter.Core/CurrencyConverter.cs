using System;
using System.Globalization;

namespace InstantConverter.Core;

/// <summary>Conversion of euros / dollars into CFA francs (XOF).</summary>
public sealed class CurrencyConverter
{
    /// <summary>Fixed parity guaranteed by the CFA franc / euro peg.</summary>
    public const double EurToXof = 655.957;

    private readonly double _usdToXof;
    private readonly CultureInfo _culture;

    public CurrencyConverter(double usdToXof, CultureInfo? culture = null)
    {
        _usdToXof = usdToXof;
        _culture = culture ?? CultureInfo.CurrentCulture;
    }

    public double Rate(Currency c) => c == Currency.EUR ? EurToXof : _usdToXof;

    public double ToXof(double value, Currency c) => value * Rate(c);

    /// <summary>"32 791 FCFA"</summary>
    public string FormatXof(double value) =>
        Math.Round(value, MidpointRounding.AwayFromZero).ToString("#,0", _culture) + " FCFA";

    /// <summary>"49,99 €" or "120 $"</summary>
    public string FormatSource(double value, Currency c)
    {
        bool hasCents = Math.Abs(value - Math.Round(value)) > 0.004;
        return value.ToString(hasCents ? "#,0.00" : "#,0", _culture) + " " + Symbol(c);
    }

    /// <summary>"1 € = 655,957 FCFA"</summary>
    public string FormatRate(Currency c) =>
        "1 " + Symbol(c) + " = " + Rate(c).ToString("#,0.###", _culture) + " FCFA";

    public static string Symbol(Currency c) => c == Currency.EUR ? "€" : "$";
}

using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text.RegularExpressions;

namespace InstantConverter.Core;

public enum Currency { EUR, USD, GBP, CNY }

/// <summary>An amount found in text. <see cref="Currency"/> is null for a plain number.</summary>
public sealed record ParsedAmount(double Value, Currency? Currency);

/// <summary>
/// Extracts a monetary amount (euros, dollars, pounds sterling or Chinese yuan)
/// from arbitrary selected text:
/// "49,99 €", "€49.99", "1 299,00 EUR", "$1,299.99", "USD 15", "12€50",
/// "1.234,56 €", "2,5 M€", "$3k", "15 euros", "20 dollars", "£49.99", "¥199", "199 元"...
/// Same rules as the Android app.
/// </summary>
public static class AmountParser
{
    // Number with space/apostrophe thousand separators (1 299,99 / 1'299.99),
    // or digits mixed with dots and commas (1.299,99 / 1,299.99 / 49,99).
    private const string Num =
        @"[0-9]{1,3}(?:[   '’][0-9]{3})+(?:[.,][0-9]+)?|[0-9]+(?:[.,][0-9]+)*";

    // Optional space, including the non-breaking spaces used by French websites.
    private const string Sp = @"[\s  ]?";

    private const string Mult =
        @"(?:" + Sp + @"(?<mult>k|K|M|Md|Mds|mio|Mio|mrd|Mrd|millions?|milliards?|bn|billions?)(?![\p{L}0-9]))?";

    private const string EurSym = @"€|EUR(?!\p{L})|euros?(?!\p{L})";
    private const string UsdSym = @"US\$|USD(?!\p{L})|\$|US" + Sp + @"dollars?(?!\p{L})|dollars?(?!\p{L})";

    private const string GbpSym =
        @"£|GBP(?!\p{L})|livres?" + Sp + @"sterling(?!\p{L})|pounds?" + Sp + @"sterling(?!\p{L})";

    // "¥" is also the yen sign; Chinese shops use it for the yuan, which is
    // the currency handled here (the yen is not).
    private const string CnySym = @"CN¥|CNY(?!\p{L})|RMB(?!\p{L})|¥|￥|元|yuans?(?!\p{L})|renminbi(?!\p{L})";

    private const string AnySym = UsdSym + "|" + EurSym + "|" + GbpSym + "|" + CnySym;

    // "12€50" (French way of writing 12.50 €)
    private static readonly Regex InlineEuro = new(
        @"(?<![0-9.,])(?<int>[0-9]+)" + Sp + "€" + Sp + @"(?<dec>[0-9]{2})(?![0-9])");

    // Currency before the number: "€ 49,99", "USD 1,299.99", "$3k"
    private static readonly Regex Prefix = new(
        "(?<cur>" + AnySym + ")" + Sp + "(?<num>" + Num + ")" + Mult,
        RegexOptions.IgnoreCase);

    // Currency after the number: "49,99 €", "1 299 EUR", "2,5 M€", "3 milliards d'euros"
    private static readonly Regex Suffix = new(
        @"(?<![0-9.,])(?<num>" + Num + ")" + Mult + Sp + @"(?:d['’]" + Sp + @"|de\s)?(?<cur>" + AnySym + ")",
        RegexOptions.IgnoreCase);

    private static readonly Regex Bare = new(@"(?<![0-9.,])(?<num>" + Num + ")" + Mult);

    // A price written without currency, e.g. "49,99" or "1 299.00".
    private static readonly Regex PriceLike = new(@"^\s*[0-9][0-9   '’.,]*[.,][0-9]{2}\s*$");

    /// <summary>
    /// Returns the first amount found in <paramref name="text"/>. When
    /// <paramref name="requireCurrency"/> is false a plain number is accepted
    /// and returned with a null currency.
    /// </summary>
    public static ParsedAmount? Parse(string? text, bool requireCurrency = true)
    {
        var input = (text ?? "").Trim();
        if (input.Length > 500) input = input[..500];
        if (input.Length == 0) return null;

        int bestPos = int.MaxValue;
        ParsedAmount? best = null;

        var inline = InlineEuro.Match(input);
        if (inline.Success && double.TryParse(inline.Groups["int"].Value + "." + inline.Groups["dec"].Value,
                NumberStyles.Float, CultureInfo.InvariantCulture, out var iv))
        {
            bestPos = inline.Index;
            best = new ParsedAmount(iv, Currency.EUR);
        }

        foreach (var regex in new[] { Prefix, Suffix })
        {
            foreach (Match m in regex.Matches(input))
            {
                var amount = ToAmount(m);
                if (amount == null) continue;
                if (m.Index < bestPos)
                {
                    bestPos = m.Index;
                    best = amount;
                }
                break;
            }
        }
        if (best != null) return best;
        if (requireCurrency) return null;

        foreach (Match m in Bare.Matches(input))
        {
            var v = ParseNumber(m.Groups["num"].Value);
            if (v != null) return new ParsedAmount(v.Value * Multiplier(m.Groups["mult"]), null);
        }
        return null;
    }

    /// <summary>
    /// Rules used when the conversion is triggered automatically by a selection:
    /// an amount with a currency, or a short price-looking number such as
    /// "49,99" (double-clicking a price usually selects the number only).
    /// Plain integers ("2024") are ignored to avoid popping up on dates.
    /// </summary>
    public static ParsedAmount? ParseAutomatic(string? text)
    {
        var withCurrency = Parse(text);
        if (withCurrency != null) return withCurrency;
        if (text == null || text.Length > 30 || !PriceLike.IsMatch(text)) return null;
        return Parse(text, requireCurrency: false);
    }

    // "49, 99" / "49 ,99" as OCR sometimes reads "49,99".
    private static readonly Regex OcrSeparatorSpaces = new(@"(?<=[0-9])[ \u00A0]*([.,])[ \u00A0]*(?=[0-9]{2}(?![0-9]))");

    /// <summary>Fixes the usual OCR glitches in numbers before parsing.</summary>
    public static string NormalizeOcr(string text) => OcrSeparatorSpaces.Replace(text, "$1");

    /// <summary>
    /// Picks the amount to show from text read in a screenshot: the first line
    /// holding an amount with a currency, else the first price-looking number,
    /// else any number. Returns the line it was found in.
    /// </summary>
    public static (string Line, ParsedAmount Amount)? FindInScreenshot(IEnumerable<string> lines)
    {
        var cleaned = new List<string>();
        foreach (var l in lines)
        {
            var t = NormalizeOcr(l).Trim();
            if (t.Length > 0) cleaned.Add(t);
        }
        foreach (var l in cleaned)
            if (Parse(l) is { } a) return (l, a);
        foreach (var l in cleaned)
            if (ParseAutomatic(l) is { } a) return (l, a);
        foreach (var l in cleaned)
            if (Parse(l, requireCurrency: false) is { } a) return (l, a);
        return null;
    }

    private static ParsedAmount? ToAmount(Match m)
    {
        var value = ParseNumber(m.Groups["num"].Value);
        var cur = CurrencyOf(m.Groups["cur"].Value);
        if (value == null || cur == null) return null;
        return new ParsedAmount(value.Value * Multiplier(m.Groups["mult"]), cur);
    }

    private static Currency? CurrencyOf(string symbol)
    {
        var s = symbol.ToLowerInvariant();
        if (s == "€" || s == "eur" || s.StartsWith("euro", StringComparison.Ordinal)) return Currency.EUR;
        if (s == "£" || s == "gbp" || s.Contains("sterling", StringComparison.Ordinal)) return Currency.GBP;
        if (s.Contains('¥') || s == "￥" || s == "元" || s == "cny" || s == "rmb" ||
            s.StartsWith("yuan", StringComparison.Ordinal) || s == "renminbi") return Currency.CNY;
        if (s.Contains('$') || s == "usd" || s.Contains("dollar", StringComparison.Ordinal)) return Currency.USD;
        return null;
    }

    private static double Multiplier(Group g)
    {
        if (!g.Success) return 1;
        return g.Value.ToLowerInvariant() switch
        {
            "k" => 1e3,
            "m" or "mio" or "million" or "millions" => 1e6,
            "md" or "mds" or "mrd" or "milliard" or "milliards" or "bn" or "billion" or "billions" => 1e9,
            _ => 1,
        };
    }

    /// <summary>Converts a localized number string into a double, guessing separators.</summary>
    public static double? ParseNumber(string raw)
    {
        var s = Regex.Replace(raw, @"[   '’]", "");
        if (s.Length == 0) return null;
        int lastDot = s.LastIndexOf('.');
        int lastComma = s.LastIndexOf(',');
        if (lastDot >= 0 && lastComma >= 0)
        {
            // Both present: the right-most one is the decimal separator.
            char dec = lastDot > lastComma ? '.' : ',';
            char thousands = dec == '.' ? ',' : '.';
            s = s.Replace(thousands.ToString(), "").Replace(dec, '.');
        }
        else if (lastDot >= 0 || lastComma >= 0)
        {
            char sep = lastDot >= 0 ? '.' : ',';
            int count = 0;
            foreach (var c in s) if (c == sep) count++;
            int digitsAfter = s.Length - s.LastIndexOf(sep) - 1;
            string intPart = s[..s.IndexOf(sep)];
            bool isThousands = count > 1 || (digitsAfter == 3 && intPart != "0");
            s = isThousands ? s.Replace(sep.ToString(), "") : s.Replace(sep, '.');
        }
        return double.TryParse(s, NumberStyles.Float, CultureInfo.InvariantCulture, out var v) ? v : null;
    }
}

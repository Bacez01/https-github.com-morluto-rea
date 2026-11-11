using System.Globalization;
using InstantConverter.Core;
using Xunit;

namespace InstantConverter.Core.Tests;

public class AmountParserTests
{
    [Theory]
    [InlineData("49,99 €", 49.99, Currency.EUR)]
    [InlineData("49,99€", 49.99, Currency.EUR)]
    [InlineData("1 299,00 €", 1299.0, Currency.EUR)]
    [InlineData("1 299,50 €", 1299.5, Currency.EUR)]
    [InlineData("12€50", 12.5, Currency.EUR)]
    [InlineData("Prix : 15 euros TTC", 15.0, Currency.EUR)]
    [InlineData("250 EUR", 250.0, Currency.EUR)]
    [InlineData("1.234,56 €", 1234.56, Currency.EUR)]
    [InlineData("€ 3.50", 3.5, Currency.EUR)]
    [InlineData("€1,000", 1000.0, Currency.EUR)]
    [InlineData("2,5 M€", 2500000.0, Currency.EUR)]
    [InlineData("3 milliards d'euros", 3e9, Currency.EUR)]
    [InlineData("$1,299.99", 1299.99, Currency.USD)]
    [InlineData("$ 20", 20.0, Currency.USD)]
    [InlineData("USD 15.5", 15.5, Currency.USD)]
    [InlineData("US$99", 99.0, Currency.USD)]
    [InlineData("120 $", 120.0, Currency.USD)]
    [InlineData("20 dollars", 20.0, Currency.USD)]
    [InlineData("only 5 US dollars", 5.0, Currency.USD)]
    [InlineData("$3k", 3000.0, Currency.USD)]
    [InlineData("$1.5 billion", 1.5e9, Currency.USD)]
    [InlineData("£49.99", 49.99, Currency.GBP)]
    [InlineData("£ 1,299", 1299.0, Currency.GBP)]
    [InlineData("49,99 £", 49.99, Currency.GBP)]
    [InlineData("GBP 20", 20.0, Currency.GBP)]
    [InlineData("10 livres sterling", 10.0, Currency.GBP)]
    [InlineData("5 pounds sterling", 5.0, Currency.GBP)]
    [InlineData("¥199", 199.0, Currency.CNY)]
    [InlineData("￥ 1,299.00", 1299.0, Currency.CNY)]
    [InlineData("199 元", 199.0, Currency.CNY)]
    [InlineData("199元", 199.0, Currency.CNY)]
    [InlineData("RMB 88", 88.0, Currency.CNY)]
    [InlineData("CNY 15.5", 15.5, Currency.CNY)]
    [InlineData("CN¥30", 30.0, Currency.CNY)]
    [InlineData("50 yuans", 50.0, Currency.CNY)]
    [InlineData("de 10 € à 20 €", 10.0, Currency.EUR)]
    [InlineData("en 2024 : 150 €", 150.0, Currency.EUR)]
    [InlineData("3 articles à 15 €", 15.0, Currency.EUR)]
    public void WithCurrency(string text, double value, Currency currency)
    {
        var r = AmountParser.Parse(text);
        Assert.NotNull(r);
        Assert.Equal(value, r!.Value, 4);
        Assert.Equal(currency, r.Currency);
    }

    [Theory]
    [InlineData("2024")]
    [InlineData("hello world")]
    [InlineData("")]
    public void NoAmount(string text) => Assert.Null(AmountParser.Parse(text));

    [Theory]
    [InlineData("42,50", 42.5)]
    [InlineData("1 500", 1500.0)]
    public void PlainNumber(string text, double value)
    {
        var r = AmountParser.Parse(text, requireCurrency: false);
        Assert.NotNull(r);
        Assert.Equal(value, r!.Value, 4);
        Assert.Null(r.Currency);
    }

    [Theory]
    [InlineData("49,99", 49.99)]
    [InlineData(" 1 299.00 ", 1299.0)]
    public void AutomaticAcceptsPrices(string text, double value)
    {
        var r = AmountParser.ParseAutomatic(text);
        Assert.NotNull(r);
        Assert.Equal(value, r!.Value, 4);
    }

    [Theory]
    [InlineData("2024")]
    [InlineData("12")]
    [InlineData("Version 1.2")]
    public void AutomaticIgnoresPlainNumbers(string text) => Assert.Null(AmountParser.ParseAutomatic(text));

    [Fact]
    public void Numbers()
    {
        Assert.Equal(0.5, AmountParser.ParseNumber("0,500")!.Value, 9);
        Assert.Equal(1234567.0, AmountParser.ParseNumber("1,234,567")!.Value, 9);
        Assert.Equal(1234.5, AmountParser.ParseNumber("1'234.5")!.Value, 9);
    }

    private static string Spaces(string s) => s.Replace(' ', ' ').Replace(' ', ' ');

    [Fact]
    public void Conversion()
    {
        var rates = new System.Collections.Generic.Dictionary<Currency, double>
        {
            [Currency.USD] = 600.0, [Currency.GBP] = 750.0, [Currency.CNY] = 80.0,
        };
        var c = new CurrencyConverter(rates, new CultureInfo("fr-FR"));
        Assert.Equal(7500.0, c.ToXof(10, Currency.GBP), 9);
        Assert.Equal(800.0, c.ToXof(10, Currency.CNY), 9);
        Assert.Equal("15 £", c.FormatSource(15, Currency.GBP));
        Assert.Equal("1 ¥ (yuan) = 80 FCFA", c.FormatRate(Currency.CNY));
        Assert.Equal(655.957, c.ToXof(1, Currency.EUR), 9);
        Assert.Equal(6000.0, c.ToXof(10, Currency.USD), 9);
        Assert.Equal("32 791 FCFA", Spaces(c.FormatXof(c.ToXof(49.99, Currency.EUR))));
        Assert.Equal("120 $", c.FormatSource(120, Currency.USD));
        Assert.Equal("49,99 €", Spaces(c.FormatSource(49.99, Currency.EUR)));
        Assert.Equal("1 € = 655,957 FCFA", Spaces(c.FormatRate(Currency.EUR)));
    }

    [Fact]
    public void ScreenshotPrefersAmountWithCurrency()
    {
        var r = AmountParser.FindInScreenshot(new[] { "Réf. 2024-118", "Quantité 2", "Total : 1 299,00 €", "TVA 20 %" });
        Assert.NotNull(r);
        Assert.Equal(1299.0, r!.Value.Amount.Value, 4);
        Assert.Equal(Currency.EUR, r.Value.Amount.Currency);
    }

    [Fact]
    public void ScreenshotFixesOcrSpaces()
    {
        var r = AmountParser.FindInScreenshot(new[] { "Prix 49, 99 €" });
        Assert.NotNull(r);
        Assert.Equal(49.99, r!.Value.Amount.Value, 4);
    }

    [Fact]
    public void ScreenshotFallsBackToPriceThenNumber()
    {
        Assert.Equal(19.9, AmountParser.FindInScreenshot(new[] { "Article 3", "19,90" })!.Value.Amount.Value, 4);
        Assert.Equal(3.0, AmountParser.FindInScreenshot(new[] { "Article 3" })!.Value.Amount.Value, 4);
        Assert.Null(AmountParser.FindInScreenshot(new[] { "Bonjour", "" }));
    }
}

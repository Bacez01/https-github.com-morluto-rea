package com.instantconverter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class AmountParserTest {

    private fun check(text: String, value: Double, currency: Currency?) {
        val r = AmountParser.parse(text, requireCurrency = currency != null)
        requireNotNull(r) { "no amount found in \"$text\"" }
        assertEquals("value of \"$text\"", value, r.value, 0.0001)
        assertEquals("currency of \"$text\"", currency, r.currency)
    }

    @Test fun euroFrench() {
        check("49,99 €", 49.99, Currency.EUR)
        check("49,99€", 49.99, Currency.EUR)
        check("1 299,00 €", 1299.0, Currency.EUR)
        check("1 299,50 €", 1299.5, Currency.EUR)
        check("12€50", 12.5, Currency.EUR)
        check("Prix : 15 euros TTC", 15.0, Currency.EUR)
        check("250 EUR", 250.0, Currency.EUR)
        check("1.234,56 €", 1234.56, Currency.EUR)
        check("€ 3.50", 3.5, Currency.EUR)
        check("€1,000", 1000.0, Currency.EUR)
        check("2,5 M€", 2_500_000.0, Currency.EUR)
        check("3 milliards d'euros", 3e9, Currency.EUR)
    }

    @Test fun dollar() {
        check("$1,299.99", 1299.99, Currency.USD)
        check("$ 20", 20.0, Currency.USD)
        check("USD 15.5", 15.5, Currency.USD)
        check("US$99", 99.0, Currency.USD)
        check("120 $", 120.0, Currency.USD)
        check("20 dollars", 20.0, Currency.USD)
        check("only 5 US dollars", 5.0, Currency.USD)
        check("\$3k", 3000.0, Currency.USD)
        check("\$1.5 billion", 1.5e9, Currency.USD)
    }

    @Test fun poundSterling() {
        check("£49.99", 49.99, Currency.GBP)
        check("£ 1,299", 1299.0, Currency.GBP)
        check("49,99 £", 49.99, Currency.GBP)
        check("GBP 20", 20.0, Currency.GBP)
        check("10 livres sterling", 10.0, Currency.GBP)
        check("5 pounds sterling", 5.0, Currency.GBP)
    }

    @Test fun chineseYuan() {
        check("¥199", 199.0, Currency.CNY)
        check("￥ 1,299.00", 1299.0, Currency.CNY)
        check("199 元", 199.0, Currency.CNY)
        check("199元", 199.0, Currency.CNY)
        check("RMB 88", 88.0, Currency.CNY)
        check("CNY 15.5", 15.5, Currency.CNY)
        check("CN¥30", 30.0, Currency.CNY)
        check("50 yuans", 50.0, Currency.CNY)
    }

    @Test fun firstAmountWins() {
        check("de 10 € à 20 €", 10.0, Currency.EUR)
        check("en 2024 : 150 €", 150.0, Currency.EUR)
        check("3 articles à 15 €", 15.0, Currency.EUR)
    }

    @Test fun noCurrency() {
        assertNull(AmountParser.parse("2024"))
        assertNull(AmountParser.parse("hello world"))
        assertNull(AmountParser.parse(""))
        check("42,50", 42.5, null)
        check("1 500", 1500.0, null)
    }

    @Test fun numbers() {
        assertEquals(0.5, AmountParser.parseNumber("0,500")!!, 1e-9)
        assertEquals(1234567.0, AmountParser.parseNumber("1,234,567")!!, 1e-9)
        assertEquals(1234.5, AmountParser.parseNumber("1'234.5")!!, 1e-9)
    }

    @Test fun conversion() {
        val c = CurrencyConverter(
            mapOf(Currency.USD to 600.0, Currency.GBP to 750.0, Currency.CNY to 80.0), Locale.FRANCE
        )
        assertEquals(7500.0, c.toXof(10.0, Currency.GBP), 1e-9)
        assertEquals(800.0, c.toXof(10.0, Currency.CNY), 1e-9)
        assertEquals("15 £", c.formatSource(15.0, Currency.GBP))
        assertEquals("1 ¥ (yuan) = 80 FCFA", c.formatRate(Currency.CNY))
        assertEquals(655.957, c.toXof(1.0, Currency.EUR), 1e-9)
        assertEquals(6000.0, c.toXof(10.0, Currency.USD), 1e-9)
        val s = c.formatXof(c.toXof(49.99, Currency.EUR)).replace(' ', ' ').replace(' ', ' ')
        assertEquals("32 791 FCFA", s)
        assertEquals("120 $", c.formatSource(120.0, Currency.USD))
        assertEquals("49,99 €", c.formatSource(49.99, Currency.EUR).replace(' ', ' ').replace(' ', ' '))
    }
}

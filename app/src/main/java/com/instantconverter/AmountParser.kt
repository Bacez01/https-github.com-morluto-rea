package com.instantconverter

enum class Currency { EUR, USD }

/**
 * An amount found in a piece of text. [currency] is null when the text holds a
 * number without any euro/dollar marker.
 */
data class ParsedAmount(val value: Double, val currency: Currency?)

/**
 * Extracts a monetary amount (euros or dollars) from arbitrary selected text.
 *
 * Handles the usual French / English / German notations:
 * "49,99 €", "€49.99", "1 299,00 EUR", "$1,299.99", "USD 15", "12€50",
 * "1.234,56 €", "2,5 M€", "3k$", "15 euros", "20 dollars"...
 */
object AmountParser {

    // Number with space/apostrophe thousand separators (1 299,99 / 1'299.99),
    // or digits mixed with dots and commas (1.299,99 / 1,299.99 / 49,99).
    private const val NUM =
        """\d{1,3}(?:[   '’]\d{3})+(?:[.,]\d+)?|\d+(?:[.,]\d+)*"""

    // Optional space, including the non-breaking spaces used by French websites.
    private const val SP = """[\s\u00A0\u202F]?"""

    private const val MULT =
        """(?:$SP(?<mult>k|K|M|Md|Mds|mio|Mio|mrd|Mrd|millions?|milliards?|bn|billions?)(?![\p{L}\d]))?"""

    private const val EUR_SYM = """€|EUR(?![\p{L}])|euros?(?![\p{L}])"""
    private const val USD_SYM =
        """US\$|USD(?![\p{L}])|\$|US${SP}dollars?(?![\p{L}])|dollars?(?![\p{L}])"""

    // "12€50" (French way of writing 12.50 €)
    private val INLINE_EURO = Regex("""(?<![\d.,])(?<int>\d+)$SP€$SP(?<dec>\d{2})(?!\d)""")

    // Currency before the number: "€ 49,99", "USD 1,299.99", "$3k"
    private val PREFIX = Regex(
        """(?<cur>$USD_SYM|$EUR_SYM)$SP(?<num>$NUM)$MULT""",
        RegexOption.IGNORE_CASE
    )

    // Currency after the number: "49,99 €", "1 299 EUR", "2,5 M€", "20 dollars"
    private val SUFFIX = Regex(
        """(?<![\d.,])(?<num>$NUM)$MULT$SP(?:d['’]$SP|de\s)?(?<cur>$EUR_SYM|$USD_SYM)""",
        RegexOption.IGNORE_CASE
    )

    private val BARE = Regex("""(?<![\d.,])(?<num>$NUM)$MULT""")

    /**
     * Returns the first amount found in [text].
     * When [requireCurrency] is false, a plain number is accepted and returned
     * with a null currency.
     */
    fun parse(text: String, requireCurrency: Boolean = true): ParsedAmount? {
        val input = text.trim().take(500)
        if (input.isEmpty()) return null

        val candidates = mutableListOf<Pair<Int, ParsedAmount>>()

        INLINE_EURO.find(input)?.let { m ->
            val v = "${m.groups["int"]!!.value}.${m.groups["dec"]!!.value}".toDoubleOrNull()
            if (v != null) candidates += m.range.first to ParsedAmount(v, Currency.EUR)
        }
        for (regex in listOf(PREFIX, SUFFIX)) {
            regex.findAll(input).firstNotNullOfOrNull { toAmount(it) }?.let {
                candidates += it
            }
        }
        candidates.minByOrNull { it.first }?.let { return it.second }

        if (requireCurrency) return null
        return BARE.findAll(input).firstNotNullOfOrNull { m ->
            val v = parseNumber(m.groups["num"]!!.value) ?: return@firstNotNullOfOrNull null
            ParsedAmount(v * multiplier(m.groups["mult"]?.value), null)
        }
    }

    private fun toAmount(m: MatchResult): Pair<Int, ParsedAmount>? {
        val value = parseNumber(m.groups["num"]!!.value) ?: return null
        val cur = currencyOf(m.groups["cur"]!!.value) ?: return null
        return m.range.first to ParsedAmount(value * multiplier(m.groups["mult"]?.value), cur)
    }

    private fun currencyOf(symbol: String): Currency? {
        val s = symbol.lowercase()
        return when {
            s == "€" || s == "eur" || s.startsWith("euro") -> Currency.EUR
            s.contains("$") || s == "usd" || s.contains("dollar") -> Currency.USD
            else -> null
        }
    }

    private fun multiplier(m: String?): Double = when (m?.lowercase()) {
        null -> 1.0
        "k" -> 1e3
        "m", "mio", "million", "millions" -> 1e6
        "md", "mds", "mrd", "milliard", "milliards", "bn", "billion", "billions" -> 1e9
        else -> 1.0
    }

    /** Converts a localized number string into a Double, guessing separators. */
    fun parseNumber(raw: String): Double? {
        var s = raw.replace(Regex("""[   '’]"""), "")
        if (s.isEmpty()) return null
        val lastDot = s.lastIndexOf('.')
        val lastComma = s.lastIndexOf(',')
        s = when {
            lastDot >= 0 && lastComma >= 0 -> {
                // Both present: the right-most one is the decimal separator.
                val decimal = if (lastDot > lastComma) '.' else ','
                val thousands = if (decimal == '.') "," else "."
                s.replace(thousands, "").replace(decimal, '.')
            }
            lastDot >= 0 || lastComma >= 0 -> {
                val sep = if (lastDot >= 0) '.' else ','
                val count = s.count { it == sep }
                val digitsAfter = s.length - s.lastIndexOf(sep) - 1
                val intPart = s.substringBefore(sep)
                val isThousands = count > 1 || (digitsAfter == 3 && intPart != "0")
                if (isThousands) s.replace(sep.toString(), "") else s.replace(sep, '.')
            }
            else -> s
        }
        return s.toDoubleOrNull()
    }
}

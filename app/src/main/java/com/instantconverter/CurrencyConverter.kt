package com.instantconverter

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Conversion of euros, dollars, pounds sterling and Chinese yuan into CFA
 * francs (XOF). [xofRates] gives the value of one unit of each currency in
 * XOF; the euro always uses the fixed parity.
 */
class CurrencyConverter(
    private val xofRates: Map<Currency, Double>,
    private val locale: Locale = Locale.getDefault(),
) {
    companion object {
        /** Fixed parity guaranteed by the CFA franc / euro peg. */
        const val EUR_TO_XOF = 655.957

        /** Approximate rates used until the first download. */
        val DEFAULT_RATES = mapOf(
            Currency.USD to 565.0,
            Currency.GBP to 760.0,
            Currency.CNY to 79.0,
        )

        fun symbol(currency: Currency): String = when (currency) {
            Currency.EUR -> "€"
            Currency.USD -> "$"
            Currency.GBP -> "£"
            Currency.CNY -> "¥"
        }
    }

    /** Kept for simple USD-only uses. */
    constructor(usdToXof: Double, locale: Locale = Locale.getDefault()) :
        this(DEFAULT_RATES + (Currency.USD to usdToXof), locale)

    fun rate(currency: Currency): Double = when (currency) {
        Currency.EUR -> EUR_TO_XOF
        else -> xofRates[currency] ?: DEFAULT_RATES.getValue(currency)
    }

    fun toXof(value: Double, currency: Currency): Double = value * rate(currency)

    /** "32 791 FCFA" */
    fun formatXof(value: Double): String {
        val nf = NumberFormat.getIntegerInstance(locale)
        nf.isGroupingUsed = true
        return "${nf.format(value.roundToLong())} FCFA"
    }

    /** "49,99 €", "120 $", "15 £", "199 ¥" */
    fun formatSource(value: Double, currency: Currency): String {
        val nf = NumberFormat.getNumberInstance(locale)
        nf.isGroupingUsed = true
        val hasCents = abs(value - value.roundToLong()) > 0.004
        nf.minimumFractionDigits = if (hasCents) 2 else 0
        nf.maximumFractionDigits = 2
        return "${nf.format(value)} ${symbol(currency)}"
    }

    /** "1 € = 655,957 FCFA", "1 ¥ (yuan) = 79 FCFA" */
    fun formatRate(currency: Currency): String {
        val nf = NumberFormat.getNumberInstance(locale)
        nf.maximumFractionDigits = if (rate(currency) < 100) 2 else 3
        val unit = if (currency == Currency.CNY) "¥ (yuan)" else symbol(currency)
        return "1 $unit = ${nf.format(rate(currency))} FCFA"
    }
}

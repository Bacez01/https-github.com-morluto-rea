package com.instantconverter

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Conversion of euros / dollars into CFA francs (XOF). */
class CurrencyConverter(
    private val usdToXof: Double,
    private val locale: Locale = Locale.getDefault(),
) {
    companion object {
        /** Fixed parity guaranteed by the CFA franc / euro peg. */
        const val EUR_TO_XOF = 655.957
    }

    fun rate(currency: Currency): Double = when (currency) {
        Currency.EUR -> EUR_TO_XOF
        Currency.USD -> usdToXof
    }

    fun toXof(value: Double, currency: Currency): Double = value * rate(currency)

    /** "32 791 FCFA" */
    fun formatXof(value: Double): String {
        val nf = NumberFormat.getIntegerInstance(locale)
        nf.isGroupingUsed = true
        return "${nf.format(value.roundToLong())} FCFA"
    }

    /** "49,99 €" or "120 $" */
    fun formatSource(value: Double, currency: Currency): String {
        val nf = NumberFormat.getNumberInstance(locale)
        nf.isGroupingUsed = true
        val hasCents = abs(value - value.roundToLong()) > 0.004
        nf.minimumFractionDigits = if (hasCents) 2 else 0
        nf.maximumFractionDigits = 2
        val symbol = if (currency == Currency.EUR) "€" else "$"
        return "${nf.format(value)} $symbol"
    }

    /** "1 € = 655,957 FCFA" */
    fun formatRate(currency: Currency): String {
        val nf = NumberFormat.getNumberInstance(locale)
        nf.maximumFractionDigits = 3
        val symbol = if (currency == Currency.EUR) "€" else "$"
        return "1 $symbol = ${nf.format(rate(currency))} FCFA"
    }
}

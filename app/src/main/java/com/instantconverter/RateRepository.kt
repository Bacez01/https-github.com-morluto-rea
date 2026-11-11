package com.instantconverter

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors

/**
 * Stores the USD, GBP and CNY → XOF exchange rates. EUR → XOF is a fixed parity
 * and never needs to be downloaded. The other rates are refreshed from a free public API
 * (no key required) and cached so the app keeps working offline.
 */
object RateRepository {
    private const val PREFS = "rates"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val MAX_AGE_MS = 6 * 60 * 60 * 1000L
    private const val API_URL = "https://open.er-api.com/v6/latest/USD"

    /** Currencies whose rate is downloaded (the euro has a fixed parity). */
    private val DOWNLOADED = listOf(Currency.USD, Currency.GBP, Currency.CNY)

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun key(c: Currency) = "${c.name.lowercase()}_xof"

    /** Value of one unit of [currency] in XOF. */
    fun toXof(context: Context, currency: Currency): Double {
        if (currency == Currency.EUR) return CurrencyConverter.EUR_TO_XOF
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val default = CurrencyConverter.DEFAULT_RATES.getValue(currency)
        val v = java.lang.Double.longBitsToDouble(
            prefs.getLong(key(currency), java.lang.Double.doubleToRawLongBits(default))
        )
        return if (v > 0) v else default
    }

    /** Last successful update in epoch millis, or 0 if never updated. */
    fun lastUpdate(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_UPDATED_AT, 0L)

    fun converter(context: Context) =
        CurrencyConverter(DOWNLOADED.associateWith { toXof(context, it) })

    /**
     * Downloads the latest rates if the cached ones are older than 6 hours (or
     * always when [force] is true). [onDone] is invoked on the main thread with
     * true when fresh rates were stored.
     */
    fun refresh(context: Context, force: Boolean = false, onDone: ((Boolean) -> Unit)? = null) {
        val app = context.applicationContext
        if (!force && System.currentTimeMillis() - lastUpdate(app) < MAX_AGE_MS) {
            onDone?.invoke(false)
            return
        }
        executor.execute {
            val ok = try {
                val conn = URI.create(API_URL).toURL().openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                try {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val rates = json.getJSONObject("rates")
                    // Rates are given per US dollar: 1 unit = XOF / (units per dollar).
                    val xofPerUsd = rates.getDouble("XOF")
                    if (json.optString("result") == "success" && xofPerUsd > 0) {
                        val edit = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        for (c in DOWNLOADED) {
                            val perUsd = if (c == Currency.USD) 1.0 else rates.optDouble(c.name, 0.0)
                            if (perUsd > 0) {
                                edit.putLong(key(c), java.lang.Double.doubleToRawLongBits(xofPerUsd / perUsd))
                            }
                        }
                        edit.putLong(KEY_UPDATED_AT, System.currentTimeMillis()).apply()
                        true
                    } else false
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                false
            }
            mainHandler.post { onDone?.invoke(ok) }
        }
    }
}

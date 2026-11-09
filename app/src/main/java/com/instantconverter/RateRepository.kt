package com.instantconverter

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors

/**
 * Stores the USD → XOF exchange rate. EUR → XOF is a fixed parity and never
 * needs to be downloaded. The USD rate is refreshed from a free public API
 * (no key required) and cached so the app keeps working offline.
 */
object RateRepository {
    private const val PREFS = "rates"
    private const val KEY_USD_XOF = "usd_xof"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val DEFAULT_USD_XOF = 565.0
    private const val MAX_AGE_MS = 6 * 60 * 60 * 1000L
    private const val API_URL = "https://open.er-api.com/v6/latest/USD"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun usdToXof(context: Context): Double {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val v = java.lang.Double.longBitsToDouble(
            prefs.getLong(KEY_USD_XOF, java.lang.Double.doubleToRawLongBits(DEFAULT_USD_XOF))
        )
        return if (v > 0) v else DEFAULT_USD_XOF
    }

    /** Last successful update in epoch millis, or 0 if never updated. */
    fun lastUpdate(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_UPDATED_AT, 0L)

    fun converter(context: Context) = CurrencyConverter(usdToXof(context))

    /**
     * Downloads the latest rate if the cached one is older than 6 hours (or
     * always when [force] is true). [onDone] is invoked on the main thread with
     * true when a fresh rate was stored.
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
                    val rate = json.getJSONObject("rates").getDouble("XOF")
                    if (json.optString("result") == "success" && rate > 0) {
                        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putLong(KEY_USD_XOF, java.lang.Double.doubleToRawLongBits(rate))
                            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                            .apply()
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

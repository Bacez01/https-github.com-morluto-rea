package com.instantconverter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** The last 10 conversions, kept on the device only. */
object HistoryStore {
    private const val PREFS = "history"
    private const val KEY = "items"
    private const val MAX = 10

    data class Entry(val time: Long, val source: String, val result: String) {
        fun format(): String {
            val t = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time))
            return "$t   $source → $result"
        }
    }

    fun list(context: Context): List<Entry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Entry(o.getLong("t"), o.getString("s"), o.getString("r"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(context: Context, source: String, result: String) {
        val items = list(context).toMutableList()
        // Selecting the same price twice in a row adds it only once.
        if (items.firstOrNull()?.let { it.source == source && it.result == result } == true) {
            items.removeAt(0)
        }
        items.add(0, Entry(System.currentTimeMillis(), source, result))
        save(context, items.take(MAX))
    }

    fun clear(context: Context) = save(context, emptyList())

    private fun save(context: Context, items: List<Entry>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("t", it.time).put("s", it.source).put("r", it.result)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}

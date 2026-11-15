package com.radiomonde

import android.content.Context
import org.json.JSONArray

/** Favourites, recently played stations and settings, kept in SharedPreferences. */
class StationStore(context: Context) {
    private val prefs = context.getSharedPreferences("radio_monde", Context.MODE_PRIVATE)

    var countryCode: String
        get() = prefs.getString(KEY_COUNTRY, "FR") ?: "FR"
        set(value) = prefs.edit().putString(KEY_COUNTRY, value).apply()

    var countryName: String
        get() = prefs.getString(KEY_COUNTRY_NAME, "France") ?: "France"
        set(value) = prefs.edit().putString(KEY_COUNTRY_NAME, value).apply()

    fun favorites(): List<Station> = read(KEY_FAVORITES)
    fun recents(): List<Station> = read(KEY_RECENTS)

    fun isFavorite(uuid: String) = favorites().any { it.uuid == uuid }

    /** Adds or removes a favourite; returns true when the station is now a favourite. */
    fun toggleFavorite(station: Station): Boolean {
        val list = favorites().toMutableList()
        val removed = list.removeAll { it.uuid == station.uuid }
        if (!removed) list.add(0, station)
        write(KEY_FAVORITES, list)
        return !removed
    }

    fun addRecent(station: Station) {
        val list = recents().filterNot { it.uuid == station.uuid }.toMutableList()
        list.add(0, station)
        write(KEY_RECENTS, list.take(MAX_RECENTS))
    }

    fun clearRecents() = write(KEY_RECENTS, emptyList())

    /** Last station played: the first recent one. */
    fun lastStation(): Station? = recents().firstOrNull()

    fun find(uuid: String): Station? =
        recents().firstOrNull { it.uuid == uuid } ?: favorites().firstOrNull { it.uuid == uuid }

    private fun read(key: String): List<Station> {
        val text = prefs.getString(key, null) ?: return emptyList()
        return try {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(Station::fromJson) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun write(key: String, list: List<Station>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        prefs.edit().putString(key, array.toString()).apply()
    }

    private companion object {
        const val KEY_COUNTRY = "country"
        const val KEY_COUNTRY_NAME = "country_name"
        const val KEY_FAVORITES = "favorites"
        const val KEY_RECENTS = "recents"
        const val MAX_RECENTS = 30
    }
}

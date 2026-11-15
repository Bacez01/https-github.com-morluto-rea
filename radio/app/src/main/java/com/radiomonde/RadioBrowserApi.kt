package com.radiomonde

import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Client for the free Radio Browser API (no key needed). Several mirror servers exist; one is
 * picked at random and the others are tried if it fails. All calls block: run them off the UI thread.
 */
object RadioBrowserApi {
    private const val USER_AGENT = "RadioMonde/1.0 (Android)"
    private val fallbackServers = listOf(
        "de1.api.radio-browser.info",
        "de2.api.radio-browser.info",
        "fi1.api.radio-browser.info",
        "nl1.api.radio-browser.info",
    )

    @Volatile private var servers: List<String>? = null
    @Volatile private var preferred: String? = null

    const val PAGE_SIZE = 60

    fun topStations(countryCode: String, offset: Int = 0): List<Station> =
        stations(searchQuery(countryCode = countryCode, order = "clickcount", offset = offset))

    fun search(text: String, countryCode: String? = null, offset: Int = 0): List<Station> =
        stations(searchQuery(name = text, countryCode = countryCode, order = "clickcount", offset = offset))

    fun byGenre(genre: String, countryCode: String? = null, offset: Int = 0): List<Station> =
        stations(searchQuery(tag = genre, countryCode = countryCode, order = "clickcount", offset = offset))

    fun genres(): List<Genre> {
        val array = JSONArray(get("/json/tags?order=stationcount&reverse=true&hidebroken=true&limit=150"))
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").trim()
            val count = o.optInt("stationcount")
            if (name.length < 2 || name.length > 30 || count < 20) null else Genre(name, count)
        }
    }

    fun countries(): List<Country> {
        val array = JSONArray(get("/json/countries?order=stationcount&reverse=true&hidebroken=true"))
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val code = o.optString("iso_3166_1").trim().uppercase()
            val count = o.optInt("stationcount")
            if (code.length != 2 || count < 1) null else Country(o.optString("name").trim(), code, count)
        }
    }

    /** Tells the directory a station was played (it uses this to rank popular stations). Errors are ignored. */
    fun registerClick(uuid: String) {
        try { get("/json/url/" + enc(uuid)) } catch (_: Exception) { }
    }

    fun searchQuery(
        name: String? = null,
        tag: String? = null,
        countryCode: String? = null,
        order: String = "clickcount",
        offset: Int = 0,
        limit: Int = PAGE_SIZE,
    ): String {
        val params = mutableListOf<String>()
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { params += "name=" + enc(it) }
        tag?.trim()?.takeIf { it.isNotEmpty() }?.let { params += "tag=" + enc(it) + "&tagExact=true" }
        countryCode?.trim()?.takeIf { it.isNotEmpty() }?.let { params += "countrycode=" + enc(it.uppercase()) }
        params += "order=$order"
        params += "reverse=true"
        params += "hidebroken=true"
        params += "offset=$offset"
        params += "limit=$limit"
        return "/json/stations/search?" + params.joinToString("&")
    }

    private fun stations(path: String) = Station.listFromJson(get(path))

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun serverList(): List<String> {
        servers?.let { return it }
        val list = try {
            val array = JSONArray(fetch("https://all.api.radio-browser.info/json/servers"))
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
                .distinct().ifEmpty { fallbackServers }
        } catch (_: Exception) {
            fallbackServers
        }
        return list.shuffled().also { servers = it }
    }

    private fun get(path: String): String {
        val candidates = (listOfNotNull(preferred) + serverList()).distinct()
        var last: Exception? = null
        for (host in candidates) {
            try {
                return fetch("https://$host$path").also { preferred = host }
            } catch (e: Exception) {
                last = e
                if (preferred == host) preferred = null
            }
        }
        throw IOException("Radio Browser unreachable", last)
    }

    private fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode} for $url")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

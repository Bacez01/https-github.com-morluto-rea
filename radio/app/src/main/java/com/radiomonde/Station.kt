package com.radiomonde

import org.json.JSONArray
import org.json.JSONObject

/** A radio station from the Radio Browser directory (https://www.radio-browser.info). */
data class Station(
    val uuid: String,
    val name: String,
    val streamUrl: String,
    val favicon: String,
    val homepage: String,
    val tags: List<String>,
    val country: String,
    val countryCode: String,
    val codec: String,
    val bitrate: Int,
) {
    /** One-line description shown under the station name: "France · pop, news · MP3 128 kbit/s". */
    fun subtitle(): String = listOfNotNull(
        country.takeIf { it.isNotBlank() },
        tags.take(3).joinToString(", ").takeIf { it.isNotBlank() },
        quality().takeIf { it.isNotBlank() },
    ).joinToString(" · ")

    fun quality(): String = when {
        codec.isNotBlank() && bitrate > 0 -> "${codec.uppercase()} $bitrate kbit/s"
        bitrate > 0 -> "$bitrate kbit/s"
        else -> codec.uppercase()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("stationuuid", uuid)
        .put("name", name)
        .put("url_resolved", streamUrl)
        .put("favicon", favicon)
        .put("homepage", homepage)
        .put("tags", tags.joinToString(","))
        .put("country", country)
        .put("countrycode", countryCode)
        .put("codec", codec)
        .put("bitrate", bitrate)

    companion object {
        /** Parses one station object; returns null when it has no usable id or stream. */
        fun fromJson(o: JSONObject): Station? {
            val uuid = o.optString("stationuuid").trim()
            val stream = o.optString("url_resolved").trim().ifEmpty { o.optString("url").trim() }
            if (uuid.isEmpty() || !(stream.startsWith("http://") || stream.startsWith("https://"))) return null
            return Station(
                uuid = uuid,
                name = o.optString("name").trim().replace(Regex("\\s+"), " ").ifEmpty { "Radio" },
                streamUrl = stream,
                favicon = o.optString("favicon").trim(),
                homepage = o.optString("homepage").trim(),
                tags = o.optString("tags").split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                country = o.optString("country").trim(),
                countryCode = o.optString("countrycode").trim().uppercase(),
                codec = o.optString("codec").trim().takeUnless { it.equals("UNKNOWN", true) }.orEmpty(),
                bitrate = o.optInt("bitrate", 0).coerceAtLeast(0),
            )
        }

        /** Parses an array of stations, skipping broken entries and duplicates (same name and stream). */
        fun listFromJson(text: String): List<Station> {
            val array = JSONArray(text)
            val seen = HashSet<String>()
            val result = ArrayList<Station>(array.length())
            for (i in 0 until array.length()) {
                val s = array.optJSONObject(i)?.let { fromJson(it) } ?: continue
                if (seen.add(s.uuid) && seen.add(s.name.lowercase() + "|" + s.streamUrl)) result.add(s)
            }
            return result
        }
    }
}

/** A genre (Radio Browser "tag") with its number of stations. */
data class Genre(val name: String, val stationCount: Int)

/** A country with its ISO code and number of stations. */
data class Country(val name: String, val code: String, val stationCount: Int) {
    val flag: String get() = flagEmoji(code)
}

/** Turns "FR" into the 🇫🇷 flag. */
fun flagEmoji(code: String): String {
    if (code.length != 2 || !code.all { it in 'A'..'Z' || it in 'a'..'z' }) return "🏳"
    val c = code.uppercase()
    return String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (c[1] - 'A')))
}

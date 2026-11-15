package com.radiomonde

import java.net.HttpURLConnection
import java.net.URL

/**
 * Some directory entries point to a playlist file (.pls, .m3u, .asx) instead of the audio stream.
 * The player cannot open those, so the first stream address is read from the file.
 */
object PlaylistResolver {
    private val playlistExt = Regex("""\.(pls|m3u|asx|xspf)(\?.*)?$""", RegexOption.IGNORE_CASE)
    private val urlPattern = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

    fun isPlaylist(url: String): Boolean = playlistExt.containsMatchIn(url.substringBefore('#'))

    /** First stream address found in a playlist file, or null. */
    fun firstStream(text: String): String? {
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            // PLS: "File1=http://…"; M3U: the address alone; ASX/XSPF: inside an attribute or tag.
            val value = if (line.matches(Regex("(?i)^file\\d+=.*"))) line.substringAfter('=').trim() else line
            urlPattern.find(value)?.let { return it.value.trimEnd('&', ';') }
        }
        return null
    }

    /** Returns the address to play: the stream itself, or the first entry of the playlist it points to. */
    fun resolve(url: String): String {
        if (!isPlaylist(url)) return url
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 6000
                c.readTimeout = 6000
                c.setRequestProperty("User-Agent", "RadioMonde/1.0 (Android)")
                val text = c.inputStream.bufferedReader().use { r -> CharArray(64 * 1024).let { String(it, 0, r.read(it).coerceAtLeast(0)) } }
                firstStream(text) ?: url
            } finally {
                c.disconnect()
            }
        } catch (_: Exception) {
            url
        }
    }
}

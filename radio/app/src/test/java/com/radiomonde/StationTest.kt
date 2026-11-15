package com.radiomonde

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationTest {
    private val sample = """
        [
          {"stationuuid":"a1","name":"  FIP  ","url":"http://x/fip.m3u","url_resolved":"https://icecast.radiofrance.fr/fip-hifi.aac",
           "favicon":"https://x/fip.png","tags":"jazz, eclectic,,jazz","country":"France","countrycode":"fr","codec":"AAC","bitrate":192},
          {"stationuuid":"a2","name":"FIP","url_resolved":"https://icecast.radiofrance.fr/fip-hifi.aac"},
          {"stationuuid":"","name":"No id","url_resolved":"http://x"},
          {"stationuuid":"a3","name":"No stream","url_resolved":"ftp://x"},
          {"stationuuid":"a4","name":"Only url","url":"http://only/url","codec":"UNKNOWN","bitrate":0}
        ]
    """.trimIndent()

    @Test
    fun parsesAndCleansStations() {
        val list = Station.listFromJson(sample)
        assertEquals(listOf("a1", "a4"), list.map { it.uuid })
        val fip = list[0]
        assertEquals("FIP", fip.name)
        assertEquals("https://icecast.radiofrance.fr/fip-hifi.aac", fip.streamUrl)
        assertEquals(listOf("jazz", "eclectic"), fip.tags)
        assertEquals("FR", fip.countryCode)
        assertEquals("France · jazz, eclectic · AAC 192 kbit/s", fip.subtitle())
        assertEquals("http://only/url", list[1].streamUrl)
        assertEquals("", list[1].quality())
    }

    @Test
    fun jsonRoundTrip() {
        val fip = Station.listFromJson(sample)[0]
        assertEquals(fip, Station.fromJson(JSONObject(fip.toJson().toString())))
    }

    @Test
    fun flags() {
        assertEquals("🇫🇷", flagEmoji("FR"))
        assertEquals("🇨🇮", flagEmoji("ci"))
        assertEquals("🏳", flagEmoji("FRA"))
    }

    @Test
    fun searchQueryEncodesParameters() {
        val q = RadioBrowserApi.searchQuery(name = "Radio Nova", tag = "hip hop", countryCode = "fr", offset = 60)
        assertTrue(q.startsWith("/json/stations/search?"))
        assertTrue("name=Radio+Nova" in q)
        assertTrue("tag=hip+hop&tagExact=true" in q)
        assertTrue("countrycode=FR" in q)
        assertTrue("offset=60" in q)
        assertTrue("hidebroken=true" in q)
        assertFalse("name=" in RadioBrowserApi.searchQuery(name = "  ", countryCode = null))
    }

    @Test
    fun playlists() {
        assertTrue(PlaylistResolver.isPlaylist("http://x/live.pls"))
        assertTrue(PlaylistResolver.isPlaylist("http://x/live.M3U?id=2"))
        assertFalse(PlaylistResolver.isPlaylist("http://x/live.m3u8"))
        assertFalse(PlaylistResolver.isPlaylist("http://x/stream.mp3"))

        val pls = "[playlist]\nNumberOfEntries=1\nFile1=http://stream.example/live.mp3\nTitle1=Live"
        assertEquals("http://stream.example/live.mp3", PlaylistResolver.firstStream(pls))
        val m3u = "#EXTM3U\n#EXTINF:-1,Radio\nhttps://s.example/aac\n"
        assertEquals("https://s.example/aac", PlaylistResolver.firstStream(m3u))
        val asx = "<asx version=\"3.0\"><entry><ref href=\"http://asx.example/stream\"/></entry></asx>"
        assertEquals("http://asx.example/stream", PlaylistResolver.firstStream(asx))
        assertNull(PlaylistResolver.firstStream("[playlist]\nNumberOfEntries=0"))
    }
}

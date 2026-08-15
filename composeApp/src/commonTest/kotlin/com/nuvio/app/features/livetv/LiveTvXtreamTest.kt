package com.nuvio.app.features.livetv

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveTvXtreamTest {
    @Test
    fun buildsXtreamXmlTvEndpoint() {
        val settings = LiveTvXtreamSettings(
            serverUrl = "https://provider.example/",
            username = "demo",
            password = "secret",
        )

        assertEquals(
            "https://provider.example/xmltv.php?username=demo&password=secret",
            settings.xmlTvEndpoint(),
        )
    }
    @Test
    fun parsesBase64XtreamShortEpg() {
        val response = Json.parseToJsonElement(
            """{"epg_listings":[{"title":"VGVzdCBTaG93","start_timestamp":"1700000000","stop_timestamp":"1700003600"}]}""",
        )

        val programmes = parseXtreamEpgListings(
            data = response,
            nowEpochMs = 1_699_999_000_000L,
        )

        assertEquals(1, programmes.size)
        assertEquals("Test Show", programmes.single().title)
        assertEquals(1_700_000_000_000L, programmes.single().startEpochMs)
        assertEquals(1_700_003_600_000L, programmes.single().stopEpochMs)
    }

    @Test
    fun fallsBackToChannelIdWhenXtreamTvgIdIsMissing() {
        val channel = LiveTvChannel(
            id = "xtream-123-0",
            name = "Demo",
            streamUrl = "https://example.invalid/live/123.ts",
            xtreamStreamId = "123",
        )

        assertEquals("xtream-123-0", channel.epgKey())
    }

}

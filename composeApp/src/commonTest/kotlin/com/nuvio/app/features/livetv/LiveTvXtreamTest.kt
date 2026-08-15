package com.nuvio.app.features.livetv

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
}

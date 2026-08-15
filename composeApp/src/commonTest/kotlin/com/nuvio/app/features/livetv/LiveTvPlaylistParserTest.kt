package com.nuvio.app.features.livetv

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveTvPlaylistParserTest {
    @Test
    fun parsesChannelMetadataAndHeaders() {
        val playlist = parseM3uPlaylistData(
            """
            #EXTM3U url-tvg="https://epg.test/guide.xml"
            #EXTINF:-1 tvg-id="trt1.tr" tvg-name="TRT 1" tvg-logo="https://img.test/trt.png" group-title="Ulusal",TRT 1 HD
            #EXTVLCOPT:http-user-agent=Nuvio
            #EXTVLCOPT:http-referrer=https://playlist-referrer.test/
            https://stream.test/trt.m3u8
            """.trimIndent(),
        )
        val channels = playlist.channels

        assertEquals(1, channels.size)
        assertEquals(listOf("https://epg.test/guide.xml"), playlist.epgUrls)
        assertEquals("trt1.tr", channels.first().tvgId)
        assertEquals("TRT 1 HD", channels.first().name)
        assertEquals("Ulusal", channels.first().group)
        assertEquals("https://img.test/trt.png", channels.first().logoUrl)
        assertEquals("Nuvio", channels.first().headers["User-Agent"])
        assertEquals("https://playlist-referrer.test/", channels.first().headers["Referer"])
        assertEquals("hls", channels.first().streamType)
    }

    @Test
    fun parsesInlineStreamHeaders() {
        val channels = parseM3uPlaylist(
            """
            #EXTM3U
            #EXTINF:-1,Header Channel
            https://stream.test/header.m3u8|Referer=https://example.test
            """.trimIndent(),
        )

        assertEquals(1, channels.size)
        assertEquals("https://example.test", channels.first().headers["Referer"])
    }

    @Test
    fun marksMatroskaStreamsFromPlaylist() {
        val channels = parseM3uPlaylist(
            """
            #EXTM3U
            #EXTINF:-1,Movie Stream
            #EXTVLCOPT:http-referrer=https://example.test/
            https://stream.test/movie.mkv
            """.trimIndent(),
        )

        assertEquals(1, channels.size)
        assertEquals("matroska", channels.first().streamType)
        assertEquals("https://example.test/", channels.first().headers["Referer"])
    }

    @Test
    fun removesDuplicateStreamUrls() {
        val channels = parseM3uPlaylist(
            """
            #EXTM3U
            #EXTINF:-1,Channel One
            https://stream.test/live.m3u8
            #EXTINF:-1,Channel One Duplicate
            https://stream.test/live.m3u8
            """.trimIndent(),
        )

        assertEquals(1, channels.size)
        assertEquals("Channel One", channels.first().name)
    }

    @Test
    fun skipsCategoryHeadingLikeEntries() {
        val channels = parseM3uPlaylist(
            """
            #EXTM3U
            #EXTINF:-1,#### HABER KANALLARI ####
            https://stream.test/haber.m3u8
            #EXTINF:-1,TRT 1 HD
            https://stream.test/trt1.m3u8
            """.trimIndent(),
        )

        assertEquals(1, channels.size)
        assertEquals("TRT 1 HD", channels.first().name)
    }

    @Test
    fun retainsCurrentAndFutureXmlTvProgrammesForFavoriteChannel() {
        val schedule = parseXmlTvProgrammeSchedule(
            content = """
                <tv>
                    <programme start="20240101000000 +0000" stop="20240101003000 +0000" channel="one">
                        <title>Past</title>
                    </programme>
                    <programme start="20240101003000 +0000" stop="20240101010000 +0000" channel="one">
                        <title>Current &amp; Live</title>
                    </programme>
                    <programme start="20240101010000 +0000" stop="20240101020000 +0000" channel="one">
                        <title>Future</title>
                    </programme>
                </tv>
            """.trimIndent(),
            nowEpochMs = 1704069900000L,
            relevantChannelIds = setOf("one"),
            retainedScheduleChannelIds = setOf("one"),
        )

        assertEquals(
            listOf("Current & Live", "Future"),
            schedule["one"]?.map(LiveTvProgramme::title),
        )
        assertEquals("00:30 - 01:00", schedule["one"]?.first()?.timeLabel)

        val current = currentXmlTvProgrammes(
            programmesByChannel = schedule,
            nowEpochMs = 1704069900000L,
        )
        assertEquals("Current & Live", current["one"]?.title)
    }

    @Test
    fun retainsCurrentForAllRelevantChannelsButFutureOnlyForFavorites() {
        val schedule = parseXmlTvProgrammeSchedule(
            content = """
                <tv>
                    <programme start="20240101003000 +0000" stop="20240101010000 +0000" channel="favorite">
                        <title>Favorite Current</title>
                    </programme>
                    <programme start="20240101010000 +0000" stop="20240101020000 +0000" channel="favorite">
                        <title>Favorite Future</title>
                    </programme>
                    <programme start="20240101003000 +0000" stop="20240101010000 +0000" channel="other">
                        <title>Other Current</title>
                    </programme>
                    <programme start="20240101010000 +0000" stop="20240101020000 +0000" channel="other">
                        <title>Other Future</title>
                    </programme>
                    <programme start="20240101003000 +0000" stop="20240101010000 +0000" channel="provider-only">
                        <title>Provider Only</title>
                    </programme>
                </tv>
            """.trimIndent(),
            nowEpochMs = 1704069900000L,
            relevantChannelIds = setOf("favorite", "other"),
            retainedScheduleChannelIds = setOf("favorite"),
        )

        assertEquals(
            listOf("Favorite Current", "Favorite Future"),
            schedule["favorite"]?.map(LiveTvProgramme::title),
        )
        assertEquals(listOf("Other Current"), schedule["other"]?.map(LiveTvProgramme::title))
        assertEquals(null, schedule["provider-only"])

        val current = currentXmlTvProgrammes(schedule, nowEpochMs = 1704069900000L)
        assertEquals("Favorite Current", current["favorite"]?.title)
        assertEquals("Other Current", current["other"]?.title)
    }

    @Test
    fun dropsProviderProgrammesWhenNoChannelIdsAreRelevant() {
        val schedule = parseXmlTvProgrammeSchedule(
            content = """
                <tv>
                    <programme start="20240101003000 +0000" stop="20240101010000 +0000" channel="provider-one">
                        <title>Provider Current</title>
                    </programme>
                    <programme start="20240101010000 +0000" stop="20240101020000 +0000" channel="provider-one">
                        <title>Provider Future</title>
                    </programme>
                </tv>
            """.trimIndent(),
            nowEpochMs = 1704069900000L,
            relevantChannelIds = emptySet(),
            retainedScheduleChannelIds = emptySet(),
        )

        assertEquals(emptyMap(), schedule)
    }

    @Test
    fun mergesAndDeduplicatesXmlTvSchedules() {
        val first = LiveTvProgramme(
            title = "First",
            startEpochMs = 1000L,
            stopEpochMs = 2000L,
            timeLabel = "00:00 - 00:30",
        )
        val second = LiveTvProgramme(
            title = "Second",
            startEpochMs = 2000L,
            stopEpochMs = 3000L,
            timeLabel = "00:30 - 01:00",
        )

        val merged = mergeXmlTvProgrammeSchedules(
            listOf(
                mapOf("one" to listOf(second, first)),
                mapOf("one" to listOf(first)),
            ),
        )

        assertEquals(listOf(first, second), merged["one"])
    }
}

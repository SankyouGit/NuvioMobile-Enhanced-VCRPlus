package com.nuvio.app.features.livetv

private val fullXmlTvProgrammeRegex = Regex(
    """<programme\b([^>]*)>([\s\S]*?)</programme>""",
    RegexOption.IGNORE_CASE,
)
private val fullXmlTvTitleRegex = Regex(
    """<title\b[^>]*>([\s\S]*?)</title>""",
    RegexOption.IGNORE_CASE,
)
private val fullXmlTvAttributeRegex = Regex("""([\w-]+)="([^"]*)"""")

/**
 * Parses XMLTV without retaining an entire provider guide in memory.
 *
 * Current programmes are kept for every channel in the loaded playlist so the existing Live TV
 * list continues to show what is airing now. Future programmes are retained only for favorite
 * channels because the full guide is favorites-only. Provider channels that are not present in the
 * loaded playlist are skipped entirely.
 *
 * Tests and compatibility callers can supply explicit channel sets. When no sets are supplied and
 * no playlist is loaded, all channels are treated as relevant so the parser remains independently
 * testable.
 */
internal fun parseXmlTvProgrammeSchedule(
    content: String,
    nowEpochMs: Long = LiveTvClock.nowEpochMs(),
    relevantChannelIds: Set<String>? = null,
    retainedScheduleChannelIds: Set<String>? = null,
): Map<String, List<LiveTvProgramme>> {
    val liveState = LiveTvRepository.uiState.value
    val loadedChannelIds = liveState.channels
        .mapNotNull(LiveTvChannel::tvgId)
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()
    val inferredRelevantChannelIds = loadedChannelIds.takeIf(Set<String>::isNotEmpty)
    val favoriteChannelIds = liveState.channels
        .asSequence()
        .filter { channel -> channel.streamUrl in liveState.favoriteUrls }
        .mapNotNull(LiveTvChannel::tvgId)
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()

    val relevantIds = relevantChannelIds ?: inferredRelevantChannelIds
    val futureIds = retainedScheduleChannelIds ?: favoriteChannelIds
    val programmes = mutableMapOf<String, MutableList<LiveTvProgramme>>()

    fullXmlTvProgrammeRegex.findAll(content).forEach { match ->
        val attributes = fullXmlTvAttributeRegex.findAll(match.groupValues[1])
            .associate { attribute ->
                attribute.groupValues[1].lowercase() to attribute.groupValues[2]
            }
        val channelId = attributes["channel"]?.trim()?.takeIf(String::isNotBlank)
            ?: return@forEach
        if (relevantIds != null && channelId !in relevantIds) return@forEach

        val rawStart = attributes["start"].orEmpty()
        val rawStop = attributes["stop"].orEmpty()
        val startEpochMs = LiveTvClock.parseXmlTvTimestamp(rawStart) ?: return@forEach
        val stopEpochMs = LiveTvClock.parseXmlTvTimestamp(rawStop) ?: return@forEach
        if (stopEpochMs <= startEpochMs || stopEpochMs <= nowEpochMs) return@forEach

        val isCurrent = nowEpochMs in startEpochMs until stopEpochMs
        val retainFutureSchedule = channelId in futureIds
        if (!isCurrent && !retainFutureSchedule) return@forEach

        val title = fullXmlTvTitleRegex.find(match.groupValues[2])
            ?.groupValues
            ?.get(1)
            ?.decodeFullXmlTvEntities()
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return@forEach

        programmes.getOrPut(channelId) { mutableListOf() } += LiveTvProgramme(
            title = title,
            startEpochMs = startEpochMs,
            stopEpochMs = stopEpochMs,
            timeLabel = "${rawStart.fullXmlTvTimePart()} - ${rawStop.fullXmlTvTimePart()}",
        )
    }

    return programmes.mapValues { (_, entries) ->
        entries
            .distinctBy { programme ->
                Triple(programme.startEpochMs, programme.stopEpochMs, programme.title)
            }
            .sortedBy(LiveTvProgramme::startEpochMs)
    }
}

internal fun mergeXmlTvProgrammeSchedules(
    schedules: Iterable<Map<String, List<LiveTvProgramme>>>,
): Map<String, List<LiveTvProgramme>> {
    val merged = mutableMapOf<String, MutableList<LiveTvProgramme>>()
    schedules.forEach { schedule ->
        schedule.forEach { (channelId, programmes) ->
            merged.getOrPut(channelId) { mutableListOf() }.addAll(programmes)
        }
    }
    return merged.mapValues { (_, programmes) ->
        programmes
            .distinctBy { programme ->
                Triple(programme.startEpochMs, programme.stopEpochMs, programme.title)
            }
            .sortedBy(LiveTvProgramme::startEpochMs)
    }
}

internal fun currentXmlTvProgrammes(
    programmesByChannel: Map<String, List<LiveTvProgramme>>,
    nowEpochMs: Long = LiveTvClock.nowEpochMs(),
): Map<String, LiveTvProgramme> =
    programmesByChannel.entries.mapNotNull { (channelId, programmes) ->
        programmes
            .firstOrNull { programme -> nowEpochMs in programme.startEpochMs until programme.stopEpochMs }
            ?.let { programme -> channelId to programme }
    }.toMap()

private fun String.fullXmlTvTimePart(): String {
    val digits = takeWhile(Char::isDigit)
    return if (digits.length >= 12) {
        "${digits.substring(8, 10)}:${digits.substring(10, 12)}"
    } else {
        ""
    }
}

private fun String.decodeFullXmlTvEntities(): String =
    replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")

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
 * Parses the usable XMLTV schedule, retaining the programme that is currently airing and all
 * future programmes. Entries that have already ended are deliberately discarded so a large EPG
 * does not keep growing the Live TV state with data the guide can no longer display.
 */
internal fun parseXmlTvProgrammeSchedule(
    content: String,
    nowEpochMs: Long = LiveTvClock.nowEpochMs(),
): Map<String, List<LiveTvProgramme>> {
    val programmes = mutableMapOf<String, MutableList<LiveTvProgramme>>()

    fullXmlTvProgrammeRegex.findAll(content).forEach { match ->
        val attributes = fullXmlTvAttributeRegex.findAll(match.groupValues[1])
            .associate { attribute ->
                attribute.groupValues[1].lowercase() to attribute.groupValues[2]
            }
        val channelId = attributes["channel"]?.trim()?.takeIf(String::isNotBlank)
            ?: return@forEach
        val rawStart = attributes["start"].orEmpty()
        val rawStop = attributes["stop"].orEmpty()
        val startEpochMs = LiveTvClock.parseXmlTvTimestamp(rawStart) ?: return@forEach
        val stopEpochMs = LiveTvClock.parseXmlTvTimestamp(rawStop) ?: return@forEach
        if (stopEpochMs <= startEpochMs || stopEpochMs <= nowEpochMs) return@forEach

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

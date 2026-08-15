package com.nuvio.app.features.livetv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvio

private const val GUIDE_MINUTE_MS = 60_000L
private const val GUIDE_HOUR_MS = 60L * GUIDE_MINUTE_MS
private const val GUIDE_PAGE_MS = 24L * GUIDE_HOUR_MS
private val GUIDE_CHANNEL_WIDTH = 136.dp
private val GUIDE_ROW_HEIGHT = 82.dp
private val GUIDE_TIME_HEADER_HEIGHT = 44.dp
private val GUIDE_DP_PER_MINUTE = 2.dp
private val GUIDE_HOUR_WIDTH = 60 * GUIDE_DP_PER_MINUTE
private val GUIDE_TIMELINE_WIDTH = 24 * GUIDE_HOUR_WIDTH

@Composable
internal fun LiveTvFavoritesGuide(
    channels: List<LiveTvChannel>,
    favoriteUrls: Set<String>,
    programmesByChannel: Map<String, List<LiveTvProgramme>>,
    isEpgLoading: Boolean,
    onChannelClick: (LiveTvChannel) -> Unit,
    onBack: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val favoriteChannels = remember(channels, favoriteUrls) {
        channels.filter { channel -> channel.streamUrl in favoriteUrls }
    }
    val nowEpochMs = remember(programmesByChannel) { LiveTvClock.nowEpochMs() }
    val basePageStartEpochMs = remember(nowEpochMs) {
        nowEpochMs - (nowEpochMs % GUIDE_HOUR_MS)
    }
    val favoriteProgrammes = remember(favoriteChannels, programmesByChannel) {
        favoriteChannels.flatMap { channel ->
            channel.tvgId?.let(programmesByChannel::get).orEmpty()
        }
    }
    val latestProgrammeStopEpochMs = remember(favoriteProgrammes, nowEpochMs) {
        favoriteProgrammes.maxOfOrNull(LiveTvProgramme::stopEpochMs) ?: nowEpochMs
    }
    val referenceProgramme = remember(favoriteProgrammes) {
        favoriteProgrammes.minByOrNull(LiveTvProgramme::startEpochMs)
    }
    val maxPageOffset = remember(latestProgrammeStopEpochMs, basePageStartEpochMs) {
        if (latestProgrammeStopEpochMs <= basePageStartEpochMs) {
            0
        } else {
            ((latestProgrammeStopEpochMs - basePageStartEpochMs - 1L) / GUIDE_PAGE_MS)
                .toInt()
                .coerceAtLeast(0)
        }
    }
    var pageOffset by remember { mutableStateOf(0) }
    val horizontalScroll = rememberScrollState()
    val verticalScroll = rememberScrollState()

    val pageStartEpochMs = basePageStartEpochMs + (pageOffset * GUIDE_PAGE_MS)
    val pageEndEpochMs = pageStartEpochMs + GUIDE_PAGE_MS
    val canGoEarlier = pageOffset > 0
    val canGoLater = pageOffset < maxPageOffset

    LaunchedEffect(maxPageOffset) {
        if (pageOffset > maxPageOffset) pageOffset = maxPageOffset
    }
    LaunchedEffect(pageOffset) {
        horizontalScroll.scrollTo(0)
        verticalScroll.scrollTo(0)
    }
    PlatformBackHandler(enabled = true, onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.colors.background)
            .padding(horizontal = 16.dp),
    ) {
        NuvioScreenHeader(
            title = "Favorites TV Guide",
            includeStatusBarPadding = false,
            onBack = onBack,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (pageOffset == 0) "Now · next 24 hours" else "+${pageOffset * 24} to +${(pageOffset + 1) * 24} hours",
                    style = MaterialTheme.typography.titleSmall,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${favoriteChannels.size} favorite channels",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    enabled = canGoEarlier,
                    onClick = { pageOffset-- },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ChevronLeft,
                        contentDescription = "Earlier guide",
                        tint = if (canGoEarlier) tokens.colors.textPrimary else tokens.colors.textDisabled,
                    )
                }
                Surface(
                    onClick = {
                        pageOffset = 0
                    },
                    enabled = pageOffset != 0,
                    color = tokens.colors.overlaySelected,
                    shape = tokens.shapes.chip,
                ) {
                    Text(
                        text = "Now",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (pageOffset == 0) tokens.colors.textMuted else tokens.colors.textPrimary,
                    )
                }
                IconButton(
                    enabled = canGoLater,
                    onClick = { pageOffset++ },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = "Later guide",
                        tint = if (canGoLater) tokens.colors.textPrimary else tokens.colors.textDisabled,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        when {
            favoriteChannels.isEmpty() -> {
                GuideMessage(
                    title = "No favorite channels yet",
                    body = "Star channels in Live TV and they will appear here automatically.",
                )
            }

            isEpgLoading && programmesByChannel.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(color = tokens.colors.accent)
                        Text(
                            text = "Loading XMLTV guide…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textMuted,
                        )
                    }
                }
            }

            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(verticalScroll),
                ) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        GuideChannelColumn(
                            channels = favoriteChannels,
                            onChannelClick = onChannelClick,
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(horizontalScroll),
                        ) {
                            Column(modifier = Modifier.width(GUIDE_TIMELINE_WIDTH)) {
                                GuideTimeHeader(
                                    pageStartEpochMs = pageStartEpochMs,
                                    referenceProgramme = referenceProgramme,
                                )
                                favoriteChannels.forEach { channel ->
                                    GuideProgrammeRow(
                                        channel = channel,
                                        programmes = channel.tvgId?.let(programmesByChannel::get).orEmpty(),
                                        pageStartEpochMs = pageStartEpochMs,
                                        pageEndEpochMs = pageEndEpochMs,
                                        nowEpochMs = nowEpochMs,
                                        onChannelClick = onChannelClick,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideMessage(
    title: String,
    body: String,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Tv,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = tokens.colors.accent,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
            )
        }
    }
}

@Composable
private fun GuideChannelColumn(
    channels: List<LiveTvChannel>,
    onChannelClick: (LiveTvChannel) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .width(GUIDE_CHANNEL_WIDTH)
            .background(tokens.colors.background),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(GUIDE_TIME_HEADER_HEIGHT)
                .padding(end = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = "CHANNEL",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted,
                fontWeight = FontWeight.SemiBold,
            )
        }

        channels.forEach { channel ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(GUIDE_ROW_HEIGHT)
                    .padding(end = 8.dp, bottom = 4.dp),
                onClick = { onChannelClick(channel) },
                color = tokens.colors.surface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(NuvioTokens.Border.thin, tokens.colors.borderSubtle),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(tokens.colors.overlaySelected),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!channel.logoUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = channel.logoUrl,
                                contentDescription = channel.name,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(4.dp),
                                contentScale = ContentScale.Fit,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Tv,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = tokens.colors.accent,
                            )
                        }
                    }
                    Text(
                        text = channel.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideTimeHeader(
    pageStartEpochMs: Long,
    referenceProgramme: LiveTvProgramme?,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .width(GUIDE_TIMELINE_WIDTH)
            .height(GUIDE_TIME_HEADER_HEIGHT),
    ) {
        repeat(24) { hourIndex ->
            val x = hourIndex * GUIDE_HOUR_WIDTH
            Box(
                modifier = Modifier
                    .offset(x = x)
                    .width(GUIDE_HOUR_WIDTH)
                    .fillMaxHeight(),
            ) {
                Box(
                    modifier = Modifier
                        .width(NuvioTokens.Border.thin)
                        .fillMaxHeight()
                        .background(tokens.colors.borderSubtle),
                )
                Text(
                    text = guideClockLabel(
                        epochMs = pageStartEpochMs + (hourIndex * GUIDE_HOUR_MS),
                        referenceProgramme = referenceProgramme,
                    ),
                    modifier = Modifier.padding(start = 8.dp, top = 12.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun GuideProgrammeRow(
    channel: LiveTvChannel,
    programmes: List<LiveTvProgramme>,
    pageStartEpochMs: Long,
    pageEndEpochMs: Long,
    nowEpochMs: Long,
    onChannelClick: (LiveTvChannel) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val visibleProgrammes = remember(programmes, pageStartEpochMs, pageEndEpochMs) {
        programmes.filter { programme ->
            programme.stopEpochMs > pageStartEpochMs && programme.startEpochMs < pageEndEpochMs
        }
    }

    Box(
        modifier = Modifier
            .width(GUIDE_TIMELINE_WIDTH)
            .height(GUIDE_ROW_HEIGHT),
    ) {
        repeat(24) { hourIndex ->
            Box(
                modifier = Modifier
                    .offset(x = hourIndex * GUIDE_HOUR_WIDTH)
                    .width(NuvioTokens.Border.thin)
                    .fillMaxHeight()
                    .background(tokens.colors.borderSubtle),
            )
        }

        if (visibleProgrammes.isEmpty()) {
            Text(
                text = "No EPG data",
                modifier = Modifier.padding(start = 12.dp, top = 28.dp),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }

        visibleProgrammes.forEach { programme ->
            val visibleStart = maxOf(programme.startEpochMs, pageStartEpochMs)
            val visibleStop = minOf(programme.stopEpochMs, pageEndEpochMs)
            val x = durationToGuideDp(visibleStart - pageStartEpochMs)
            val width = durationToGuideDp(visibleStop - visibleStart).coerceAtLeast(10.dp)
            val isCurrent = nowEpochMs in programme.startEpochMs until programme.stopEpochMs

            Surface(
                modifier = Modifier
                    .offset(x = x, y = 4.dp)
                    .width(width)
                    .height(GUIDE_ROW_HEIGHT - 8.dp)
                    .padding(end = 4.dp),
                onClick = { onChannelClick(channel) },
                color = if (isCurrent) tokens.colors.overlaySelected else tokens.colors.surfaceCard,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(
                    width = if (isCurrent) NuvioTokens.Border.medium else NuvioTokens.Border.thin,
                    color = if (isCurrent) tokens.colors.accent else tokens.colors.borderSubtle,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = programme.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textPrimary,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (width >= 90.dp) {
                        Text(
                            text = programme.timeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isCurrent) tokens.colors.accent else tokens.colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (nowEpochMs in pageStartEpochMs until pageEndEpochMs) {
            Box(
                modifier = Modifier
                    .offset(x = durationToGuideDp(nowEpochMs - pageStartEpochMs))
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(tokens.colors.accent),
            )
        }
    }
}

private fun durationToGuideDp(durationMs: Long): Dp =
    ((durationMs.toDouble() / GUIDE_MINUTE_MS.toDouble()) * GUIDE_DP_PER_MINUTE.value).dp

private fun guideClockLabel(
    epochMs: Long,
    referenceProgramme: LiveTvProgramme?,
): String {
    val referenceTime = referenceProgramme
        ?.timeLabel
        ?.substringBefore(" - ")
        ?.split(':')
        ?.takeIf { it.size == 2 }
    val referenceHour = referenceTime?.getOrNull(0)?.toIntOrNull()
    val referenceMinute = referenceTime?.getOrNull(1)?.toIntOrNull()

    if (referenceProgramme == null || referenceHour == null || referenceMinute == null) {
        val relativeHour = ((epochMs / GUIDE_HOUR_MS) % 24L).toInt()
        return "${relativeHour.toString().padStart(2, '0')}:00"
    }

    val referenceMinutes = (referenceHour * 60) + referenceMinute
    val deltaMinutes = ((epochMs - referenceProgramme.startEpochMs) / GUIDE_MINUTE_MS).toInt()
    val totalMinutes = ((referenceMinutes + deltaMinutes) % (24 * 60) + (24 * 60)) % (24 * 60)
    val hour = totalMinutes / 60
    val minute = totalMinutes % 60
    return "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
}

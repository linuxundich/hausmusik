// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import de.linuxundich.hausmusik.MainActivity
import de.linuxundich.hausmusik.R
import de.linuxundich.hausmusik.data.Connection
import de.linuxundich.hausmusik.data.GroupState
import de.linuxundich.hausmusik.data.audibleVolume
import de.linuxundich.hausmusik.sonos.DidlObject
import de.linuxundich.hausmusik.sonos.SourceKind
import de.linuxundich.hausmusik.system.HouseholdAccess
import de.linuxundich.hausmusik.ui.theme.CoverColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the widget can show: the player, or why there is none. */
private enum class WidgetStatus { Ready, Reconnecting, Searching, NoWifi, NoSpeakers }

/**
 * Home screen player for the selected room, in the stage's look: background
 * and accent come from the cover. Three sizes: one row, a large player, and
 * the large player with the queue to scroll.
 */
class PlayerWidget : GlanceAppWidget() {
    // Exact: the queue shows as many whole rows as the real height takes (a widget
    // list cannot snap, so a fitting height is the only way to avoid a cut row).
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val household = HouseholdAccess.entry(context).household()
        provideContent {
            // The session stays alive between updates, so the state is observed
            // here instead of being read once.
            val state by household.state.collectAsState()
            // Asked directly: with the app not running, the household state may be old.
            val local = WidgetNetwork.hasLocalNetwork(context)
            // Connect briefly on every redraw: without the app open nothing polls, and
            // the widget would show what was known when the app last ran.
            LaunchedEffect(local) { if (local) HouseholdAccess.withSelected(context) { _, _ -> } }
            val status = when {
                !local || state.connection == Connection.NoLocalNetwork -> WidgetStatus.NoWifi
                state.connection is Connection.NotFound -> WidgetStatus.NoSpeakers
                state.selected == null -> WidgetStatus.Searching
                state.connection == Connection.Reconnecting -> WidgetStatus.Reconnecting
                else -> WidgetStatus.Ready
            }
            val offline = status == WidgetStatus.NoWifi
            LaunchedEffect(offline) { if (offline) WidgetNetwork.wakeOnLocalNetwork(context) }
            val group = state.selected.takeIf { status == WidgetStatus.Ready || status == WidgetStatus.Reconnecting }
            val cover by produceState<Bitmap?>(null, group?.artUrl) { value = group?.artUrl?.let { loadCover(context, it) } }
            val look by produceState(lookOf(null), cover) { value = withContext(Dispatchers.Default) { lookOf(cover) } }
            val size = LocalSize.current
            val tall = size.height >= TALL.height
            val fontScale = context.resources.configuration.fontScale
            val queueRows = if (tall) queueRows(size.height, fontScale) else 0
            // The queue is only read again when it or the room changes.
            val queue by produceState<List<DidlObject>?>(null, queueRows > 0, group?.id, group?.queueLength) {
                value = if (queueRows > 0 && group != null && group.queueLength > 0) household.browseAll("Q:0", limit = QUEUE_LIMIT).getOrNull() else null
            }
            val c = look.colors
            Box(
                GlanceModifier.fillMaxSize().cornerRadius(28.dp)
                    .background(ImageProvider(look.background), contentScale = ContentScale.FillBounds),
            ) {
                when {
                    group == null -> Status(context, status, c, size.height >= LARGE.height)
                    // The top stays the same in every large size: pulled taller, only the
                    // volume row and then the queue are added below. Its height follows the
                    // content (one title line, so a large font still fits the smallest size).
                    size.height >= LARGE.height && size.width >= LARGE.width -> Column(GlanceModifier.fillMaxSize()) {
                        Large(context, group, cover, offline, c, GlanceModifier.fillMaxWidth(), status)
                        if (tall) VolumeRow(context, group, c, big = true)
                        if (queueRows > 0) QueueList(context, group, queue, c, queueRows, queueRowHeight(fontScale))
                    }
                    else -> Compact(context, group, cover, offline, c, status)
                }
            }
        }
    }

    /**
     * No player when the speakers cannot be reached: what is wrong and what
     * helps. No Wi-Fi: a tap opens the Wi-Fi picker; otherwise the app.
     */
    @Composable
    private fun Status(context: Context, status: WidgetStatus, c: CoverColors, roomy: Boolean) {
        val (icon, title, body) = when (status) {
            WidgetStatus.NoWifi -> Triple(R.drawable.ic_wifi_off, R.string.offline_short, R.string.widget_no_wifi_body)
            WidgetStatus.NoSpeakers -> Triple(R.drawable.ic_speaker, R.string.widget_no_speakers, R.string.widget_no_speakers_body)
            else -> Triple(R.drawable.ic_speaker, R.string.widget_searching, null)
        }
        val action = if (status == WidgetStatus.NoWifi) {
            val panel = if (android.os.Build.VERSION.SDK_INT >= 29) android.provider.Settings.Panel.ACTION_WIFI else android.provider.Settings.ACTION_WIFI_SETTINGS
            androidx.glance.appwidget.action.actionStartActivity(android.content.Intent(panel).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            actionStartActivity<MainActivity>()
        }
        val iconBox = @Composable {
            Box(GlanceModifier.size(if (roomy) 56.dp else 44.dp).cornerRadius(if (roomy) 18.dp else 14.dp).background(ColorProvider(c.subtle)), contentAlignment = Alignment.Center) {
                Image(ImageProvider(icon), null, GlanceModifier.size(if (roomy) 28.dp else 22.dp), colorFilter = ColorFilter.tint(ColorProvider(c.accent)))
            }
        }
        val texts = @Composable {
            Text(context.getString(title), maxLines = 1, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = if (roomy) 18.sp else 15.sp, color = ColorProvider(c.content)))
            if (body != null) {
                Text(context.getString(body), maxLines = 2, style = TextStyle(fontSize = 13.sp, color = ColorProvider(c.content.copy(alpha = 0.7f))))
            }
        }
        if (roomy) {
            Column(GlanceModifier.fillMaxSize().padding(20.dp).clickable(action), verticalAlignment = Alignment.CenterVertically) {
                iconBox()
                Spacer(GlanceModifier.height(12.dp))
                texts()
            }
        } else {
            Row(GlanceModifier.fillMaxSize().padding(12.dp).clickable(action), verticalAlignment = Alignment.CenterVertically) {
                iconBox()
                Spacer(GlanceModifier.width(12.dp))
                Column(GlanceModifier.defaultWeight()) { texts() }
            }
        }
    }

    private fun title(context: Context, group: GroupState?) =
        group?.nowPlaying?.title ?: context.getString(if (group == null) R.string.app_name else R.string.nothing_playing)

    private fun subtitle(context: Context, group: GroupState?, offline: Boolean) =
        if (offline) context.getString(R.string.offline_short) else (group?.nowPlaying?.let { it.artist ?: it.station }).orEmpty()

    @Composable
    private fun CoverImage(cover: Bitmap?, c: CoverColors, size: Dp, corner: Dp) {
        Box(GlanceModifier.size(size).cornerRadius(corner).background(ColorProvider(c.subtle)), contentAlignment = Alignment.Center) {
            if (cover != null) {
                Image(ImageProvider(cover), null, GlanceModifier.size(size).cornerRadius(corner), contentScale = ContentScale.Crop)
            } else {
                Image(ImageProvider(R.drawable.ic_speaker), null, GlanceModifier.size(size / 2), colorFilter = ColorFilter.tint(ColorProvider(c.content)))
            }
        }
    }

    /** Previous, a pill-shaped play/pause in the accent color like on the stage, next. */
    @Composable
    private fun Controls(context: Context, group: GroupState?, c: CoverColors, big: Boolean, slim: Boolean = false) {
        val playing = group?.isPlaying == true
        val side = if (big) 44.dp else if (slim) 34.dp else 40.dp
        val gap = if (slim) 2.dp else 6.dp
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBox(context, R.drawable.ic_previous, R.string.previous, actionRunCallback<PreviousAction>(), side, side, null, c.content)
            Spacer(GlanceModifier.width(gap))
            IconBox(
                context, if (playing) R.drawable.ic_pause else R.drawable.ic_play, if (playing) R.string.pause else R.string.play,
                actionRunCallback<PlayPauseAction>(), if (big) 72.dp else if (slim) 48.dp else 56.dp, side, c.accent, c.onAccent,
            )
            Spacer(GlanceModifier.width(gap))
            IconBox(context, R.drawable.ic_next, R.string.next, actionRunCallback<NextAction>(), side, side, null, c.content)
        }
    }

    @Composable
    private fun IconBox(context: Context, icon: Int, label: Int, action: Action, width: Dp, height: Dp, fill: Color?, tint: Color) {
        var m = GlanceModifier.width(width).height(height).cornerRadius(height / 2)
        if (fill != null) m = m.background(ColorProvider(fill))
        Box(m.clickable(action), contentAlignment = Alignment.Center) {
            Image(ImageProvider(icon), context.getString(label), GlanceModifier.size(24.dp), colorFilter = ColorFilter.tint(ColorProvider(tint)))
        }
    }

    /**
     * One row: cover, title and room, controls. Narrow (two cells), only
     * play/pause, so the title keeps room to be read.
     */
    @Composable
    private fun Compact(context: Context, group: GroupState?, cover: Bitmap?, offline: Boolean, c: CoverColors, status: WidgetStatus) {
        val narrow = LocalSize.current.width < ROW_WIDE.width
        val texts = @Composable { modifier: GlanceModifier ->
            Column(modifier) {
                Text(title(context, group), maxLines = 1, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, color = ColorProvider(c.content)))
                Text(
                    if (status == WidgetStatus.Reconnecting) context.getString(R.string.widget_reconnecting)
                    else listOf(subtitle(context, group, offline), group?.group?.displayName.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · "),
                    maxLines = 1, style = TextStyle(fontSize = 12.sp, color = ColorProvider(c.content.copy(alpha = 0.7f))),
                )
            }
        }
        if (narrow) {
            Row(GlanceModifier.fillMaxSize().padding(10.dp).clickable(actionStartActivity<MainActivity>()), verticalAlignment = Alignment.CenterVertically) {
                CoverImage(cover, c, 48.dp, 14.dp)
                Spacer(GlanceModifier.width(10.dp))
                texts(GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(6.dp))
                PlayPause(context, group, c)
            }
            return
        }
        // Four cells: the cover over the full height; beside it title and controls, below them the volume.
        Row(
            GlanceModifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp).clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverImage(cover, c, 64.dp, 16.dp)
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight()) {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    texts(GlanceModifier.defaultWeight())
                    Spacer(GlanceModifier.width(4.dp))
                    Controls(context, group, c, big = false, slim = true)
                }
                VolumeRow(context, group, c, big = false)
            }
        }
    }

    @Composable
    private fun PlayPause(context: Context, group: GroupState?, c: CoverColors) {
        val playing = group?.isPlaying == true
        IconBox(
            context, if (playing) R.drawable.ic_pause else R.drawable.ic_play, if (playing) R.string.pause else R.string.play,
            actionRunCallback<PlayPauseAction>(), 44.dp, 44.dp, c.accent, c.onAccent,
        )
    }

    /**
     * Quieter, the level, louder: Glance has no slider. With muted rooms in the
     * group it shows and moves the audible ones, as the app's group slider does.
     */
    @Composable
    private fun VolumeRow(context: Context, group: GroupState?, c: CoverColors, big: Boolean) {
        val volume = group?.let { audibleVolume(it.memberVolumes, it.memberMutes) ?: it.groupVolume } ?: 0
        val button = if (big) 36.dp else 28.dp
        val step = @Composable { icon: Int, label: Int, delta: Int ->
            Box(
                GlanceModifier.size(button).cornerRadius(button / 2).background(ColorProvider(c.subtle))
                    .clickable(actionRunCallback<VolumeStepAction>(actionParametersOf(VOLUME_STEP to delta))),
                contentAlignment = Alignment.Center,
            ) {
                Image(ImageProvider(icon), context.getString(label), GlanceModifier.size(button / 2 + 2.dp), colorFilter = ColorFilter.tint(ColorProvider(c.content)))
            }
        }
        Row(
            GlanceModifier.fillMaxWidth().padding(horizontal = if (big) 14.dp else 0.dp, vertical = if (big) 4.dp else 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            step(R.drawable.ic_volume_down, R.string.volume_down, -VOLUME_DELTA)
            Box(GlanceModifier.defaultWeight().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                LinearProgressIndicator(
                    progress = volume / 100f,
                    modifier = GlanceModifier.fillMaxWidth().height(if (big) 6.dp else 4.dp).cornerRadius(3.dp),
                    color = ColorProvider(c.accent), backgroundColor = ColorProvider(c.subtle),
                )
            }
            step(R.drawable.ic_volume_up, R.string.volume_up, VOLUME_DELTA)
        }
    }

    /** Large cover, room, title, artist, controls. */
    @Composable
    private fun Large(context: Context, group: GroupState?, cover: Bitmap?, offline: Boolean, c: CoverColors, modifier: GlanceModifier, status: WidgetStatus) {
        Row(modifier.padding(horizontal = 14.dp, vertical = 10.dp).clickable(actionStartActivity<MainActivity>()), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(cover, c, 112.dp, 20.dp)
            Spacer(GlanceModifier.width(14.dp))
            Column(GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(ImageProvider(R.drawable.ic_speaker), null, GlanceModifier.size(14.dp), colorFilter = ColorFilter.tint(ColorProvider(c.accent)))
                    Spacer(GlanceModifier.width(4.dp))
                    Text(
                        if (status == WidgetStatus.Reconnecting) context.getString(R.string.widget_reconnecting)
                        else group?.group?.displayName ?: context.getString(R.string.app_name), maxLines = 1,
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = ColorProvider(c.accent)),
                    )
                }
                Spacer(GlanceModifier.height(4.dp))
                Text(title(context, group), maxLines = 1, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp, color = ColorProvider(c.content)))
                Text(subtitle(context, group, offline), maxLines = 1, style = TextStyle(fontSize = 13.sp, color = ColorProvider(c.content.copy(alpha = 0.7f))))
                Spacer(GlanceModifier.height(8.dp))
                Controls(context, group, c, big = true)
            }
        }
    }

    /**
     * The queue from the playing track on: a widget list cannot be scrolled to
     * a position, so it starts where the music is. Tapping a track plays it.
     */
    @Composable
    private fun QueueList(context: Context, group: GroupState?, queue: List<DidlObject>?, c: CoverColors, visible: Int, rowHeight: Dp) {
        val current = group?.nowPlaying?.takeIf { it.kind == SourceKind.Queue }?.queuePosition
        val first = (current ?: 1).coerceAtLeast(1)
        val rows = queue.orEmpty().withIndex().drop(first - 1)
        if (rows.isEmpty()) {
            Box(GlanceModifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(
                    context.getString(if (group == null) R.string.offline_short else R.string.widget_queue_empty),
                    style = TextStyle(fontSize = 13.sp, color = ColorProvider(c.content.copy(alpha = 0.7f))),
                )
            }
            return
        }
        LazyColumn(GlanceModifier.fillMaxWidth().height(rowHeight * visible).padding(start = 8.dp, end = 8.dp)) {
            items(rows, itemId = { it.index.toLong() }) { (i, t) ->
                val nr = i + 1
                val playing = nr == current
                Column {
                    Row(
                        GlanceModifier.fillMaxWidth().height(rowHeight - 2.dp).cornerRadius(14.dp)
                            .background(ColorProvider(if (playing) c.subtle else Color.Transparent))
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                            .clickable(actionRunCallback<JumpAction>(actionParametersOf(JumpAction.TRACK to nr))),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "$nr", modifier = GlanceModifier.width(30.dp),
                            style = TextStyle(fontSize = 12.sp, color = ColorProvider(if (playing) c.accent else c.content.copy(alpha = 0.55f))),
                        )
                        Column(GlanceModifier.defaultWeight()) {
                            Text(
                                t.title, maxLines = 1,
                                style = TextStyle(fontSize = 14.sp, fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal, color = ColorProvider(c.content)),
                            )
                            t.creator?.let {
                                Text(it, maxLines = 1, style = TextStyle(fontSize = 12.sp, color = ColorProvider(c.content.copy(alpha = 0.6f))))
                            }
                        }
                    }
                    Spacer(GlanceModifier.height(2.dp))
                }
            }
        }
    }

    companion object {
        private val ROW = DpSize(200.dp, 72.dp)
        private val ROW_WIDE = DpSize(300.dp, 72.dp)
        private val LARGE = DpSize(250.dp, 140.dp)
        /** From here the volume row and the queue below it: the queue gets whatever height is left. */
        private val TALL = DpSize(250.dp, 180.dp)
        internal val VOLUME_STEP = ActionParameters.Key<Int>("step")
        private const val QUEUE_LIMIT = 300
        private const val VOLUME_DELTA = 2

        suspend fun refresh(context: Context) {
            if (GlanceAppWidgetManager(context).getGlanceIds(PlayerWidget::class.java).isNotEmpty()) PlayerWidget().updateAll(context)
        }

        /** One queue row: title (14 sp) and artist (12 sp) lines, 14 dp padding, 2 dp gap. */
        internal fun queueRowHeight(fontScale: Float): Dp = (16f + 36f * fontScale).dp

        /**
         * How many whole queue rows fit below the player and the volume row. The
         * player's height is the cover's or, with a large font, its text column's;
         * a few dp spare, as text heights are only estimated.
         */
        internal fun queueRows(height: Dp, fontScale: Float): Int {
            val text = 16f * fontScale + 4 + 24f * fontScale + 18f * fontScale + 8 + 44
            val player = maxOf(112f, text) + 20
            val volume = 44f
            val left = height.value - player - volume - 12
            return (left / queueRowHeight(fontScale).value).toInt().coerceAtLeast(0)
        }
    }
}

class PlayerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlayerWidget()

    override fun onUpdate(context: Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // Also after a reboot or an app update: the registration does not survive either.
        WidgetNetwork.wakeOnLocalNetwork(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetNetwork.unwatch(context)
    }
}

class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        HouseholdAccess.withSelected(context) { h, g -> h.command { if (g.isPlaying) it.pause() else it.play() } }
    }
}

/** One step for the selected room, from the volume known now (not when the widget was drawn). */
class VolumeStepAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val delta = parameters[PlayerWidget.VOLUME_STEP] ?: return
        HouseholdAccess.withSelected(context) { h, _ ->
            val g = h.state.value.selected ?: return@withSelected
            h.requestGroupVolume(g.id, (audibleVolume(g.memberVolumes, g.memberMutes) ?: g.groupVolume) + delta)
        }
    }
}

class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        HouseholdAccess.withSelected(context) { h, _ -> h.command { it.next() } }
    }
}

class JumpAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val track = parameters[TRACK] ?: return
        HouseholdAccess.withSelected(context) { h, _ -> h.playQueueTrack(track) }
    }

    companion object {
        val TRACK = ActionParameters.Key<Int>("track")
    }
}

class PreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        HouseholdAccess.withSelected(context) { h, _ -> h.command { it.previous() } }
    }
}

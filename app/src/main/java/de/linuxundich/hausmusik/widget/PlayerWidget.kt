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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
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
import androidx.glance.layout.fillMaxHeight
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
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import de.linuxundich.hausmusik.MainActivity
import de.linuxundich.hausmusik.R
import de.linuxundich.hausmusik.data.Connection
import de.linuxundich.hausmusik.data.GroupState
import de.linuxundich.hausmusik.sonos.DidlObject
import de.linuxundich.hausmusik.sonos.SourceKind
import de.linuxundich.hausmusik.system.HouseholdAccess
import de.linuxundich.hausmusik.ui.theme.CoverColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cover colors and the background gradient drawn from them. */
private data class WidgetLook(val colors: CoverColors, val background: Bitmap)

/**
 * Home screen player for the selected room, in the stage's look: background
 * and accent come from the cover. Three sizes: one row, a large player, and
 * the large player with the queue to scroll.
 */
class PlayerWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(ROW, LARGE, TALL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val household = HouseholdAccess.entry(context).household()
        provideContent {
            // The session stays alive between updates, so the state is observed
            // here instead of being read once.
            val state by household.state.collectAsState()
            // Connect briefly on every redraw: without the app open nothing polls, and
            // the widget would show what was known when the app last ran.
            LaunchedEffect(Unit) { HouseholdAccess.withSelected(context) { _, _ -> } }
            val offline = state.connection == Connection.NoLocalNetwork
            val group = state.selected.takeIf { !offline }
            val cover by produceState<Bitmap?>(null, group?.artUrl) { value = group?.artUrl?.let { loadCover(context, it) } }
            val look by produceState(lookOf(null), cover) { value = withContext(Dispatchers.Default) { lookOf(cover) } }
            val size = LocalSize.current
            // The queue only in the tall size, and only read again when it or the room changes.
            val tall = size.height >= TALL.height
            val queue by produceState<List<DidlObject>?>(null, tall, group?.id, group?.queueLength) {
                value = if (tall && group != null && group.queueLength > 0) household.browseAll("Q:0", limit = QUEUE_LIMIT).getOrNull() else null
            }
            val c = look.colors
            Box(
                GlanceModifier.fillMaxSize().cornerRadius(28.dp)
                    .background(ImageProvider(look.background), contentScale = ContentScale.FillBounds),
            ) {
                when {
                    tall -> Column(GlanceModifier.fillMaxSize()) {
                        Large(context, group, cover, offline, c, GlanceModifier.fillMaxWidth().height(LARGE.height))
                        QueueList(context, group, queue, c)
                    }
                    size.height >= LARGE.height && size.width >= LARGE.width ->
                        Large(context, group, cover, offline, c, GlanceModifier.fillMaxSize())
                    else -> Compact(context, group, cover, offline, c)
                }
            }
        }
    }

    private suspend fun loadCover(context: Context, url: String): Bitmap? {
        val request = ImageRequest.Builder(context).data(url).size(384).allowHardware(false).build()
        return (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
    }

    /** The stage's colors from the cover, and its gradient as a small bitmap to stretch. */
    private fun lookOf(cover: Bitmap?): WidgetLook {
        val colors = cover?.let { CoverColors.from(Palette.from(it).generate()) } ?: CoverColors.Default
        val bmp = Bitmap.createBitmap(1, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) bmp.setPixel(0, y, lerp(colors.backgroundTop, colors.backgroundBottom, y / 63f).toArgb())
        return WidgetLook(colors, bmp)
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
    private fun Controls(context: Context, group: GroupState?, c: CoverColors, big: Boolean) {
        val playing = group?.isPlaying == true
        val side = if (big) 44.dp else 40.dp
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBox(context, R.drawable.ic_previous, R.string.previous, actionRunCallback<PreviousAction>(), side, side, null, c.content)
            Spacer(GlanceModifier.width(6.dp))
            IconBox(
                context, if (playing) R.drawable.ic_pause else R.drawable.ic_play, if (playing) R.string.pause else R.string.play,
                actionRunCallback<PlayPauseAction>(), if (big) 72.dp else 56.dp, side, c.accent, c.onAccent,
            )
            Spacer(GlanceModifier.width(6.dp))
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

    /** One row: cover, title and room, controls. */
    @Composable
    private fun Compact(context: Context, group: GroupState?, cover: Bitmap?, offline: Boolean, c: CoverColors) {
        Row(GlanceModifier.fillMaxSize().padding(10.dp).clickable(actionStartActivity<MainActivity>()), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(cover, c, 56.dp, 16.dp)
            Spacer(GlanceModifier.width(10.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text(title(context, group), maxLines = 1, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, color = ColorProvider(c.content)))
                Text(
                    listOf(subtitle(context, group, offline), group?.group?.displayName.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · "),
                    maxLines = 1, style = TextStyle(fontSize = 12.sp, color = ColorProvider(c.content.copy(alpha = 0.7f))),
                )
            }
            Controls(context, group, c, big = false)
        }
    }

    /** Large cover, room, title over two lines, artist, controls. */
    @Composable
    private fun Large(context: Context, group: GroupState?, cover: Bitmap?, offline: Boolean, c: CoverColors, modifier: GlanceModifier) {
        Row(modifier.padding(14.dp).clickable(actionStartActivity<MainActivity>()), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(cover, c, 112.dp, 20.dp)
            Spacer(GlanceModifier.width(14.dp))
            Column(GlanceModifier.defaultWeight().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(ImageProvider(R.drawable.ic_speaker), null, GlanceModifier.size(14.dp), colorFilter = ColorFilter.tint(ColorProvider(c.accent)))
                    Spacer(GlanceModifier.width(4.dp))
                    Text(
                        group?.group?.displayName ?: context.getString(R.string.app_name), maxLines = 1,
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = ColorProvider(c.accent)),
                    )
                }
                Spacer(GlanceModifier.height(4.dp))
                Text(title(context, group), maxLines = 2, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp, color = ColorProvider(c.content)))
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
    private fun QueueList(context: Context, group: GroupState?, queue: List<DidlObject>?, c: CoverColors) {
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
        LazyColumn(GlanceModifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
            items(rows, itemId = { it.index.toLong() }) { (i, t) ->
                val nr = i + 1
                val playing = nr == current
                Column {
                    Row(
                        GlanceModifier.fillMaxWidth().cornerRadius(14.dp)
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
        private val LARGE = DpSize(250.dp, 140.dp)
        private val TALL = DpSize(250.dp, 260.dp)
        private const val QUEUE_LIMIT = 300

        suspend fun refresh(context: Context) {
            if (GlanceAppWidgetManager(context).getGlanceIds(PlayerWidget::class.java).isNotEmpty()) PlayerWidget().updateAll(context)
        }
    }
}

class PlayerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlayerWidget()
}

class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        HouseholdAccess.withSelected(context) { h, g -> h.command { if (g.isPlaying) it.pause() else it.play() } }
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

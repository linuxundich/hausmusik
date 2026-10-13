// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import de.linuxundich.hausmusik.R
import de.linuxundich.hausmusik.system.CommandActivity
import de.linuxundich.hausmusik.system.HouseholdAccess

/**
 * A small widget for one room, or one favorite in one room, chosen when it is
 * placed (RoomWidgetConfigActivity). A tap plays or pauses that room, or plays
 * the favorite there; both go through CommandActivity. In the player's look:
 * a room takes its colors from the cover it plays; wider than two cells, the
 * cover shows too.
 */
class RoomWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(NARROW, WIDE))
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val household = HouseholdAccess.entry(context).household()
        provideContent {
            val prefs = currentState<Preferences>()
            val room = prefs[ROOM]
            val favorite = prefs[FAVORITE]
            val state by household.state.collectAsState()
            val local = WidgetNetwork.hasLocalNetwork(context)
            // Connect briefly, so the room's state is current even with the app closed.
            LaunchedEffect(local, room) { if (local) HouseholdAccess.withSelected(context) { _, _ -> } }
            val group = state.groups.firstOrNull { g -> g.group.members.any { it.roomName == room } }
            val playing = group?.isPlaying == true
            val now = group?.nowPlaying
            // A favorite does not know its cover here: it keeps the app's colors.
            val art = group?.artUrl.takeIf { favorite == null }
            val cover by produceState<Bitmap?>(null, art) { value = art?.let { loadCover(context, it) } }
            val look by produceState(lookOf(null), cover) { value = withContext(Dispatchers.Default) { lookOf(cover) } }
            val c = look.colors
            val wide = LocalSize.current.width >= WIDE.width
            val intent = if (favorite != null) CommandActivity.intent(context, CommandActivity.ACTION_PLAY_FAVORITE, favorite = favorite, room = room)
            else CommandActivity.intent(context, CommandActivity.ACTION_PLAY_PAUSE, room = room)
            val (title, subtitle) = when {
                room == null -> context.getString(R.string.room_widget_unset) to context.getString(R.string.room_widget_label)
                favorite != null -> favorite to null
                now != null -> now.title to (now.artist ?: now.station)
                else -> context.getString(R.string.nothing_playing) to null
            }
            Row(
                GlanceModifier.fillMaxSize().cornerRadius(24.dp)
                    .background(ImageProvider(look.background), contentScale = ContentScale.FillBounds)
                    .padding(start = if (wide) 8.dp else 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
                    .clickable(actionStartActivity(intent)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (wide) {
                    Box(GlanceModifier.size(48.dp).cornerRadius(14.dp).background(ColorProvider(c.subtle)), contentAlignment = Alignment.Center) {
                        val bmp = cover
                        if (bmp != null) {
                            Image(ImageProvider(bmp), null, GlanceModifier.size(48.dp).cornerRadius(14.dp), contentScale = ContentScale.Crop)
                        } else {
                            Image(
                                ImageProvider(if (favorite != null) R.drawable.ic_favorite else R.drawable.ic_speaker), null,
                                GlanceModifier.size(24.dp), colorFilter = ColorFilter.tint(ColorProvider(c.accent)),
                            )
                        }
                    }
                    Spacer(GlanceModifier.width(10.dp))
                }
                Column(GlanceModifier.defaultWeight()) {
                    // Which room, marked as room or favorite, in the accent color like on the large player.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            ImageProvider(if (favorite != null) R.drawable.ic_favorite else R.drawable.ic_speaker), null,
                            GlanceModifier.size(12.dp), colorFilter = ColorFilter.tint(ColorProvider(c.accent)),
                        )
                        Spacer(GlanceModifier.width(4.dp))
                        Text(
                            room ?: context.getString(R.string.app_name), maxLines = 1,
                            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = ColorProvider(c.accent)),
                        )
                    }
                    Text(title, maxLines = 1, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, color = ColorProvider(c.content)))
                    if (subtitle != null) {
                        Text(subtitle, maxLines = 1, style = TextStyle(fontSize = 12.sp, color = ColorProvider(c.content.copy(alpha = 0.7f))))
                    }
                }
                Spacer(GlanceModifier.width(6.dp))
                Box(GlanceModifier.size(44.dp).cornerRadius(22.dp).background(ColorProvider(c.accent)), contentAlignment = Alignment.Center) {
                    Image(
                        ImageProvider(if (favorite == null && playing) R.drawable.ic_pause else R.drawable.ic_play),
                        context.getString(if (favorite == null && playing) R.string.pause else R.string.play),
                        GlanceModifier.size(24.dp), colorFilter = ColorFilter.tint(ColorProvider(c.onAccent)),
                    )
                }
            }
        }
    }

    companion object {
        val ROOM = stringPreferencesKey("room")
        val FAVORITE = stringPreferencesKey("favorite")
        private val NARROW = DpSize(110.dp, 40.dp)
        private val WIDE = DpSize(220.dp, 40.dp)

        suspend fun refresh(context: Context) = RoomWidget().updateAll(context)
    }
}

class RoomWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RoomWidget()
}

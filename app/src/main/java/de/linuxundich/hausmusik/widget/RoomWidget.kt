// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.widget

import android.content.Context
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
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
import de.linuxundich.hausmusik.ui.theme.CoverColors

/**
 * A small widget for one room, or one favorite in one room, chosen when it is
 * placed (RoomWidgetConfigActivity). A tap plays or pauses that room, or plays
 * the favorite there; both go through CommandActivity.
 */
class RoomWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single
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
            val c = CoverColors.Default
            val intent = if (favorite != null) CommandActivity.intent(context, CommandActivity.ACTION_PLAY_FAVORITE, favorite = favorite, room = room)
            else CommandActivity.intent(context, CommandActivity.ACTION_PLAY_PAUSE, room = room)
            Row(
                GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(ColorProvider(c.backgroundTop)).padding(horizontal = 14.dp, vertical = 8.dp)
                    .clickable(actionStartActivity(intent)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(GlanceModifier.defaultWeight()) {
                    Text(
                        favorite ?: room ?: context.getString(R.string.room_widget_unset), maxLines = 1,
                        style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, color = ColorProvider(c.content)),
                    )
                    val line = when {
                        room == null -> context.getString(R.string.room_widget_label)
                        favorite != null -> room
                        group?.nowPlaying != null -> group.nowPlaying.title
                        else -> context.getString(R.string.rooms_idle)
                    }
                    Text(line, maxLines = 1, style = TextStyle(fontSize = 12.sp, color = ColorProvider(c.content.copy(alpha = 0.7f))))
                }
                Spacer(GlanceModifier.width(8.dp))
                Box(GlanceModifier.size(40.dp).cornerRadius(20.dp).background(ColorProvider(c.accent)), contentAlignment = Alignment.Center) {
                    Image(
                        ImageProvider(if (favorite == null && playing) R.drawable.ic_pause else R.drawable.ic_play),
                        context.getString(if (favorite == null && playing) R.string.pause else R.string.play),
                        GlanceModifier.size(22.dp), colorFilter = ColorFilter.tint(ColorProvider(c.onAccent)),
                    )
                }
            }
        }
    }

    companion object {
        val ROOM = stringPreferencesKey("room")
        val FAVORITE = stringPreferencesKey("favorite")

        suspend fun refresh(context: Context) = RoomWidget().updateAll(context)
    }
}

class RoomWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RoomWidget()
}

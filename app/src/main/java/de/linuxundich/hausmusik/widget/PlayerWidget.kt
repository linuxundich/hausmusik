package de.linuxundich.hausmusik.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
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
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import de.linuxundich.hausmusik.MainActivity
import de.linuxundich.hausmusik.R
import de.linuxundich.hausmusik.data.Connection
import de.linuxundich.hausmusik.data.GroupState
import de.linuxundich.hausmusik.system.HouseholdAccess

/** Home screen player for the selected room. */
class PlayerWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val household = HouseholdAccess.entry(context).household()
        provideContent {
            // The session stays alive between updates, so the state is observed
            // here instead of being read once.
            val state by household.state.collectAsState()
            // Connect briefly when nothing is known yet (app process just started).
            LaunchedEffect(Unit) { if (household.state.value.selected == null) HouseholdAccess.withSelected(context) { _, _ -> } }
            val offline = state.connection == Connection.NoLocalNetwork
            val group = state.selected.takeIf { !offline }
            val cover by produceState<Bitmap?>(null, group?.artUrl) { value = group?.artUrl?.let { loadCover(context, it) } }
            GlanceTheme { Content(context, group, cover, offline) }
        }
    }

    private suspend fun loadCover(context: Context, url: String): Bitmap? {
        val request = ImageRequest.Builder(context).data(url).size(256).allowHardware(false).build()
        return (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
    }

    @Composable
    private fun Content(context: Context, group: GroupState?, cover: Bitmap?, offline: Boolean) {
        val now = group?.nowPlaying
        Row(
            GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(24.dp).padding(10.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(GlanceModifier.size(64.dp).cornerRadius(14.dp).background(GlanceTheme.colors.secondaryContainer), contentAlignment = Alignment.Center) {
                if (cover != null) {
                    Image(ImageProvider(cover), contentDescription = null, modifier = GlanceModifier.size(64.dp).cornerRadius(14.dp))
                } else {
                    Image(ImageProvider(R.drawable.ic_speaker), contentDescription = null, colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer))
                }
            }
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text(
                    now?.title ?: context.getString(if (group == null) R.string.app_name else R.string.nothing_playing),
                    maxLines = 1, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, color = GlanceTheme.colors.onSurface),
                )
                Text(
                    if (offline) context.getString(R.string.offline_short)
                    else listOfNotNull(now?.artist ?: now?.station, group?.group?.displayName).joinToString(" · "),
                    maxLines = 1, style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant),
                )
                Spacer(GlanceModifier.height(6.dp))
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CircleIconButton(
                        ImageProvider(R.drawable.ic_previous), context.getString(R.string.previous),
                        onClick = actionRunCallback<PreviousAction>(), backgroundColor = null, modifier = GlanceModifier.size(40.dp),
                    )
                    Spacer(GlanceModifier.width(4.dp))
                    CircleIconButton(
                        ImageProvider(if (group?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play),
                        context.getString(if (group?.isPlaying == true) R.string.pause else R.string.play),
                        onClick = actionRunCallback<PlayPauseAction>(), modifier = GlanceModifier.size(44.dp),
                    )
                    Spacer(GlanceModifier.width(4.dp))
                    CircleIconButton(
                        ImageProvider(R.drawable.ic_next), context.getString(R.string.next),
                        onClick = actionRunCallback<NextAction>(), backgroundColor = null, modifier = GlanceModifier.size(40.dp),
                    )
                }
            }
        }
    }

    companion object {
        private val SMALL = DpSize(180.dp, 80.dp)
        private val WIDE = DpSize(260.dp, 100.dp)

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

class PreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        HouseholdAccess.withSelected(context) { h, _ -> h.command { it.previous() } }
    }
}

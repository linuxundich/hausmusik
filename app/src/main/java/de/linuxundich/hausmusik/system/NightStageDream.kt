// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.system

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.dreams.DreamService
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dagger.hilt.android.AndroidEntryPoint
import de.linuxundich.hausmusik.R
import de.linuxundich.hausmusik.data.GroupState
import de.linuxundich.hausmusik.data.HouseholdRepository
import de.linuxundich.hausmusik.data.Lyrics
import de.linuxundich.hausmusik.data.LyricsRepository
import de.linuxundich.hausmusik.data.Settings
import de.linuxundich.hausmusik.data.lyricsTrackKey
import de.linuxundich.hausmusik.ui.common.Cover
import de.linuxundich.hausmusik.ui.stage.rememberPosition
import de.linuxundich.hausmusik.ui.theme.AppIcons
import de.linuxundich.hausmusik.ui.theme.rememberCoverColors
import javax.inject.Inject
import kotlinx.coroutines.delay

/**
 * The night stage: Android's screensaver while charging, chosen in the
 * system's screensaver settings. Shows what plays in the selected room
 * (cover, title, room, the line being sung if lyrics are on) and the time,
 * dimmed and slowly wandering so nothing burns in. Only listed while the
 * setting is on ([setAvailable]).
 */
@AndroidEntryPoint
class NightStageDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    @Inject lateinit var household: HouseholdRepository
    @Inject lateinit var lyrics: LyricsRepository
    @Inject lateinit var settings: Settings

    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Not interactive: a tap ends it, as with any screensaver (the system takes the touch anyway).
        isInteractive = false
        isFullscreen = true
        isScreenBright = false
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@NightStageDream)
            setViewTreeSavedStateRegistryOwner(this@NightStageDream)
            setContent { NightStage(household, lyrics, settings) }
        }
        window.decorView.setViewTreeLifecycleOwner(this)
        window.decorView.setViewTreeSavedStateRegistryOwner(this)
        setContentView(view)
        registry.currentState = Lifecycle.State.RESUMED
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        household.acquire(OWNER)
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        household.release(OWNER)
    }

    override fun onDestroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    companion object {
        private const val OWNER = "night_stage"

        /** Lists the screensaver in the system settings or takes it out again. */
        fun setAvailable(context: Context, on: Boolean) {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, NightStageDream::class.java),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}

@Composable
private fun NightStage(household: HouseholdRepository, lyricsRepo: LyricsRepository, settings: Settings) {
    val state by household.state.collectAsState()
    val group = state.selected
    val now = group?.nowPlaying?.takeIf { group.isPlaying }
    val context = LocalContext.current
    val time by produceState(clock(context)) {
        while (true) {
            delay(5_000)
            value = clock(context)
        }
    }
    val colors = rememberCoverColors(group?.artUrl)
    // Wanders a little over a minute: no pixel stays lit in the same place.
    val t = rememberInfiniteTransition(label = "wander")
    val wx by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(61_000, easing = LinearEasing), RepeatMode.Reverse), label = "x")
    val wy by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(43_000, easing = LinearEasing), RepeatMode.Reverse), label = "y")
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val wide = maxWidth > maxHeight
        val coverSize = if (wide) min(maxHeight * 0.62f, maxWidth * 0.36f) else maxWidth * 0.6f
        val content = Modifier.offset(x = (wx * 24).dp, y = (wy * 18).dp).alpha(0.85f)
        val texts = @Composable {
            Text(time, color = Color.White.copy(alpha = 0.85f), fontSize = 64.sp, fontWeight = FontWeight.Light)
            if (now != null) {
                Text(now.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                Text(
                    listOfNotNull(now.artist ?: now.station, group.group.displayName).joinToString(" · "),
                    color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp, maxLines = 1,
                )
                SungLine(group, lyricsRepo, settings, colors.accent)
            } else {
                Text(stringResource(R.string.night_stage_idle), color = Color.White.copy(alpha = 0.6f), fontSize = 18.sp)
            }
        }
        if (now == null) {
            Column(content, horizontalAlignment = Alignment.CenterHorizontally) { texts() }
        } else if (wide) {
            Row(content.padding(32.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(group.artUrl, Modifier.size(coverSize), corner = 22.dp, placeholderColor = colors.subtle, iconColor = colors.content, icon = AppIcons.Speaker)
                Spacer(Modifier.width(32.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { texts() }
            }
        } else {
            Column(content.padding(32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Cover(group.artUrl, Modifier.size(coverSize), corner = 22.dp, placeholderColor = colors.subtle, iconColor = colors.content, icon = AppIcons.Speaker)
                Spacer(Modifier.height(20.dp))
                texts()
            }
        }
    }
}

/** The line being sung, if lyrics are switched on and synced; with the user's shift for this track. */
@Composable
private fun SungLine(group: GroupState, lyricsRepo: LyricsRepository, settings: Settings, accent: Color) {
    val now = group.nowPlaying ?: return
    val enabled by settings.lyricsEnabled.collectAsState(false)
    val offsets by settings.lyricsOffsets.collectAsState(emptyMap())
    val lyrics by produceState<Lyrics?>(null, enabled, now.title, now.artist, now.album) {
        value = if (enabled) lyricsRepo.find(now.title, now.artist, now.album, now.durationMs).getOrNull() else null
    }
    val lines = lyrics?.synced.orEmpty()
    if (lines.isEmpty()) return
    val position = rememberPosition(group)
    val offset = offsets[lyricsTrackKey(now)] ?: 0
    val line = lines.lastOrNull { it.timeMs + offset <= position }?.text?.takeIf { it.isNotBlank() } ?: return
    Box(Modifier.padding(top = 14.dp)) {
        Text(line, color = accent, fontSize = 20.sp, lineHeight = 27.sp, maxLines = 3)
    }
}

private fun clock(context: Context): String = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date())

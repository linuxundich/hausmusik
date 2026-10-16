// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import de.linuxundich.hausmusik.ui.theme.CoverColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

/**
 * What every volume control shares: the finger alone moves the control, the
 * speaker follows behind it. While dragging, neither events nor reads move it;
 * [onChange] gets the latest value at most every 100 ms, so the household state
 * does not recompose the screen on every frame. After the finger lets go the
 * control stays put until the speaker reports that value (or 2.5 s pass), so it
 * does not jump back to an older reading.
 */
private class VolumeDrag {
    var drag by mutableStateOf<Int?>(null)
    var hold by mutableStateOf<Int?>(null)
    fun current(value: Int) = drag ?: hold ?: value
}

@Composable
private fun rememberVolumeDrag(value: Int, onChange: (Int) -> Unit): Pair<VolumeDrag, (Int) -> Unit> {
    val d = remember { VolumeDrag() }
    val send by rememberUpdatedState(onChange)
    LaunchedEffect(Unit) {
        snapshotFlow { d.drag }.filterNotNull().distinctUntilChanged().conflate().collect {
            send(it)
            delay(100)
        }
    }
    LaunchedEffect(d.hold, value) {
        val h = d.hold ?: return@LaunchedEffect
        if (h != value) delay(2_500)
        d.hold = null
    }
    return d to { v: Int -> send(v) }
}

private fun VolumeDrag.finish(send: (Int) -> Unit) {
    drag?.let { hold = it; send(it) }
    drag = null
}

/**
 * Every volume slider, built on [VolumeDrag], in the style of Sonos' own app: a
 * thin line and a big round knob that is easy to find and hold. [trailing]
 * shows the current value (the finger's while dragging).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VolumeSlider(
    value: Int,
    colors: CoverColors,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    compact: Boolean = false,
    trailing: @Composable (Int) -> Unit = {},
    onChange: (Int) -> Unit,
) {
    val (d, send) = rememberVolumeDrag(value, onChange)
    val current = d.current(value)
    val state = remember { SliderState(current.toFloat(), 0, 0f..100f) }
    // Follow the speaker whenever the finger is not on the slider.
    LaunchedEffect(current) { if (d.drag == null) state.value = current.toFloat() }
    val sliderColors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent, inactiveTrackColor = colors.subtle)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Slider(
            state = state,
            onValueChange = { state.value = it; d.drag = Math.round(it) },
            onValueChangeFinished = { d.finish(send) },
            colors = sliderColors,
            thumb = {
                Box(Modifier.size(if (compact) 20.dp else 26.dp).clip(CircleShape).background(colors.accent))
            },
            track = {
                SliderDefaults.Track(
                    sliderState = it, colors = sliderColors,
                    modifier = Modifier.height(4.dp),
                    drawStopIndicator = null,
                    thumbTrackGapSize = 0.dp,
                    trackInsideCornerSize = 2.dp,
                )
            },
            modifier = Modifier.weight(1f).alpha(if (dimmed) 0.4f else 1f),
        )
        trailing(current)
    }
}

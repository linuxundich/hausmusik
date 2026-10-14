// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

/**
 * Every place that can show messages registers here; only the topmost one
 * (the last that appeared, e.g. an open sheet above the stage) draws them.
 */
private object MessageHosts {
    val stack = mutableStateListOf<Any>()
}

/**
 * Messages as a pill at the top of the screen, above everything (design C
 * of the mockups). A bottom sheet lives
 * in its own window, so a message drawn by the screen below would sit
 * behind it: each sheet places a host too, and its popup belongs to the
 * sheet's window. All hosts share the app's one [SnackbarHostState].
 */
@Composable
fun MessageHost(state: SnackbarHostState, onDark: Boolean = true) {
    val token = remember { Any() }
    DisposableEffect(token) {
        MessageHosts.stack += token
        onDispose { MessageHosts.stack -= token }
    }
    if (MessageHosts.stack.lastOrNull() !== token) return
    val data = state.currentSnackbarData
    // The standard SnackbarHost ends a message after its duration; this host has to do that itself,
    // or the first message stays and every later one waits behind it.
    LaunchedEffect(data) {
        val d = data ?: return@LaunchedEffect
        val ms = when (d.visuals.duration) {
            SnackbarDuration.Short -> 4_000L
            SnackbarDuration.Long -> 10_000L
            SnackbarDuration.Indefinite -> return@LaunchedEffect
        }
        delay(ms)
        d.dismiss()
    }
    // Keep the last message while the pill slides out.
    var shown by remember { mutableStateOf<SnackbarData?>(null) }
    if (data != null) shown = data
    val density = LocalDensity.current
    val topPx = with(density) { (WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp).roundToPx() }
    // Placed against the window, not the composable that hosts it: inside a sheet
    // that would be the sheet's top edge, halfway down the screen.
    val position = remember(topPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
                IntOffset((windowSize.width - popupContentSize.width) / 2, topPx)
        }
    }
    Popup(popupPositionProvider = position, properties = PopupProperties(focusable = false)) {
        Box(Modifier.padding(horizontal = 16.dp)) {
            AnimatedVisibility(
                visible = data != null,
                enter = slideInVertically { -it * 2 } + fadeIn(),
                exit = slideOutVertically { -it * 2 } + fadeOut(),
            ) {
                shown?.let { Pill(it, onDark) }
            }
        }
    }
}

@Composable
private fun Pill(data: SnackbarData, onDark: Boolean) {
    // Light on the dark stage and sheets, dark on the light library: always the contrast to what lies below.
    val container = if (onDark) PILL_LIGHT else MaterialTheme.colorScheme.inverseSurface
    val content = if (onDark) PILL_LIGHT_TEXT else MaterialTheme.colorScheme.inverseOnSurface
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = container,
        contentColor = content,
        shadowElevation = 8.dp,
        modifier = Modifier.widthIn(max = 480.dp).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            Modifier.heightIn(min = 52.dp).padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                data.visuals.message, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(end = 12.dp),
            )
            data.visuals.actionLabel?.let { label ->
                Button(
                    onClick = { data.performAction() },
                    colors = ButtonDefaults.buttonColors(containerColor = content, contentColor = container),
                ) { Text(label, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

private val PILL_LIGHT = androidx.compose.ui.graphics.Color(0xFFEFE4DE)
private val PILL_LIGHT_TEXT = androidx.compose.ui.graphics.Color(0xFF2A1D18)

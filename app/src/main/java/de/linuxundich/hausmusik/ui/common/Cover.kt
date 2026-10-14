// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import de.linuxundich.hausmusik.ui.theme.AppIcons

/** A cover with a quiet placeholder while it loads or when there is none. */
@Composable
fun Cover(
    url: String?,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
    placeholderColor: Color,
    iconColor: Color,
    icon: ImageVector = AppIcons.Album,
    keepPrevious: Boolean = false,
    /** Shown instead of the icon while the picture loads or when it fails (a station's named placeholder). */
    fallback: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(corner)
    // With keepPrevious, the last cover that loaded stays until the next one is
    // there: within an album nothing flickers, and a slow speaker shows no gap.
    var shown by remember { mutableStateOf<String?>(null) }
    Box(modifier.clip(shape).background(placeholderColor), contentAlignment = Alignment.Center) {
        val placeholder = @Composable {
            val previous = shown.takeIf { keepPrevious && it != url && fallback == null }
            if (fallback != null) {
                fallback()
            } else if (previous != null) {
                AsyncImage(previous, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(32.dp))
                }
            }
        }
        if (url == null) placeholder() else SubcomposeAsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = { placeholder() },
            error = { placeholder() },
            onSuccess = { shown = url },
        )
    }
}

fun formatTime(ms: Long?): String {
    if (ms == null) return "–:––"
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * A cover that knows radio: a station shows its named placeholder until its
 * logo is there (and if none is found or it does not load), never the
 * previous album or a generic radio.
 */
@Composable
fun NowPlayingCover(
    url: String?,
    station: String?,
    modifier: Modifier,
    corner: Dp,
    placeholderColor: Color,
    iconColor: Color,
    icon: ImageVector,
    small: Boolean = false,
    keepPrevious: Boolean = false,
) {
    if (station == null) {
        Cover(url, modifier, corner, placeholderColor, iconColor, icon, keepPrevious)
        return
    }
    // Logos come in every shape (wide wordmarks, transparent ones): whole and centered on
    // the station's own colors, not cropped to the square like an album cover.
    var loaded by remember(url) { mutableStateOf(false) }
    Box(modifier) {
        de.linuxundich.hausmusik.ui.stage.StationPlaceholder(station, Modifier.fillMaxSize(), corner, small, nameShown = !loaded)
        if (url != null) AsyncImage(
            model = url, contentDescription = null, contentScale = ContentScale.Fit,
            onSuccess = { loaded = true }, onError = { loaded = false },
            modifier = Modifier.fillMaxSize().padding(if (small) 4.dp else 28.dp),
        )
    }
}

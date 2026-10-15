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
import coil3.toBitmap
import coil3.request.allowHardware
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
    // How the logo is laid out depends on the logo: see [LogoLook].
    var look by remember(url) { mutableStateOf<LogoLook?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val request = remember(url) {
        url?.let { coil3.request.ImageRequest.Builder(context).data(it).allowHardware(false).build() }
    }
    Box(modifier.clip(RoundedCornerShape(corner))) {
        val l = look
        when {
            l == null -> de.linuxundich.hausmusik.ui.stage.StationPlaceholder(station, Modifier.fillMaxSize(), corner, small)
            !l.fill -> Box(Modifier.fillMaxSize().background(l.background))
        }
        if (request != null) AsyncImage(
            model = request, contentDescription = null,
            contentScale = if (l?.fill == true) ContentScale.Crop else ContentScale.Fit,
            onSuccess = { st -> look = LogoLook.of(st.result.image.toBitmap()) },
            onError = { look = null },
            modifier = Modifier.fillMaxSize().padding(if (l?.fill == true) 0.dp else if (small) 4.dp else 28.dp),
        )
    }
}

/**
 * A station logo's layout: a square, opaque logo fills the card like a cover
 * ([fill]); a transparent or wide one sits whole on a calm [background] that
 * contrasts with it (dark for a light logo, light for a dark one), not on a
 * color of its own that would clash.
 */
internal data class LogoLook(val fill: Boolean, val background: Color) {
    companion object {
        fun of(bitmap: android.graphics.Bitmap): LogoLook {
            val w = bitmap.width
            val h = bitmap.height
            val square = w > 0 && h > 0 && w.toFloat() / h in 0.9f..1.1f
            val corners = listOf(0 to 0, w - 1 to 0, 0 to h - 1, w - 1 to h - 1)
            val opaque = corners.all { (x, y) -> android.graphics.Color.alpha(bitmap.getPixel(x, y)) > 240 }
            if (square && opaque) return LogoLook(true, Color.Transparent)
            // Brightness of the logo's own pixels, on a coarse grid.
            var sum = 0.0
            var n = 0
            val step = maxOf(1, minOf(w, h) / 24)
            for (y in 0 until h step step) for (x in 0 until w step step) {
                val c = bitmap.getPixel(x, y)
                if (android.graphics.Color.alpha(c) < 128) continue
                sum += (0.299 * android.graphics.Color.red(c) + 0.587 * android.graphics.Color.green(c) + 0.114 * android.graphics.Color.blue(c)) / 255
                n++
            }
            val light = n > 0 && sum / n > 0.55
            return LogoLook(false, if (light) Color(0xFF26272B) else Color(0xFFF1F0EC))
        }
    }
}

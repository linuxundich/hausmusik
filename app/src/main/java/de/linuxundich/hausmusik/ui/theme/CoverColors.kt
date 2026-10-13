package de.linuxundich.hausmusik.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Stage palette: always dark, tinted by the cover. */
@Immutable
data class CoverColors(
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val accent: Color,
    val onAccent: Color,
    val content: Color,
    val subtle: Color,
) {
    companion object {
        val Default = CoverColors(
            backgroundTop = Color(0xFF2A3D57),
            backgroundBottom = Color(0xFF0E1420),
            accent = Color(0xFFFFB547),
            onAccent = Color(0xFF3A2400),
            content = Color(0xFFF3F1FA),
            subtle = Color(0x33F3F1FA),
        )

        fun from(palette: Palette): CoverColors {
            fun c(rgb: Int?) = rgb?.let { Color(it) }
            val base = c(palette.darkMutedSwatch?.rgb) ?: c(palette.dominantSwatch?.rgb) ?: return Default
            val accentRaw = c(palette.lightVibrantSwatch?.rgb) ?: c(palette.vibrantSwatch?.rgb)
                ?: c(palette.lightMutedSwatch?.rgb) ?: Default.accent
            val accent = lerp(accentRaw, Color.White, 0.25f)
            return CoverColors(
                backgroundTop = lerp(base, Color.Black, 0.15f),
                backgroundBottom = lerp(base, Color.Black, 0.82f),
                accent = accent,
                onAccent = lerp(base, Color.Black, 0.6f),
                content = lerp(accent, Color.White, 0.85f),
                subtle = lerp(accent, Color.White, 0.6f).copy(alpha = 0.2f),
            )
        }
    }
}

/** Extracts colors from the cover at [url] and animates between covers. */
@Composable
fun rememberCoverColors(url: String?): CoverColors {
    val context = LocalContext.current
    var target by remember { mutableStateOf(CoverColors.Default) }
    LaunchedEffect(url) {
        if (url == null) {
            target = CoverColors.Default
            return@LaunchedEffect
        }
        val request = ImageRequest.Builder(context).data(url).size(96).allowHardware(false).build()
        val result = context.imageLoader.execute(request)
        if (result is SuccessResult) {
            target = withContext(Dispatchers.Default) { CoverColors.from(Palette.from(result.image.toBitmap()).generate()) }
        }
    }
    val spec = tween<Color>(700)
    val top by animateColorAsState(target.backgroundTop, spec, label = "top")
    val bottom by animateColorAsState(target.backgroundBottom, spec, label = "bottom")
    val accent by animateColorAsState(target.accent, spec, label = "accent")
    val onAccent by animateColorAsState(target.onAccent, spec, label = "onAccent")
    val content by animateColorAsState(target.content, spec, label = "content")
    val subtle by animateColorAsState(target.subtle, spec, label = "subtle")
    return CoverColors(top, bottom, accent, onAccent, content, subtle)
}

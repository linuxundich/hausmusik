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
) {
    val shape = RoundedCornerShape(corner)
    Box(modifier.clip(shape).background(placeholderColor), contentAlignment = Alignment.Center) {
        val placeholder = @Composable {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(32.dp))
            }
        }
        if (url == null) placeholder() else SubcomposeAsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = { placeholder() },
            error = { placeholder() },
        )
    }
}

fun formatTime(ms: Long?): String {
    if (ms == null) return "–:––"
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import de.linuxundich.hausmusik.ui.theme.CoverColors

/** Cover colors and the background gradient drawn from them. */
internal data class WidgetLook(val colors: CoverColors, val background: Bitmap)

internal suspend fun loadCover(context: Context, url: String): Bitmap? {
    val request = ImageRequest.Builder(context).data(url).size(384).allowHardware(false).build()
    return (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
}

/** The stage's colors from the cover, and its gradient as a small bitmap to stretch. */
internal fun lookOf(cover: Bitmap?): WidgetLook {
    val colors = cover?.let { CoverColors.from(Palette.from(it).generate()) } ?: CoverColors.Default
    val bmp = Bitmap.createBitmap(1, 64, Bitmap.Config.ARGB_8888)
    for (y in 0 until 64) bmp.setPixel(0, y, lerp(colors.backgroundTop, colors.backgroundBottom, y / 63f).toArgb())
    return WidgetLook(colors, bmp)
}

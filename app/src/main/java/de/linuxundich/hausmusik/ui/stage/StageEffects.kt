// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui.stage

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.linuxundich.hausmusik.R
import de.linuxundich.hausmusik.ui.theme.CoverColors
import kotlinx.coroutines.delay
import kotlin.math.pow

/** The system's "Remove animations" (animator duration scale 0): then everything stays still. */
fun animationsOff(context: Context): Boolean =
    android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** Grey "house lights" the stage fades to in a pause. */
val HouseTop = Color(0xFF4A4446)
val HouseBottom = Color(0xFF2C2829)

/** A second light color: the accent turned on the color wheel, so two lights never look the same. */
private fun partner(accent: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(accent.toArgb(), hsv)
    hsv[0] = (hsv[0] + 150f) % 360f
    hsv[1] = hsv[1].coerceAtLeast(0.45f)
    hsv[2] = hsv[2].coerceAtLeast(0.75f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * Stage lights behind everything: two soft lights drifting and two beams
 * swinging from above, in the cover's colors. [on] fades them (the house
 * lights in a pause); [pulse] (0..1) brightens them on a tapped beat. The
 * infinite animation only runs while some light is visible.
 */
@Composable
fun StageLights(colors: CoverColors, on: Float, pulse: Float, modifier: Modifier = Modifier) {
    if (on <= 0.01f) return
    val a = colors.accent
    val b = remember(a) { partner(a) }
    val t = rememberInfiniteTransition(label = "lights")
    val drift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(10_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "drift")
    val swing by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(7_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "swing")
    val strength = on * (0.8f + 0.35f * pulse)
    // Blurred, so the beams have no hard edges (Android 12 and later; before, they stay sharp).
    Canvas(modifier.fillMaxSize().blur(28.dp, BlurredEdgeTreatment.Unbounded)) {
        val w = size.width
        val h = size.height
        fun glow(center: Offset, radius: Float, color: Color, alpha: Float) = drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = alpha * strength), Color.Transparent), center, radius),
            radius, center,
        )
        glow(Offset(w * (0.05f + 0.35f * drift), h * (0.22f + 0.08f * drift)), w * 0.6f, a, 0.6f)
        glow(Offset(w * (0.95f - 0.3f * drift), h * (0.42f - 0.06f * drift)), w * 0.65f, b, 0.5f)
        // Beams from the top edge, swinging against each other.
        fun beam(x: Float, angle: Float, color: Color) = rotate(angle, Offset(x, 0f)) {
            val top = w * 0.04f
            val bottom = w * 0.22f
            val path = Path().apply {
                moveTo(x - top, 0f); lineTo(x + top, 0f); lineTo(x + bottom, h * 0.75f); lineTo(x - bottom, h * 0.75f); close()
            }
            drawPath(path, Brush.verticalGradient(listOf(color.copy(alpha = 0.3f * strength), Color.Transparent), 0f, h * 0.75f))
        }
        beam(w * 0.25f, 16f * swing, a)
        beam(w * 0.75f, -14f * swing, b)
    }
}

/** How strongly the stage is lit: 1 while music plays, 0 in a pause (house lights), animated. */
@Composable
fun rememberStageLit(playing: Boolean): Float {
    val lit by animateFloatAsState(if (playing) 1f else 0f, tween(1_200), label = "lit")
    return lit
}

/**
 * Tapping along: four or more taps in time on the cover set a beat; the
 * light and the cover pulse with it until the track changes. The app cannot
 * hear the music (it plays on the speakers), so the beat is the listener's.
 */
class TapTempo {
    private var taps = listOf<Long>()
    var beatMs by mutableLongStateOf(0L)
        private set
    var tapCount by mutableStateOf(0)
        private set

    fun tap(now: Long = System.currentTimeMillis()) {
        taps = (taps.filter { now - it < 2_500 } + now).takeLast(8)
        tapCount = taps.size
        if (taps.size >= 4) {
            val gaps = taps.zipWithNext { x, y -> y - x }
            val avg = gaps.average()
            if (avg in 250.0..1_500.0) beatMs = avg.toLong()
        }
    }
}

/** 1 on each beat, falling off quickly until the next one; 0 without a beat or in a pause. */
@Composable
fun rememberPulse(beatMs: Long, playing: Boolean): Float {
    val pulse by produceState(0f, beatMs, playing) {
        if (beatMs <= 0 || !playing) { value = 0f; return@produceState }
        val start = withFrameMillis { it }
        while (true) {
            withFrameMillis { now ->
                val phase = ((now - start) % beatMs).toFloat() / beatMs
                value = (1f - phase).pow(4)
            }
        }
    }
    return pulse
}

/** Catches taps on the cover for [TapTempo], with a soft click each. */
fun Modifier.tapAlong(tempo: TapTempo?, onTap: () -> Unit): Modifier =
    if (tempo == null) this else pointerInput(tempo) { detectTapGestures { tempo.tap(); onTap() } }

/** A short note over the cover while tapping: how many taps yet, then the tempo. */
@Composable
fun TapNote(tempo: TapTempo, lastTap: Long, colors: CoverColors, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(lastTap) {
        if (lastTap == 0L) return@LaunchedEffect
        visible = true
        delay(2_500)
        visible = false
    }
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) {
        val bpm = if (tempo.beatMs > 0) (60_000 / tempo.beatMs).toInt() else 0
        val text = if (bpm > 0) stringResource(R.string.fx_tap_bpm, bpm)
        else pluralStringResource(R.plurals.fx_tap_more, (4 - tempo.tapCount).coerceAtLeast(1), (4 - tempo.tapCount).coerceAtLeast(1))
        Text(
            text, color = colors.content, fontSize = 13.sp,
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * The cover with a record beside it: while music plays the record slides out
 * to the right and turns (33⅓, a little faster to be seen); in a pause it
 * slows down and slides back behind the cover. Its label is the cover itself.
 */
@Composable
fun CoverOnTurntable(
    playing: Boolean,
    colors: CoverColors,
    modifier: Modifier,
    label: @Composable (Modifier) -> Unit,
    cover: @Composable (Modifier) -> Unit,
) {
    val out by animateFloatAsState(if (playing) 1f else 0f, tween(900, easing = FastOutSlowInEasing), label = "out")
    val angle = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        if (playing) {
            while (true) angle.animateTo(angle.value + 360f, tween(1_800, easing = LinearEasing))
        } else {
            // Runs down instead of stopping dead.
            angle.animateTo(angle.value + 140f, tween(1_500, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
        }
    }
    BoxWithConstraints(modifier) {
        val side = maxWidth * 0.8f
        // Out: cover left, record right; in: both centered, the record hidden behind the cover.
        val coverBias = -out
        val discBias = out
        Box(Modifier.size(side * 0.96f).align(BiasAlignment(discBias, 0f)).graphicsLayer { rotationZ = angle.value }) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2
                drawCircle(Color(0xFF111111), r)
                var g = r * 0.98f
                while (g > r * 0.4f) {
                    drawCircle(Color.White.copy(alpha = 0.05f), g, style = Stroke(1f))
                    g -= r * 0.035f
                }
                // A highlight that turns with it, so the turning shows.
                drawArc(Color.White.copy(alpha = 0.07f), -40f, 30f, true)
            }
            label(Modifier.size(side * 0.34f).align(Alignment.Center).clip(CircleShape))
            Box(Modifier.size(8.dp).align(Alignment.Center).clip(CircleShape).background(Color(0xFF111111)))
        }
        cover(Modifier.size(side).align(BiasAlignment(coverBias, 0f)))
    }
}

/** Whether the turntable fits what plays: albums and own music, not radio, TV or line-in. */
fun turntableFits(kind: de.linuxundich.hausmusik.sonos.SourceKind?): Boolean =
    kind == de.linuxundich.hausmusik.sonos.SourceKind.Queue

@Composable
fun rememberAnimationsOff(): Boolean {
    val context = LocalContext.current
    return remember { animationsOff(context) }
}

@Composable
fun rememberTapHaptic(): () -> Unit {
    val haptics = LocalHapticFeedback.current
    return { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
}

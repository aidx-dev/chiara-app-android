package com.chiarasystems.chiara.designsystem

import android.graphics.BlurMaskFilter
import android.graphics.Matrix
import android.graphics.SweepGradient
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.LayoutDirection

/** How alive the assistant's outline looks. It always moves (unless animations are off); the state only changes speed and brightness. */
enum class GlowState { Idle, Focused, Active }

private val GLOW_COLORS = intArrayOf(
    Color(0.74f, 0.51f, 0.95f).toArgb(), Color(0.96f, 0.73f, 0.92f).toArgb(), Color(0.55f, 0.62f, 1.00f).toArgb(),
    Color(0.67f, 0.43f, 0.93f).toArgb(), Color(1.00f, 0.40f, 0.47f).toArgb(), Color(1.00f, 0.73f, 0.44f).toArgb(),
    Color(0.74f, 0.51f, 0.95f).toArgb(),
)

/**
 * Port of iOS `GlowBorder`: a soft pastel sweep (violet, pink, periwinkle, coral, amber) that turns around the outline — a
 * crisp line over two blurred halos. [subtle] is for small controls; [visible] fades it in and out.
 */
@Composable
fun Modifier.glowBorder(shape: Shape, state: GlowState, subtle: Boolean = false, visible: Boolean = true): Modifier {
    val context = LocalContext.current
    // "Remove animations" in the system settings stops the motion (iOS: Reduce Motion).
    val motion = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
    val speed = when (state) { GlowState.Idle -> 28f; GlowState.Focused -> 55f; GlowState.Active -> 150f } // degrees per second
    val level = when (state) { GlowState.Idle -> 0.28f; GlowState.Focused -> 0.55f; GlowState.Active -> 0.95f } * if (subtle) 0.45f else 1f
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(350), label = "glow-visible")
    val halo by animateFloatAsState(level, tween(400), label = "glow-level")
    val currentSpeed by rememberUpdatedState(speed)
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(motion, visible) {
        if (!motion || !visible) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            angle = (angle + currentSpeed * (now - last) / 1_000_000_000f) % 360f
            last = now
        }
    }
    val line = if (subtle) 1.25f else 1.75f
    val matrix = remember { Matrix() }
    return this.drawWithContent {
        drawContent()
        if (alpha <= 0.01f) return@drawWithContent
        val outline = shape.createOutline(size, LayoutDirection.Ltr, this)
        val path = androidx.compose.ui.graphics.Path().apply {
            when (outline) {
                is androidx.compose.ui.graphics.Outline.Rectangle -> addRect(outline.rect)
                is androidx.compose.ui.graphics.Outline.Rounded -> addRoundRect(outline.roundRect)
                is androidx.compose.ui.graphics.Outline.Generic -> addPath(outline.path)
            }
        }
        val density = this.density
        fun ring(widthDp: Float, blurDp: Float, opacity: Float, offset: Float) {
            val shader = SweepGradient(size.width / 2, size.height / 2, GLOW_COLORS, null)
            matrix.setRotate(angle + offset, size.width / 2, size.height / 2)
            shader.setLocalMatrix(matrix)
            val paint = Paint().apply {
                style = PaintingStyle.Stroke
                strokeWidth = widthDp * density
                isAntiAlias = true
                this.alpha = (opacity * alpha).coerceIn(0f, 1f)
                asFrameworkPaint().shader = shader
                if (blurDp > 0f) asFrameworkPaint().maskFilter = BlurMaskFilter(blurDp * density, BlurMaskFilter.Blur.NORMAL)
            }
            drawIntoCanvas { it.drawPath(path, paint) }
        }
        ring(line * 4, if (subtle) 6f else 12f, halo * 0.8f, 200f)
        ring(line * 2, if (subtle) 2f else 4f, halo, 100f)
        ring(line, 0f, if (subtle) 0.8f else 0.95f, 0f)
    }
}

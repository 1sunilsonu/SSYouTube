package com.tensei.ytxtube

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

@Composable
fun CollapsingHeaderLayout(
    resetKey: Any? = null,
    header: @Composable () -> Unit,
    content: @Composable (top: Dp) -> Unit,
) {
    val density = LocalDensity.current
    val c = MaterialTheme.colorScheme
    val reduced = rememberReducedMotion()
    var headerPx by remember { mutableIntStateOf(0) }
    var offsetPx by remember { mutableFloatStateOf(0f) }
    val settleAnim = remember { Animatable(0f) }
    val settleSpec = remember { spring<Float>(dampingRatio = 0.92f, stiffness = 460f) }

    LaunchedEffect(resetKey) {
        if (offsetPx != 0f) {
            if (reduced) {
                offsetPx = 0f
            } else {
                settleAnim.snapTo(offsetPx)
                settleAnim.animateTo(0f, settleSpec) { offsetPx = value }
            }
        }
    }

    val connection = remember(reduced) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val h = headerPx.toFloat()
                if (h <= 0f || consumed.y == 0f) return Offset.Zero
                offsetPx = (offsetPx + consumed.y).coerceIn(-h, 0f)
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val h = headerPx.toFloat()
                val v = offsetPx
                if (h > 0f && v < 0f && v > -h) {
                    val target = if (v < -h / 2f) -h else 0f
                    if (reduced) {
                        offsetPx = target
                    } else {
                        settleAnim.snapTo(v)
                        settleAnim.animateTo(target, settleSpec) { offsetPx = value }
                    }
                }
                return Velocity.Zero
            }
        }
    }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val headerBackdrop = rememberLayerBackdrop()
    val surfaceColor = c.surface
    val headerBg = remember(surfaceColor, GlassQuality.effectsSupported) {
        if (GlassQuality.effectsSupported) {
            Brush.verticalGradient(
                0f to surfaceColor.copy(alpha = 0.95f),
                0.62f to surfaceColor.copy(alpha = 0.76f),
                1f to surfaceColor.copy(alpha = 0.0f),
            )
        } else {
            Brush.verticalGradient(listOf(surfaceColor, surfaceColor))
        }
    }

    Box(Modifier.fillMaxSize().nestedScroll(connection)) {
        Box(Modifier.fillMaxSize().layerBackdrop(headerBackdrop)) {
            content(with(density) { headerPx.toDp() })
        }

        val vignette = remember(surfaceColor) { vignetteBrush(surfaceColor, opaqueAtTop = true) }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height((statusTop + 72.dp) / 2)
                .graphicsLayer { alpha = if (headerPx > 0) (-offsetPx / headerPx).coerceIn(0f, 1f) else 0f }
                .drawBehind { drawRect(vignette) },
        )
        Box(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { headerPx = it.height }
                .graphicsLayer { translationY = offsetPx }
                .background(headerBg),
        ) {
            CompositionLocalProvider(LocalGlassBackdrop provides headerBackdrop) { header() }
        }
    }
}

const val VignetteAlpha = 0.76f

fun vignetteBrush(color: Color, opaqueAtTop: Boolean): Brush {
    val stops = 12
    return Brush.verticalGradient(
        *Array(stops + 1) { i ->
            val t = i / stops.toFloat()
            val strength = if (opaqueAtTop) 1f - t else t
            t to color.copy(alpha = VignetteAlpha * strength * strength * (3f - 2f * strength))
        },
    )
}

fun PaddingValues.withTop(top: Dp) = PaddingValues(top = top, bottom = calculateBottomPadding())

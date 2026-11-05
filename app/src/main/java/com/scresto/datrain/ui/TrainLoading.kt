package com.scresto.datrain.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** An old steam engine rolling on the spot while the ties run under it. Drawn in units of a 220 x 108 box. */
@Composable
internal fun TrainLoadingLine() {
    val transition = rememberInfiniteTransition(label = "train")
    val clock by transition.animateFloat(
        initialValue = 0f,
        targetValue = LOCO_CYCLE_MS,
        animationSpec = infiniteRepeatable(tween(LOCO_CYCLE_MS.toInt(), easing = LinearEasing)),
        label = "clock",
    )
    val ink = MaterialTheme.colorScheme.onSurface
    // Offscreen, so that the window and the spokes can be cut out of whatever is behind.
    Canvas(Modifier.size(width = 128.dp, height = 63.dp).graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        val unit = size.width / 220f
        withTransform({
            scale(unit, unit, pivot = Offset.Zero)
            translate(0f, 34f)
        }) {
            drawLine(ink.copy(alpha = 0.35f), Offset(0f, 66f), Offset(220f, 66f), 2f)
            var tie = -(clock % 350f) / 350f * 16f
            while (tie < 236f) {
                drawLine(ink.copy(alpha = 0.2f), Offset(tie, 62f), Offset(tie, 72f), 3f)
                tie += 16f
            }
            translate(55f, 0f) {
                for (i in 0..2) {
                    val puff = ((clock + i * 400f) % 1200f) / 1200f
                    drawCircle(ink.copy(alpha = 0.45f * (1 - puff)), 6f * (0.4f + 1.2f * puff), Offset(80f - 18f * puff, 4f - 30f * puff))
                }
                drawPath(LOCO_BODY, ink)
                drawRect(Color.Black, Offset(15f, 20f), Size(16f, 11f), blendMode = BlendMode.Clear)
                drawCircle(LOGO_RED, 3.2f, Offset(96f, 33f))
                val angle = (clock % 700f) / 700f * 2f * PI.toFloat()
                locoWheel(ink, Offset(24f, 56f), 10f, angle)
                locoWheel(ink, Offset(50f, 56f), 10f, angle)
                locoWheel(ink, Offset(80f, 59f), 7f, angle)
                val crank = Offset(4f * cos(angle), 4f * sin(angle))
                drawLine(ink, Offset(24f, 56f) + crank, Offset(50f, 56f) + crank, 3f, cap = StrokeCap.Round)
            }
        }
    }
}

private fun DrawScope.locoWheel(ink: Color, center: Offset, radius: Float, angle: Float) {
    drawCircle(ink, radius, center)
    val spoke = radius * 0.8f
    for (turn in 0..1) {
        val a = angle + turn * PI.toFloat() / 2f
        val end = Offset(spoke * cos(a), spoke * sin(a))
        drawLine(Color.Black, center - end, center + end, radius * 0.2f, blendMode = BlendMode.Clear)
    }
    drawCircle(Color.Black, radius * 0.22f, center, blendMode = BlendMode.Clear)
}

private const val LOCO_CYCLE_MS = 8400f
private val LOCO_BODY: Path = PathParser().parsePathString(
    "M4 9h40v6H4zM8 15h32v31H8zM40 24h46a6 6 0 0 1 6 6v12a6 6 0 0 1-6 6H40zM58 24v-5a5 5 0 0 1 10 0v5z" +
        "M76 24V12l-3-5h14l-3 5v12zM92 44l12 12H92zM2 46h98v5H2z",
).toPath()

@Composable
internal fun CenteredProgress() {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        TrainLoadingLine()
    }
}

package com.lmulaptimer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lmulaptimer.data.LapSamples
import com.lmulaptimer.data.LocalDeltaPoint
import com.lmulaptimer.data.Samples
import com.lmulaptimer.data.TrackShape
import kotlin.math.max

/**
 * Canvas map from the world positions recorded in the samples. No map tiles,
 * no network: enough to recognise braking zones and compare two lines.
 */
@Composable
fun TrackMap(
    samples: LapSamples,
    modifier: Modifier = Modifier,
    secondary: LapSamples? = null,
    highlights: List<LocalDeltaPoint> = emptyList(),
    cursorDistanceM: Float? = null,
) {
    val primary = Samples.track(samples) ?: return
    val second = secondary?.let { Samples.track(it) }
    val highlightColor = Bad
    val primaryColor = Cyan
    val secondaryColor = Lime
    val gridColor = Line
    val startColor = Lime

    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(230.dp)) {
            val shape = primary
            val spanX = max(1f, shape.maxX - shape.minX)
            val spanZ = max(1f, shape.maxZ - shape.minZ)
            val scale = minOf(size.width / spanX, size.height / spanZ) * 0.88f
            val offsetX = (size.width - spanX * scale) / 2f
            val offsetZ = (size.height - spanZ * scale) / 2f
            fun point(x: Float, z: Float): Offset = Offset(
                offsetX + (x - shape.minX) * scale,
                size.height - offsetZ - (z - shape.minZ) * scale,
            )

            for (index in 1..3) {
                val y = size.height * index / 4f
                drawLine(gridColor.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y), 1f)
            }

            fun DrawScope.drawShape(track: TrackShape, color: Color, width: Float) {
                val path = Path()
                track.points.forEachIndexed { index, sample ->
                    val p = point(sample.x, sample.z)
                    if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, color, style = Stroke(width = width))
            }

            if (second != null && second.hasPosition) {
                // The second lap is drawn first so the primary line stays visible.
                drawShape(second, secondaryColor.copy(alpha = 0.65f), 3.dp.toPx())
            }
            drawShape(shape, primaryColor, 3.5.dp.toPx())

            val startPoint = shape.points.firstOrNull()
            if (startPoint != null) {
                val p = point(startPoint.x, startPoint.z)
                drawCircle(startColor, 6.dp.toPx(), p)
                drawCircle(Color.Black.copy(alpha = 0.4f), 10.dp.toPx(), p, style = Stroke(1.dp.toPx()))
            }

            if (cursorDistanceM != null && shape.points.isNotEmpty()) {
                val closest = shape.points.minByOrNull { kotlin.math.abs(it.distanceM - cursorDistanceM) }
                if (closest != null) {
                    val p = point(closest.x, closest.z)
                    drawCircle(Color.White, 5.dp.toPx(), p)
                    drawCircle(Color.White.copy(alpha = 0.35f), 11.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
                }
            }

            for (hotspot in highlights) {
                val closest = shape.points.minByOrNull { kotlin.math.abs(it.distanceM - hotspot.distanceM) }
                if (closest != null) {
                    val p = point(closest.x, closest.z)
                    drawCircle(highlightColor.copy(alpha = 0.9f), 7.dp.toPx(), p)
                    drawCircle(highlightColor.copy(alpha = 0.25f), 14.dp.toPx(), p)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (secondary != null) {
                Text("■ Lap A", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("■ Lap B", color = Lime, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            } else {
                Text("■ Start/Ziel", color = Lime, fontSize = 10.sp)
                Text("■ schnell", color = Cyan, fontSize = 10.sp)
            }
            if (highlights.isNotEmpty()) Text("● Zeitverlust", color = Bad, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text("GPS-Rohdaten · keine Kartenkacheln", color = TextDim, fontSize = 9.sp)
        }
    }
}

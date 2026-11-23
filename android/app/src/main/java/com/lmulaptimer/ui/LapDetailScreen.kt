package com.lmulaptimer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lmulaptimer.data.LapRow
import com.lmulaptimer.data.LapSamples
import com.lmulaptimer.data.Repo
import com.lmulaptimer.data.Samples
import com.lmulaptimer.data.formatClock
import com.lmulaptimer.data.formatDelta
import com.lmulaptimer.data.formatLapTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

private data class DeltaPoint(val distanceM: Float, val deltaS: Float)

@Composable
fun LapDetailScreen(repo: Repo, lap: LapRow, trackLaps: List<LapRow>, onBack: () -> Unit) {
    var samples by remember(lap.id) { mutableStateOf<JSONObject?>(null) }
    var lapData by remember(lap.id) { mutableStateOf<LapSamples?>(null) }
    var cached by remember(lap.id) { mutableStateOf(false) }
    var deltaPoints by remember(lap.id) { mutableStateOf<List<DeltaPoint>>(emptyList()) }
    var reference by remember(lap.id) { mutableStateOf<LapRow?>(null) }
    var status by remember(lap.id) { mutableStateOf("Lade Telemetrie von der Bridge…") }
    var isReference by remember(lap.id) { mutableStateOf(lap.isReference) }
    var referenceBusy by remember(lap.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(lap.id) {
        val ref = withContext(Dispatchers.IO) { repo.bestLap(lap.track, lap.id) }
        reference = ref
        val loaded = repo.lapSamplesCached(lap.id)
        samples = loaded?.payload
        lapData = loaded?.payload?.let { LapSamples.from(it) }
        cached = loaded?.cached == true
        if (loaded == null) {
            status = "Keine Samples auf der Bridge und kein lokaler Cache. Telemetrie gibt es nur für Runden, die die Bridge selbst aufgezeichnet hat."
        } else {
            status = ""
            if (ref != null) {
                // Prefer the bridge delta, fall back to the locally computed one.
                val remote = parseDelta(repo.lapDelta(lap.id, ref.id))
                deltaPoints = if (remote.size > 1) remote else {
                    repo.compareLaps(lap, ref)?.delta?.map { DeltaPoint(it.distanceM, it.deltaS) } ?: emptyList()
                }
            }
        }
    }

    val speed = remember(samples) { samples?.floatArray("speed_kmh") ?: FloatArray(0) }
    val distance = remember(samples) { samples?.floatArray("distance_m") ?: FloatArray(0) }
    val finalDelta = deltaPoints.lastOrNull()?.deltaS
    val mapData = lapData

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("‹ Zurück", fontSize = 12.sp, color = Cyan)
            }
            Column(Modifier.weight(1f)) {
                Text("Runde ${lap.lapNumber + 1}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextMain)
                Text(
                    "${lap.track} · ${formatClock(lap.startedAt)}",
                    fontSize = 11.sp,
                    color = TextDim,
                )
            }
            if (!lap.valid) Pill("INVALID", Bad)
            if (lap.outLap) Pill("OUTLAP", Warn)
            if (lap.inLap) Pill("INLAP", Warn)
            if (cached) Pill("CACHE", Warn)
            if (lap.valid && !lap.outLap && lap.lapTimeMs > 0) {
                TextButton(
                    onClick = {
                        if (!isReference && !referenceBusy) {
                            referenceBusy = true
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { repo.setReference(lap.id) }
                                isReference = ok
                                referenceBusy = false
                            }
                        }
                    },
                    enabled = !referenceBusy,
                ) {
                    Text(
                        if (isReference) "REFERENZ" else "Als Referenz",
                        fontSize = 11.sp,
                        color = if (isReference) Lime else Cyan,
                    )
                }
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Label("Rundenzeit")
                    Mono(
                        formatLapTime(lap.lapTimeMs),
                        size = 34.sp,
                        color = if (lap.valid) Lime else TextDim,
                        weight = FontWeight.Bold,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Label("Top-Speed")
                    Mono(
                        String.format(Locale.US, "%.0f km/h", lap.topSpeedKmh),
                        size = 16.sp,
                        color = Cyan,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SectorBlock("S1", lap.sector1Ms)
                SectorBlock("S2", lap.sector2Ms)
                SectorBlock("S3", lap.sector3Ms)
            }
            Text(
                "${lap.sessionType.uppercase()} · ${lap.car}",
                fontSize = 11.sp,
                color = TextDim,
            )
        }

        if (status.isNotBlank()) {
            EmptyHint(status)
        } else {
            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("Speed über die Distanz", Modifier.weight(1f))
                    Mono(String.format(Locale.US, "%.0f km/h", speed.maxOrNull() ?: 0f), size = 14.sp, color = Cyan)
                }
                SpeedTrace(speed, distance)
                Text(
                    "Grün = unter 33 % der Höchstgeschwindigkeit, Gelb = 66 %, Rot = schneller als 85 %.",
                    fontSize = 10.sp,
                    color = TextDim,
                )
            }

            if (mapData != null && Samples.track(mapData) != null) {
                Panel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label("Streckenkarte", Modifier.weight(1f))
                        Mono("Position aus Telemetrie", size = 10.sp, color = TextDim)
                    }
                    TrackMap(mapData)
                    Text(
                        "Runde ${lap.lapNumber + 1} auf Basis der aufgezeichneten Weltkoordinaten.",
                        fontSize = 10.sp,
                        color = TextDim,
                    )
                }
            }

            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("Distanz-Heatmap", Modifier.weight(1f))
                    Mono("${distance.lastOrNull()?.toInt() ?: 0} m", size = 12.sp, color = TextDim)
                }
                SpeedHeatmap(speed)
                Text(
                    "Farbe = Geschwindigkeit an dieser Stelle. So siehst du Bremszonen und schnelle Abschnitte auf einen Blick.",
                    fontSize = 10.sp,
                    color = TextDim,
                )
            }

            if (reference != null) {
                Panel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Label("Delta zur Referenz")
                            Text(
                                "Referenz: Runde ${(reference?.lapNumber ?: 0) + 1} · " +
                                    formatLapTime(reference?.lapTimeMs),
                                fontSize = 11.sp,
                                color = TextDim,
                            )
                        }
                        if (finalDelta != null) {
                            Mono(
                                formatDelta(finalDelta.toDouble()),
                                size = 24.sp,
                                color = if (finalDelta <= 0f) Lime else Bad,
                                weight = FontWeight.Bold,
                            )
                        }
                    }
                    if (deltaPoints.size > 1) {
                        DeltaTrace(deltaPoints, finalDelta ?: 0f)
                        Text(
                            "Oberhalb der Nulllinie verlierst du Zeit, darunter holst du auf.",
                            fontSize = 10.sp,
                            color = TextDim,
                        )
                    } else {
                        Text("Keine Delta-Punkte von der Bridge erhalten.", fontSize = 11.sp, color = TextDim)
                    }
                }
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Label("Verbrauch & Bedingungen")
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Value("Fuel", String.format(Locale.US, "%.2f", lap.fuelUsedL), "L")
                Value("VE", String.format(Locale.US, "%.2f", lap.veUsed * 100), "%")
                Value("Luft", String.format(Locale.US, "%.1f", lap.airTempC), "°C")
                Value("Strecke", String.format(Locale.US, "%.1f", lap.trackTempC), "°C")
            }
            if (lap.fuelStartL > 0) {
                Text(
                    String.format(
                        Locale.US,
                        "Start %.2f L → Ende %.2f L · VE-Start %.1f %%",
                        lap.fuelStartL,
                        lap.fuelStartL - lap.fuelUsedL,
                        lap.veStart * 100,
                    ),
                    fontSize = 11.sp,
                    color = TextDim,
                )
            }
        }
    }
}

@Composable
private fun SectorBlock(label: String, value: Long?) {
    Column {
        Label(label)
        Mono(if (value != null) formatLapTime(value) else "–", size = 15.sp, color = Cyan)
    }
}

@Composable
private fun SpeedTrace(speed: FloatArray, distance: FloatArray) {
    val grid = Line
    val trace = Cyan
    val minSpeed = speed.minOrNull() ?: 0f
    val maxSpeed = speed.maxOrNull() ?: 1f
    Column {
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            for (index in 1..3) {
                val y = size.height * index / 4f
                drawLine(grid.copy(alpha = 0.6f), Offset(0f, y), Offset(size.width, y), 1f)
            }
            if (speed.size < 2) return@Canvas
            val path = Path()
            val span = max(1f, maxSpeed - minSpeed)
            speed.forEachIndexed { index, value ->
                val x = size.width * index / (speed.size - 1).toFloat()
                val y = size.height - ((value - minSpeed) / span) * size.height
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, trace, style = Stroke(width = 2.dp.toPx()))
        }
        Row {
            Mono("0 m", size = 10.sp, color = TextDim)
            Spacer(Modifier.weight(1f))
            Mono("${distance.lastOrNull()?.toInt() ?: 0} m", size = 10.sp, color = TextDim)
        }
    }
}

@Composable
private fun SpeedHeatmap(speed: FloatArray) {
    val slow = Cyan
    val medium = Lime
    val fast = Warn
    val top = Bad
    Canvas(Modifier.fillMaxWidth().height(22.dp)) {
        if (speed.isEmpty()) return@Canvas
        val minSpeed = speed.minOrNull() ?: 0f
        val span = max(1f, (speed.maxOrNull() ?: 1f) - minSpeed)
        val step = size.width / speed.size
        speed.forEachIndexed { index, value ->
            val normalized = (value - minSpeed) / span
            val color = when {
                normalized < 0.33f -> slow
                normalized < 0.66f -> medium
                normalized < 0.85f -> fast
                else -> top
            }
            drawRect(color, Offset(index * step, 0f), Size(step + 1f, size.height))
        }
    }
}

@Composable
private fun DeltaTrace(points: List<DeltaPoint>, finalDelta: Float) {
    val zero = Line
    val color = if (finalDelta <= 0f) Lime else Bad
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val middle = size.height / 2f
        drawLine(zero, Offset(0f, middle), Offset(size.width, middle), 1f)
        if (points.size < 2) return@Canvas
        val maxAbs = max(0.05f, points.maxOfOrNull { abs(it.deltaS) } ?: 0f)
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = size.width * index / (points.size - 1).toFloat()
            val y = middle - (point.deltaS / maxAbs) * (size.height / 2f - 2f)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
}

private fun JSONObject.floatArray(key: String): FloatArray {
    val array = optJSONArray(key) ?: return FloatArray(0)
    return FloatArray(array.length()) { array.optDouble(it, 0.0).toFloat() }
}

private fun parseDelta(payload: JSONObject?): List<DeltaPoint> {
    val points = payload?.optJSONArray("points") ?: return emptyList()
    return buildList {
        for (index in 0 until points.length()) {
            val point = points.optJSONObject(index) ?: continue
            add(
                DeltaPoint(
                    distanceM = point.optDouble("distance_m", 0.0).toFloat(),
                    deltaS = point.optDouble("delta_s", 0.0).toFloat(),
                )
            )
        }
    }
}

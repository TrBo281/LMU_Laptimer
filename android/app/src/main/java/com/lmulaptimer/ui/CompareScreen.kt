package com.lmulaptimer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lmulaptimer.data.LapCompare
import com.lmulaptimer.data.LapRow
import com.lmulaptimer.data.LapSamples
import com.lmulaptimer.data.LocalDeltaPoint
import com.lmulaptimer.data.Repo
import com.lmulaptimer.data.Samples
import com.lmulaptimer.data.formatDelta
import com.lmulaptimer.data.formatLapTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Compares two laps of the same track using the locally cached samples. The
 * delta is recalculated on the phone, so the screen also works without a rig
 * once both laps have been opened once.
 */
@Composable
fun CompareScreen(
    repo: Repo,
    laps: List<LapRow>,
    initialA: LapRow? = null,
    initialB: LapRow? = null,
    onBack: () -> Unit,
) {
    val candidates = remember(laps) {
        laps.filter { it.valid && !it.outLap && it.lapTimeMs > 0 }.sortedBy { it.lapTimeMs }
    }
    var lapA by remember { mutableStateOf(initialA ?: candidates.getOrNull(0)) }
    var lapB by remember {
        mutableStateOf(initialB ?: candidates.firstOrNull { it.id != (initialA ?: candidates.getOrNull(0))?.id })
    }
    var compare by remember { mutableStateOf<LapCompare?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(lapA?.id, lapB?.id) {
        val a = lapA
        val b = lapB
        if (a == null || b == null || a.id == b.id) {
            compare = null
            message = "Zwei verschiedene Runden auswählen."
            return@LaunchedEffect
        }
        loading = true
        message = ""
        val result = withContext(Dispatchers.IO) { runCatching { repo.compareLaps(a, b) }.getOrNull() }
        compare = result
        loading = false
        if (result == null) {
            message = "Keine Samples für beide Runden. Öffne die Runden einmal mit Bridge-Verbindung, damit die Telemetrie lokal gespeichert wird."
        }
    }

    val data = compare
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Zurück", fontSize = 12.sp, color = Cyan) }
            Column(Modifier.weight(1f)) {
                Text("Rundenvergleich", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextMain)
                Text(
                    lapA?.track ?: "Keine Runde ausgewählt",
                    fontSize = 11.sp,
                    color = TextDim,
                )
            }
            if (data != null) {
                Mono(formatDelta(Samples.absDelta(data).toDouble()), size = 20.sp, color = if (Samples.absDelta(data) <= 0f) Lime else Bad, weight = FontWeight.Bold)
            }
        }

        if (candidates.size < 2) {
            EmptyHint("Für einen Vergleich braucht es mindestens zwei gültige Runden dieser Strecke.")
            return@Column
        }

        Panel(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LapPicker("Lap A", lapA, candidates, Modifier.weight(1f)) { lapA = it }
                LapPicker("Lap B", lapB, candidates, Modifier.weight(1f)) { lapB = it }
            }
            Text(
                "A ist die Referenz. Der Delta-Verlauf zeigt, wo B Zeit verliert (rot) oder aufholt (grün).",
                fontSize = 10.sp,
                color = TextDim,
            )
        }

        if (loading) EmptyHint("Vergleiche Telemetrie…")
        if (message.isNotBlank()) EmptyHint(message)

        if (data != null) {
            Panel(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CompareLap("A", lapA, data.topSpeedA, data.throttlePctA, data.brakePctA, Cyan, Modifier.weight(1f))
                    CompareLap("B", lapB, data.topSpeedB, data.throttlePctB, data.brakePctB, Lime, Modifier.weight(1f))
                }
            }

            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("Delta über die Distanz", Modifier.weight(1f))
                    Mono(
                        "${data.delta.lastOrNull()?.distanceM?.toInt() ?: 0} m",
                        size = 10.sp,
                        color = TextDim,
                    )
                }
                CompareDeltaTrace(data)
            }

            Panel(Modifier.fillMaxWidth()) {
                Label("Linienvergleich")
                TrackMap(data.a, secondary = data.b, highlights = Samples.hotspots(data))
                Text(
                    "Gleiche Weltkoordinaten, keine Kartenkacheln. Rote Punkte markieren die 100-m-Fenster mit dem größten Zeitverlust von B.",
                    fontSize = 10.sp,
                    color = TextDim,
                )
            }

            val segments = Samples.segmentDeltas(data)
            if (segments.isNotEmpty()) {
                Panel(Modifier.fillMaxWidth()) {
                    Label("Drittel-Bilanz")
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        segments.forEachIndexed { index, delta ->
                            Column {
                                Label("S${index + 1}")
                                Mono(
                                    formatDelta(delta.toDouble()),
                                    size = 17.sp,
                                    color = if (delta <= 0f) Lime else Bad,
                                    weight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                    Text("Delta am Ende des jeweiligen Streckendrittels.", fontSize = 10.sp, color = TextDim)
                }
            }

            val hotspots = Samples.hotspots(data)
            if (hotspots.isNotEmpty()) {
                Panel(Modifier.fillMaxWidth()) {
                    Label("Größte Zeitverluste")
                    hotspots.forEach { spot ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Mono("${spot.distanceM.toInt()} m", size = 12.sp, color = TextDim, modifier = Modifier.weight(1f))
                            Mono(formatDelta(spot.deltaS.toDouble()), size = 16.sp, color = Bad, weight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LapPicker(
    label: String,
    selected: LapRow?,
    candidates: List<LapRow>,
    modifier: Modifier = Modifier,
    onSelect: (LapRow) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Label(label)
        Box {
            TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    selected?.let { "R${it.lapNumber + 1} · ${formatLapTime(it.lapTimeMs)}" } ?: "auswählen",
                    fontSize = 12.sp,
                    color = Cyan,
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                candidates.forEach { lap ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                "R${lap.lapNumber + 1} · ${formatLapTime(lap.lapTimeMs)} · ${lap.car}",
                                fontSize = 11.sp,
                            )
                        },
                        onClick = {
                            onSelect(lap)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CompareLap(
    name: String,
    lap: LapRow?,
    topSpeed: Float,
    throttlePct: Float,
    brakePct: Float,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(name, color)
            Spacer(Modifier.weight(1f))
            Mono(formatLapTime(lap?.lapTimeMs), size = 16.sp, color = color, weight = FontWeight.Bold)
        }
        Text(
            "R${(lap?.lapNumber ?: 0) + 1} · ${lap?.car.orEmpty()}",
            fontSize = 10.sp,
            color = TextDim,
        )
        Text(
            String.format(Locale.US, "Top %.0f km/h · Vollgas %.0f %% · Bremse %.0f %%", topSpeed, throttlePct, brakePct),
            fontSize = 10.sp,
            color = TextDim,
        )
    }
}

@Composable
private fun CompareDeltaTrace(data: LapCompare) {
    val points: List<LocalDeltaPoint> = data.delta
    val final = Samples.absDelta(data)
    val color = if (final <= 0f) Lime else Bad
    val zero = Line
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
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
        val endY = middle - (final / maxAbs) * (size.height / 2f - 2f)
        drawCircle(color, 4.dp.toPx(), Offset(size.width, endY))
    }
}

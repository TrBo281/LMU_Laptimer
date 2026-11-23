package com.lmulaptimer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lmulaptimer.data.LapRow
import com.lmulaptimer.data.LapSamples
import com.lmulaptimer.data.Repo
import com.lmulaptimer.data.Samples
import com.lmulaptimer.data.formatLapTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Multi-lap analysis: several laps on one distance axis, one synchronized
 * cursor for map and traces, plus the theoretical best from best sectors.
 */
@Composable
fun AnalysisCockpitScreen(repo: Repo, laps: List<LapRow>, onBack: () -> Unit) {
    val candidates = remember(laps) {
        laps.filter { it.valid && !it.outLap && it.lapTimeMs > 0 }.sortedBy { it.lapTimeMs }
    }
    var selected by remember(laps) {
        mutableStateOf(candidates.take(3).map { it.id }.toSet())
    }
    var samplesById by remember { mutableStateOf<Map<String, LapSamples>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var cursor by remember { mutableFloatStateOf(0.35f) }

    LaunchedEffect(selected) {
        loading = true
        val result = withContext(Dispatchers.IO) {
            selected.mapNotNull { id ->
                runCatching { repo.lapSamplesCached(id) }.getOrNull()
                    ?.payload?.let { LapSamples.from(it) }
                    ?.let { id to it }
            }.toMap()
        }
        samplesById = result
        loading = false
    }

    val chosen = candidates.filter { it.id in selected }
    val maxDistance = samplesById.values.maxOfOrNull { it.distanceEndM } ?: 0f
    val cursorM = maxDistance * cursor
    val actualBest = chosen.minOfOrNull { it.lapTimeMs }
    val s1 = chosen.mapNotNull { it.sector1Ms }.filter { it > 0 }.minOrNull()
    val s2 = chosen.mapNotNull { it.sector2Ms }.filter { it > 0 }.minOrNull()
    val s3 = chosen.mapNotNull { it.sector3Ms }.filter { it > 0 }.minOrNull()
    val theoretical = if (s1 != null && s2 != null && s3 != null) s1 + s2 + s3 else null
    val consistency = consistencyOf(chosen.map { it.lapTimeMs })
    val primary = chosen.firstNotNullOfOrNull { samplesById[it.id] }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Zeiten", fontSize = 12.sp, color = Cyan) }
            Column(Modifier.weight(1f)) {
                Text("Analyse-Cockpit", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextMain)
                Text(
                    chosen.firstOrNull()?.track ?: "Mehrere Runden auf einer Distanzachse",
                    fontSize = 11.sp,
                    color = TextDim,
                )
            }
        }

        if (candidates.isEmpty()) {
            EmptyHint("Keine gültigen Runden für dieses Cockpit.")
            return@Column
        }

        Panel(Modifier.fillMaxWidth()) {
            Label("Runden (mehrere auswählbar)")
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                candidates.take(16).forEach { lap ->
                    FilterChip(
                        selected = lap.id in selected,
                        onClick = {
                            selected = if (lap.id in selected) selected - lap.id else selected + lap.id
                        },
                        label = {
                            Text(
                                "R${lap.lapNumber + 1} · ${formatLapTime(lap.lapTimeMs)}",
                                fontSize = 10.sp,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Lime,
                            selectedLabelColor = Bg,
                        ),
                    )
                }
            }
            Text(
                if (loading) "Samples werden geladen …"
                else "${samplesById.size} von ${selected.size} Runden mit lokalem Sample",
                fontSize = 10.sp,
                color = TextDim,
            )
        }

        Panel(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Value("Best gewählt", formatLapTime(actualBest), color = Lime)
                Value("Theoretisch", formatLapTime(theoretical), color = Cyan)
                Value(
                    "Konstanz",
                    consistency?.let { String.format(Locale.US, "%.0f %%", it) } ?: "–",
                    color = if ((consistency ?: 0.0) >= 98.0) Lime else Warn,
                )
                Value("Runden", chosen.size.toString())
            }
            Text(
                if (theoretical != null && actualBest != null && theoretical < actualBest) {
                    "Theoretisch ${formatLapTime(theoretical - actualBest)} schneller als die beste gewählte Runde (Summe der besten Sektoren)."
                } else {
                    "Theoretische Bestzeit = Summe der besten S1/S2/S3 der gewählten Runden."
                },
                fontSize = 10.sp,
                color = TextDim,
            )
        }

        if (primary == null) {
            EmptyHint("Für diese Runden sind keine Samples lokal. Öffne sie einmal mit Bridge-Verbindung.")
            return@Column
        }

        Panel(Modifier.fillMaxWidth()) {
            Label("Streckenkarte · synchroner Cursor")
            TrackMap(primary, cursorDistanceM = cursorM)
        }

        Panel(Modifier.fillMaxWidth()) {
            Label("Speed-Verlauf")
            MultiSpeedChart(chosen = chosen, samplesById = samplesById, cursorFraction = cursor)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    String.format(Locale.US, "%.0f m", cursorM),
                    fontSize = 10.sp,
                    color = TextDim,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Slider(
                    value = cursor,
                    onValueChange = { cursor = it },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Label("Werte am Cursor")
            chosen.forEachIndexed { index, lap ->
                val sample = samplesById[lap.id] ?: return@forEachIndexed
                val nearest = nearestIndex(sample, cursorM)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "R${lap.lapNumber + 1}",
                        fontSize = 10.sp,
                        color = colorAt(index),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Mono(
                        String.format(
                            Locale.US,
                            "%.0f km/h · T %.0f%% · B %.0f%% · G%d · %.2f L",
                            sample.speedKmh.getOrElse(nearest) { 0f },
                            sample.throttle.getOrElse(nearest) { 0f } * 100f,
                            sample.brake.getOrElse(nearest) { 0f } * 100f,
                            sample.gear.getOrElse(nearest) { 0 },
                            sample.fuelL.getOrElse(nearest) { 0f },
                        ),
                        size = 10.sp,
                        color = TextMain,
                    )
                }
            }
            Text(
                "Ein Slider bewegt Karte und alle Geschwindigkeitslinien gemeinsam.",
                fontSize = 9.sp,
                color = TextDim,
            )
        }
    }
}

@Composable
private fun MultiSpeedChart(
    chosen: List<LapRow>,
    samplesById: Map<String, LapSamples>,
    cursorFraction: Float,
) {
    val all = chosen.mapNotNull { samplesById[it.id] }
    if (all.isEmpty()) {
        EmptyHint("Keine Samples für den Speed-Verlauf.")
        return
    }
    val minSpeed = all.minOf { samples -> (0 until samples.count).minOf { samples.speedKmh[it] } }
    val maxSpeed = max(1f, all.maxOf { it.maxSpeedKmh })
    val grid = Line
    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        for (index in 0..3) {
            val y = size.height * index / 3f
            drawLine(grid.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y), 1f)
        }
        all.forEachIndexed { index, samples ->
            val end = max(1f, samples.distanceEndM)
            val path = Path()
            for (i in 0 until samples.count) {
                val x = (samples.distanceM[i] / end).coerceIn(0f, 1f) * size.width
                val speed = ((samples.speedKmh[i] - minSpeed) / (maxSpeed - minSpeed)).coerceIn(0f, 1f)
                val y = size.height - speed * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, colorAt(index), style = Stroke(2.dp.toPx()))
        }
        val x = cursorFraction.coerceIn(0f, 1f) * size.width
        drawLine(Color.White.copy(alpha = 0.8f), Offset(x, 0f), Offset(x, size.height), 1.5f)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        chosen.forEachIndexed { index, lap ->
            if (samplesById.containsKey(lap.id)) {
                Text(
                    "■ R${lap.lapNumber + 1}",
                    color = colorAt(index),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private fun nearestIndex(samples: LapSamples, distance: Float): Int {
    if (samples.count == 0) return 0
    var best = 0
    var bestDelta = Float.MAX_VALUE
    for (i in 0 until samples.count) {
        val delta = kotlin.math.abs(samples.distanceM[i] - distance)
        if (delta < bestDelta) {
            bestDelta = delta
            best = i
        }
    }
    return best
}

private fun consistencyOf(times: List<Long>): Double? {
    if (times.size < 2) return null
    val mean = times.average()
    if (mean <= 0.0) return null
    val variance = times.sumOf { (it - mean) * (it - mean) } / times.size
    return 100.0 - sqrt(variance) / mean * 100.0
}

private fun colorAt(index: Int): Color = when (index % 4) {
    0 -> Color(0xFF54D7FF)
    1 -> Color(0xFFB6FF3B)
    2 -> Color(0xFFFFB84D)
    else -> Color(0xFFFF5D5D)
}

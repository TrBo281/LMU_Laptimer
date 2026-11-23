package com.lmulaptimer.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lmulaptimer.data.LapRow
import com.lmulaptimer.data.Repo
import com.lmulaptimer.data.formatLapTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.pow
import kotlin.math.sqrt

@Composable
fun AnalysisScreen(repo: Repo, refresh: Int) {
    var tracks by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var laps by remember { mutableStateOf<List<LapRow>>(emptyList()) }
    var selectedCar by remember { mutableStateOf("alle") }
    var condition by remember { mutableStateOf("alle") }

    LaunchedEffect(refresh) {
        tracks = withContext(Dispatchers.IO) { repo.tracks() }
        if (selected == null || tracks.none { it == selected }) {
            selected = tracks.firstOrNull()
        }
    }
    LaunchedEffect(selected, refresh) {
        laps = selected?.let { withContext(Dispatchers.IO) { repo.lapsFor(it) } } ?: emptyList()
    }

    val cars = remember(laps) {
        listOf("alle") + laps.map { it.car }.filter { it.isNotBlank() }.distinct().sorted()
    }
    LaunchedEffect(laps) {
        if (selectedCar != "alle" && laps.none { it.car == selectedCar }) {
            selectedCar = "alle"
        }
    }
    val filteredLaps = remember(laps, selectedCar, condition) {
        laps.filter { lap ->
            (selectedCar == "alle" || lap.car == selectedCar) &&
                (condition == "alle" || (condition == "nass") == lap.wet)
        }
    }
    val valid = remember(filteredLaps) {
        filteredLaps.filter { it.valid && !it.outLap && it.lapTimeMs > 0 }
    }
    val mean = remember(valid) { if (valid.isEmpty()) 0.0 else valid.map { it.lapTimeMs }.average() }
    val stddev = remember(valid, mean) {
        if (valid.isEmpty()) 0.0 else sqrt(valid.map { (it.lapTimeMs - mean).pow(2) }.average())
    }
    val bestS1 = remember(valid) { valid.mapNotNull { it.sector1Ms }.minOrNull() }
    val bestS2 = remember(valid) { valid.mapNotNull { it.sector2Ms }.minOrNull() }
    val bestS3 = remember(valid) { valid.mapNotNull { it.sector3Ms }.minOrNull() }
    val theoretical = if (bestS1 != null && bestS2 != null && bestS3 != null) bestS1 + bestS2 + bestS3 else null

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                Text("Analyse", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Konsistenz und theoretisch beste Runde – gefiltert nach Fahrzeug und Bedingungen.",
                    fontSize = 12.sp,
                    color = TextDim,
                )
            }
        }

        if (tracks.isEmpty()) {
            item { EmptyHint("Noch keine gültigen Runden. Synchronisiere zuerst im Bridge-Tab.") }
        } else {
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tracks.forEach { track ->
                        FilterChip(
                            selected = selected == track,
                            onClick = { selected = track },
                            label = { Text(track, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Lime,
                                selectedLabelColor = Bg,
                            ),
                        )
                    }
                }
            }

            if (cars.size > 1) {
                item {
                    FilterRow(
                        label = "Fahrzeug",
                        values = cars,
                        selected = selectedCar,
                        onSelect = { selectedCar = it },
                    )
                }
            }
            item {
                FilterRow(
                    label = "Bedingungen",
                    values = listOf("alle", "trocken", "nass"),
                    selected = condition,
                    onSelect = { condition = it },
                )
            }

            item {
                Panel(Modifier.fillMaxWidth()) {
                    Label("Konsistenz")
                    Row {
                        StatBlock("Runden", "${valid.size}", Modifier.weight(1f))
                        StatBlock("Schnitt", formatLapTime(mean.toLong()), Modifier.weight(1f))
                        StatBlock("Streuung", String.format(Locale.US, "%.3f s", stddev / 1000.0), Modifier.weight(1f))
                    }
                    if (valid.isNotEmpty()) {
                        Text(
                            "Beste Runde: ${formatLapTime(valid.minOf { it.lapTimeMs })} · " +
                                "Differenz zum Schnitt: ${String.format(Locale.US, "%.3f s", (mean - valid.minOf { it.lapTimeMs }) / 1000.0)}",
                            fontSize = 11.sp,
                            color = TextDim,
                        )
                    }
                }
            }

            item {
                Panel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label("Theoretisch beste Runde", Modifier.weight(1f))
                        if (theoretical != null) {
                            Mono(formatLapTime(theoretical), size = 22.sp, color = Lime, weight = FontWeight.Bold)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        SectorValue("S1", bestS1)
                        SectorValue("S2", bestS2)
                        SectorValue("S3", bestS3)
                    }
                    Text(
                        "Summe der jeweils besten Sektoren – unabhängig davon, ob sie in einer Runde zusammenkamen.",
                        fontSize = 11.sp,
                        color = TextDim,
                    )
                }
            }

            if (valid.isEmpty()) {
                item { EmptyHint("Keine gültige Runde für diese Auswahl.") }
            }

            if (valid.isNotEmpty()) {
                item {
                    Text("Beste Runden", fontSize = 11.sp, color = TextDim, modifier = Modifier.padding(top = 6.dp))
                }
                items(valid.sortedBy { it.lapTimeMs }.take(8)) { lap ->
                    Panel(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Mono(formatLapTime(lap.lapTimeMs), size = 16.sp, weight = FontWeight.Bold)
                            Text(
                                "  Runde ${lap.lapNumber + 1}",
                                fontSize = 11.sp,
                                color = TextDim,
                            )
                            Row(
                                Modifier.weight(1f),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                SectorChip("S1", lap.sector1Ms, bestS1)
                                SectorChip("S2", lap.sector2Ms, bestS2)
                                SectorChip("S3", lap.sector3Ms, bestS3)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatBlock(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Label(label)
        Mono(value, size = 15.sp, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun SectorValue(label: String, value: Long?) {
    Column {
        Label(label)
        Mono(if (value != null) formatLapTime(value) else "–", size = 14.sp, color = Cyan)
    }
}

@Composable
private fun SectorChip(label: String, value: Long?, best: Long?) {
    val isBest = value != null && best != null && value == best
    Text(
        text = "$label ${if (value != null) formatLapTime(value) else "–"}",
        fontSize = 10.sp,
        color = if (isBest) Lime else TextDim,
        fontWeight = if (isBest) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.padding(start = 8.dp),
    )
}

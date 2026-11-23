package com.lmulaptimer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.lmulaptimer.data.PersonalBest
import com.lmulaptimer.data.Repo
import com.lmulaptimer.data.SessionRow
import com.lmulaptimer.data.formatClock
import com.lmulaptimer.data.formatLapTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LapsScreen(repo: Repo, refresh: Int) {
    var bests by remember { mutableStateOf<List<PersonalBest>>(emptyList()) }
    var sessions by remember { mutableStateOf<List<SessionRow>>(emptyList()) }
    var selectedTrack by remember { mutableStateOf<String?>(null) }
    var selectedSession by remember { mutableStateOf<SessionRow?>(null) }
    var laps by remember { mutableStateOf<List<LapRow>>(emptyList()) }
    var typeFilter by remember { mutableStateOf("alle") }
    var carFilter by remember { mutableStateOf("alle") }
    var conditionFilter by remember { mutableStateOf("alle") }
    var archiveRefresh by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<LapRow?>(null) }
    var sessionDetail by remember { mutableStateOf<SessionRow?>(null) }
    var compareOpen by remember { mutableStateOf(false) }
    var cockpitOpen by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) {
        bests = withContext(Dispatchers.IO) { repo.personalBests() }
        sessions = withContext(Dispatchers.IO) { repo.sessions() }
        if (selectedTrack == null || bests.none { it.track == selectedTrack }) {
            selectedTrack = bests.firstOrNull()?.track
        }
    }
    LaunchedEffect(selectedTrack, selectedSession, refresh, archiveRefresh) {
        laps = withContext(Dispatchers.IO) {
            val session = selectedSession
            if (session != null) repo.lapsForSession(session.id)
            else selectedTrack?.let { repo.lapsFor(it) } ?: emptyList()
        }
    }

    val detailLap = detail
    if (detailLap != null) {
        LapDetailScreen(repo, detailLap, laps, onBack = {
            detail = null
            archiveRefresh += 1
        })
        return
    }

    val sessionForDetail = sessionDetail
    if (sessionForDetail != null) {
        SessionDetailScreen(
            repo = repo,
            session = sessionForDetail,
            laps = laps,
            onBack = { sessionDetail = null },
            onOpenLap = { detail = it },
        )
        return
    }

    val types = listOf("alle", "practice", "qualifying", "race")
    val cars = remember(sessions) {
        listOf("alle") + sessions.map { it.car }.filter { it.isNotBlank() }.distinct().sorted()
    }
    val filteredSessions = sessions.filter { session ->
        (typeFilter == "alle" || session.sessionType.equals(typeFilter, ignoreCase = true)) &&
            (carFilter == "alle" || session.car == carFilter)
    }
    val filteredBests = bests.filter {
        conditionFilter == "alle" || (conditionFilter == "nass") == it.wet
    }
    val visibleLaps = laps.filter {
        conditionFilter == "alle" || (conditionFilter == "nass") == it.wet
    }
    val bestForSelection = visibleLaps.filter { it.valid && !it.outLap }.minOfOrNull { it.lapTimeMs }
    val comparable = visibleLaps.count { it.valid && !it.outLap && it.lapTimeMs > 0 }

    if (cockpitOpen) {
        AnalysisCockpitScreen(repo, visibleLaps, onBack = { cockpitOpen = false })
        return
    }

    if (compareOpen) {
        CompareScreen(
            repo = repo,
            laps = visibleLaps,
            initialA = visibleLaps.filter { it.valid && !it.outLap }.minByOrNull { it.lapTimeMs },
            onBack = { compareOpen = false },
        )
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                Text("Bestzeiten & Archiv", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextMain)
                Text(
                    "Sessions filtern, Runden öffnen und Telemetrie je Runde ansehen.",
                    fontSize = 12.sp,
                    color = TextDim,
                )
            }
        }

        if (sessions.isEmpty()) {
            item {
                EmptyHint("Noch keine Sessions im Archiv. Öffne den Bridge-Tab und synchronisiere.")
            }
        }

        if (sessions.isNotEmpty()) {
            item {
                FilterRow(
                    label = "Session-Typ",
                    values = types,
                    selected = typeFilter,
                    onSelect = { typeFilter = it },
                )
            }
            if (cars.size > 1) {
                item {
                    FilterRow(
                        label = "Fahrzeug",
                        values = cars,
                        selected = carFilter,
                        onSelect = { carFilter = it },
                    )
                }
            }
            item {
                FilterRow(
                    label = "Bedingungen",
                    values = listOf("alle", "trocken", "nass"),
                    selected = conditionFilter,
                    onSelect = { conditionFilter = it },
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Sessions · ${filteredSessions.size}",
                        fontSize = 11.sp,
                        color = TextDim,
                        modifier = Modifier.weight(1f),
                    )
                    if (selectedSession != null) {
                        TextButton(onClick = { selectedSession = null }) {
                            Text("Runden der Strecke", fontSize = 11.sp, color = Cyan)
                        }
                    }
                }
            }
            items(filteredSessions.take(40)) { session ->
                SessionCard(
                    session = session,
                    selected = selectedSession?.id == session.id,
                    onClick = {
                        selectedSession = session
                        selectedTrack = session.track
                    },
                    onDetails = {
                        selectedSession = session
                        selectedTrack = session.track
                        sessionDetail = session
                    },
                )
            }
            if (filteredSessions.isEmpty()) {
                item { EmptyHint("Keine Session passt zu den Filtern.") }
            }
        }

        if (bests.isNotEmpty()) {
            item {
                Text(
                    "Bestzeiten pro Strecke & Fahrzeug",
                    fontSize = 11.sp,
                    color = TextDim,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(filteredBests) { pb ->
                PersonalBestCard(pb, selectedSession == null && selectedTrack == pb.track) {
                    selectedSession = null
                    selectedTrack = pb.track
                }
            }
            if (filteredBests.isEmpty()) {
                item { EmptyHint("Keine Bestzeit passt zum Bedingungsfilter.") }
            }
        }

        if (visibleLaps.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Runden · ${selectedSession?.track ?: selectedTrack.orEmpty()}" +
                                if (laps.any { it.outLap }) " · OUT zählt nicht" else "",
                            fontSize = 11.sp,
                            color = TextDim,
                        )
                        if (selectedSession != null) {
                            Text(
                                "${selectedSession?.sessionType?.uppercase()} · " +
                                    "${selectedSession?.car} · ${formatClock(selectedSession?.startedAt ?: 0L)}",
                                fontSize = 10.sp,
                                color = TextDim,
                            )
                        }
                    }
                    if (comparable >= 2) {
                        TextButton(onClick = { cockpitOpen = true }) {
                            Text("Cockpit", fontSize = 10.sp, color = Lime)
                        }
                        TextButton(onClick = { compareOpen = true }) {
                            Text("Vergleichen", fontSize = 10.sp, color = Cyan)
                        }
                    }
                    Text("Antippen für Detail", fontSize = 10.sp, color = TextDim)
                }
            }
            items(visibleLaps) { lap ->
                LapCard(
                    lap = lap,
                    isBest = lap.lapTimeMs == bestForSelection,
                    onClick = { detail = lap },
                )
            }
        } else if (laps.isNotEmpty()) {
            item { EmptyHint("Keine Runde passt zum Bedingungenfilter.") }
        }
    }
}

@Composable
fun FilterRow(
    label: String,
    values: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label(label)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            values.forEach { value ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = {
                        Text(
                            if (value == "alle") "Alle" else value.uppercase(),
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
    }
}

@Composable
private fun SessionCard(session: SessionRow, selected: Boolean, onClick: () -> Unit, onDetails: () -> Unit) {
    Panel(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    session.track.ifBlank { "Unbekannte Strecke" },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) Lime else TextMain,
                )
                Text(
                    "${session.sessionType.uppercase()} · ${session.car}",
                    fontSize = 11.sp,
                    color = TextDim,
                )
                Text(
                    "${formatClock(session.startedAt)} · ${session.lapCount} Runden",
                    fontSize = 10.sp,
                    color = TextDim,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Mono(formatLapTime(session.bestLapMs), size = 17.sp, color = Lime, weight = FontWeight.Bold)
                if (selected) {
                    Text("AUSGEWÄHLT", fontSize = 9.sp, color = Lime, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onDetails) {
                    Text("Details ›", fontSize = 10.sp, color = Cyan)
                }
            }
        }
    }
}

@Composable
private fun PersonalBestCard(pb: PersonalBest, selected: Boolean, onClick: () -> Unit) {
    Panel(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    pb.track.ifBlank { "Unbekannte Strecke" },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) Lime else TextMain,
                )
                Text(
                    pb.car.ifBlank { "Unbekanntes Fahrzeug" } +
                        if (pb.carClass.isNotBlank()) " · ${pb.carClass}" else "",
                    fontSize = 11.sp,
                    color = TextDim,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${pb.lapCount} Runden · zuletzt ${formatClock(pb.lastAt)}",
                        fontSize = 11.sp,
                        color = TextDim,
                        modifier = Modifier.weight(1f),
                    )
                    Pill(if (pb.wet) "NASS" else "TROCKEN", if (pb.wet) Cyan else Lime)
                }
            }
            Mono(formatLapTime(pb.bestMs), size = 20.sp, color = Lime, weight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LapCard(lap: LapRow, isBest: Boolean, onClick: () -> Unit) {
    Panel(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Runde ${lap.lapNumber + 1}",
                        fontSize = 12.sp,
                        color = TextDim,
                    )
                    if (isBest) {
                        Text("  PB", fontSize = 10.sp, color = Lime, fontWeight = FontWeight.Bold)
                    }
                    if (!lap.valid) {
                        Text("  INVALID", fontSize = 10.sp, color = Bad, fontWeight = FontWeight.Bold)
                    }
                    if (lap.outLap) {
                        Text("  OUT", fontSize = 10.sp, color = Warn, fontWeight = FontWeight.Bold)
                    }
                    if (lap.isReference) {
                        Text("  REF", fontSize = 10.sp, color = Cyan, fontWeight = FontWeight.Bold)
                    }
                    if (lap.wet) {
                        Text("  NASS", fontSize = 10.sp, color = Cyan, fontWeight = FontWeight.Bold)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectorText("S1", lap.sector1Ms)
                    SectorText("S2", lap.sector2Ms)
                    SectorText("S3", lap.sector3Ms)
                }
                Text(
                    "${lap.sessionType.uppercase()} · ${formatClock(lap.startedAt)} · " +
                        String.format(java.util.Locale.US, "%.2f L", lap.fuelUsedL),
                    fontSize = 10.sp,
                    color = TextDim,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Mono(
                    formatLapTime(lap.lapTimeMs),
                    size = 18.sp,
                    color = if (lap.valid) TextMain else TextDim,
                    weight = FontWeight.Bold,
                )
                Text("Detail ›", fontSize = 10.sp, color = Cyan)
            }
        }
    }
}

@Composable
private fun SectorText(label: String, value: Long?) {
    Text(
        "$label ${if (value != null) formatLapTime(value) else "–"}",
        fontSize = 10.sp,
        color = if (value != null) TextDim else Color(0xFF4A4A4A),
    )
}

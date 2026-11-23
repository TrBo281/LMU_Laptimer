package com.lmulaptimer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lmulaptimer.data.LapRow
import com.lmulaptimer.data.Repo
import com.lmulaptimer.data.SessionEvent
import com.lmulaptimer.data.SessionRow
import com.lmulaptimer.data.SetupRow
import com.lmulaptimer.data.formatClock
import com.lmulaptimer.data.formatLapTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Session view with stint aggregation, PB progression and the setup that was
 * used. This is the archive counterpart to the live dashboard.
 */
@Composable
fun SessionDetailScreen(
    repo: Repo,
    session: SessionRow,
    laps: List<LapRow>,
    onBack: () -> Unit,
    onOpenLap: (LapRow) -> Unit,
) {
    var setups by remember { mutableStateOf<List<SetupRow>>(emptyList()) }
    var setupId by remember(session.id) { mutableStateOf(session.setupId) }
    var progress by remember(session.id) { mutableStateOf<List<LapRow>>(emptyList()) }
    var compareOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(session.id) {
        setups = withContext(Dispatchers.IO) { repo.setups() }
        progress = withContext(Dispatchers.IO) {
            repo.lapsFor(session.track)
                .filter { it.car == session.car && it.valid && !it.outLap && it.lapTimeMs > 0 }
                .sortedBy { it.startedAt }
                .let { all ->
                    var best = Long.MAX_VALUE
                    all.mapNotNull { lap ->
                        if (lap.lapTimeMs < best) {
                            best = lap.lapTimeMs
                            lap
                        } else null
                    }
                }
                .takeLast(10)
        }
    }

    val valid = laps.filter { it.valid && !it.outLap && it.lapTimeMs > 0 }
    val best = valid.minByOrNull { it.lapTimeMs }
    val average = if (valid.isEmpty()) null else valid.sumOf { it.lapTimeMs } / valid.size
    val consistency = if (valid.size < 2 || average == null || average <= 0) {
        null
    } else {
        val variance = valid.sumOf { (it.lapTimeMs - average) * (it.lapTimeMs - average).toDouble() } /
            valid.size
        100.0 - sqrt(variance) / average * 100.0
    }
    val stints = laps.groupBy { it.stintNumber }.toSortedMap()
    val setup = setups.firstOrNull { it.id == setupId }

    if (compareOpen) {
        CompareScreen(
            repo = repo,
            laps = laps,
            initialA = best,
            initialB = valid.filter { it.id != best?.id }.minByOrNull { it.lapTimeMs },
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Sessions", fontSize = 12.sp, color = Cyan) }
                Column(Modifier.weight(1f)) {
                    Text(
                        session.track.ifBlank { "Unbekannte Strecke" },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMain,
                    )
                    Text(
                        "${session.sessionType.uppercase()} · ${session.car} · ${formatClock(session.startedAt)}",
                        fontSize = 11.sp,
                        color = TextDim,
                    )
                }
                if (valid.size >= 2) {
                    TextButton(onClick = { compareOpen = true }) {
                        Text("Vergleichen", fontSize = 11.sp, color = Cyan)
                    }
                }
            }
        }

        item {
            Panel(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Value("Best", formatLapTime(best?.lapTimeMs), color = Lime)
                    Value("Schnitt", formatLapTime(average), color = Cyan)
                    Value("Runden", valid.size.toString())
                    Value("Stints", stints.size.toString())
                    Value(
                        "Konstanz",
                        consistency?.let { String.format(Locale.US, "%.0f %%", it) } ?: "–",
                        color = when {
                            consistency == null -> TextDim
                            consistency >= 98 -> Lime
                            consistency >= 95 -> Cyan
                            else -> Warn
                        },
                    )
                }
                Text(
                    "Session-ID $session.id",
                    fontSize = 9.sp,
                    color = TextDim,
                )
            }
        }

        item {
            Panel(Modifier.fillMaxWidth()) {
                Label("Setup dieser Session")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SetupPicker(
                        selected = setup,
                        setups = setups,
                        modifier = Modifier.weight(1f),
                    ) { picked ->
                        setupId = picked.id
                        scope.launch {
                            withContext(Dispatchers.IO) { repo.setSessionSetup(session.id, picked.id) }
                        }
                    }
                    Text(
                        if (setup != null) "${setup.name} · ${formatClock(setup.updatedAt)}" else "kein Setup zugeordnet",
                        fontSize = 10.sp,
                        color = TextDim,
                    )
                }
                Text(
                    "Ordnet Session und Setup dauerhaft zu. Der Setup-Payload bleibt im Strategie-Tab bearbeitbar.",
                    fontSize = 10.sp,
                    color = TextDim,
                )
            }
        }

        item {
            Text("Renn-Timeline", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextMain)
        }
        if (session.events.isEmpty()) {
            item { EmptyHint("Keine Ereignisse aufgezeichnet. Die Bridge erzeugt sie ab dem nächsten Sync.") }
        } else {
            item {
                Panel(Modifier.fillMaxWidth()) {
                    session.events.forEach { event -> TimelineEntry(event) }
                    Text(
                        "Zeiten als Offset ab Sessionstart. Ereignisse kommen aus der Bridge (Flaggwechsel, Boxenstopp, Strafe, Schaden, Runden).",
                        fontSize = 9.sp,
                        color = TextDim,
                    )
                }
            }
        }

        item {
            Text("Stints", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextMain)
        }
        if (stints.isEmpty()) {
            item { EmptyHint("Keine Stints aufgezeichnet.") }
        } else {
            items(stints.entries.toList()) { (number, stintLaps) ->
                StintCard(number, stintLaps)
            }
        }

        if (progress.isNotEmpty()) {
            item {
                Text(
                    "PB-Entwicklung · ${session.track} · ${session.car}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMain,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            item {
                Panel(Modifier.fillMaxWidth()) {
                    progress.forEach { lap ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Mono(formatClock(lap.startedAt), size = 10.sp, color = TextDim, modifier = Modifier.weight(1f))
                            Mono("R${lap.lapNumber + 1}", size = 10.sp, color = TextDim)
                            Mono(formatLapTime(lap.lapTimeMs), size = 14.sp, color = Lime, weight = FontWeight.Bold)
                        }
                    }
                    Text(
                        "Nur neue Bestzeiten, chronologisch. So siehst du, ob Setup-Änderungen wirklich etwas gebracht haben.",
                        fontSize = 10.sp,
                        color = TextDim,
                    )
                }
            }
        }

        item {
            Text("Alle Runden", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextMain, modifier = Modifier.padding(top = 6.dp))
        }
        items(laps) { lap ->
            LapLine(lap, best?.id == lap.id, onClick = { onOpenLap(lap) })
        }
    }
}

@Composable
private fun TimelineEntry(event: SessionEvent) {
    val color = when (event.kind) {
        "flag" -> Warn
        "penalty" -> Bad
        "damage" -> Bad
        "pit" -> Cyan
        "lap" -> Lime
        else -> TextMain
    }
    val label = when (event.kind) {
        "session" -> "SESSION"
        "flag" -> "FLAGGE"
        "pit" -> "BOX"
        "penalty" -> "STRAFE"
        "damage" -> "SCHADEN"
        "lap" -> "RUNDE"
        else -> event.kind.uppercase()
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Mono("+${formatLapTime((event.timeS * 1000).roundToLong())}", size = 10.sp, color = TextDim)
        Text(
            label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Text(event.message, fontSize = 11.sp, color = TextMain)
    }
}

@Composable
private fun SetupPicker(
    selected: SetupRow?,
    setups: List<SetupRow>,
    modifier: Modifier = Modifier,
    onSelect: (SetupRow) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { expanded = true }) {
            Text(selected?.name ?: "Setup wählen", fontSize = 12.sp, color = Cyan)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (setups.isEmpty()) {
                DropdownMenuItem(text = { Text("Keine Setups gespeichert", fontSize = 11.sp) }, onClick = { expanded = false })
            }
            setups.forEach { setup ->
                DropdownMenuItem(
                    text = { Text("${setup.name} · ${setup.car}", fontSize = 11.sp) },
                    onClick = {
                        onSelect(setup)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun StintCard(number: Int, laps: List<LapRow>) {
    val valid = laps.filter { it.valid && !it.outLap && it.lapTimeMs > 0 }
    val best = valid.minByOrNull { it.lapTimeMs }
    val average = if (valid.isEmpty()) null else valid.sumOf { it.lapTimeMs } / valid.size
    val fuel = valid.map { it.fuelUsedL }.filter { it > 0 }.average().takeIf { !it.isNaN() }
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Stint $number", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextMain)
                Text(
                    "${laps.size} Runden · ${laps.count { it.inLap }} In-Lap(s)",
                    fontSize = 10.sp,
                    color = TextDim,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Mono(formatLapTime(best?.lapTimeMs), size = 16.sp, color = Lime, weight = FontWeight.Bold)
                Text("Schnitt ${formatLapTime(average)}", fontSize = 10.sp, color = TextDim)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(
                fuel?.let { String.format(Locale.US, "Ø %.2f L/Runde", it) } ?: "Ø Verbrauch –",
                fontSize = 10.sp,
                color = TextDim,
            )
            Text(
                "Letzte Runde ${formatLapTime(laps.lastOrNull()?.lapTimeMs)}",
                fontSize = 10.sp,
                color = TextDim,
            )
        }
    }
}

@Composable
private fun LapLine(lap: LapRow, isBest: Boolean, onClick: () -> Unit) {
    Panel(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row {
                    Text("Runde ${lap.lapNumber + 1}", fontSize = 12.sp, color = TextDim)
                    if (isBest) Text("  PB", fontSize = 10.sp, color = Lime, fontWeight = FontWeight.Bold)
                    if (!lap.valid) Text("  INVALID", fontSize = 10.sp, color = Bad, fontWeight = FontWeight.Bold)
                    if (lap.inLap) Text("  IN", fontSize = 10.sp, color = Warn, fontWeight = FontWeight.Bold)
                    if (lap.outLap) Text("  OUT", fontSize = 10.sp, color = Warn, fontWeight = FontWeight.Bold)
                }
                Text(
                    "Stint ${lap.stintNumber} · ${String.format(Locale.US, "%.2f L", lap.fuelUsedL)} · ${formatClock(lap.startedAt)}",
                    fontSize = 10.sp,
                    color = TextDim,
                )
            }
            Mono(formatLapTime(lap.lapTimeMs), size = 16.sp, color = if (lap.valid) TextMain else TextDim, weight = FontWeight.Bold)
            Text("  ›", fontSize = 14.sp, color = Cyan)
        }
    }
}

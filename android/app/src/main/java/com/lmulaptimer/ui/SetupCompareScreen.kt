package com.lmulaptimer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lmulaptimer.data.SessionRow
import com.lmulaptimer.data.SetupRow
import com.lmulaptimer.data.formatClock

/**
 * A/B view for two setups. Values are parsed from key=value lines in the
 * payload; differing keys are highlighted. No automatic setup advice.
 */
@Composable
fun SetupCompareScreen(
    setups: List<SetupRow>,
    sessions: List<SessionRow> = emptyList(),
    onBack: () -> Unit,
) {
    var left by remember { mutableStateOf<SetupRow?>(null) }
    var right by remember { mutableStateOf<SetupRow?>(null) }
    var combo by remember { mutableStateOf<String?>(null) }
    val combos = remember(sessions) {
        sessions.mapNotNull { session ->
            if (session.car.isBlank() && session.track.isBlank()) null
            else session.track to session.car
        }.distinct()
    }
    val pool = remember(setups, combo) {
        val parts = combo?.split(" · ", limit = 2)
        if (parts == null || parts.size < 2) setups
        else setups.filter { setup ->
            setup.car.equals(parts[1], ignoreCase = true) &&
                (setup.track.isBlank() || setup.track.equals(parts[0], ignoreCase = true))
        }
    }
    val activeLeft = pool.firstOrNull { it.id == left?.id } ?: pool.getOrNull(0)
    val activeRight = pool.firstOrNull { it.id == right?.id && it.id != activeLeft?.id }
        ?: pool.firstOrNull { it.id != activeLeft?.id }
    val leftValues = remember(activeLeft) { parseSetup(activeLeft?.payload.orEmpty()) }
    val rightValues = remember(activeRight) { parseSetup(activeRight?.payload.orEmpty()) }
    val keys = remember(leftValues, rightValues) { (leftValues.keys + rightValues.keys).sorted() }

    var comboMenu by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Strategie", fontSize = 12.sp, color = Cyan) }
            Column(Modifier.weight(1f)) {
                Text("Setup A/B", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextMain)
                Text("Werte aus dem Setup-Payload", fontSize = 11.sp, color = TextDim)
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            Label("Filter: Session / Fahrzeug")
            Box {
                TextButton(onClick = { comboMenu = true }) {
                    Text(
                        combo ?: "Alle Setups",
                        fontSize = 12.sp,
                        color = Cyan,
                    )
                }
                DropdownMenu(expanded = comboMenu, onDismissRequest = { comboMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Alle Setups", fontSize = 11.sp) },
                        onClick = { combo = null; comboMenu = false },
                    )
                    combos.forEach { (track, car) ->
                        val label = "$track · $car"
                        DropdownMenuItem(
                            text = { Text(label, fontSize = 11.sp) },
                            onClick = { combo = label; comboMenu = false },
                        )
                    }
                }
            }
            Text(
                "Zeigt nur Setups passend zu Fahrzeug und Strecke der gewählten Session.",
                fontSize = 10.sp,
                color = TextDim,
            )
        }
        if (pool.size < 2) {
            EmptyHint("Für diese Session/Fahrzeug-Kombination sind weniger als zwei Setups vorhanden.")
            return@Column
        }
        Panel(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SetupChoice("A", activeLeft, pool, Modifier.weight(1f)) { left = it }
                SetupChoice("B", activeRight, pool, Modifier.weight(1f)) { right = it }
            }
            Text(
                "Format im Payload: eine Zeile je Wert, z. B. Flügel=3 oder Reifendruck=200.",
                fontSize = 10.sp,
                color = TextDim,
            )
        }
        if (keys.isEmpty()) {
            EmptyHint("Die gewählten Setups haben noch keinen Payload. Trage ihn im Setup-Log ein.")
        } else {
            Panel(Modifier.fillMaxWidth()) {
                keys.forEach { key ->
                    val a = leftValues[key]
                    val b = rightValues[key]
                    val changed = a != b
                    Column {
                        Text(key, fontSize = 10.sp, color = TextDim, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Mono(a ?: "–", size = 13.sp, color = if (changed) Cyan else TextMain, modifier = Modifier.weight(1f))
                            Mono(b ?: "–", size = 13.sp, color = if (changed) Warn else TextMain, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Text(
                    "Blau/Cyan = nur in A, Orange = nur in B bzw. abweichend. Keine automatische Empfehlung.",
                    fontSize = 10.sp,
                    color = TextDim,
                )
            }
        }
        if (left != null && left?.notes?.isNotBlank() == true || right?.notes?.isNotBlank() == true) {
            Panel(Modifier.fillMaxWidth()) {
                Label("Notizen")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(left?.notes.orEmpty().ifBlank { "–" }, fontSize = 11.sp, color = TextDim, modifier = Modifier.weight(1f))
                    Text(right?.notes.orEmpty().ifBlank { "–" }, fontSize = 11.sp, color = TextDim, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SetupChoice(
    label: String,
    selected: SetupRow?,
    setups: List<SetupRow>,
    modifier: Modifier = Modifier,
    onSelect: (SetupRow) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Label(label)
        Box {
            TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selected?.name ?: "wählen", fontSize = 12.sp, color = Cyan)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                setups.forEach { setup ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                "${setup.name} · ${setup.car} · ${formatClock(setup.updatedAt)}",
                                fontSize = 11.sp,
                            )
                        },
                        onClick = {
                            onSelect(setup)
                            expanded = false
                        },
                    )
                }
            }
        }
        Text(selected?.track.orEmpty(), fontSize = 10.sp, color = TextDim)
    }
}

/** Accepts "key=value" lines; JSON-like braces and commas are ignored. */
private fun parseSetup(payload: String): Map<String, String> {
    if (payload.isBlank()) return emptyMap()
    return payload.lineSequence()
        .flatMap { it.split(',', ';').asSequence() }
        .mapNotNull { raw ->
            val line = raw.trim().trim('{', '}', '[', ']', '"')
            val cut = line.indexOfFirst { it == '=' || it == ':' }
            if (cut <= 0) null else {
                val key = line.substring(0, cut).trim().trim('"')
                val value = line.substring(cut + 1).trim().trim('"')
                if (key.isBlank()) null else key to value
            }
        }
        .toMap()
}

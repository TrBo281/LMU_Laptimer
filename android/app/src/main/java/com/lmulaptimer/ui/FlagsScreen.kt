package com.lmulaptimer.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lmulaptimer.data.Live
import com.lmulaptimer.data.Repo
import kotlinx.coroutines.flow.collect

private data class FlagStyle(val label: String, val color: Color)

@Composable
private fun flagStyle(flag: String): FlagStyle = when (flag.lowercase()) {
    "green" -> FlagStyle("GRÜN", Color(0xFF3DDC84))
    "yellow" -> FlagStyle("GELB", Warn)
    "blue" -> FlagStyle("BLAU", Cyan)
    "red" -> FlagStyle("ROT", Bad)
    "checkered" -> FlagStyle("ZIELFLAGGE", TextMain)
    else -> FlagStyle("KEINE FLAGGE", TextDim)
}

@Composable
fun FlagsScreen(repo: Repo, onOpenSettings: () -> Unit) {
    var live by remember { mutableStateOf<Live?>(null) }
    var reachable by remember { mutableStateOf(false) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(Unit) {
        repo.liveUpdates().collect { snapshot ->
            reachable = snapshot != null
            live = snapshot
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(if (landscape) 12.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(live, reachable)

        if (!reachable) {
            Panel(Modifier.fillMaxWidth()) {
                Text(
                    "Keine Verbindung zur Bridge. Prüfe, ob die Bridge auf dem PC läuft und ob URL und Token stimmen.",
                    fontSize = 13.sp,
                    color = TextDim,
                )
                Button(
                    onClick = onOpenSettings,
                    colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Bg),
                ) {
                    Text("Bridge einrichten")
                }
            }
        } else if (live == null || !live!!.connected) {
            Panel(Modifier.fillMaxWidth()) {
                Text(
                    "Bridge erreichbar, aber LMU liefert noch keine Telemetrie. Sobald du auf der Strecke bist, erscheint die Flagge hier.",
                    fontSize = 13.sp,
                    color = TextDim,
                )
            }
        } else {
            val data = live!!
            val style = flagStyle(data.flag)
            if (landscape) {
                Row(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    FlagHero(data, style, Modifier.weight(1.35f).fillMaxHeight())
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SectorPanel(data)
                        InfoPanel(data)
                    }
                }
            } else {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FlagHero(data, style, Modifier.fillMaxWidth().height(200.dp))
                    SectorPanel(data)
                    InfoPanel(data)
                }
            }
        }
    }
}

@Composable
private fun Header(live: Live?, reachable: Boolean) {
    val state = when {
        live?.connected == true -> "LIVE"
        reachable -> "WARTE"
        else -> "OFFLINE"
    }
    val color = when {
        live?.connected == true -> Lime
        reachable -> Warn
        else -> Bad
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Flaggen", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextMain)
            Text(
                text = listOfNotNull(
                    live?.track?.takeIf { it.isNotBlank() },
                    live?.car?.takeIf { it.isNotBlank() },
                    live?.sessionType?.takeIf { it.isNotBlank() }?.uppercase(),
                ).joinToString(" · ").ifBlank { "Warte auf LMU" },
                fontSize = 12.sp,
                color = TextDim,
            )
        }
        Pill(state, color)
    }
}

@Composable
private fun FlagHero(data: Live, style: FlagStyle, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(style.color.copy(alpha = 0.16f))
            .border(2.dp, style.color.copy(alpha = 0.65f), RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = style.label,
                fontSize = if (style.label.length > 7) 44.sp else 68.sp,
                fontWeight = FontWeight.Black,
                color = style.color,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Text(
                text = data.flagDetail.ifBlank { "LMU Flaggenstatus" },
                fontSize = 16.sp,
                color = style.color.copy(alpha = 0.9f),
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (data.blueFlag) Pill("BLAU FÜR DICH", Cyan)
                if (data.underYellow) Pill("UNTER GELB", Warn)
                if (data.flag == "green") Pill("STRECKE FREI", Lime)
            }
        }
    }
}

@Composable
private fun SectorPanel(data: Live) {
    Panel(Modifier.fillMaxWidth()) {
        Label("Sektoren")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { index ->
                val yellow = data.sectorFlags.getOrElse(index) { 0 } != 0
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (yellow) Warn.copy(alpha = 0.22f) else Surface2)
                        .border(
                            1.dp,
                            if (yellow) Warn.copy(alpha = 0.8f) else Line,
                            RoundedCornerShape(10.dp),
                        )
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("S${index + 1}", fontSize = 11.sp, color = TextDim, fontWeight = FontWeight.Bold)
                        Text(
                            if (yellow) "GELB" else "FREI",
                            fontSize = 12.sp,
                            color = if (yellow) Warn else TextMain,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoPanel(data: Live) {
    Panel(Modifier.fillMaxWidth()) {
        Label("LMU-Status")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(phaseLabel(data.gamePhase), if (data.gamePhase == 6) Warn else TextDim)
            Pill(yellowStateLabel(data.yellowFlagState), TextDim)
        }
        Text(
            "Game Phase ${data.gamePhase} · Yellow Flag State ${data.yellowFlagState}",
            fontSize = 11.sp,
            color = TextDim,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(if (data.blueFlag) "BLAU AKTIV" else "KEIN BLAU", if (data.blueFlag) Cyan else TextDim)
            Pill(if (data.underYellow) "UNTER GELB" else "NICHT UNTER GELB", if (data.underYellow) Warn else TextDim)
        }
        Text(
            "Gelb lokal kommt aus den Sektorflaggen, FCY aus der Game Phase, Blau aus mFlag. " +
                "Rot und Zielflagge werden aus dem Session-Status abgeleitet.",
            fontSize = 10.sp,
            color = TextDim,
        )
    }
}

private fun phaseLabel(phase: Int): String = when (phase) {
    0 -> "GARAGE"
    1 -> "WARMUP"
    2 -> "GRID"
    3 -> "FORMATION"
    4 -> "COUNTDOWN"
    5 -> "GRÜN"
    6 -> "FCY"
    7 -> "GESTOPPT"
    8 -> "BEENDET"
    9 -> "PAUSE"
    else -> "PHASE $phase"
}

private fun yellowStateLabel(state: Int): String = when (state) {
    -1 -> "UNGÜLTIG"
    0 -> "KEIN GELB"
    1 -> "AUSSTEHEND"
    2 -> "BOX ZU"
    3 -> "PIT LEAD LAP"
    4 -> "BOX OFFEN"
    5 -> "LETZTE RUNDE"
    6 -> "FREIGABE"
    7 -> "ABBRUCH"
    else -> "STATUS $state"
}

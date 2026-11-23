package com.lmulaptimer.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lmulaptimer.data.Live
import com.lmulaptimer.data.Repo
import com.lmulaptimer.data.TimingEntry
import com.lmulaptimer.data.Tyre
import com.lmulaptimer.data.formatDelta
import com.lmulaptimer.data.formatLapTime
import com.lmulaptimer.data.formatSeconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private data class Alert(val id: String, val text: String, val color: Color)

private data class WeatherSample(val rain: Double, val wetness: Double, val grip: Int)

private data class TyreTargets(
    val pressureKpa: Double,
    val maxTempC: Double,
    val wearWarnPct: Double,
)

@Composable
fun LiveScreen(repo: Repo, onOpenSettings: () -> Unit) {
    var live by remember { mutableStateOf<Live?>(null) }
    var reachable by remember { mutableStateOf(false) }
    var avgFuel by remember { mutableStateOf<Double?>(null) }
    var avgLap by remember { mutableStateOf<Double?>(null) }
    var weatherHistory by remember { mutableStateOf<List<WeatherSample>>(emptyList()) }
    val tyreTargets = remember {
        TyreTargets(repo.tyreTargetPressureKpa, repo.tyreMaxTempC, repo.tyreWearWarnPct)
    }
    var reference by remember { mutableStateOf<ReferenceTrace?>(null) }
    var referenceStatus by remember { mutableStateOf("") }
    val context = LocalContext.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(Unit) {
        var lastWeatherAt = 0L
        var previousSnapshot: Live? = null
        repo.liveUpdates().collect { snapshot ->
            reachable = snapshot != null
            live = snapshot
            if (snapshot != null) {
                val events = spotterEvents(previousSnapshot, snapshot)
                if (repo.liveNotifyEnabled) LiveNotifier.update(context, snapshot)
                if (events.isNotEmpty() && repo.audioEnabled) {
                    events.take(2).forEach { AudioSignal.beep(repo.audioVolume) }
                }
                previousSnapshot = snapshot
                // The socket pushes up to 30 Hz; keep the weather history on a 30 s
                // window by sampling it at most every 500 ms.
                val now = System.currentTimeMillis()
                if (now - lastWeatherAt >= 500L) {
                    lastWeatherAt = now
                    weatherHistory = (
                        weatherHistory + WeatherSample(snapshot.rain, snapshot.wetness, snapshot.grip)
                    ).takeLast(60)
                }
            }
        }
    }

    val track = live?.track.orEmpty()
    val car = live?.car.orEmpty()
    val wet = live?.let { it.rain > 0.05 || it.wetness > 0.05 } ?: false
    LaunchedEffect(track) {
        avgFuel = if (track.isBlank()) null else withContext(Dispatchers.IO) { repo.averageFuel(track) }
        avgLap = if (track.isBlank()) null else withContext(Dispatchers.IO) { repo.averageLap(track) }
    }
    LaunchedEffect(track, car, wet) {
        reference = null
        referenceStatus = ""
        if (track.isBlank()) return@LaunchedEffect
        val lap = withContext(Dispatchers.IO) { repo.referenceLap(track, car, wet) }
        if (lap == null) {
            referenceStatus = "Keine Referenzrunde im Archiv. Öffne eine Runde unter Zeiten und setze sie als Referenz."
            return@LaunchedEffect
        }
        val payload = withContext(Dispatchers.IO) { repo.lapSamplesCached(lap.id)?.payload }
        val trace = ReferenceTrace.from(lap, payload)
        if (trace == null) {
            referenceStatus = "Für die Referenzrunde fehlt die Telemetrie auf der Bridge."
        } else {
            reference = trace
        }
    }

    val data = live
    val ownDelta = data?.takeIf { !it.inPits }?.let { snapshot ->
        reference?.deltaAt(snapshot.lapDistanceM, snapshot.currentLapS)
    }
    val consumption = data?.fuelPerLapStint?.takeIf { it > 0 } ?: avgFuel
    val lapSeconds = data?.lastLapS?.takeIf { it > 0 }
        ?: data?.bestLapS?.takeIf { it > 0 }
        ?: avgLap?.div(1000.0)
    val lapsRemaining = if (
        data != null && data.sessionTimeRemainingS > 0 && lapSeconds != null && lapSeconds > 0
    ) {
        ceil(data.sessionTimeRemainingS / lapSeconds).toInt()
    } else {
        null
    }
    val fuelRange = if (consumption != null && consumption > 0 && data != null) {
        data.fuelL / consumption
    } else {
        null
    }
    val alerts = if (data != null) buildAlerts(data, fuelRange, lapsRemaining) else emptyList()
    val livePlan = data?.let {
        computeLivePlan(
            fuelL = it.fuelL,
            consumptionL = consumption ?: 0.0,
            lapsRemaining = lapsRemaining,
            underYellow = it.underYellow,
            inPits = it.inPits,
        )
    }
    val alertKey = alerts.joinToString("|") { it.id }
    var previousAlerts by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(alertKey) {
        val current = alerts.map { it.id }.toSet()
        if (current.isNotEmpty() && previousAlerts.isNotEmpty() && current != previousAlerts) {
            vibrate(context)
        }
        previousAlerts = current
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(if (landscape) 12.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(data, reachable)

        if (!reachable) {
            Panel {
                Text(
                    "Keine Verbindung zur Bridge. Prüfe, ob die Bridge auf dem PC läuft und ob URL und Token stimmen.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onOpenSettings,
                    colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Bg),
                ) {
                    Text("Bridge einrichten")
                }
            }
        }

        if (data != null) {
            if (landscape) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SpeedPanel(data)
                        OwnDeltaPanel(ownDelta, reference, referenceStatus, data.inPits)
                        TyrePanel(data, tyreTargets)
                        InputsPanel(data)
                        TechPanel(data)
                    }
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AlertsPanel(alerts)
                        BoxPanel(data, consumption, lapsRemaining)
                        livePlan?.let { LivePlanPanel(it) }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FuelPanel(data, consumption, Modifier.weight(1f))
                            EnergyPanel(data, Modifier.weight(1f))
                        }
                        TimingPanel(data)
                        StatusPanel(data)
                        WeatherPanel(data, weatherHistory)
                        TimingTower(data.timing)
                    }
                }
            } else {
                SpeedPanel(data)
                OwnDeltaPanel(ownDelta, reference, referenceStatus, data.inPits)
                AlertsPanel(alerts)
                TyrePanel(data, tyreTargets)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FuelPanel(data, consumption, Modifier.weight(1f))
                    EnergyPanel(data, Modifier.weight(1f))
                }
                TimingPanel(data)
                BoxPanel(data, consumption, lapsRemaining)
                livePlan?.let { LivePlanPanel(it) }
                StatusPanel(data)
                WeatherPanel(data, weatherHistory)
                TimingTower(data.timing)
                InputsPanel(data)
                TechPanel(data)
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
            Text(
                text = live?.track?.ifBlank { "Keine Strecke" } ?: "LMU Laptimer",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextMain,
            )
            Text(
                text = listOfNotNull(
                    live?.car?.takeIf { it.isNotBlank() },
                    live?.carClass?.takeIf { it.isNotBlank() },
                ).joinToString(" · ").ifBlank { "Warte auf Telemetrie" },
                fontSize = 11.sp,
                color = TextDim,
            )
        }
        Pill(state, color)
    }
}

@Composable
private fun SpeedPanel(data: Live) {
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Label("Speed")
                Row(verticalAlignment = Alignment.Bottom) {
                    Mono("${data.speedKmh.roundToInt()}", size = 58.sp, weight = FontWeight.Bold, color = TextMain)
                    Text("km/h", color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Label("Gang")
                Mono("${data.gear}", size = 44.sp, weight = FontWeight.Bold, color = Lime)
                Mono("${data.rpm.roundToInt()} rpm", size = 11.sp, color = TextDim)
            }
        }
        if (data.trackLengthM > 0.0) {
            LevelBar(
                fraction = (data.lapDistanceM / data.trackLengthM).toFloat(),
                color = Cyan,
                height = 4.dp,
            )
        }
    }
}

@Composable
private fun OwnDeltaPanel(
    delta: Double?,
    reference: ReferenceTrace?,
    status: String,
    inPits: Boolean,
) {
    val color = when {
        delta == null -> TextDim
        delta < -0.001 -> Lime
        delta > 0.001 -> Bad
        else -> TextDim
    }
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("Eigenes Delta")
                if (reference != null) {
                    Text(
                        "Runde ${reference.lapNumber + 1} · ${formatLapTime(reference.lapTimeMs)} · " +
                            reference.car.ifBlank { "Referenz" },
                        fontSize = 11.sp,
                        color = TextDim,
                    )
                }
            }
            Mono(
                text = if (delta == null) "–" else formatDelta(delta),
                size = 30.sp,
                color = color,
                weight = FontWeight.Bold,
            )
        }
        if (delta == null) {
            Text(
                if (inPits) {
                    "In der Box – Delta pausiert."
                } else {
                    status.ifBlank { "Delta erscheint, sobald die aktuelle Runde die Referenzstrecke erreicht." }
                },
                fontSize = 11.sp,
                color = TextDim,
            )
        } else {
            Text("Positiv = langsamer als deine Referenz.", fontSize = 10.sp, color = TextDim)
        }
    }
}

@Composable
private fun TyrePanel(data: Live, targets: TyreTargets) {
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Reifen", Modifier.weight(1f))
            val compound = data.tyres.firstOrNull()?.compound?.takeIf { it.isNotBlank() }
            if (compound != null) Pill(compound.uppercase(), Cyan)
        }
        if (data.tyres.isEmpty()) {
            Text(
                "Noch keine Reifendaten – LMU liefert sie erst auf der Strecke.",
                fontSize = 12.sp,
                color = TextDim,
            )
            return@Panel
        }
        val labels = listOf("VL", "VR", "HL", "HR")
        data.tyres.chunked(2).forEachIndexed { rowIndex, wheels ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                wheels.forEachIndexed { columnIndex, tyre ->
                    TyreCell(
                        labels.getOrElse(rowIndex * 2 + columnIndex) { "?" },
                        tyre,
                        targets,
                        Modifier.weight(1f),
                    )
                }
                if (wheels.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Text(
            "Druck kPa · Temp außen/mitte/innen · Verschleiß · Bremse",
            fontSize = 10.sp,
            color = TextDim,
        )
    }
}

@Composable
private fun TyreCell(label: String, tyre: Tyre, targets: TyreTargets, modifier: Modifier = Modifier) {
    val pressureDelta = tyre.pressureKpa - targets.pressureKpa
    val pressureColor = when {
        tyre.pressureKpa <= 0.0 -> TextDim
        kotlin.math.abs(pressureDelta) <= 8.0 -> Lime
        kotlin.math.abs(pressureDelta) <= 20.0 -> Warn
        else -> Bad
    }
    val hottest = maxOf(tyre.tempLeftC, tyre.tempCenterC, tyre.tempRightC)
    val wearPct = (tyre.wear * 100).roundToInt().coerceIn(0, 100)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                fontSize = 10.sp,
                color = TextDim,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(24.dp),
            )
            Mono(
                String.format(Locale.US, "%.0f", tyre.pressureKpa),
                size = 16.sp,
                color = pressureColor,
                weight = FontWeight.Bold,
            )
            Text(" kPa", fontSize = 9.sp, color = TextDim)
        }
        Text(
            String.format(
                Locale.US,
                "%.0f / %.0f / %.0f °C",
                tyre.tempLeftC,
                tyre.tempCenterC,
                tyre.tempRightC,
            ),
            fontSize = 10.sp,
            color = when {
                hottest > targets.maxTempC -> Bad
                hottest > targets.maxTempC - 8.0 -> Warn
                else -> TextDim
            },
        )
        Text(
            "Ziel ${targets.pressureKpa.roundToInt()} kPa (${if (pressureDelta >= 0) "+" else ""}${pressureDelta.roundToInt()})",
            fontSize = 9.sp,
            color = pressureColor,
        )
        Text(
            "Verschleiß $wearPct %",
            fontSize = 10.sp,
            color = if (wearPct >= targets.wearWarnPct) Warn else TextDim,
        )
        Text(
            String.format(Locale.US, "Bremse %.0f °C", tyre.brakeTempC),
            fontSize = 10.sp,
            color = if (tyre.brakeTempC >= 700.0) Bad else TextDim,
        )
    }
}

@Composable
private fun FuelPanel(data: Live, consumption: Double?, modifier: Modifier = Modifier) {
    val capacity = if (data.fuelCapacityL > 0) data.fuelCapacityL else 1.0
    Panel(modifier) {
        Label("Fuel")
        Row(verticalAlignment = Alignment.Bottom) {
            Mono(String.format(Locale.US, "%.1f", data.fuelL), size = 24.sp, weight = FontWeight.Bold)
            Text("L", color = TextDim, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        }
        LevelBar((data.fuelL / capacity).toFloat(), Lime)
        val range = if (consumption != null && consumption > 0) data.fuelL / consumption else null
        Text(
            text = if (range != null) {
                String.format(Locale.US, "≈ %.1f Runden · %.2f L/Rd", range, consumption)
            } else {
                "kein Verbrauchswert"
            },
            fontSize = 11.sp,
            color = TextDim,
        )
        if (data.fuelPerLapStint > 0) {
            Text("Live-Verbrauch aus dem Stint", fontSize = 10.sp, color = Lime.copy(alpha = 0.8f))
        } else if (range != null) {
            Text("Verbrauch aus dem Archiv", fontSize = 10.sp, color = TextDim)
        }
    }
}

@Composable
private fun EnergyPanel(data: Live, modifier: Modifier = Modifier) {
    Panel(modifier) {
        Label("Virtual Energy")
        Row(verticalAlignment = Alignment.Bottom) {
            Mono("${(data.virtualEnergy * 100).roundToInt()}", size = 24.sp, weight = FontWeight.Bold)
            Text("%", color = TextDim, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        }
        LevelBar(data.virtualEnergy.toFloat(), Cyan)
        if (data.vePerLapStint > 0) {
            val range = data.virtualEnergy / data.vePerLapStint
            Text(
                String.format(Locale.US, "≈ %.1f Runden · %.2f %% je Runde", range, data.vePerLapStint * 100),
                fontSize = 10.sp,
                color = TextDim,
            )
        } else {
            Text("VE-Reserve laut Spiel", fontSize = 11.sp, color = TextDim)
        }
    }
}

@Composable
private fun TimingPanel(data: Live) {
    val deltaColor = when {
        data.deltaBestS < -0.001 -> Lime
        data.deltaBestS > 0.001 -> Bad
        else -> TextDim
    }
    Panel(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Value("Aktuelle Runde", formatSeconds(data.currentLapS), modifier = Modifier.weight(1f))
            Value("Letzte", formatSeconds(data.lastLapS), modifier = Modifier.weight(1f))
            Column {
                Label("LMU-Delta")
                Mono(formatDelta(data.deltaBestS), size = 22.sp, color = deltaColor, weight = FontWeight.Bold)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (data.bestLapS > 0) Value("Best", formatSeconds(data.bestLapS), color = Lime)
            Pill(
                if (data.lapValid) "RUNDE GÜLTIG" else "RUNDE INVALID",
                if (data.lapValid) Lime else Bad,
            )
        }
    }
}

@Composable
private fun BoxPanel(data: Live, consumption: Double?, lapsRemaining: Int?) {
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Stint & Boxenfenster", Modifier.weight(1f))
            if (data.inPits) Pill("IN DER BOX", Warn)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Value("Stint-Zeit", formatDuration(data.stintElapsedS))
            Value("Stint-Runden", "${data.stintLaps}")
            Value("Stint", "#${data.stintNumber}")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Value("Stopps", "${data.pitStops}")
            if (data.lastPitS > 0) Value("Letzter Stopp", formatDuration(data.lastPitS))
            if (lapsRemaining != null) Value("Rennen", "$lapsRemaining Rd")
        }
        if (consumption == null || consumption <= 0) {
            Text(
                "Noch kein Verbrauchswert. Sobald eine Runde gespeichert ist, rechnet die App Reichweite und Boxenfenster.",
                fontSize = 12.sp,
                color = TextDim,
            )
            return@Panel
        }
        val fuelRange = data.fuelL / consumption
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Value("Reichweite", String.format(Locale.US, "%.1f", fuelRange), "Runden")
            Value("Verbrauch", String.format(Locale.US, "%.2f", consumption), "L/Rd")
        }
        val remainingLaps = lapsRemaining
        val fuelToEnd = remainingLaps?.let { it * consumption }
        if (remainingLaps != null && fuelToEnd != null) {
            if (fuelRange < remainingLaps) {
                val window = floor(fuelRange).toInt().coerceAtLeast(0)
                val missing = (fuelToEnd - data.fuelL).coerceAtLeast(0.0)
                val savePerLap = missing / remainingLaps.coerceAtLeast(1)
                Text(
                    "BOX in spätestens $window Runden – es fehlen " +
                        String.format(Locale.US, "%.1f L.", missing),
                    fontSize = 12.sp,
                    color = Warn,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    String.format(Locale.US, "Sparziel: %.2f L pro Runde.", savePerLap),
                    fontSize = 11.sp,
                    color = Warn,
                )
            } else {
                Text(
                    String.format(Locale.US, "Sprit reicht bis Rennende (Reserve %.1f L).", data.fuelL - fuelToEnd),
                    fontSize = 12.sp,
                    color = Lime,
                )
            }
        } else if (data.sessionTimeRemainingS <= 0) {
            Text(
                "Restzeit liefert LMU nur in Sessions mit Zeitlimit; der Stint-Timer läuft trotzdem.",
                fontSize = 11.sp,
                color = TextDim,
            )
        }
        if (data.vePerLapStint > 0) {
            val veRange = data.virtualEnergy / data.vePerLapStint
            Text(
                String.format(
                    Locale.US,
                    "VE reicht ≈ %.1f Runden (%.2f %% je Runde).",
                    veRange,
                    data.vePerLapStint * 100,
                ),
                fontSize = 11.sp,
                color = TextDim,
            )
        }
    }
}

@Composable
private fun AlertsPanel(alerts: List<Alert>) {
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Alarme", Modifier.weight(1f))
            if (alerts.isEmpty()) Pill("OK", Lime) else Pill("${alerts.size} AKTIV", Warn)
        }
        if (alerts.isEmpty()) {
            Text("Keine Schwellwerte verletzt.", fontSize = 12.sp, color = TextDim)
        } else {
            alerts.forEach { alert ->
                Text(
                    "• ${alert.text}",
                    fontSize = 12.sp,
                    color = alert.color,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun StatusPanel(data: Live) {
    Panel(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(data.sessionType.ifBlank { "SESSION" }.uppercase(), Cyan)
            if (data.position > 0) Pill("P${data.position}/${data.numVehicles}", TextMain)
            Pill(if (data.rain > 0.05) "REGEN" else "TROCKEN", if (data.rain > 0.05) Warn else TextDim)
            Pill("${data.airTempC.roundToInt()}° LUFT", TextDim)
            Pill("${data.trackTempC.roundToInt()}° TRACK", TextDim)
        }
    }
}

@Composable
private fun InputsPanel(data: Live) {
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("THR", fontSize = 10.sp, color = TextDim, modifier = Modifier.width(32.dp))
            LevelBar(data.throttle.toFloat(), Lime, height = 6.dp, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Mono("${(data.throttle * 100).roundToInt()}%", size = 11.sp, color = TextDim)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("BRK", fontSize = 10.sp, color = TextDim, modifier = Modifier.width(32.dp))
            LevelBar(data.brake.toFloat(), Bad, height = 6.dp, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Mono("${(data.brake * 100).roundToInt()}%", size = 11.sp, color = TextDim)
        }
    }
}

@Composable
private fun TechPanel(data: Live) {
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Fahrzeugtechnik & Abstände", Modifier.weight(1f))
            if (data.penalties > 0) Pill("${data.penalties} STRAFE(N)", Bad)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Value("TC", if (data.tc > 0) data.tc.toString() else "–")
            Value("ABS", if (data.abs > 0) data.abs.toString() else "–")
            Value("Bremsbalance", String.format(Locale.US, "%.1f", data.brakeBias), "%")
            Value("Batterie", String.format(Locale.US, "%.0f", data.batteryCharge * 100), "%")
            Value("Regen", String.format(Locale.US, "%.0f", data.regen * 100), "%")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Value("Gap vor", data.gapAheadS?.let { formatDelta(-it) } ?: "–")
            Value("Gap hinter", data.gapBehindS?.let { formatDelta(it) } ?: "–")
            if (data.damage.isNotEmpty()) {
                Value("Schaden", String.format(Locale.US, "%.0f", data.damage.sum() * 100), "%")
            }
        }
        Text(
            "Rohwerte aus LMU. TC/ABS-Stufen und Bremsbalance sind fahrzeugspezifisch; ohne Rig nicht validiert.",
            fontSize = 10.sp,
            color = TextDim,
        )
    }
}

@Composable
private fun LivePlanPanel(plan: LivePlan) {
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Live-Plan", Modifier.weight(1f))
            if (plan.underYellow) Pill("FCY-PLAN", Warn)
        }
        if (plan.fuelNeededL == null || plan.lapsToGo == null) {
            Text(plan.message, fontSize = 12.sp, color = TextDim)
            return@Panel
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Value("Bis Ziel", "${plan.lapsToGo} Rd")
            Value("Nötig", String.format(Locale.US, "%.1f", plan.fuelNeededL), "L")
            Value(
                "Reserve",
                String.format(Locale.US, "%+.1f", plan.fuelDeltaL ?: 0.0),
                "L",
                color = if ((plan.fuelDeltaL ?: 0.0) >= 0) Lime else Bad,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Value(
                "Zielverbrauch",
                String.format(Locale.US, "%.2f", plan.targetPerLapL ?: 0.0),
                "L/Rd",
                color = Cyan,
            )
            Value("Stopp in", planLapsText(plan.stopInLaps))
            if ((plan.savingPerLapL ?: 0.0) > 0.0) {
                Value(
                    "Sparen",
                    String.format(Locale.US, "%.2f", plan.savingPerLapL),
                    "L/Rd",
                    color = Warn,
                )
            }
        }
        Text(
            plan.message,
            fontSize = 12.sp,
            color = if ((plan.fuelDeltaL ?: 0.0) >= 0) Lime else Bad,
            fontWeight = FontWeight.SemiBold,
        )
        if (plan.underYellow) {
            Text(
                "FCY: Verbrauch mit 85 % gerechnet – Boxenstopp jetzt prüfen.",
                fontSize = 10.sp,
                color = Warn,
            )
        }
    }
}

@Composable
private fun WeatherPanel(data: Live, history: List<WeatherSample>) {
    val first = history.firstOrNull()
    val trend = when {
        first == null -> "–"
        data.wetness - first.wetness > 0.02 -> "nasser"
        first.wetness - data.wetness > 0.02 -> "trockener"
        else -> "stabil"
    }
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Wetter", Modifier.weight(1f))
            Pill(if (data.rain > 0.05) "REGEN" else "TROCKEN", if (data.rain > 0.05) Warn else TextDim)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Value("Luft", String.format(Locale.US, "%.1f", data.airTempC), "°C")
            Value("Strecke", String.format(Locale.US, "%.1f", data.trackTempC), "°C")
            Value("Nässe", "${(data.wetness * 100).roundToInt()}", "%")
            Value("Grip", if (data.grip > 0) "${data.grip}" else "–")
        }
        Text(
            "30-Sekunden-Trend: $trend · Regenintensität ${(data.rain * 100).roundToInt()} %.",
            fontSize = 11.sp,
            color = TextDim,
        )
    }
}

@Composable
private fun TimingTower(entries: List<TimingEntry>) {
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Timing Tower", Modifier.weight(1f))
            if (entries.isEmpty()) Pill("WARTE", TextDim) else Pill("${entries.size} AUTOS", Cyan)
        }
        if (entries.isEmpty()) {
            Text(
                "LMU liefert die Gegnerdaten erst, wenn du auf der Strecke bist.",
                fontSize = 12.sp,
                color = TextDim,
            )
            return@Panel
        }
        entries.take(10).forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Mono(
                    "P${entry.position}",
                    size = 11.sp,
                    color = if (entry.isPlayer) Lime else TextDim,
                    modifier = Modifier.width(30.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.name.ifBlank { entry.carClass.ifBlank { "Auto" } },
                        fontSize = 11.sp,
                        color = if (entry.isPlayer) Lime else TextMain,
                        maxLines = 1,
                    )
                    Text(entry.carClass.ifBlank { "–" }, fontSize = 9.sp, color = TextDim)
                }
                Mono(
                    if (entry.lastLapS > 0) formatSeconds(entry.lastLapS) else "–",
                    size = 11.sp,
                    color = TextMain,
                )
                if (entry.inPits) {
                    Text(" PIT", fontSize = 9.sp, color = Warn, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(
            "Best effort aus dem Scoring-Block – Reihenfolge und Abstände können je nach LMU-Build abweichen.",
            fontSize = 9.sp,
            color = TextDim,
        )
    }
}

@Composable
private fun buildAlerts(data: Live, fuelRange: Double?, lapsRemaining: Int?): List<Alert> {
    val alerts = mutableListOf<Alert>()
    val wheelNames = listOf("VL", "VR", "HL", "HR")
    if (data.fuelL > 0.0 && data.fuelL < 10.0) {
        alerts += Alert(
            "fuel-low",
            String.format(Locale.US, "Fuel niedrig: %.1f L", data.fuelL),
            Bad,
        )
    }
    if (fuelRange != null && lapsRemaining != null && fuelRange < lapsRemaining) {
        alerts += Alert(
            "fuel-end",
            String.format(
                Locale.US,
                "Sprit reicht nicht bis Rennende (%.1f statt %d Runden)",
                fuelRange,
                lapsRemaining,
            ),
            Bad,
        )
    }
    if (data.vePerLapStint > 0 && data.virtualEnergy < data.vePerLapStint * 2) {
        alerts += Alert(
            "ve-low",
            String.format(Locale.US, "VE niedrig: %.1f %%", data.virtualEnergy * 100),
            Warn,
        )
    }
    data.tyres.forEachIndexed { index, tyre ->
        val name = wheelNames.getOrElse(index) { "Rad ${index + 1}" }
        if (tyre.pressureKpa > 0.0 && tyre.pressureKpa < 150.0) {
            alerts += Alert(
                "pressure-low-$index",
                String.format(Locale.US, "Druck %s zu niedrig: %.0f kPa", name, tyre.pressureKpa),
                Warn,
            )
        } else if (tyre.pressureKpa > 230.0) {
            alerts += Alert(
                "pressure-high-$index",
                String.format(Locale.US, "Druck %s zu hoch: %.0f kPa", name, tyre.pressureKpa),
                Warn,
            )
        }
        if (tyre.brakeTempC >= 700.0) {
            alerts += Alert(
                "brake-$index",
                String.format(Locale.US, "Bremse %s heiß: %.0f °C", name, tyre.brakeTempC),
                Bad,
            )
        }
    }
    return alerts.take(6)
}

private fun vibrate(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        val legacy = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        legacy
    }
    vibrator?.vibrate(VibrationEffect.createOneShot(140, VibrationEffect.DEFAULT_AMPLITUDE))
}

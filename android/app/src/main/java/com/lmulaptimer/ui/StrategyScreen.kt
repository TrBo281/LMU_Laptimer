package com.lmulaptimer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lmulaptimer.data.Repo
import com.lmulaptimer.data.SessionRow
import com.lmulaptimer.data.SetupRow
import com.lmulaptimer.data.StrategyPreset
import com.lmulaptimer.data.formatClock
import com.lmulaptimer.data.formatLapTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

data class Stint(
    val laps: Int,
    val fuelNeededL: Double,
    val veNeededPct: Double,
    val limitedBy: String,
)

data class StrategyResult(
    val totalLaps: Int,
    val stints: List<Stint>,
    val totalTimeS: Double,
    val error: String? = null,
    val veLimited: Boolean = false,
)

data class PitStopResult(
    val refuelS: Double,
    val stationaryS: Double,
    val totalLossS: Double,
)

data class DriverStint(val driver: Int, val laps: Int, val driveS: Double)

data class EndurancePlan(
    val totalLaps: Int,
    val stints: List<DriverStint>,
    val stops: Int,
    val maxDriveS: Double,
    val overLimit: Boolean,
    val error: String? = null,
)

fun computeEndurance(
    raceMinutes: Double,
    lapSeconds: Double,
    stintLaps: Int,
    drivers: Int,
    maxDriverMinutes: Double,
): EndurancePlan {
    if (raceMinutes <= 0 || lapSeconds <= 0 || stintLaps < 1 || drivers < 1) {
        return EndurancePlan(
            0,
            emptyList(),
            0,
            0.0,
            false,
            "Bitte Renndauer, Rundenzeit, Stint-Länge und Fahrerzahl ausfüllen.",
        )
    }
    val totalLaps = ceil(raceMinutes * 60.0 / lapSeconds).toInt()
    val stints = mutableListOf<DriverStint>()
    var remaining = totalLaps
    var index = 0
    var guard = 0
    while (remaining > 0 && guard++ < 500) {
        val laps = minOf(remaining, stintLaps)
        stints += DriverStint((index % drivers) + 1, laps, laps * lapSeconds)
        remaining -= laps
        index += 1
    }
    val longest = stints.groupBy { it.driver }.values.maxOfOrNull { group ->
        group.sumOf { it.driveS }
    } ?: 0.0
    val overLimit = maxDriverMinutes > 0 && longest > maxDriverMinutes * 60.0 + 1.0
    return EndurancePlan(totalLaps, stints, (stints.size - 1).coerceAtLeast(0), longest, overLimit)
}

fun computeStrategy(
    raceMinutes: Double,
    lapSeconds: Double,
    fuelPerLap: Double,
    tankL: Double,
    startFuelL: Double,
    vePerLapPct: Double,
    startVePct: Double,
    refuelRateLps: Double,
    pitLossS: Double,
    tyreChangeS: Double,
    driverChangeS: Double,
    tyreChange: Boolean,
): StrategyResult {
    if (raceMinutes <= 0 || lapSeconds <= 0 || fuelPerLap <= 0 || tankL <= 0) {
        return StrategyResult(0, emptyList(), 0.0, "Bitte Renndauer, Rundenzeit, Verbrauch und Tank ausfüllen.")
    }
    val totalLaps = ceil(raceMinutes * 60.0 / lapSeconds).toInt()
    var remaining = totalLaps
    var capacity = if (startFuelL > 0) startFuelL else tankL
    var veAvailable = if (startVePct > 0) startVePct else 100.0
    val stints = mutableListOf<Stint>()
    var guard = 0
    while (remaining > 0 && guard++ < 500) {
        val fuelLaps = floor(capacity / fuelPerLap).toInt()
        val veLaps = if (vePerLapPct > 0) {
            floor(veAvailable / vePerLapPct).toInt()
        } else {
            Int.MAX_VALUE
        }
        val maxLaps = minOf(fuelLaps, veLaps)
        if (maxLaps < 1) {
            val reason = if (veLaps < 1) "Die VE reicht nicht für eine Runde." else "Der Tank reicht nicht für eine Runde."
            return StrategyResult(totalLaps, emptyList(), 0.0, reason)
        }
        val laps = minOf(remaining, maxLaps)
        val limitedBy = when {
            veLaps < fuelLaps -> "VE"
            fuelLaps < veLaps -> "Fuel"
            else -> "Fuel/VE"
        }
        stints += Stint(
            laps = laps,
            fuelNeededL = laps * fuelPerLap,
            veNeededPct = laps * vePerLapPct,
            limitedBy = limitedBy,
        )
        remaining -= laps
        capacity = tankL
        veAvailable = 100.0
    }
    if (stints.isEmpty()) {
        return StrategyResult(0, emptyList(), 0.0, "Keine gültige Stint-Planung.")
    }
    var total = totalLaps * lapSeconds
    for (index in 0 until stints.size - 1) {
        val refuel = stints[index + 1].fuelNeededL / refuelRateLps.coerceAtLeast(0.1)
        val tyre = if (tyreChange) tyreChangeS else 0.0
        total += pitLossS + tyre + driverChangeS + refuel
    }
    return StrategyResult(
        totalLaps = totalLaps,
        stints = stints,
        totalTimeS = total,
        veLimited = stints.any { it.limitedBy == "VE" },
    )
}

fun computePitStop(
    fuelToAddL: Double,
    refuelRateLps: Double,
    tyreChange: Boolean,
    tyreChangeS: Double,
    driverChangeS: Double,
    repairS: Double,
    pitLossS: Double,
): PitStopResult {
    val refuel = fuelToAddL.coerceAtLeast(0.0) / refuelRateLps.coerceAtLeast(0.1)
    val tyre = if (tyreChange) tyreChangeS.coerceAtLeast(0.0) else 0.0
    val stationary = refuel + tyre + driverChangeS.coerceAtLeast(0.0) + repairS.coerceAtLeast(0.0)
    return PitStopResult(refuel, stationary, stationary + pitLossS.coerceAtLeast(0.0))
}

@Composable
fun StrategyScreen(repo: Repo) {
    var minutes by remember { mutableStateOf("120") }
    var lapSeconds by remember { mutableStateOf("95") }
    var fuelPerLap by remember { mutableStateOf("3.10") }
    var tank by remember { mutableStateOf("68") }
    var startFuel by remember { mutableStateOf("68") }
    var vePerLap by remember { mutableStateOf("3.10") }
    var startVe by remember { mutableStateOf("100") }
    var refuelRate by remember { mutableStateOf("2.5") }
    var pitLoss by remember { mutableStateOf("40") }
    var tyreChangeS by remember { mutableStateOf("12") }
    var driverChangeS by remember { mutableStateOf("0") }
    var tyreChange by remember { mutableStateOf(true) }
    var fuelToAdd by remember { mutableStateOf("40") }
    var repairS by remember { mutableStateOf("0") }
    var drivers by remember { mutableStateOf("2") }
    var maxDriverMinutes by remember { mutableStateOf("60") }
    var note by remember { mutableStateOf<String?>(null) }
    var presets by remember { mutableStateOf<List<StrategyPreset>>(emptyList()) }
    var selectedPreset by remember { mutableStateOf<StrategyPreset?>(null) }
    var presetMenu by remember { mutableStateOf(false) }
    var setups by remember { mutableStateOf<List<SetupRow>>(emptyList()) }
    var sessions by remember { mutableStateOf<List<SessionRow>>(emptyList()) }
    var selectedSetup by remember { mutableStateOf<SetupRow?>(null) }
    var setupTrack by remember { mutableStateOf("") }
    var setupCar by remember { mutableStateOf("") }
    var setupName by remember { mutableStateOf("") }
    var setupNotes by remember { mutableStateOf("") }
    var setupPayload by remember { mutableStateOf("") }
    var setupCompare by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        presets = withContext(Dispatchers.IO) { repo.strategyPresets() }
        setups = withContext(Dispatchers.IO) { repo.setups() }
        sessions = withContext(Dispatchers.IO) { repo.sessions() }
        if (selectedPreset == null) selectedPreset = presets.firstOrNull()
    }

    fun number(value: String): Double = value.replace(',', '.').toDoubleOrNull() ?: 0.0

    fun applyPreset(preset: StrategyPreset) {
        lapSeconds = String.format(Locale.US, "%.3f", preset.lapMs / 1000.0)
        fuelPerLap = String.format(Locale.US, "%.2f", preset.fuelPerLapL)
        if (preset.vePerLap > 0) {
            vePerLap = String.format(Locale.US, "%.2f", preset.vePerLap * 100)
        }
        if (preset.tankL > 0) {
            tank = String.format(Locale.US, "%.1f", preset.tankL)
            startFuel = tank
        }
        if (setupTrack.isBlank()) setupTrack = preset.track
        if (setupCar.isBlank()) setupCar = preset.car
        note = "Preset übernommen: ${preset.track} · ${preset.car}"
    }

    fun persistSetup() {
        val row = SetupRow(
            id = selectedSetup?.id ?: System.currentTimeMillis().toString(),
            track = setupTrack.trim(),
            car = setupCar.trim(),
            name = setupName.trim().ifBlank { "Setup" },
            notes = setupNotes.trim(),
            updatedAt = System.currentTimeMillis(),
            payload = setupPayload.trim(),
        )
        scope.launch {
            withContext(Dispatchers.IO) { repo.saveSetup(row) }
            selectedSetup = row
            setups = withContext(Dispatchers.IO) { repo.setups() }
            setupName = row.name
            setupNotes = row.notes
            setupPayload = row.payload
            note = "Setup gespeichert: ${row.name}"
        }
    }

    val result = remember(
        minutes, lapSeconds, fuelPerLap, tank, startFuel, vePerLap, startVe,
        refuelRate, pitLoss, tyreChangeS, driverChangeS, tyreChange,
    ) {
        computeStrategy(
            raceMinutes = number(minutes),
            lapSeconds = number(lapSeconds),
            fuelPerLap = number(fuelPerLap),
            tankL = number(tank),
            startFuelL = number(startFuel),
            vePerLapPct = number(vePerLap),
            startVePct = number(startVe),
            refuelRateLps = number(refuelRate),
            pitLossS = number(pitLoss),
            tyreChangeS = number(tyreChangeS),
            driverChangeS = number(driverChangeS),
            tyreChange = tyreChange,
        )
    }
    val pit = remember(
        fuelToAdd, refuelRate, tyreChange, tyreChangeS, driverChangeS, repairS, pitLoss,
    ) {
        computePitStop(
            fuelToAddL = number(fuelToAdd),
            refuelRateLps = number(refuelRate),
            tyreChange = tyreChange,
            tyreChangeS = number(tyreChangeS),
            driverChangeS = number(driverChangeS),
            repairS = number(repairS),
            pitLossS = number(pitLoss),
        )
    }
    val stintLaps = result.stints.firstOrNull()?.laps ?: 0
    val endurance = remember(minutes, lapSeconds, stintLaps, drivers, maxDriverMinutes) {
        computeEndurance(
            raceMinutes = number(minutes),
            lapSeconds = number(lapSeconds),
            stintLaps = stintLaps,
            drivers = number(drivers).toInt(),
            maxDriverMinutes = number(maxDriverMinutes),
        )
    }

    if (setupCompare) {
        SetupCompareScreen(setups, sessions) { setupCompare = false }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Rennstrategie", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "Fuel- und VE-Planung mit echten Verbrauchswerten aus deinem Archiv.",
            fontSize = 12.sp,
            color = TextDim,
        )

        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label("Archiv-Preset")
                    Text(
                        selectedPreset?.let { "${it.track} · ${it.car}" }
                            ?: "Noch keine gültigen Runden im Archiv",
                        fontSize = 12.sp,
                        color = TextDim,
                    )
                }
                Box {
                    OutlinedButton(
                        onClick = { presetMenu = true },
                        enabled = presets.isNotEmpty(),
                    ) {
                        Text("Auswählen", fontSize = 12.sp)
                    }
                    DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                        presets.forEach { preset ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "${preset.track} · ${preset.car}",
                                        fontSize = 12.sp,
                                    )
                                },
                                onClick = {
                                    selectedPreset = preset
                                    presetMenu = false
                                },
                            )
                        }
                    }
                }
            }
            selectedPreset?.let { preset ->
                OutlinedButton(onClick = { applyPreset(preset) }) {
                    Text("Rundenzeit, Verbrauch, VE und Tank übernehmen", fontSize = 12.sp)
                }
                Text(
                    String.format(
                        Locale.US,
                        "Archiv: %s · %.2f L/Rd · %.2f %% VE/Rd",
                        formatLapTime(preset.lapMs),
                        preset.fuelPerLapL,
                        preset.vePerLap * 100,
                    ),
                    fontSize = 11.sp,
                    color = TextDim,
                )
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField("Renndauer", minutes, { minutes = it }, "min", Modifier.weight(1f))
                NumberField("Rundenzeit", lapSeconds, { lapSeconds = it }, "s", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField("Fuel/Runde", fuelPerLap, { fuelPerLap = it }, "L", Modifier.weight(1f))
                NumberField("Tank", tank, { tank = it }, "L", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField("Startsprit", startFuel, { startFuel = it }, "L", Modifier.weight(1f))
                NumberField("VE/Runde", vePerLap, { vePerLap = it }, "%", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField("Start-VE", startVe, { startVe = it }, "%", Modifier.weight(1f))
                NumberField("Tankrate", refuelRate, { refuelRate = it }, "L/s", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField("Boxenverlust", pitLoss, { pitLoss = it }, "s", Modifier.weight(1f))
                NumberField("Fahrerwechsel", driverChangeS, { driverChangeS = it }, "s", Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = tyreChange, onCheckedChange = { tyreChange = it })
                Text("Reifenwechsel pro Stopp", fontSize = 12.sp, color = TextMain)
                Spacer(Modifier.weight(1f))
                NumberField(
                    "Dauer",
                    tyreChangeS,
                    { tyreChangeS = it },
                    "s",
                    Modifier.width(110.dp),
                )
            }
            Text(
                "Fahrerwechsel 0 = kein Wechsel. VE wird im Stopp zusammen mit Fuel aufgefüllt; " +
                    "eine separate VE-Auffüllzeit ist nicht modelliert.",
                fontSize = 11.sp,
                color = TextDim,
            )
            note?.let { Text(it, fontSize = 11.sp, color = Lime) }
        }

        if (result.error != null) {
            EmptyHint(result.error)
        } else {
            Panel(Modifier.fillMaxWidth()) {
                Row {
                    Value("Runden", "${result.totalLaps}", modifier = Modifier.weight(1f))
                    Value("Stopps", "${(result.stints.size - 1).coerceAtLeast(0)}", modifier = Modifier.weight(1f))
                    Value("Gesamtzeit", formatDuration(result.totalTimeS), color = Lime)
                }
                if (result.veLimited) {
                    Text(
                        "Mindestens ein Stint ist VE-limitiert – Fuel allein würde weiter reichen.",
                        fontSize = 11.sp,
                        color = Warn,
                    )
                }
            }

            Panel(Modifier.fillMaxWidth()) {
                Label("Stint-Plan")
                result.stints.forEachIndexed { index, stint ->
                    if (index > 0) HorizontalDivider(color = Line)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Stint ${index + 1}",
                            fontSize = 13.sp,
                            color = TextMain,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Mono("${stint.laps} Rd", size = 13.sp)
                        Mono(String.format(Locale.US, "%.1f L", stint.fuelNeededL), size = 13.sp, color = Cyan)
                        Mono(
                            if (stint.veNeededPct > 0) String.format(Locale.US, "%.1f %%", stint.veNeededPct) else "–",
                            size = 13.sp,
                            color = TextDim,
                        )
                        Pill(stint.limitedBy, if (stint.limitedBy == "VE") Warn else TextDim)
                    }
                }
                Text(
                    "Reserve nicht eingerechnet – plane 0,3–0,5 L pro Runde Puffer ein.",
                    fontSize = 11.sp,
                    color = TextDim,
                )
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Label("Endurance-Planer")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField("Fahrer", drivers, { drivers = it }, "", Modifier.weight(1f))
                NumberField("Max. Fahrzeit", maxDriverMinutes, { maxDriverMinutes = it }, "min", Modifier.weight(1f))
            }
            Text(
                if (stintLaps > 0) {
                    "Stint-Länge aus dem Fuel/VE-Plan: $stintLaps Runden (${
                        formatDuration(stintLaps * number(lapSeconds))
                    })."
                } else {
                    "Noch kein Stint-Plan – zuerst Renndauer, Rundenzeit und Verbrauch ausfüllen."
                },
                fontSize = 11.sp,
                color = TextDim,
            )
            if (endurance.error != null) {
                Text(endurance.error, fontSize = 12.sp, color = Warn)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Value("Runden", "${endurance.totalLaps}")
                    Value("Fahrer-Stints", "${endurance.stints.size}")
                    Value("Wechsel", "${endurance.stops}")
                }
                endurance.stints.take(16).forEachIndexed { index, stint ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Stint ${index + 1}", fontSize = 12.sp, color = TextMain)
                        Pill("FAHRER ${stint.driver}", Cyan)
                        Mono("${stint.laps} Rd", size = 12.sp)
                        Mono(formatDuration(stint.driveS), size = 12.sp, color = TextDim)
                    }
                }
                Text(
                    if (endurance.overLimit) {
                        "Ein Fahrer überschreitet die maximale Fahrzeit (${formatDuration(endurance.maxDriveS)})."
                    } else {
                        "Maximale Fahrzeit eingehalten (längster Einsatz ${formatDuration(endurance.maxDriveS)})."
                    },
                    fontSize = 11.sp,
                    color = if (endurance.overLimit) Warn else Lime,
                )
                Text(
                    "Fahrer werden reihum getauscht; die Standzeit für den Fahrerwechsel steht im Boxenstopp-Rechner.",
                    fontSize = 10.sp,
                    color = TextDim,
                )
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Label("Boxenstopp-Rechner")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField("Fuel nachfüllen", fuelToAdd, { fuelToAdd = it }, "L", Modifier.weight(1f))
                NumberField("Reparatur", repairS, { repairS = it }, "s", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Value("Tankzeit", formatDuration(pit.refuelS))
                Value("Standzeit", formatDuration(pit.stationaryS))
                Value("Boxenverlust", formatDuration(pit.totalLossS), color = Warn)
            }
            Text(
                "Standzeit = Tanken + Reifenwechsel + Fahrerwechsel + Reparatur. " +
                    "Boxenverlust = Standzeit + Anfahrt/Durchfahrt.",
                fontSize = 11.sp,
                color = TextDim,
            )
        }

        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("Setup-Log", Modifier.weight(1f))
                Pill("${setups.size} EINTRÄGE", if (setups.isEmpty()) TextDim else Cyan)
                TextButton(onClick = { setupCompare = true }, enabled = setups.size >= 2) {
                    Text("A/B", fontSize = 11.sp, color = Cyan)
                }
            }
            setups.take(5).forEach { setup ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${setup.name} · ${setup.car}",
                            fontSize = 12.sp,
                            color = if (selectedSetup?.id == setup.id) Lime else TextMain,
                        )
                        Text(
                            "${setup.track} · ${formatClock(setup.updatedAt)}",
                            fontSize = 10.sp,
                            color = TextDim,
                        )
                    }
                    TextButton(
                        onClick = {
                            selectedSetup = setup
                            setupTrack = setup.track
                            setupCar = setup.car
                            setupName = setup.name
                            setupNotes = setup.notes
                            setupPayload = setup.payload
                        }
                    ) {
                        Text("Laden", fontSize = 11.sp, color = Cyan)
                    }
                }
            }
            OutlinedTextField(
                value = setupTrack,
                onValueChange = { setupTrack = it },
                label = { Text("Strecke", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = setupCar,
                onValueChange = { setupCar = it },
                label = { Text("Fahrzeug", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = setupName,
                onValueChange = { setupName = it },
                label = { Text("Setup-Name", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = setupNotes,
                onValueChange = { setupNotes = it },
                label = { Text("Notizen (Flügel, Druck, Sturz, …)", fontSize = 11.sp) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = setupPayload,
                onValueChange = { setupPayload = it },
                label = { Text("Payload für A/B (Flügel=3, Reifendruck=200 …)", fontSize = 11.sp) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { persistSetup() },
                    colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Bg),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Setup speichern", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                if (selectedSetup != null) {
                    OutlinedButton(
                        onClick = {
                            val id = selectedSetup?.id ?: return@OutlinedButton
                            scope.launch {
                                withContext(Dispatchers.IO) { repo.deleteSetup(id) }
                                selectedSetup = null
                                setupName = ""
                                setupNotes = ""
                                setupPayload = ""
                                setups = withContext(Dispatchers.IO) { repo.setups() }
                                note = "Setup gelöscht."
                            }
                        },
                    ) {
                        Text("Löschen", fontSize = 12.sp, color = Bad)
                    }
                }
            }
            Text(
                "Setups bleiben lokal auf dem Handy und wandern ins Backup.",
                fontSize = 10.sp,
                color = TextDim,
            )
        }
    }
}

fun formatDuration(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    suffix: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp) },
        suffix = { Text(suffix, fontSize = 10.sp, color = TextDim) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

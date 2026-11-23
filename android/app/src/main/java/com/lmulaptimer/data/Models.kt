package com.lmulaptimer.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale

data class SessionEvent(
    val timeS: Double,
    val kind: String,
    val message: String,
)

data class SessionRow(
    val id: String,
    val startedAt: Long,
    val track: String,
    val car: String,
    val carClass: String,
    val sessionType: String,
    val lapCount: Int,
    val bestLapMs: Long?,
    val synced: Boolean,
    val setupId: String = "",
    val events: List<SessionEvent> = emptyList(),
)

data class LapRow(
    val id: String,
    val sessionId: String,
    val track: String,
    val car: String,
    val sessionType: String,
    val lapNumber: Int,
    val lapTimeMs: Long,
    val sector1Ms: Long?,
    val sector2Ms: Long?,
    val sector3Ms: Long?,
    val valid: Boolean,
    val outLap: Boolean,
    val fuelUsedL: Double,
    val veUsed: Double,
    val topSpeedKmh: Double,
    val startedAt: Long,
    val airTempC: Double,
    val trackTempC: Double,
    val fuelStartL: Double = 0.0,
    val veStart: Double = 0.0,
    val rain: Double = 0.0,
    val wetness: Double = 0.0,
    val grip: Int = 0,
    val isReference: Boolean = false,
    val stintNumber: Int = 1,
    val inLap: Boolean = false,
) {
    /** LMU reports rain and average path wetness separately; either means wet. */
    val wet: Boolean get() = rain > 0.05 || wetness > 0.05
}

data class PersonalBest(
    val track: String,
    val car: String,
    val carClass: String,
    val wet: Boolean,
    val bestMs: Long,
    val lapCount: Int,
    val lastAt: Long,
)

data class Tyre(
    val tempLeftC: Double,
    val tempCenterC: Double,
    val tempRightC: Double,
    val pressureKpa: Double,
    val wear: Double,
    val brakeTempC: Double,
    val compound: String,
)

data class StrategyPreset(
    val track: String,
    val car: String,
    val lapMs: Long,
    val fuelPerLapL: Double,
    val vePerLap: Double,
    val tankL: Double,
    val lastAt: Long,
)

data class DemoCandidates(
    val sessionIds: List<String>,
    val sessions: Int,
    val laps: Int,
)

data class SetupRow(
    val id: String,
    val track: String,
    val car: String,
    val name: String,
    val notes: String,
    val updatedAt: Long,
    val payload: String = "",
)

data class TimingEntry(
    val position: Int,
    val name: String,
    val carClass: String,
    val laps: Int,
    val lastLapS: Double,
    val bestLapS: Double,
    val gapS: Double,
    val inPits: Boolean,
    val isPlayer: Boolean,
)

data class Live(
    val connected: Boolean,
    val track: String,
    val car: String,
    val carClass: String,
    val sessionType: String,
    val flag: String,
    val flagDetail: String,
    val sectorFlags: List<Int>,
    val gamePhase: Int,
    val yellowFlagState: Int,
    val blueFlag: Boolean,
    val underYellow: Boolean,
    val pitStops: Int,
    val pitTotalS: Double,
    val lastPitS: Double,
    val stintNumber: Int,
    val timing: List<TimingEntry>,
    val sessionTimeRemainingS: Double,
    val stintLaps: Int,
    val stintElapsedS: Double,
    val fuelPerLapStint: Double,
    val vePerLapStint: Double,
    val lapNumber: Int,
    val lapDistanceM: Double,
    val trackLengthM: Double,
    val speedKmh: Double,
    val gear: Int,
    val rpm: Double,
    val throttle: Double,
    val brake: Double,
    val fuelL: Double,
    val fuelCapacityL: Double,
    val virtualEnergy: Double,
    val deltaBestS: Double,
    val currentLapS: Double,
    val lastLapS: Double,
    val bestLapS: Double,
    val position: Int,
    val numVehicles: Int,
    val inPits: Boolean,
    val lapValid: Boolean,
    val sector: Int,
    val airTempC: Double,
    val trackTempC: Double,
    val rain: Double,
    val wetness: Double,
    val grip: Int,
    val tyres: List<Tyre>,
    val posX: Double = 0.0,
    val posY: Double = 0.0,
    val posZ: Double = 0.0,
    val gapAheadS: Double? = null,
    val gapBehindS: Double? = null,
    val tc: Int = 0,
    val abs: Int = 0,
    val brakeBias: Double = 0.0,
    val batteryCharge: Double = 0.0,
    val regen: Double = 0.0,
    val damage: List<Double> = emptyList(),
    val penalties: Int = 0,
)

data class SyncResult(val sessions: Int, val laps: Int, val error: String?)

fun formatLapTime(ms: Long?): String {
    if (ms == null || ms <= 0L) return "–"
    val minutes = ms / 60_000
    val seconds = (ms % 60_000) / 1000.0
    return if (minutes > 0) {
        String.format(Locale.US, "%d:%06.3f", minutes, seconds)
    } else {
        String.format(Locale.US, "%.3f", seconds)
    }
}

fun formatSeconds(value: Double): String {
    if (value <= 0.0) return "–"
    return formatLapTime((value * 1000).toLong())
}

fun formatDelta(seconds: Double): String {
    if (seconds == 0.0) return "±0.000"
    return String.format(Locale.US, "%+.3f", seconds)
}

fun formatClock(epochMs: Long): String {
    val instant = Instant.ofEpochMilli(epochMs)
    val date = java.time.ZonedDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
    return String.format(Locale.US, "%02d.%02d. %02d:%02d", date.dayOfMonth, date.monthValue, date.hour, date.minute)
}

private fun JSONObject.longOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key)

private fun JSONObject.doubleOr(key: String, fallback: Double): Double =
    if (!has(key) || isNull(key)) fallback else optDouble(key, fallback)

private fun JSONObject.stringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key)

private fun parseInstant(value: String?): Long = try {
    if (value.isNullOrBlank()) System.currentTimeMillis() else Instant.parse(value).toEpochMilli()
} catch (_: Exception) {
    System.currentTimeMillis()
}

fun parseLive(json: JSONObject): Live {
    val conditions = json.optJSONObject("conditions") ?: JSONObject()
    return Live(
        connected = json.optBoolean("connected", false),
        track = json.optString("track", ""),
        car = json.optString("car", ""),
        carClass = json.optString("car_class", ""),
        sessionType = json.optString("session_type", ""),
        flag = json.optString("flag", "none"),
        flagDetail = json.optString("flag_detail", ""),
        sectorFlags = parseIntList(json.optJSONArray("sector_flags")),
        gamePhase = json.optInt("game_phase", 0),
        yellowFlagState = json.optInt("yellow_flag_state", 0),
        blueFlag = json.optBoolean("blue_flag", false),
        underYellow = json.optBoolean("under_yellow", false),
        pitStops = json.optInt("pit_stops", 0),
        pitTotalS = json.doubleOr("pit_total_s", 0.0),
        lastPitS = json.doubleOr("last_pit_s", 0.0),
        stintNumber = json.optInt("stint_number", 1),
        timing = parseTiming(json.optJSONArray("timing")),
        sessionTimeRemainingS = json.doubleOr("session_time_remaining_s", 0.0),
        stintLaps = json.optInt("stint_laps", 0),
        stintElapsedS = json.doubleOr("stint_elapsed_s", 0.0),
        fuelPerLapStint = json.doubleOr("fuel_per_lap_stint", 0.0),
        vePerLapStint = json.doubleOr("ve_per_lap_stint", 0.0),
        lapNumber = json.optInt("lap_number", 0),
        lapDistanceM = json.doubleOr("lap_distance_m", 0.0),
        trackLengthM = json.doubleOr("track_length_m", 0.0),
        speedKmh = json.doubleOr("speed_kmh", 0.0),
        gear = json.optInt("gear", 0),
        rpm = json.doubleOr("rpm", 0.0),
        throttle = json.doubleOr("throttle", 0.0),
        brake = json.doubleOr("brake", 0.0),
        fuelL = json.doubleOr("fuel_l", 0.0),
        fuelCapacityL = json.doubleOr("fuel_capacity_l", 0.0),
        virtualEnergy = json.doubleOr("virtual_energy", 0.0),
        deltaBestS = json.doubleOr("delta_best_s", 0.0),
        currentLapS = json.doubleOr("current_lap_s", 0.0),
        lastLapS = json.doubleOr("last_lap_s", 0.0),
        bestLapS = json.doubleOr("best_lap_s", 0.0),
        position = json.optInt("position", 0),
        numVehicles = json.optInt("num_vehicles", 0),
        inPits = json.optBoolean("in_pits", false),
        lapValid = json.optBoolean("lap_valid", true),
        sector = json.optInt("sector", 0),
        airTempC = conditions.doubleOr("air_temp_c", 0.0),
        trackTempC = conditions.doubleOr("track_temp_c", 0.0),
        rain = conditions.doubleOr("rain", 0.0),
        wetness = conditions.doubleOr("wetness", 0.0),
        grip = conditions.optInt("grip", 0),
        tyres = parseTyres(json),
        posX = json.doubleOr("pos_x", 0.0),
        posY = json.doubleOr("pos_y", 0.0),
        posZ = json.doubleOr("pos_z", 0.0),
        gapAheadS = if (json.has("gap_ahead_s") && !json.isNull("gap_ahead_s")) json.optDouble("gap_ahead_s") else null,
        gapBehindS = if (json.has("gap_behind_s") && !json.isNull("gap_behind_s")) json.optDouble("gap_behind_s") else null,
        tc = json.optInt("tc", 0),
        abs = json.optInt("abs", 0),
        brakeBias = json.doubleOr("brake_bias", 0.0),
        batteryCharge = json.doubleOr("battery_charge", 0.0),
        regen = json.doubleOr("regen", 0.0),
        damage = parseDoubleList(json.optJSONArray("damage")),
        penalties = json.optInt("penalties", 0),
    )
}

private fun parseDoubleList(array: JSONArray?): List<Double> {
    if (array == null) return emptyList()
    return buildList { for (index in 0 until array.length()) add(array.optDouble(index, 0.0)) }
}

private fun parseTiming(array: JSONArray?): List<TimingEntry> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            add(
                TimingEntry(
                    position = entry.optInt("position", 0),
                    name = entry.optString("name", ""),
                    carClass = entry.optString("car_class", ""),
                    laps = entry.optInt("laps", 0),
                    lastLapS = entry.doubleOr("last_lap_s", 0.0),
                    bestLapS = entry.doubleOr("best_lap_s", 0.0),
                    gapS = entry.doubleOr("gap_s", 0.0),
                    inPits = entry.optBoolean("in_pits", false),
                    isPlayer = entry.optBoolean("is_player", false),
                )
            )
        }
    }
}

private fun parseIntList(array: JSONArray?): List<Int> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) add(array.optInt(index, 0))
    }
}

private fun parseTyres(json: JSONObject): List<Tyre> {
    val array = json.optJSONArray("tyres") ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val wheel = array.optJSONObject(index) ?: continue
            add(
                Tyre(
                    tempLeftC = wheel.doubleOr("temp_left_c", 0.0),
                    tempCenterC = wheel.doubleOr("temp_center_c", 0.0),
                    tempRightC = wheel.doubleOr("temp_right_c", 0.0),
                    pressureKpa = wheel.doubleOr("pressure_kpa", 0.0),
                    wear = wheel.doubleOr("wear_raw", 0.0),
                    brakeTempC = wheel.doubleOr("brake_temp_c", 0.0),
                    compound = wheel.optString("compound", ""),
                )
            )
        }
    }
}

fun parseSession(json: JSONObject): SessionRow {
    val conditions = json.optJSONObject("conditions") ?: JSONObject()
    return SessionRow(
        id = json.optString("id", ""),
        startedAt = parseInstant(json.stringOrNull("started_at")),
        track = json.optString("track", ""),
        car = json.optString("car", ""),
        carClass = json.optString("car_class", ""),
        sessionType = json.optString("session_type", ""),
        lapCount = json.optInt("lap_count", 0),
        bestLapMs = json.longOrNull("best_lap_ms"),
        synced = json.optBoolean("synced", false),
        setupId = json.optString("setup_id", ""),
        events = parseSessionEvents(json.optJSONArray("events")),
    )
}

private fun parseSessionEvents(array: JSONArray?): List<SessionEvent> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            add(
                SessionEvent(
                    timeS = item.doubleOr("time_s", 0.0),
                    kind = item.optString("kind", ""),
                    message = item.optString("message", ""),
                )
            )
        }
    }
}

fun parseLap(json: JSONObject, fallbackTrack: String, fallbackCar: String, fallbackType: String): LapRow? {
    val id = json.optString("id", "")
    if (id.isBlank()) return null
    val conditions = json.optJSONObject("conditions") ?: JSONObject()
    return LapRow(
        id = id,
        sessionId = json.optString("session_id", ""),
        track = json.optString("track", fallbackTrack),
        car = json.optString("car", fallbackCar),
        sessionType = json.optString("session_type", fallbackType),
        lapNumber = json.optInt("lap_number", 0),
        lapTimeMs = json.optLong("lap_time_ms", 0L),
        sector1Ms = json.longOrNull("sector1_ms"),
        sector2Ms = json.longOrNull("sector2_ms"),
        sector3Ms = json.longOrNull("sector3_ms"),
        valid = json.optBoolean("valid", true),
        outLap = json.optBoolean("out_lap", false),
        fuelUsedL = json.doubleOr("fuel_used_l", 0.0),
        veUsed = json.doubleOr("ve_used", 0.0),
        topSpeedKmh = json.doubleOr("top_speed_kmh", 0.0),
        startedAt = parseInstant(json.stringOrNull("started_at")),
        airTempC = conditions.doubleOr("air_temp_c", 0.0),
        trackTempC = conditions.doubleOr("track_temp_c", 0.0),
        fuelStartL = json.doubleOr("fuel_start_l", 0.0),
        veStart = json.doubleOr("ve_start", 0.0),
        rain = conditions.doubleOr("rain", 0.0),
        wetness = conditions.doubleOr("wetness", 0.0),
        grip = conditions.optInt("grip", 0),
        isReference = json.optBoolean("reference", false),
        stintNumber = json.optInt("stint_number", 1),
        inLap = json.optBoolean("in_lap", false),
    )
}

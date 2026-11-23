package com.lmulaptimer.ui

import java.util.Locale
import kotlin.math.floor

/** Fuel/distance plan for the remaining race distance. */
data class LivePlan(
    val lapsToGo: Int?,
    val fuelNeededL: Double?,
    val fuelDeltaL: Double?,
    val targetPerLapL: Double?,
    val stopInLaps: Int?,
    val savingPerLapL: Double?,
    val underYellow: Boolean,
    val message: String,
)

fun computeLivePlan(
    fuelL: Double,
    consumptionL: Double,
    lapsRemaining: Int?,
    underYellow: Boolean,
    inPits: Boolean,
): LivePlan {
    if (lapsRemaining == null || lapsRemaining <= 0) {
        return LivePlan(
            lapsToGo = null,
            fuelNeededL = null,
            fuelDeltaL = null,
            targetPerLapL = null,
            stopInLaps = null,
            savingPerLapL = null,
            underYellow = underYellow,
            message = "Restdistanz unbekannt – LMU liefert keine Rennrunden.",
        )
    }
    if (consumptionL <= 0.0 || fuelL <= 0.0) {
        return LivePlan(
            lapsToGo = lapsRemaining,
            fuelNeededL = null,
            fuelDeltaL = null,
            targetPerLapL = null,
            stopInLaps = null,
            savingPerLapL = null,
            underYellow = underYellow,
            message = "Kein Verbrauchswert – Planung startet nach der ersten Runde.",
        )
    }
    // Under FCY the car runs slower and burns less; 85 % is a deliberate
    // conservative estimate, not a measurement.
    val effective = if (underYellow) consumptionL * 0.85 else consumptionL
    val needed = lapsRemaining * effective
    val delta = fuelL - needed
    val stop = floor(fuelL / effective).toInt().coerceAtLeast(0)
    val saving = if (delta < 0) -delta / lapsRemaining else 0.0
    val message = when {
        inPits -> "In der Box – Plan wird nach dem Stint neu gerechnet."
        delta >= 0 -> String.format(Locale.US, "Sprit reicht mit %.1f L Reserve.", delta)
        else -> String.format(Locale.US, "Es fehlen %.1f L bis Rennende.", -delta)
    }
    return LivePlan(
        lapsToGo = lapsRemaining,
        fuelNeededL = needed,
        fuelDeltaL = delta,
        targetPerLapL = fuelL / lapsRemaining,
        stopInLaps = stop,
        savingPerLapL = saving,
        underYellow = underYellow,
        message = message,
    )
}

/** Compact helper for the dashboard. */
fun planLapsText(laps: Int?): String = laps?.let { "$it Rd" } ?: "–"

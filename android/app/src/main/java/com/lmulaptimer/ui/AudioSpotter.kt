package com.lmulaptimer.ui

import android.media.AudioManager
import android.media.ToneGenerator
import com.lmulaptimer.data.Live
import com.lmulaptimer.data.formatSeconds

/** Pure event rules, kept separate so they can be unit-tested. */
data class SpotterEvent(val id: String, val text: String)

fun spotterEvents(previous: Live?, current: Live): List<SpotterEvent> {
    if (previous == null) return emptyList()
    val events = mutableListOf<SpotterEvent>()
    if (previous.flag != current.flag) {
        when (current.flag.lowercase()) {
            "yellow", "fcy", "safetycar" -> events += SpotterEvent("flag-${current.flag}-${current.lapNumber}", "Gelb: ${current.flagDetail.ifBlank { current.flag }}")
            "red" -> events += SpotterEvent("flag-red-${current.lapNumber}", "Rote Flagge")
            "blue" -> events += SpotterEvent("flag-blue-${current.lapNumber}", "Blaue Flagge")
            "green", "none" -> Unit
        }
    }
    if (current.blueFlag && !previous.blueFlag) {
        events += SpotterEvent("blue-${current.lapNumber}", "Blaue Flagge – überrunden lassen")
    }
    if (current.lapNumber != previous.lapNumber && current.lapNumber > 0) {
        val time = if (current.lastLapS > 0) " · ${formatSeconds(current.lastLapS)}" else ""
        events += SpotterEvent("lap-${current.lapNumber}", "Runde ${current.lapNumber} beendet$time")
    }
    if (current.penalties > previous.penalties) {
        events += SpotterEvent("penalty-${current.penalties}", "Neue Strafe (${current.penalties})")
    }
    if (current.damage.sum() > previous.damage.sum() + 0.15) {
        events += SpotterEvent("damage-${current.damage.sum()}", "Neuer Schaden gemeldet")
    }
    if (previous.fuelL >= 5.0 && current.fuelL in 0.01..5.0) {
        events += SpotterEvent("fuel-low-${current.stintNumber}", "Wenig Sprit: ${String.format(java.util.Locale.US, "%.1f L", current.fuelL)}")
    }
    if (current.inPits && !previous.inPits) {
        events += SpotterEvent("pit-in-${current.stintNumber}", "Boxeneinfahrt")
    }
    if (!current.inPits && previous.inPits) {
        events += SpotterEvent("pit-out-${current.stintNumber}", "Boxenausfahrt – Stint ${current.stintNumber}")
    }
    return events
}

object AudioSignal {
    /** Short beep; ToneGenerator is cheap but must always be released. */
    fun beep(volume: Int) {
        val tone = runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, volume.coerceIn(0, 100))
        }.getOrNull() ?: return
        runCatching {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching { tone.release() }
            }, 350L)
        }.onFailure { runCatching { tone.release() } }
    }
}

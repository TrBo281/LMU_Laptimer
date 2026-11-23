package com.lmulaptimer.ui

import com.lmulaptimer.data.LapRow
import org.json.JSONObject

/**
 * A reference lap reduced to the two parallel arrays needed for a live delta.
 * The bridge samples are ordered by lap distance, so a binary search is enough.
 */
data class ReferenceTrace(
    val lapId: String,
    val lapNumber: Int,
    val lapTimeMs: Long,
    val car: String,
    private val distanceM: FloatArray,
    private val timeS: FloatArray,
) {
    fun timeAt(distance: Double): Double? {
        if (distanceM.size < 2 || timeS.size != distanceM.size) return null
        if (distance < distanceM.first() || distance > distanceM.last()) return null
        var low = 0
        var high = distanceM.size - 1
        while (low < high) {
            val mid = (low + high) / 2
            if (distanceM[mid] < distance) low = mid + 1 else high = mid
        }
        if (low == 0) return timeS[0].toDouble()
        val d0 = distanceM[low - 1].toDouble()
        val d1 = distanceM[low].toDouble()
        val t0 = timeS[low - 1].toDouble()
        val t1 = timeS[low].toDouble()
        if (d1 <= d0) return t1
        val ratio = ((distance - d0) / (d1 - d0)).coerceIn(0.0, 1.0)
        return t0 + (t1 - t0) * ratio
    }

    /** Positive means the current lap is slower than the reference here. */
    fun deltaAt(distance: Double, currentLapS: Double): Double? {
        if (currentLapS <= 0.0) return null
        val referenceTime = timeAt(distance) ?: return null
        return currentLapS - referenceTime
    }

    companion object {
        fun from(lap: LapRow, payload: JSONObject?): ReferenceTrace? {
            val distances = payload?.floatArray("distance_m") ?: return null
            val times = payload.floatArray("time_s")
            if (distances.size < 2 || times.size != distances.size) return null
            return ReferenceTrace(
                lapId = lap.id,
                lapNumber = lap.lapNumber,
                lapTimeMs = lap.lapTimeMs,
                car = lap.car,
                distanceM = distances,
                timeS = times,
            )
        }
    }
}

private fun JSONObject.floatArray(key: String): FloatArray {
    val array = optJSONArray(key) ?: return FloatArray(0)
    return FloatArray(array.length()) { array.optDouble(it, 0.0).toFloat() }
}

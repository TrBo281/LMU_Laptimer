package com.lmulaptimer.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * One recorded lap as parallel arrays. The bridge sends these arrays inside
 * /api/laps/{id}/samples; the same document is cached locally so analysis
 * keeps working when the rig is offline.
 */
data class LapSamples(
    val timeS: FloatArray,
    val distanceM: FloatArray,
    val speedKmh: FloatArray,
    val throttle: FloatArray,
    val brake: FloatArray,
    val gear: IntArray,
    val fuelL: FloatArray,
    val virtualEnergy: FloatArray,
    val posX: FloatArray,
    val posZ: FloatArray,
) {
    /** Arrays may be truncated independently; only complete samples count. */
    val count: Int get() = minOf(
        timeS.size, distanceM.size, speedKmh.size, throttle.size,
        brake.size, gear.size, fuelL.size, virtualEnergy.size,
    )
    val durationS: Double get() = if (count > 0) timeS[count - 1].toDouble() else 0.0
    val maxSpeedKmh: Float get() = if (count == 0) 0f else (0 until count).maxOf { speedKmh[it] }
    val distanceEndM: Float get() = if (count == 0) 0f else distanceM[count - 1]

    companion object {
        fun from(json: JSONObject?): LapSamples? {
            if (json == null) return null
            val time = json.floatArray("time_s")
            val distance = json.floatArray("distance_m")
            if (time.size < 2 || distance.size < 2) return null
            val gearJson = json.optJSONArray("gear")
            return LapSamples(
                timeS = time,
                distanceM = distance,
                speedKmh = json.floatArray("speed_kmh"),
                throttle = json.floatArray("throttle"),
                brake = json.floatArray("brake"),
                gear = IntArray(gearJson?.length() ?: 0) { gearJson!!.optInt(it, 0) },
                fuelL = json.floatArray("fuel_l"),
                virtualEnergy = json.floatArray("virtual_energy"),
                posX = json.floatArray("pos_x"),
                posZ = json.floatArray("pos_z"),
            )
        }
    }
}

data class TrackSample(
    val x: Float,
    val z: Float,
    val speedKmh: Float,
    val throttle: Float,
    val brake: Float,
    val distanceM: Float,
)

data class TrackShape(
    val points: List<TrackSample>,
    val minX: Float,
    val maxX: Float,
    val minZ: Float,
    val maxZ: Float,
) {
    val hasPosition: Boolean get() = points.size >= 10
}

data class LocalDeltaPoint(val distanceM: Float, val deltaS: Float)

data class LapCompare(
    val a: LapSamples,
    val b: LapSamples,
    val delta: List<LocalDeltaPoint>,
    val topSpeedA: Float,
    val topSpeedB: Float,
    val throttlePctA: Float,
    val throttlePctB: Float,
    val brakePctA: Float,
    val brakePctB: Float,
)

object Samples {
    fun parse(raw: String?): LapSamples? = try {
        if (raw.isNullOrBlank()) null else LapSamples.from(JSONObject(raw))
    } catch (_: Exception) {
        null
    }

    /** Interpolated lap time at a distance; null outside the recorded range. */
    fun timeAt(samples: LapSamples, distance: Float): Float? {
        if (samples.count < 2) return null
        val first = samples.distanceM[0]
        val last = samples.distanceM[samples.count - 1]
        if (distance < first || distance > last) return null
        var low = 0
        var high = samples.count - 1
        while (low < high) {
            val mid = (low + high) / 2
            if (samples.distanceM[mid] < distance) low = mid + 1 else high = mid
        }
        if (low == 0) return samples.timeS[0]
        val d0 = samples.distanceM[low - 1]
        val d1 = samples.distanceM[low]
        if (d1 <= d0) return samples.timeS[low]
        val ratio = ((distance - d0) / (d1 - d0)).coerceIn(0f, 1f)
        return samples.timeS[low - 1] + (samples.timeS[low] - samples.timeS[low - 1]) * ratio
    }

    /** Positive delta means b is slower than a at that distance. */
    fun delta(a: LapSamples, b: LapSamples, stepM: Float = 10f): List<LocalDeltaPoint> {
        val end = minOf(a.distanceEndM, b.distanceEndM)
        if (end <= 0f) return emptyList()
        val points = ArrayList<LocalDeltaPoint>()
        var distance = 0f
        while (distance <= end) {
            val timeA = timeAt(a, distance)
            val timeB = timeAt(b, distance)
            if (timeA != null && timeB != null) {
                points += LocalDeltaPoint(distance, timeB - timeA)
            }
            distance += stepM
        }
        return points
    }

    fun compare(a: LapSamples, b: LapSamples): LapCompare = LapCompare(
        a = a,
        b = b,
        delta = delta(a, b),
        topSpeedA = a.maxSpeedKmh,
        topSpeedB = b.maxSpeedKmh,
        throttlePctA = fullThrottlePct(a),
        throttlePctB = fullThrottlePct(b),
        brakePctA = brakingPct(a),
        brakePctB = brakingPct(b),
    )

    fun fullThrottlePct(samples: LapSamples): Float {
        if (samples.count == 0) return 0f
        var full = 0
        for (index in 0 until samples.count) if (samples.throttle[index] > 0.95f) full++
        return full * 100f / samples.count
    }

    fun brakingPct(samples: LapSamples): Float {
        if (samples.count == 0) return 0f
        var braking = 0
        for (index in 0 until samples.count) if (samples.brake[index] > 0.05f) braking++
        return braking * 100f / samples.count
    }

    /** Resamples positions to at most [maxPoints] for a cheap Canvas map. */
    fun track(samples: LapSamples, maxPoints: Int = 400): TrackShape? {
        val size = minOf(samples.count, samples.posX.size, samples.posZ.size)
        if (size < 10) return null
        val points = ArrayList<TrackSample>(minOf(size, maxPoints))
        val stride = maxOf(1, size / maxPoints)
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        var index = 0
        while (index < size) {
            val x = samples.posX[index]
            val z = samples.posZ[index]
            if (x != 0f || z != 0f) {
                points += TrackSample(x, z, samples.speedKmh[index], samples.throttle[index], samples.brake[index], samples.distanceM[index])
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (z < minZ) minZ = z
                if (z > maxZ) maxZ = z
            }
            index += stride
        }
        if (points.size < 10 || minX == Float.MAX_VALUE) return null
        return TrackShape(points, minX, maxX, minZ, maxZ)
    }

    /** Distance windows with the most time loss of b against a. */
    fun hotspots(compare: LapCompare, windowM: Float = 100f, limit: Int = 3): List<LocalDeltaPoint> {
        if (compare.delta.size < 2) return emptyList()
        val buckets = LinkedHashMap<Int, Float>()
        compare.delta.forEach { point ->
            val key = (point.distanceM / windowM).toInt()
            buckets[key] = (buckets[key] ?: 0f) + point.deltaS
        }
        return buckets.entries
            .sortedByDescending { it.value }
            .take(limit)
            .filter { it.value > 0.01f }
            .map { LocalDeltaPoint(it.key * windowM + windowM / 2f, it.value) }
    }

    fun segmentDeltas(compare: LapCompare, segments: Int = 3): List<Float> {
        if (compare.delta.isEmpty()) return emptyList()
        val end = compare.delta.last().distanceM.coerceAtLeast(1f)
        return (0 until segments).map { segment ->
            val from = end * segment / segments
            val to = end * (segment + 1) / segments
            val points = compare.delta.filter { it.distanceM in from..to }
            if (points.size < 2) 0f else points.last().deltaS - points.first().deltaS
        }
    }

    fun absDelta(compare: LapCompare): Float = compare.delta.lastOrNull()?.deltaS ?: 0f
}

private fun JSONObject.floatArray(key: String): FloatArray {
    val array: JSONArray = optJSONArray(key) ?: return FloatArray(0)
    return FloatArray(array.length()) { array.optDouble(it, 0.0).toFloat() }
}

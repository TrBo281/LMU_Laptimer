package com.lmulaptimer

import com.lmulaptimer.data.LapSamples
import com.lmulaptimer.data.Samples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplesTest {

    private fun lap(times: FloatArray, speeds: FloatArray? = null): LapSamples {
        val count = times.size
        val distances = FloatArray(count) { it * 50f }
        return LapSamples(
            timeS = times,
            distanceM = distances,
            speedKmh = speeds ?: FloatArray(count) { 100f },
            throttle = FloatArray(count) { 1f },
            brake = FloatArray(count) { 0f },
            gear = IntArray(count) { 4 },
            fuelL = FloatArray(count) { 10f - it * 0.1f },
            virtualEnergy = FloatArray(count) { 1f },
            posX = FloatArray(count) { it * 10f },
            posZ = FloatArray(count) { it * 5f },
        )
    }

    @Test
    fun interpolatesTimeAtDistance() {
        val a = lap(floatArrayOf(0f, 10f, 20f))
        assertEquals(15f, Samples.timeAt(a, 75f)!!, 0.001f)
    }

    @Test
    fun deltaComparesTwoLapsAtSameDistance() {
        val a = lap(floatArrayOf(0f, 10f, 20f))
        val b = lap(floatArrayOf(0f, 11f, 22f))
        val delta = Samples.delta(a, b, stepM = 50f)
        assertEquals(3, delta.size)
        assertEquals(0f, delta.first().deltaS, 0.001f)
        assertEquals(2f, delta.last().deltaS, 0.001f)
    }

    @Test
    fun compareReportsTopSpeedAndThrottle() {
        val a = lap(floatArrayOf(0f, 10f, 20f), floatArrayOf(100f, 200f, 150f))
        val b = lap(floatArrayOf(0f, 10f, 20f), floatArrayOf(90f, 180f, 140f))
        val compare = Samples.compare(a, b)
        assertEquals(200f, compare.topSpeedA, 0.01f)
        assertEquals(100f, compare.throttlePctA, 0.01f)
        assertTrue(Samples.absDelta(compare) == 0f)
    }

    @Test
    fun buildsTrackFromPositions() {
        val shape = Samples.track(lap(floatArrayOf(0f, 1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f)))
        assertNotNull(shape)
        assertTrue(shape!!.hasPosition)
        // The bridge uses (0,0) as "missing position", so the origin is skipped.
        assertEquals(10f, shape.minX, 0.01f)
        assertEquals(100f, shape.maxX, 0.01f)
    }

    @Test
    fun hotspotsUseWorstWindows() {
        val a = lap(floatArrayOf(0f, 10f, 20f, 30f, 40f, 50f, 60f, 70f, 80f, 90f, 100f))
        val b = lap(floatArrayOf(0f, 10f, 21f, 33f, 46f, 60f, 74f, 89f, 105f, 121f, 138f))
        val compare = Samples.compare(a, b)
        val spots = Samples.hotspots(compare, windowM = 100f, limit = 2)
        assertTrue(spots.isNotEmpty())
        assertTrue(spots.first().deltaS > 0f)
    }
}

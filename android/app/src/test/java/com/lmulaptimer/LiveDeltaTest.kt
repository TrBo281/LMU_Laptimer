package com.lmulaptimer

import com.lmulaptimer.ui.ReferenceTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveDeltaTest {

    private fun trace() = ReferenceTrace(
        lapId = "ref",
        lapNumber = 4,
        lapTimeMs = 20_000,
        car = "Test Car",
        distanceM = floatArrayOf(0f, 100f, 200f),
        timeS = floatArrayOf(0f, 10f, 20f),
    )

    @Test
    fun interpolatesTimeAtDistance() {
        assertEquals(5.0, trace().timeAt(50.0)!!, 0.0001)
        assertEquals(15.0, trace().timeAt(150.0)!!, 0.0001)
    }

    @Test
    fun deltaIsNegativeWhenAheadOfReference() {
        assertEquals(-1.0, trace().deltaAt(150.0, 14.0)!!, 0.0001)
        assertEquals(0.5, trace().deltaAt(150.0, 15.5)!!, 0.0001)
    }

    @Test
    fun outsideTraceOrBeforeStartHasNoDelta() {
        assertNull(trace().timeAt(-1.0))
        assertNull(trace().deltaAt(250.0, 20.0))
        assertNull(trace().deltaAt(100.0, 0.0))
    }
}

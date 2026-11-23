package com.lmulaptimer

import com.lmulaptimer.data.Live
import com.lmulaptimer.ui.spotterEvents
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotterTest {

    private fun live() = Live(
        connected = true,
        track = "Spa",
        car = "Test",
        carClass = "Hypercar",
        sessionType = "race",
        flag = "green",
        flagDetail = "",
        sectorFlags = emptyList(),
        gamePhase = 5,
        yellowFlagState = 0,
        blueFlag = false,
        underYellow = false,
        pitStops = 0,
        pitTotalS = 0.0,
        lastPitS = 0.0,
        stintNumber = 2,
        timing = emptyList(),
        sessionTimeRemainingS = 3600.0,
        stintLaps = 3,
        stintElapsedS = 300.0,
        fuelPerLapStint = 3.1,
        vePerLapStint = 0.03,
        lapNumber = 3,
        lapDistanceM = 1000.0,
        trackLengthM = 7000.0,
        speedKmh = 200.0,
        gear = 5,
        rpm = 6000.0,
        throttle = 0.9,
        brake = 0.0,
        fuelL = 20.0,
        fuelCapacityL = 68.0,
        virtualEnergy = 0.8,
        deltaBestS = 0.0,
        currentLapS = 60.0,
        lastLapS = 95.0,
        bestLapS = 94.0,
        position = 3,
        numVehicles = 20,
        inPits = false,
        lapValid = true,
        sector = 2,
        airTempC = 21.0,
        trackTempC = 28.0,
        rain = 0.0,
        wetness = 0.0,
        grip = 0,
        tyres = emptyList(),
    )

    @Test
    fun yellowFlagAndLapChangeProduceEvents() {
        val before = live()
        val after = before.copy(flag = "yellow", lapNumber = 4)
        val events = spotterEvents(before, after)
        assertTrue(events.any { it.id.startsWith("flag-yellow") })
        assertTrue(events.any { it.id == "lap-4" })
    }

    @Test
    fun firstSnapshotIsSilent() {
        assertTrue(spotterEvents(null, live()).isEmpty())
    }

    @Test
    fun pitEntryAndLowFuelProduceEvents() {
        val before = live()
        val after = before.copy(fuelL = 4.0, inPits = true)
        val events = spotterEvents(before, after)
        assertTrue(events.any { it.id == "pit-in-2" })
        assertTrue(events.any { it.id == "fuel-low-2" })
    }

    @Test
    fun penaltyIncreaseProducesEvent() {
        val before = live()
        val after = before.copy(penalties = 1)
        assertTrue(spotterEvents(before, after).any { it.id == "penalty-1" })
    }
}

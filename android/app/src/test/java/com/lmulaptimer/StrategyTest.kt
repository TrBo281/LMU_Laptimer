package com.lmulaptimer

import com.lmulaptimer.ui.computeEndurance
import com.lmulaptimer.ui.computeLivePlan
import com.lmulaptimer.ui.computePitStop
import com.lmulaptimer.ui.computeStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyTest {

    @Test
    fun fuelLimitedPlanSplitsIntoTankSizedStints() {
        val result = computeStrategy(
            raceMinutes = 120.0,
            lapSeconds = 95.0,
            fuelPerLap = 3.1,
            tankL = 68.0,
            startFuelL = 68.0,
            vePerLapPct = 0.0,
            startVePct = 100.0,
            refuelRateLps = 2.5,
            pitLossS = 40.0,
            tyreChangeS = 12.0,
            driverChangeS = 0.0,
            tyreChange = true,
        )

        assertNull(result.error)
        assertEquals(76, result.totalLaps)
        assertEquals(listOf(21, 21, 21, 13), result.stints.map { it.laps })
        assertEquals(3, result.stints.size - 1)
        assertFalse(result.veLimited)
        assertEquals("Fuel", result.stints.first().limitedBy)
        assertEquals(7444.2, result.totalTimeS, 0.05)
    }

    @Test
    fun virtualEnergyCanLimitAStint() {
        val result = computeStrategy(
            raceMinutes = 120.0,
            lapSeconds = 95.0,
            fuelPerLap = 3.1,
            tankL = 120.0,
            startFuelL = 120.0,
            vePerLapPct = 3.1,
            startVePct = 100.0,
            refuelRateLps = 2.5,
            pitLossS = 40.0,
            tyreChangeS = 12.0,
            driverChangeS = 0.0,
            tyreChange = true,
        )

        assertNull(result.error)
        assertEquals(listOf(32, 32, 12), result.stints.map { it.laps })
        assertTrue(result.veLimited)
        assertEquals("VE", result.stints.first().limitedBy)
        assertEquals(99.2, result.stints.first().veNeededPct, 0.001)
    }

    @Test
    fun pitStopAddsRefuelTyresDriverRepairAndLoss() {
        val quick = computePitStop(
            fuelToAddL = 40.0,
            refuelRateLps = 2.5,
            tyreChange = true,
            tyreChangeS = 12.0,
            driverChangeS = 0.0,
            repairS = 0.0,
            pitLossS = 40.0,
        )
        assertEquals(16.0, quick.refuelS, 0.001)
        assertEquals(28.0, quick.stationaryS, 0.001)
        assertEquals(68.0, quick.totalLossS, 0.001)

        val long = computePitStop(
            fuelToAddL = 40.0,
            refuelRateLps = 2.5,
            tyreChange = false,
            tyreChangeS = 12.0,
            driverChangeS = 45.0,
            repairS = 10.0,
            pitLossS = 40.0,
        )
        assertEquals(71.0, long.stationaryS, 0.001)
        assertEquals(111.0, long.totalLossS, 0.001)
    }

    @Test
    fun enduranceRotatesDriversAndWarnsAboutLongStints() {
        val plan = computeEndurance(
            raceMinutes = 120.0,
            lapSeconds = 95.0,
            stintLaps = 21,
            drivers = 2,
            maxDriverMinutes = 60.0,
        )

        assertNull(plan.error)
        assertEquals(76, plan.totalLaps)
        assertEquals(listOf(21, 21, 21, 13), plan.stints.map { it.laps })
        assertEquals(listOf(1, 2, 1, 2), plan.stints.map { it.driver })
        assertEquals(3, plan.stops)
        assertTrue(plan.overLimit)
    }

    @Test
    fun enduranceWithThreeDriversStaysInsideLimit() {
        val plan = computeEndurance(
            raceMinutes = 120.0,
            lapSeconds = 95.0,
            stintLaps = 21,
            drivers = 3,
            maxDriverMinutes = 60.0,
        )

        assertNull(plan.error)
        assertEquals(listOf(1, 2, 3, 1), plan.stints.map { it.driver })
        assertFalse(plan.overLimit)
        assertEquals(53.833, plan.maxDriveS / 60.0, 0.01)
    }

    @Test
    fun livePlanReportsReserveAndSavingTarget() {
        val enough = computeLivePlan(
            fuelL = 40.0,
            consumptionL = 3.1,
            lapsRemaining = 12,
            underYellow = false,
            inPits = false,
        )
        assertEquals(12, enough.lapsToGo)
        assertEquals(37.2, enough.fuelNeededL!!, 0.001)
        assertEquals(2.8, enough.fuelDeltaL!!, 0.001)
        assertEquals(0.0, enough.savingPerLapL!!, 0.001)
        assertEquals(12, enough.stopInLaps)

        val short = computeLivePlan(
            fuelL = 20.0,
            consumptionL = 3.1,
            lapsRemaining = 10,
            underYellow = false,
            inPits = false,
        )
        assertEquals(-11.0, short.fuelDeltaL!!, 0.001)
        assertEquals(1.1, short.savingPerLapL!!, 0.001)
        assertEquals(6, short.stopInLaps)

        val fcy = computeLivePlan(
            fuelL = 30.0,
            consumptionL = 3.1,
            lapsRemaining = 10,
            underYellow = true,
            inPits = false,
        )
        assertEquals(26.35, fcy.fuelNeededL!!, 0.001)
    }

    @Test
    fun livePlanHandlesUnknownRemainingDistance() {
        val plan = computeLivePlan(
            fuelL = 30.0,
            consumptionL = 3.1,
            lapsRemaining = null,
            underYellow = false,
            inPits = false,
        )
        assertNull(plan.lapsToGo)
        assertNull(plan.fuelNeededL)
        assertTrue(plan.message.contains("unbekannt"))
    }
}

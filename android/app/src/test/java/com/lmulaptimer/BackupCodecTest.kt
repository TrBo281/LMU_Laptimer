package com.lmulaptimer

import com.lmulaptimer.data.BackupCodec
import com.lmulaptimer.data.LapRow
import com.lmulaptimer.data.SessionEvent
import com.lmulaptimer.data.SessionRow
import com.lmulaptimer.data.SetupRow
import org.junit.Assert.assertEquals
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {

    private val session = SessionRow(
        id = "session-1",
        startedAt = 1_700_000_000_000L,
        track = "Circuit de la Sarthe",
        car = "Ferrari 499P",
        carClass = "Hypercar",
        sessionType = "race",
        lapCount = 1,
        bestLapMs = 215_000L,
        synced = true,
        setupId = "setup-1",
        events = listOf(SessionEvent(12.5, "flag", "yellow: Sektor 2")),
    )

    private val lap = LapRow(
        id = "lap-1",
        sessionId = "session-1",
        track = "Circuit de la Sarthe",
        car = "Ferrari 499P",
        sessionType = "race",
        lapNumber = 7,
        lapTimeMs = 215_000L,
        sector1Ms = 70_000L,
        sector2Ms = 72_000L,
        sector3Ms = 73_000L,
        valid = true,
        outLap = false,
        fuelUsedL = 3.12,
        veUsed = 0.031,
        topSpeedKmh = 331.5,
        startedAt = 1_700_000_200_000L,
        airTempC = 21.5,
        trackTempC = 28.0,
        fuelStartL = 55.0,
        veStart = 0.84,
        rain = 0.0,
        wetness = 0.0,
        grip = 2,
        isReference = true,
        stintNumber = 4,
        inLap = true,
    )

    private val setup = SetupRow(
        id = "setup-1",
        track = "Circuit de la Sarthe",
        car = "Ferrari 499P",
        name = "Le Mans Low Drag",
        notes = "Flügel flach",
        updatedAt = 1_700_000_300_000L,
        payload = "Flügel=3\nReifendruck=200\nSturz=-1.8",
    )

    @Test
    fun jsonRoundtripKeepsSetupPayloadStintAndInLap() {
        val json = BackupCodec.encode(
            sessions = listOf(session),
            lapsBySession = mapOf(session.id to listOf(lap)),
            setups = listOf(setup),
        )

        assertTrue(json.contains("\"stint_number\": 4"))
        assertTrue(json.contains("\"in_lap\": true"))
        assertTrue(json.contains("\"payload\""))

        // Check the serialized document directly, not only the decoded model.
        val root = JSONObject(json)
        val lapJson = root.getJSONArray("sessions").getJSONObject(0)
            .getJSONArray("laps").getJSONObject(0)
        assertEquals(4, lapJson.getInt("stint_number"))
        assertTrue(lapJson.getBoolean("in_lap"))
        val setupJson = root.getJSONArray("setups").getJSONObject(0)
        assertEquals(
            "Flügel=3\nReifendruck=200\nSturz=-1.8",
            setupJson.getString("payload"),
        )

        assertEquals(
            "setup-1",
            root.getJSONArray("sessions").getJSONObject(0).getString("setup_id"),
        )

        val restored = BackupCodec.decode(json)
        val restoredSession = restored.sessions.single()
        val restoredLap = restored.lapsBySession[restoredSession.id]!!.single()
        val restoredSetup = restored.setups.single()

        assertEquals("setup-1", restoredSession.setupId)
        assertEquals(1, restoredSession.events.size)
        assertEquals("flag", restoredSession.events.single().kind)
        assertEquals(12.5, restoredSession.events.single().timeS, 0.001)
        assertEquals(4, restoredLap.stintNumber)
        assertTrue(restoredLap.inLap)
        assertEquals(7, restoredLap.lapNumber)
        assertEquals(215_000L, restoredLap.lapTimeMs)
        assertEquals(3.12, restoredLap.fuelUsedL, 0.0001)
        assertEquals("Flügel=3\nReifendruck=200\nSturz=-1.8", restoredSetup.payload)
        assertEquals("Le Mans Low Drag", restoredSetup.name)
        assertEquals(1_700_000_300_000L, restoredSetup.updatedAt)
    }
}

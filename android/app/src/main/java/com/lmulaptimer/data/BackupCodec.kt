package com.lmulaptimer.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class BackupContent(
    val sessions: List<SessionRow>,
    val lapsBySession: Map<String, List<LapRow>>,
    val setups: List<SetupRow>,
)

/**
 * Portable JSON format for manual backups and the ZIP export. It intentionally
 * contains every local field that is not reproducible from LMU: setup payloads,
 * session/setup links and stint/in-lap markers included.
 */
object BackupCodec {

    fun encode(
        sessions: List<SessionRow>,
        lapsBySession: Map<String, List<LapRow>>,
        setups: List<SetupRow>,
    ): String {
        val root = JSONObject()
        root.put("app", "LMU Laptimer")
        root.put("format", 2)
        root.put("exported_at", Instant.now().toString())

        val sessionsJson = JSONArray()
        sessions.forEach { session ->
            val obj = JSONObject()
            obj.put("id", session.id)
            obj.put("started_at", Instant.ofEpochMilli(session.startedAt).toString())
            obj.put("track", session.track)
            obj.put("car", session.car)
            obj.put("car_class", session.carClass)
            obj.put("session_type", session.sessionType)
            obj.put("lap_count", session.lapCount)
            session.bestLapMs?.let { obj.put("best_lap_ms", it) }
            obj.put("synced", session.synced)
            obj.put("setup_id", session.setupId)
            obj.put(
                "events",
                JSONArray().apply {
                    session.events.forEach { event ->
                        put(
                            JSONObject()
                                .put("time_s", event.timeS)
                                .put("kind", event.kind)
                                .put("message", event.message)
                        )
                    }
                },
            )

            val lapsJson = JSONArray()
            lapsBySession[session.id].orEmpty().forEach { lap ->
                lapsJson.put(
                    JSONObject().apply {
                        put("id", lap.id)
                        put("session_id", lap.sessionId)
                        put("track", lap.track)
                        put("car", lap.car)
                        put("session_type", lap.sessionType)
                        put("lap_number", lap.lapNumber)
                        put("lap_time_ms", lap.lapTimeMs)
                        put("sector1_ms", lap.sector1Ms ?: JSONObject.NULL)
                        put("sector2_ms", lap.sector2Ms ?: JSONObject.NULL)
                        put("sector3_ms", lap.sector3Ms ?: JSONObject.NULL)
                        put("valid", lap.valid)
                        put("out_lap", lap.outLap)
                        put("in_lap", lap.inLap)
                        put("stint_number", lap.stintNumber)
                        put("fuel_used_l", lap.fuelUsedL)
                        put("ve_used", lap.veUsed)
                        put("fuel_start_l", lap.fuelStartL)
                        put("ve_start", lap.veStart)
                        put("top_speed_kmh", lap.topSpeedKmh)
                        put("reference", lap.isReference)
                        put("started_at", Instant.ofEpochMilli(lap.startedAt).toString())
                        put(
                            "conditions",
                            JSONObject()
                                .put("air_temp_c", lap.airTempC)
                                .put("track_temp_c", lap.trackTempC)
                                .put("rain", lap.rain)
                                .put("wetness", lap.wetness)
                                .put("grip", lap.grip),
                        )
                    }
                )
            }
            obj.put("laps", lapsJson)
            sessionsJson.put(obj)
        }
        root.put("sessions", sessionsJson)

        val setupsJson = JSONArray()
        setups.forEach { setup ->
            setupsJson.put(
                JSONObject().apply {
                    put("id", setup.id)
                    put("track", setup.track)
                    put("car", setup.car)
                    put("name", setup.name)
                    put("notes", setup.notes)
                    put("updatedAt", setup.updatedAt)
                    put("payload", setup.payload)
                }
            )
        }
        root.put("setups", setupsJson)
        return root.toString(2)
    }

    fun decode(raw: String): BackupContent {
        val root = JSONObject(raw)
        val sessionsJson = root.optJSONArray("sessions") ?: JSONArray()
        val sessions = mutableListOf<SessionRow>()
        val lapsBySession = mutableMapOf<String, List<LapRow>>()

        for (index in 0 until sessionsJson.length()) {
            val obj = sessionsJson.optJSONObject(index) ?: continue
            val session = parseSession(obj)
            if (session.id.isBlank()) continue
            val lapsJson = obj.optJSONArray("laps") ?: JSONArray()
            val laps = buildList {
                for (lapIndex in 0 until lapsJson.length()) {
                    val lapJson = lapsJson.optJSONObject(lapIndex) ?: continue
                    parseLap(lapJson, session.track, session.car, session.sessionType)?.let(::add)
                }
            }
            sessions += session
            lapsBySession[session.id] = laps
        }

        val setupsJson = root.optJSONArray("setups") ?: JSONArray()
        val setups = buildList {
            for (index in 0 until setupsJson.length()) {
                val obj = setupsJson.optJSONObject(index) ?: continue
                val id = obj.optString("id", "")
                if (id.isBlank()) continue
                add(
                    SetupRow(
                        id = id,
                        track = obj.optString("track", ""),
                        car = obj.optString("car", ""),
                        name = obj.optString("name", ""),
                        notes = obj.optString("notes", ""),
                        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                        payload = obj.optString("payload", ""),
                    )
                )
            }
        }

        return BackupContent(sessions, lapsBySession, setups)
    }
}

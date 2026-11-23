package com.lmulaptimer.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

class Db(context: Context) : SQLiteOpenHelper(context, "lmu_laptimer.db", null, 8) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // WAL lets archive reads continue while sync/sample writes happen.
        db.enableWriteAheadLogging()
        db.execSQL("PRAGMA synchronous = NORMAL")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sessions(
                id TEXT PRIMARY KEY,
                startedAt INTEGER NOT NULL,
                track TEXT NOT NULL,
                car TEXT NOT NULL,
                carClass TEXT NOT NULL,
                sessionType TEXT NOT NULL,
                lapCount INTEGER NOT NULL,
                bestLapMs INTEGER,
                synced INTEGER NOT NULL,
                setupId TEXT NOT NULL DEFAULT '',
                eventsJson TEXT NOT NULL DEFAULT '[]'
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE laps(
                id TEXT PRIMARY KEY,
                sessionId TEXT NOT NULL,
                track TEXT NOT NULL,
                car TEXT NOT NULL,
                sessionType TEXT NOT NULL,
                lapNumber INTEGER NOT NULL,
                lapTimeMs INTEGER NOT NULL,
                s1 INTEGER,
                s2 INTEGER,
                s3 INTEGER,
                valid INTEGER NOT NULL,
                outLap INTEGER NOT NULL,
                fuelUsed REAL NOT NULL,
                veUsed REAL NOT NULL,
                fuelStart REAL NOT NULL DEFAULT 0,
                veStart REAL NOT NULL DEFAULT 0,
                topSpeed REAL NOT NULL,
                startedAt INTEGER NOT NULL,
                airTemp REAL NOT NULL,
                trackTemp REAL NOT NULL,
                rain REAL NOT NULL DEFAULT 0,
                wetness REAL NOT NULL DEFAULT 0,
                grip INTEGER NOT NULL DEFAULT 0,
                isReference INTEGER NOT NULL DEFAULT 0,
                stintNumber INTEGER NOT NULL DEFAULT 1,
                inLap INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_laps_track ON laps(track, valid, outLap)")
        db.execSQL("CREATE INDEX idx_laps_session ON laps(sessionId)")
        db.execSQL("CREATE TABLE deleted_sessions(id TEXT PRIMARY KEY)")
        db.execSQL(
            """
            CREATE TABLE sample_cache(
                lapId TEXT PRIMARY KEY,
                payload TEXT NOT NULL,
                savedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE setups(
                id TEXT PRIMARY KEY,
                track TEXT NOT NULL,
                car TEXT NOT NULL,
                name TEXT NOT NULL,
                notes TEXT NOT NULL,
                updatedAt INTEGER NOT NULL,
                payload TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE laps ADD COLUMN fuelStart REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE laps ADD COLUMN veStart REAL NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE TABLE IF NOT EXISTS deleted_sessions(id TEXT PRIMARY KEY)")
        }
        if (oldVersion < 4) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS setups(
                    id TEXT PRIMARY KEY,
                    track TEXT NOT NULL,
                    car TEXT NOT NULL,
                    name TEXT NOT NULL,
                    notes TEXT NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE laps ADD COLUMN rain REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE laps ADD COLUMN wetness REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE laps ADD COLUMN grip INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE laps ADD COLUMN isReference INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN setupId TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE laps ADD COLUMN stintNumber INTEGER NOT NULL DEFAULT 1")
        }
        if (oldVersion < 8) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN eventsJson TEXT NOT NULL DEFAULT '[]'")
        }
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE laps ADD COLUMN inLap INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE setups ADD COLUMN payload TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS sample_cache(
                    lapId TEXT PRIMARY KEY,
                    payload TEXT NOT NULL,
                    savedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    fun putSampleCache(lapId: String, payload: String) {
        writableDatabase.insertWithOnConflict(
            "sample_cache", null,
            ContentValues().apply {
                put("lapId", lapId)
                put("payload", payload)
                put("savedAt", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun sampleCache(lapId: String): String? =
        readableDatabase.rawQuery(
            "SELECT payload FROM sample_cache WHERE lapId = ?", arrayOf(lapId),
        ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun sampleCacheCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM sample_cache", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun sampleCacheBytes(): Long =
        readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(LENGTH(payload)), 0) FROM sample_cache", null,
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun deleteSampleCache(lapId: String): Int =
        writableDatabase.delete("sample_cache", "lapId = ?", arrayOf(lapId))

    fun clearSampleCache(): Int = writableDatabase.delete("sample_cache", null, null)

    fun setSessionSetup(sessionId: String, setupId: String) {
        writableDatabase.execSQL(
            "UPDATE sessions SET setupId = ? WHERE id = ?", arrayOf(setupId, sessionId),
        )
    }

    fun allLaps(): List<LapRow> =
        readableDatabase.rawQuery("SELECT * FROM laps ORDER BY startedAt ASC", null)
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toLap()) } }

    fun sessionSetupId(sessionId: String): String? =
        readableDatabase.rawQuery(
            "SELECT setupId FROM sessions WHERE id = ?", arrayOf(sessionId),
        ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun sessionExists(id: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM sessions WHERE id = ? UNION SELECT 1 FROM deleted_sessions WHERE id = ?",
            arrayOf(id, id),
        ).use { it.moveToFirst() }

    fun sessionDeleted(id: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM deleted_sessions WHERE id = ?",
            arrayOf(id),
        ).use { it.moveToFirst() }

    fun countedLapsForSession(sessionId: String): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM laps WHERE sessionId = ? AND outLap = 0",
            arrayOf(sessionId),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun totalLapsForSession(sessionId: String): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM laps WHERE sessionId = ?",
            arrayOf(sessionId),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun lapIdsForSession(sessionId: String): Set<String> =
        readableDatabase.rawQuery(
            "SELECT id FROM laps WHERE sessionId = ?",
            arrayOf(sessionId),
        ).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    fun referenceLapIds(sessionId: String): Set<String> =
        readableDatabase.rawQuery(
            "SELECT id FROM laps WHERE sessionId = ? AND isReference = 1",
            arrayOf(sessionId),
        ).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    fun upsertSession(session: SessionRow) {
        writableDatabase.insertWithOnConflict("sessions", null, session.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun upsertLaps(laps: List<LapRow>) {
        if (laps.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            laps.forEach { db.insertWithOnConflict("laps", null, it.toValues(), SQLiteDatabase.CONFLICT_REPLACE) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun sessions(): List<SessionRow> =
        readableDatabase.rawQuery(
            """
            SELECT s.*,
                   (SELECT COUNT(*) FROM laps l
                    WHERE l.sessionId = s.id AND l.outLap = 0) AS countedLaps,
                   (SELECT MIN(l.lapTimeMs) FROM laps l
                    WHERE l.sessionId = s.id AND l.valid = 1 AND l.outLap = 0
                      AND l.lapTimeMs > 0) AS countedBestLapMs
            FROM sessions s
            ORDER BY s.startedAt DESC
            """.trimIndent(),
            null,
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toSession()) }
        }

    fun laps(): List<LapRow> =
        readableDatabase.rawQuery("SELECT * FROM laps ORDER BY startedAt DESC", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toLap()) }
        }

    fun lapsFor(track: String): List<LapRow> =
        readableDatabase.rawQuery(
            "SELECT * FROM laps WHERE track = ? ORDER BY startedAt DESC",
            arrayOf(track),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toLap()) } }

    fun lapsForSession(sessionId: String): List<LapRow> =
        readableDatabase.rawQuery(
            "SELECT * FROM laps WHERE sessionId = ? ORDER BY lapNumber ASC",
            arrayOf(sessionId),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toLap()) } }

    fun setups(): List<SetupRow> =
        readableDatabase.rawQuery("SELECT * FROM setups ORDER BY updatedAt DESC", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        SetupRow(
                            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                            track = cursor.getString(cursor.getColumnIndexOrThrow("track")),
                            car = cursor.getString(cursor.getColumnIndexOrThrow("car")),
                            name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                            notes = cursor.getString(cursor.getColumnIndexOrThrow("notes")),
                            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updatedAt")),
                            payload = cursor.getColumnIndexOrThrow("payload").let {
                                if (cursor.isNull(it)) "" else cursor.getString(it)
                            },
                        )
                    )
                }
            }
        }

    fun upsertSetup(setup: SetupRow) {
        writableDatabase.insertWithOnConflict(
            "setups",
            null,
            ContentValues().apply {
                put("id", setup.id)
                put("track", setup.track)
                put("car", setup.car)
                put("name", setup.name)
                put("notes", setup.notes)
                put("updatedAt", setup.updatedAt)
                put("payload", setup.payload)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun deleteSetup(id: String): Int = writableDatabase.delete("setups", "id = ?", arrayOf(id))

    fun tracks(): List<String> =
        readableDatabase.rawQuery(
            "SELECT track, MIN(lapTimeMs) FROM laps WHERE valid = 1 AND outLap = 0 GROUP BY track ORDER BY MIN(lapTimeMs)",
            null,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    fun personalBests(): List<PersonalBest> =
        readableDatabase.rawQuery(
            """
            SELECT track, car, carClass,
                   CASE WHEN rain > 0.05 OR wetness > 0.05 THEN 1 ELSE 0 END AS wet,
                   MIN(lapTimeMs) AS best,
                   COUNT(*) AS laps,
                   MAX(startedAt) AS lastAt
            FROM laps
            WHERE valid = 1 AND outLap = 0 AND lapTimeMs > 0
            GROUP BY track, car, carClass,
                     CASE WHEN rain > 0.05 OR wetness > 0.05 THEN 1 ELSE 0 END
            ORDER BY best
            """.trimIndent(),
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PersonalBest(
                            track = cursor.getString(0),
                            car = cursor.getString(1),
                            carClass = cursor.getString(2),
                            wet = cursor.getInt(3) == 1,
                            bestMs = cursor.getLong(4),
                            lapCount = cursor.getInt(5),
                            lastAt = cursor.getLong(6),
                        )
                    )
                }
            }
        }

    /**
     * Reference for the live delta: a pinned lap for this car/condition wins,
     * then the best lap of the same car. A different car is never substituted;
     * only an unknown car falls back to the best lap of the track.
     */
    fun referenceLap(track: String, car: String, wet: Boolean): LapRow? {
        if (track.isBlank()) return null
        val wetValue = if (wet) 1 else 0
        val wetExpr = "CASE WHEN rain > 0.05 OR wetness > 0.05 THEN 1 ELSE 0 END"

        val pinnedArgs = mutableListOf(track, wetValue.toString())
        var pinnedSql = "SELECT * FROM laps WHERE track = ? AND isReference = 1 " +
            "AND valid = 1 AND outLap = 0 AND $wetExpr = ?"
        if (car.isNotBlank()) {
            pinnedSql += " AND car = ?"
            pinnedArgs += car
        }
        pinnedSql += " ORDER BY startedAt DESC LIMIT 1"
        queryLap(pinnedSql, pinnedArgs.toTypedArray())?.let { return it }

        val bestArgs = mutableListOf(track, wetValue.toString())
        var bestSql = "SELECT * FROM laps WHERE track = ? AND valid = 1 AND outLap = 0 " +
            "AND lapTimeMs > 0 AND $wetExpr = ?"
        if (car.isNotBlank()) {
            return queryLap(
                bestSql + " AND car = ? ORDER BY lapTimeMs ASC LIMIT 1",
                (bestArgs + car).toTypedArray(),
            )
        }
        return queryLap(bestSql + " ORDER BY lapTimeMs ASC LIMIT 1", bestArgs.toTypedArray())
    }

    /** Pins one lap per track, car and dry/wet condition. */
    fun setReference(lapId: String): Boolean {
        val db = writableDatabase
        val key = db.rawQuery(
            "SELECT track, car, rain, wetness FROM laps WHERE id = ?",
            arrayOf(lapId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return false
            ReferenceKey(
                track = cursor.getString(0),
                car = cursor.getString(1),
                wet = cursor.getDouble(2) > 0.05 || cursor.getDouble(3) > 0.05,
            )
        }
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE laps SET isReference = 0 WHERE track = ? AND car = ? " +
                    "AND (CASE WHEN rain > 0.05 OR wetness > 0.05 THEN 1 ELSE 0 END) = ?",
                arrayOf<Any>(key.track, key.car, if (key.wet) 1 else 0),
            )
            db.execSQL("UPDATE laps SET isReference = 1 WHERE id = ?", arrayOf<Any>(lapId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return true
    }

    private fun queryLap(sql: String, args: Array<String>): LapRow? =
        readableDatabase.rawQuery(sql, args).use { cursor ->
            if (cursor.moveToFirst()) cursor.toLap() else null
        }

    private data class ReferenceKey(val track: String, val car: String, val wet: Boolean)

    fun averageFuelPerLap(track: String): Double? =
        readableDatabase.rawQuery(
            "SELECT AVG(fuelUsed) FROM laps WHERE track = ? AND valid = 1 AND outLap = 0 AND fuelUsed > 0",
            arrayOf(track),
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getDouble(0) else null
        }

    fun averageLap(track: String): Double? =
        readableDatabase.rawQuery(
            "SELECT AVG(lapTimeMs) FROM laps WHERE track = ? AND valid = 1 AND outLap = 0 AND lapTimeMs > 0",
            arrayOf(track),
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getDouble(0) else null
        }

    fun strategyPresets(): List<StrategyPreset> =
        readableDatabase.rawQuery(
            """
            SELECT track, car, AVG(lapTimeMs) AS lapMs,
                   AVG(fuelUsed) AS fuel,
                   AVG(CASE WHEN veUsed > 0 THEN veUsed END) AS ve,
                   MAX(fuelStart) AS tank,
                   MAX(startedAt) AS lastAt
            FROM laps
            WHERE valid = 1 AND outLap = 0 AND lapTimeMs > 0 AND fuelUsed > 0
            GROUP BY track, car
            ORDER BY lastAt DESC
            """.trimIndent(),
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        StrategyPreset(
                            track = cursor.getString(0),
                            car = cursor.getString(1),
                            lapMs = cursor.getLong(2),
                            fuelPerLapL = cursor.getDouble(3),
                            vePerLap = if (cursor.isNull(4)) 0.0 else cursor.getDouble(4),
                            tankL = if (cursor.isNull(5)) 0.0 else cursor.getDouble(5),
                            lastAt = cursor.getLong(6),
                        )
                    )
                }
            }
        }

    fun demoCandidates(): DemoCandidates {
        val sessionIds = readableDatabase.rawQuery(
            """
            SELECT s.id
            FROM sessions s
            JOIN laps l ON l.sessionId = s.id
            WHERE s.track = 'Circuit de la Sarthe' AND s.car = 'Ferrari 499P'
            GROUP BY s.id
            HAVING SUM(
                CASE WHEN ABS(l.fuelUsed - 3.1) <= 0.02
                      AND ABS(l.veUsed - 0.031) <= 0.001
                      AND ABS(l.airTemp - 21.5) <= 0.01
                      AND ABS(l.trackTemp - 28.0) <= 0.01
                     THEN 0 ELSE 1 END
            ) = 0
            """.trimIndent(),
            null,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        if (sessionIds.isEmpty()) return DemoCandidates(emptyList(), 0, 0)
        val placeholders = sessionIds.joinToString(",") { "?" }
        val laps = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM laps WHERE sessionId IN ($placeholders)",
            sessionIds.toTypedArray(),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return DemoCandidates(sessionIds, sessionIds.size, laps)
    }

    fun deleteSessions(ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        val db = writableDatabase
        val placeholders = ids.joinToString(",") { "?" }
        db.beginTransaction()
        try {
            ids.forEach { id ->
                db.insertWithOnConflict(
                    "deleted_sessions",
                    null,
                    ContentValues().apply { put("id", id) },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.delete("laps", "sessionId IN ($placeholders)", ids.toTypedArray())
            val deleted = db.delete("sessions", "id IN ($placeholders)", ids.toTypedArray())
            db.setTransactionSuccessful()
            return deleted
        } finally {
            db.endTransaction()
        }
    }

    fun countLaps(): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM laps WHERE outLap = 0",
            null,
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun Cursor.toSession(): SessionRow = SessionRow(
        id = getString(getColumnIndexOrThrow("id")),
        startedAt = getLong(getColumnIndexOrThrow("startedAt")),
        track = getString(getColumnIndexOrThrow("track")),
        car = getString(getColumnIndexOrThrow("car")),
        carClass = getString(getColumnIndexOrThrow("carClass")),
        sessionType = getString(getColumnIndexOrThrow("sessionType")),
        lapCount = getInt(getColumnIndexOrThrow("countedLaps")),
        bestLapMs = getColumnIndexOrThrow("countedBestLapMs").let {
            if (isNull(it)) null else getLong(it)
        },
        synced = getInt(getColumnIndexOrThrow("synced")) == 1,
        setupId = getColumnIndexOrThrow("setupId").let { if (isNull(it)) "" else getString(it) },
        events = parseEvents(getColumnIndexOrThrow("eventsJson").let {
            if (isNull(it)) "[]" else getString(it)
        }),
    )

    private fun Cursor.toLap(): LapRow = LapRow(
        id = getString(getColumnIndexOrThrow("id")),
        sessionId = getString(getColumnIndexOrThrow("sessionId")),
        track = getString(getColumnIndexOrThrow("track")),
        car = getString(getColumnIndexOrThrow("car")),
        sessionType = getString(getColumnIndexOrThrow("sessionType")),
        lapNumber = getInt(getColumnIndexOrThrow("lapNumber")),
        lapTimeMs = getLong(getColumnIndexOrThrow("lapTimeMs")),
        sector1Ms = getColumnIndexOrThrow("s1").let { if (isNull(it)) null else getLong(it) },
        sector2Ms = getColumnIndexOrThrow("s2").let { if (isNull(it)) null else getLong(it) },
        sector3Ms = getColumnIndexOrThrow("s3").let { if (isNull(it)) null else getLong(it) },
        valid = getInt(getColumnIndexOrThrow("valid")) == 1,
        outLap = getInt(getColumnIndexOrThrow("outLap")) == 1,
        fuelUsedL = getDouble(getColumnIndexOrThrow("fuelUsed")),
        veUsed = getDouble(getColumnIndexOrThrow("veUsed")),
        fuelStartL = getDouble(getColumnIndexOrThrow("fuelStart")),
        veStart = getDouble(getColumnIndexOrThrow("veStart")),
        topSpeedKmh = getDouble(getColumnIndexOrThrow("topSpeed")),
        startedAt = getLong(getColumnIndexOrThrow("startedAt")),
        airTempC = getDouble(getColumnIndexOrThrow("airTemp")),
        trackTempC = getDouble(getColumnIndexOrThrow("trackTemp")),
        rain = getDouble(getColumnIndexOrThrow("rain")),
        wetness = getDouble(getColumnIndexOrThrow("wetness")),
        grip = getInt(getColumnIndexOrThrow("grip")),
        isReference = getInt(getColumnIndexOrThrow("isReference")) == 1,
        stintNumber = getInt(getColumnIndexOrThrow("stintNumber")),
        inLap = getInt(getColumnIndexOrThrow("inLap")) == 1,
    )

    private fun SessionRow.toValues() = ContentValues().apply {
        put("id", id)
        put("startedAt", startedAt)
        put("track", track)
        put("car", car)
        put("carClass", carClass)
        put("sessionType", sessionType)
        put("lapCount", lapCount)
        put("bestLapMs", bestLapMs)
        put("synced", if (synced) 1 else 0)
        put("setupId", setupId)
        put(
            "eventsJson",
            JSONArray().apply {
                events.forEach { event ->
                    put(
                        JSONObject()
                            .put("time_s", event.timeS)
                            .put("kind", event.kind)
                            .put("message", event.message)
                    )
                }
            }.toString(),
        )
    }

    private fun parseEvents(raw: String): List<com.lmulaptimer.data.SessionEvent> = try {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    com.lmulaptimer.data.SessionEvent(
                        timeS = item.optDouble("time_s", 0.0),
                        kind = item.optString("kind", ""),
                        message = item.optString("message", ""),
                    )
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun LapRow.toValues() = ContentValues().apply {
        put("id", id)
        put("sessionId", sessionId)
        put("track", track)
        put("car", car)
        put("sessionType", sessionType)
        put("lapNumber", lapNumber)
        put("lapTimeMs", lapTimeMs)
        put("s1", sector1Ms)
        put("s2", sector2Ms)
        put("s3", sector3Ms)
        put("valid", if (valid) 1 else 0)
        put("outLap", if (outLap) 1 else 0)
        put("fuelUsed", fuelUsedL)
        put("veUsed", veUsed)
        put("fuelStart", fuelStartL)
        put("veStart", veStart)
        put("topSpeed", topSpeedKmh)
        put("startedAt", startedAt)
        put("airTemp", airTempC)
        put("trackTemp", trackTempC)
        put("rain", rain)
        put("wetness", wetness)
        put("grip", grip)
        put("isReference", if (isReference) 1 else 0)
        put("stintNumber", stintNumber)
        put("inLap", if (inLap) 1 else 0)
    }
}

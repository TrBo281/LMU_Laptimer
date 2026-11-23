package com.lmulaptimer.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class Repo(context: Context) {

    private val db = Db(context)
    private val prefs = context.getSharedPreferences("lmu_laptimer_prefs", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("base_url", "") ?: ""
        set(value) {
            prefs.edit().putString("base_url", value.trim()).apply()
        }

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(value) {
            prefs.edit().putString("token", value.trim()).apply()
        }

    var autoSync: Boolean
        get() = prefs.getBoolean("auto_sync", true)
        set(value) {
            prefs.edit().putBoolean("auto_sync", value).apply()
        }

    var nightMode: Boolean
        get() = prefs.getBoolean("night_mode", false)
        set(value) {
            prefs.edit().putBoolean("night_mode", value).apply()
        }

    var audioEnabled: Boolean
        get() = prefs.getBoolean("audio_enabled", false)
        set(value) {
            prefs.edit().putBoolean("audio_enabled", value).apply()
        }

    var audioVolume: Int
        get() = prefs.getInt("audio_volume", 80)
        set(value) {
            prefs.edit().putInt("audio_volume", value.coerceIn(0, 100)).apply()
        }

    var liveNotifyEnabled: Boolean
        get() = prefs.getBoolean("live_notify_enabled", false)
        set(value) {
            prefs.edit().putBoolean("live_notify_enabled", value).apply()
        }

    var plannerTargetLaps: Int
        get() = prefs.getInt("planner_target_laps", 0)
        set(value) {
            prefs.edit().putInt("planner_target_laps", value.coerceAtLeast(0)).apply()
        }

    var tyreTargetPressureKpa: Double
        get() = prefs.getFloat("tyre_target_pressure", 200f).toDouble()
        set(value) {
            prefs.edit().putFloat("tyre_target_pressure", value.toFloat()).apply()
        }

    var tyreMaxTempC: Double
        get() = prefs.getFloat("tyre_max_temp", 110f).toDouble()
        set(value) {
            prefs.edit().putFloat("tyre_max_temp", value.toFloat()).apply()
        }

    var tyreWearWarnPct: Double
        get() = prefs.getFloat("tyre_wear_warn", 60f).toDouble()
        set(value) {
            prefs.edit().putFloat("tyre_wear_warn", value.toFloat()).apply()
        }

    fun bridge(): Bridge = Bridge(baseUrl, token)

    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext "Keine Bridge-URL eingetragen"
        val json = bridge().health() ?: return@withContext "Keine Verbindung zu $baseUrl"
        val source = json.optString("source", "?")
        val track = json.optString("track", "")
        val connected = json.optBoolean("connected", false)
        buildString {
            append("Verbunden · Quelle: ").append(source)
            if (track.isNotBlank()) append(" · ").append(track)
            if (!connected) append(" · keine Telemetrie")
        }
    }

    suspend fun health(): JSONObject? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) null else bridge().health()
    }

    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext SyncResult(0, 0, "Keine Bridge-URL eingetragen")
        val bridge = bridge()
        val sessions = bridge.sessions() ?: return@withContext SyncResult(0, 0, "Bridge nicht erreichbar")
        var importedSessions = 0
        var importedLaps = 0
        for (index in 0 until sessions.length()) {
            val summary = sessions.optJSONObject(index) ?: continue
            val id = summary.optString("id", "")
            if (id.isBlank() || db.sessionDeleted(id)) continue
            val isNew = !db.sessionExists(id)
            val knownTotal = if (isNew) 0 else db.totalLapsForSession(id)
            val bridgeTotal = summary.optInt(
                "total_laps",
                summary.optInt("lap_count", 0),
            )
            // Compare all laps, not only counted ones: an outlap-only session
            // belongs in the archive too. A session without any lap is skipped.
            if (bridgeTotal <= knownTotal) continue
            val detail = bridge.session(id) ?: continue
            val session = parseSession(detail)
            val lapsJson = detail.optJSONArray("laps") ?: JSONArray()
            val laps = buildList {
                for (lapIndex in 0 until lapsJson.length()) {
                    val lapJson = lapsJson.optJSONObject(lapIndex) ?: continue
                    parseLap(lapJson, session.track, session.car, session.sessionType)?.let(::add)
                }
            }
            if (laps.isEmpty()) continue
            val existingIds = if (isNew) emptySet() else db.lapIdsForSession(id)
            val localReferences = if (isNew) emptySet() else db.referenceLapIds(id)
            val lapsToStore = laps.map { lap ->
                if (lap.id in localReferences) lap.copy(isReference = true) else lap
            }
            val localSetupId = if (isNew) "" else db.sessionSetupId(id).orEmpty()
            db.upsertSession(if (localSetupId.isNotBlank()) session.copy(setupId = localSetupId) else session)
            db.upsertLaps(lapsToStore)
            bridge.ack(id)
            if (isNew) importedSessions += 1
            importedLaps += laps.count { it.id !in existingIds }
        }
        SyncResult(importedSessions, importedLaps, null)
    }

    suspend fun live(): Live? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) null else bridge().live()
    }

    /**
     * Live snapshots over the bridge WebSocket with a REST fallback. The
     * bridge pushes up to 30 Hz; conflation keeps only the newest snapshot
     * when Compose cannot render every packet, so latency never grows.
     */
    fun liveUpdates(pollIntervalMs: Long = 1_000L): Flow<Live?> = callbackFlow {
        if (baseUrl.isBlank()) {
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }
        val client = bridge()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val closed = AtomicBoolean(false)
        val lastMessageAt = AtomicLong(0L)
        val retryMs = AtomicLong(1_000L)
        val generation = AtomicLong(0L)
        val socket = AtomicReference<WebSocket?>(null)
        val emit: (Live?) -> Unit = { trySend(it) }

        val connection = object {
            fun connect() {
                if (closed.get()) return
                val gen = generation.incrementAndGet()
                socket.getAndSet(null)?.cancel()
                val liveSocket = client.openLiveSocket(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        retryMs.set(1_000L)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val snapshot = runCatching { parseLive(JSONObject(text)) }.getOrNull() ?: return
                        lastMessageAt.set(System.currentTimeMillis())
                        emit(snapshot)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (gen == generation.get()) {
                            socket.compareAndSet(webSocket, null)
                            scheduleReconnect(gen)
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (gen == generation.get()) {
                            socket.compareAndSet(webSocket, null)
                            scheduleReconnect(gen)
                        }
                    }
                })
                socket.set(liveSocket)
            }

            fun scheduleReconnect(gen: Long) {
                if (closed.get() || gen != generation.get()) return
                scope.launch {
                    delay(retryMs.get())
                    retryMs.set((retryMs.get() * 2).coerceAtMost(10_000L))
                    if (!closed.get() && gen == generation.get()) connect()
                }
            }
        }

        val poller = scope.launch {
            emit(client.live())
            while (isActive) {
                delay(pollIntervalMs)
                if (System.currentTimeMillis() - lastMessageAt.get() > 2_500L) {
                    emit(client.live())
                }
            }
        }

        connection.connect()

        awaitClose {
            closed.set(true)
            poller.cancel()
            socket.getAndSet(null)?.let {
                it.close(1000, "screen closed")
                it.cancel()
            }
            scope.cancel()
        }
    }.conflate()

    suspend fun lapSamples(lapId: String): JSONObject? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) null else bridge().samples(lapId)
    }

    suspend fun lapDelta(lapId: String, referenceId: String): JSONObject? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) null else bridge().delta(lapId, referenceId)
    }

    fun personalBests(): List<PersonalBest> = db.personalBests()
    fun lapsFor(track: String): List<LapRow> = db.lapsFor(track)
    fun lapsForSession(sessionId: String): List<LapRow> = db.lapsForSession(sessionId)
    fun tracks(): List<String> = db.tracks()
    fun sessions(): List<SessionRow> = db.sessions()

    /** Consecutive local days with at least one session, ending today or yesterday. */
    fun trainingStreak(): Int = TrainingStreak.compute(
        db.sessions().map { session ->
            Instant.ofEpochMilli(session.startedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        }
    )
    fun countLaps(): Int = db.countLaps()
    fun averageFuel(track: String): Double? = db.averageFuelPerLap(track)
    fun averageLap(track: String): Double? = db.averageLap(track)
    fun strategyPresets(): List<StrategyPreset> = db.strategyPresets()
    fun demoCandidates(): DemoCandidates = db.demoCandidates()
    fun deleteDemoSessions(): Int = db.deleteSessions(db.demoCandidates().sessionIds)
    fun bestLap(track: String, excludeId: String? = null): LapRow? =
        db.lapsFor(track)
            .filter { it.valid && !it.outLap && it.lapTimeMs > 0 && it.id != excludeId }
            .minByOrNull { it.lapTimeMs }

    /** Pinned or fastest lap for the live delta, matching car and conditions. */
    fun referenceLap(track: String, car: String, wet: Boolean): LapRow? =
        db.referenceLap(track, car, wet)

    fun setReference(lapId: String): Boolean = db.setReference(lapId)

    fun setups(): List<SetupRow> = db.setups()

    fun saveSetup(setup: SetupRow) = db.upsertSetup(setup)

    fun deleteSetup(id: String): Int = db.deleteSetup(id)

    /** Full archive as a portable JSON document (sessions, laps and setups). */
    fun exportArchive(): String {
        val sessions = db.sessions()
        val laps = sessions.associate { it.id to db.lapsForSession(it.id) }
        return BackupCodec.encode(sessions, laps, db.setups())
    }

    /** Full ZIP backup: metadata plus raw sample payload for every cached lap. */
    fun exportFullBackup(output: OutputStream) {
        val sessions = db.sessions()
        val laps = sessions.associate { it.id to db.lapsForSession(it.id) }
        val samples = db.allLaps().mapNotNull { lap -> db.sampleCache(lap.id)?.let { lap.id to it } }.toMap()
        BackupArchive.write(
            output,
            BackupCodec.encode(sessions, laps, db.setups()),
            samples,
            mapOf("laps.csv" to exportLapsCsv(), "samples.csv" to exportSamplesCsv()),
        )
    }

    /** Restores a full ZIP backup (backup.json + samples/<lap-id>.json). */
    fun importFullBackup(input: InputStream): Pair<Int, Int> {
        val (raw, samples) = BackupArchive.read(input)
        val result = importArchive(raw)
        samples.forEach { (lapId, payload) -> db.putSampleCache(lapId, payload) }
        return result
    }

    /** Import a backup; existing sessions and tombstones are left untouched. */
    fun importArchive(raw: String): Pair<Int, Int> {
        val backup = BackupCodec.decode(raw)
        var importedSessions = 0
        var importedLaps = 0
        for (session in backup.sessions) {
            if (db.sessionExists(session.id)) continue
            val laps = backup.lapsBySession[session.id].orEmpty()
            db.upsertSession(session)
            db.upsertLaps(laps)
            importedSessions += 1
            importedLaps += laps.count { !it.outLap }
        }
        for (setup in backup.setups) {
            db.upsertSetup(setup)
        }
        return importedSessions to importedLaps
    }

    // ---- Stage 2: offline samples and local delta ----

    suspend fun lapSamplesCached(lapId: String): CachedSamples? = withContext(Dispatchers.IO) {
        val remote = if (baseUrl.isBlank()) null else bridge().samples(lapId)
        if (remote != null) {
            db.putSampleCache(lapId, remote.toString())
            CachedSamples(remote, cached = false)
        } else {
            db.sampleCache(lapId)?.let { raw ->
                runCatching { CachedSamples(JSONObject(raw), cached = true) }.getOrNull()
            }
        }
    }

    suspend fun compareLaps(lapA: LapRow, lapB: LapRow): LapCompare? = withContext(Dispatchers.IO) {
        val a = lapSamplesCached(lapA.id)?.payload?.let { LapSamples.from(it) }
        val b = lapSamplesCached(lapB.id)?.payload?.let { LapSamples.from(it) }
        if (a == null || b == null) null else Samples.compare(a, b)
    }

    fun cachedSampleCount(): Int = db.sampleCacheCount()
    fun clearSampleCache(): Int = db.clearSampleCache()
    fun cachedSampleBytes(): Long = db.sampleCacheBytes()
    fun deleteCachedSamples(lapId: String): Int = db.deleteSampleCache(lapId)

    // ---- Stage 6: setup assignment ----

    fun sessionSetupId(sessionId: String): String? = db.sessionSetupId(sessionId)
    fun setSessionSetup(sessionId: String, setupId: String) {
        db.setSessionSetup(sessionId, setupId)
    }

    // ---- Stage 10: CSV export ----

    private fun csv(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains("\n")) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value

    fun exportLapsCsv(): String = buildString {
        appendLine("session_id,started_at,track,car,car_class,session_type,setup_id,lap_id,lap_number,lap_time_ms,s1_ms,s2_ms,s3_ms,valid,out_lap,in_lap,stint,fuel_used_l,ve_used,rain,wetness")
        for (session in db.sessions()) {
            for (lap in db.lapsForSession(session.id)) {
                append(csv(session.id)).append(',')
                append(Instant.ofEpochMilli(lap.startedAt)).append(',')
                append(csv(lap.track)).append(',')
                append(csv(lap.car)).append(',')
                append(csv(session.carClass)).append(',')
                append(csv(lap.sessionType)).append(',')
                append(csv(session.setupId)).append(',')
                append(csv(lap.id)).append(',')
                append(lap.lapNumber).append(',')
                append(lap.lapTimeMs).append(',')
                append(lap.sector1Ms ?: "").append(',')
                append(lap.sector2Ms ?: "").append(',')
                append(lap.sector3Ms ?: "").append(',')
                append(if (lap.valid) 1 else 0).append(',')
                append(if (lap.outLap) 1 else 0).append(',')
                append(if (lap.inLap) 1 else 0).append(',')
                append(lap.stintNumber).append(',')
                append(lap.fuelUsedL).append(',')
                append(lap.veUsed).append(',')
                append(lap.rain).append(',')
                append(lap.wetness).appendLine()
            }
        }
    }

    fun exportSamplesCsv(): String = buildString {
        appendLine("lap_id,time_s,distance_m,speed_kmh,throttle,brake,gear,fuel_l,virtual_energy,pos_x,pos_z")
        for (lap in db.allLaps()) {
            val samples = Samples.parse(db.sampleCache(lap.id)) ?: continue
            for (index in 0 until samples.count) {
                append(csv(lap.id)).append(',')
                append(samples.timeS[index]).append(',')
                append(samples.distanceM[index]).append(',')
                append(samples.speedKmh[index]).append(',')
                append(samples.throttle[index]).append(',')
                append(samples.brake[index]).append(',')
                append(samples.gear[index]).append(',')
                append(samples.fuelL.getOrElse(index) { 0f }).append(',')
                append(samples.virtualEnergy.getOrElse(index) { 0f }).append(',')
                append(samples.posX.getOrElse(index) { 0f }).append(',')
                append(samples.posZ.getOrElse(index) { 0f }).appendLine()
            }
        }
    }

    fun allLaps(): List<LapRow> = db.allLaps()

    // ---- Stage 9/11: storage, purge, Hue ----

    suspend fun storageInfo(): JSONObject? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) null else bridge().storage()
    }

    suspend fun purgeAllSamples(): Int = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext 0
        val bridge = bridge()
        var purged = 0
        for (session in db.sessions()) {
            if (bridge.purgeSamples(session.id)) purged++
        }
        purged
    }

    suspend fun purgeSession(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) false else bridge().purgeSession(sessionId)
    }

    suspend fun hueConfig(): JSONObject? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) null else bridge().hue()
    }

    suspend fun saveHue(config: JSONObject): Boolean = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) false else bridge().saveHue(config) != null
    }

    suspend fun testHue(): JSONObject? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) null else bridge().testHue()
    }
}

data class CachedSamples(val payload: JSONObject, val cached: Boolean)


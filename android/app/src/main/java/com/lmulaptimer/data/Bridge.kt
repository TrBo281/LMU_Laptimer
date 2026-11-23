package com.lmulaptimer.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class Bridge(private val baseUrl: String, private val token: String) {

    private val client = sharedClient

    private val root: String = baseUrl.trim().trimEnd('/')

    private companion object {
        // Reuse the connection pool and dispatcher for HTTP and WebSocket.
        val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    private fun call(path: String, post: Boolean = false, body: JSONObject? = null): String? {
        if (root.isBlank()) return null
        val builder = Request.Builder().url(root + path)
        if (token.isNotBlank()) builder.header("X-Bridge-Token", token)
        if (post) {
            val raw = body?.toString().orEmpty()
            builder.post(raw.toRequestBody("application/json".toMediaType()))
        }
        return try {
            client.newCall(builder.build()).execute().use { response ->
                if (response.isSuccessful) response.body.string() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private inline fun <T> parse(raw: String?, block: (String) -> T): T? = try {
        if (raw == null) null else block(raw)
    } catch (_: Exception) {
        null
    }

    fun health(): JSONObject? = parse(call("/health")) { JSONObject(it) }

    fun live(): Live? = parse(call("/api/live")) { parseLive(JSONObject(it)) }

    fun sessions(): JSONArray? = parse(call("/api/sessions")) { JSONArray(it) }

    fun session(id: String): JSONObject? = parse(call("/api/sessions/$id")) { JSONObject(it) }

    fun ack(id: String): Boolean = call("/api/sessions/$id/ack", post = true) != null

    fun samples(lapId: String): JSONObject? =
        parse(call("/api/laps/$lapId/samples")) { JSONObject(it) }

    fun delta(lapId: String, referenceId: String): JSONObject? =
        parse(call("/api/laps/$lapId/delta?reference=$referenceId")) { JSONObject(it) }

    fun storage(): JSONObject? = parse(call("/api/storage")) { JSONObject(it) }

    fun purgeSamples(sessionId: String): Boolean =
        call("/api/sessions/$sessionId/purge-samples", post = true) != null

    fun purgeSession(sessionId: String): Boolean =
        call("/api/sessions/$sessionId/purge", post = true) != null

    fun hue(): JSONObject? = parse(call("/api/hue")) { JSONObject(it) }

    fun saveHue(config: JSONObject): JSONObject? =
        parse(call("/api/hue", post = true, body = config)) { JSONObject(it) }

    fun testHue(): JSONObject? = parse(call("/api/hue/test", post = true)) { JSONObject(it) }

    /** Opens the bridge's push feed. The token travels as a query parameter
     *  because that is what the WebSocket endpoint validates. */
    fun openLiveSocket(listener: WebSocketListener): WebSocket? {
        if (root.isBlank()) return null
        return try {
            val wsRoot = when {
                root.startsWith("https://") -> "wss://" + root.removePrefix("https://")
                root.startsWith("http://") -> "ws://" + root.removePrefix("http://")
                else -> root
            }
            val query = if (token.isBlank()) {
                ""
            } else {
                "?token=" + URLEncoder.encode(token, "UTF-8")
            }
            val request = Request.Builder().url("$wsRoot/ws/live$query").build()
            client.newWebSocket(request, listener)
        } catch (_: Exception) {
            null
        }
    }
}

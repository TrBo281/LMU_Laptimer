package com.lmulaptimer.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class DiscoveredBridge(val host: String, val port: Int) {
    val url: String get() = "http://$host:$port"
}

/**
 * Finds the bridge advertised as _lmu-laptimer._tcp. The mDNS record contains
 * no token, so pairing still needs the token from the PC.
 */
suspend fun discoverBridge(context: Context, timeoutMs: Long = 6_000): DiscoveredBridge? =
    suspendCancellableCoroutine { continuation ->
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) {
            if (continuation.isActive) continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        val main = Handler(Looper.getMainLooper())
        val done = AtomicBoolean(false)
        lateinit var listener: NsdManager.DiscoveryListener

        fun finish(result: DiscoveredBridge?) {
            if (!done.compareAndSet(false, true)) return
            main.removeCallbacksAndMessages(null)
            runCatching { nsd.stopServiceDiscovery(listener) }
            if (continuation.isActive) continuation.resume(result)
        }

        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = finish(null)
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = finish(null)
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != "_lmu-laptimer._tcp.") return
                runCatching {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            val host = resolved.host?.hostAddress ?: return
                            finish(DiscoveredBridge(host, resolved.port))
                        }
                    })
                }
            }
        }

        continuation.invokeOnCancellation { finish(null) }
        main.postDelayed({ finish(null) }, timeoutMs)
        runCatching {
            nsd.discoverServices("_lmu-laptimer._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { finish(null) }
    }

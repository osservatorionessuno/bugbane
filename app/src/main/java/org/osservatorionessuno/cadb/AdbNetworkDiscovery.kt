package org.osservatorionessuno.cadb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Discovers ADB Wireless Debugging services on the LAN via mDNS/NSD.
 *
 * Unlike libadb's [io.github.muntashirakon.adb.android.AdbMdns], this reports
 * services on *any* host (including third-party devices), not only the local
 * loopback / same-device interfaces.
 */
class AdbNetworkDiscovery(context: Context) {
    enum class ServiceKind {
        TLS_PAIRING,
        TLS_CONNECT,
    }

    data class DiscoveredService(
        val serviceName: String,
        val host: String,
        val port: Int,
        val kind: ServiceKind,
    ) {
        val key: String get() = "$kind|$host|$port|$serviceName"
    }

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val _services = MutableStateFlow<List<DiscoveredService>>(emptyList())
    val services: StateFlow<List<DiscoveredService>> = _services.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var multicastLock: WifiManager.MulticastLock? = null
    private var pairingListener: NsdManager.DiscoveryListener? = null
    private var connectListener: NsdManager.DiscoveryListener? = null
    private val knownKeys = linkedSetOf<String>()

    fun start() {
        if (_isScanning.value) return
        _isScanning.value = true
        knownKeys.clear()
        _services.value = emptyList()
        acquireMulticastLock()
        pairingListener = startDiscovery(SERVICE_TYPE_TLS_PAIRING, ServiceKind.TLS_PAIRING)
        connectListener = startDiscovery(SERVICE_TYPE_TLS_CONNECT, ServiceKind.TLS_CONNECT)
    }

    fun stop() {
        if (!_isScanning.value) return
        _isScanning.value = false
        pairingListener?.let { stopQuietly(it) }
        connectListener?.let { stopQuietly(it) }
        pairingListener = null
        connectListener = null
        releaseMulticastLock()
    }

    private fun startDiscovery(
        serviceType: String,
        kind: ServiceKind,
    ): NsdManager.DiscoveryListener {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Discovery started for $regType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Start discovery failed for $serviceType: $errorCode")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped for $serviceType")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Stop discovery failed for $serviceType: $errorCode")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                resolveService(serviceInfo, kind)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val host = hostAddress(serviceInfo) ?: return
                val lost = DiscoveredService(
                    serviceName = serviceInfo.serviceName,
                    host = host,
                    port = serviceInfo.port,
                    kind = kind,
                )
                removeService(lost.key)
            }
        }
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        return listener
    }

    private fun resolveService(serviceInfo: NsdServiceInfo, kind: ServiceKind) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.d(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val host = hostAddress(resolved) ?: return
                if (isLocalHost(host)) {
                    Log.d(TAG, "Skipping local service ${resolved.serviceName} at $host")
                    return
                }
                val port = resolved.port
                if (port <= 0) return
                addService(
                    DiscoveredService(
                        serviceName = resolved.serviceName,
                        host = host,
                        port = port,
                        kind = kind,
                    )
                )
            }
        })
    }

    private fun addService(service: DiscoveredService) {
        synchronized(knownKeys) {
            if (!knownKeys.add(service.key)) return
            _services.update { it + service }
        }
        Log.i(TAG, "Found ${service.kind} ${service.serviceName} ${service.host}:${service.port}")
    }

    private fun removeService(key: String) {
        synchronized(knownKeys) {
            if (!knownKeys.remove(key)) return
            _services.update { list -> list.filterNot { it.key == key } }
        }
    }

    private fun stopQuietly(listener: NsdManager.DiscoveryListener) {
        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            Log.d(TAG, "stopServiceDiscovery: ${e.message}")
        }
    }

    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        val lock = wifiManager?.createMulticastLock(TAG) ?: return
        lock.setReferenceCounted(false)
        try {
            lock.acquire()
            multicastLock = lock
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire multicast lock", e)
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
    }

    companion object {
        private const val TAG = "AdbNetworkDiscovery"
        const val SERVICE_TYPE_TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val SERVICE_TYPE_TLS_CONNECT = "_adb-tls-connect._tcp"

        fun hostAddress(info: NsdServiceInfo): String? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val address = info.hostAddresses.firstOrNull() ?: return null
                return normalizeHost(address)
            }
            @Suppress("DEPRECATION")
            val address = info.host ?: return null
            return normalizeHost(address)
        }

        fun normalizeHost(address: InetAddress): String {
            val host = address.hostAddress ?: return address.hostName
            // Strip IPv6 zone id (e.g. fe80::1%wlan0)
            val percent = host.indexOf('%')
            return if (percent >= 0) host.substring(0, percent) else host
        }

        fun isLocalHost(host: String): Boolean {
            if (host == "127.0.0.1" || host == "::1" || host == "0.0.0.0") return true
            return try {
                val target = InetAddress.getByName(host)
                if (target.isLoopbackAddress || target.isAnyLocalAddress) return true
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { nif ->
                    nif.inetAddresses.toList().any { it.hostAddress == host || it == target }
                }
            } catch (_: Exception) {
                false
            }
        }
    }
}

package org.osservatorionessuno.cadb

import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.LocalServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.osservatorionessuno.qf.AcquisitionRunner
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.Volatile

private const val TAG = "AdbManager"

/** Device currently selected for ADB shell/sync (local or LAN). */
data class AdbTarget(
    val host: String,
    val port: Int,
    val label: String,
    val isLocal: Boolean,
)

/** Result of a short-lived TLS probe against a remote wireless-debugging daemon. */
enum class RemoteProbeStatus {
    Checking,
    Ready,
    PairingRequired,
    Unreachable,
}

class AdbManager(applicationContext: Context) {
    private val executor: ExecutorService = Executors.newFixedThreadPool(3)
    private var _adbState = MutableStateFlow<AdbState>(AdbState.Initial)
    val adbState: StateFlow<AdbState> = _adbState.asStateFlow()

    private val _connectedTarget = MutableStateFlow<AdbTarget?>(null)
    val connectedTarget: StateFlow<AdbTarget?> = _connectedTarget.asStateFlow()

    /** Remotes successfully paired/connected; kept for the device dropdown after switching away. */
    private val _knownRemotes = MutableStateFlow<List<AdbTarget>>(emptyList())
    val knownRemotes: StateFlow<List<AdbTarget>> = _knownRemotes.asStateFlow()

    private val _probeStatus = MutableStateFlow<Map<String, RemoteProbeStatus>>(emptyMap())
    val probeStatus: StateFlow<Map<String, RemoteProbeStatus>> = _probeStatus.asStateFlow()
    private val probingHosts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val adbPairingReceiver =
        AdbPairingResultReceiver(
            onSuccess = {
                Log.d(TAG, "paired successfully")
                _adbState.value = AdbState.Ready
                stopAdbPairingService()
                autoConnect()
            },
            onFailure = { errorMessage ->
                Log.e(TAG, "Failed pairing attempt: $errorMessage")
                _adbState.value = AdbState.ErrorConnect
                stopAdbPairingService()
            }
        )

    private val commandOutput = MutableLiveData<CharSequence?>()
    private var qfFuture: Future<*>? = null
    private val qfCancelled = AtomicBoolean(false)
    private val qrPairingCancelled = AtomicBoolean(false)

    private var appContext: Context? = null
    private var adbConnectionManager: AdbConnectionManager

    private var adbShellStream: AdbStream? = null

    internal fun stopAdbPairingService() {
        adbPairingReceiver.let { it ->
            try {
                appContext?.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
                Log.i(TAG, "Can't unregister adbBroadcastReceiver (already unregistered?)")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering adbBroadcastreceiver: $e")
            }
        }

        // Cancel the notification, if it's still showing.
        // Note: keep this cleanup despite onTimeout() in AdbPairingService, because Android
        // versions < 34 don't call onTimeout().
        val stopIntent = AdbPairingService.stopIntent(appContext)
        appContext?.stopService(stopIntent)
    }

    internal fun startAdbPairingService() {
        // Create BroadcastReceiver for pairing results.
        Log.d(TAG, "Start pairing service...")
        val filter = IntentFilter(AdbPairingService.ACTION_PAIRING_RESULT)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // This broadcast is internal to the app, so keep it private
            appContext?.registerReceiver(adbPairingReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // Pre-Android 13: old two-argument API
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext?.registerReceiver(adbPairingReceiver, filter)
        }

        // Start ADB pairing service
        val pairingIntent = AdbPairingService.startIntent(appContext)
        try {
            appContext?.startForegroundService(pairingIntent)
        } catch (ignored: Throwable) {
            appContext?.startService(pairingIntent)
        }
        // (Wait for pairing result, then update state and stop the service)
    }


    fun cleanup() {
        // Might not be running, but just in case.
        stopAdbPairingService()

        val stream = adbShellStream
        adbShellStream = null
        executor.submit(Runnable {
            try {
                stream?.close()
            } catch (e: java.lang.Exception) {
                Log.e(TAG, "Error during cleanup: ${e.message}")
                e.printStackTrace()
            }
        })
        executor.shutdown()
    }

    fun autoConnect() {
        val state = _adbState.value
        if (state !in arrayOf(AdbState.ConnectedIdle, AdbState.ConnectedAcquiring, AdbState.Connecting, AdbState.Cancelling)) {
            executor.submit(Runnable { this.autoConnectInternal() })
        } else {
            Log.w("Bugbane", "autoConnect called but adbState is $state")
        }
    }

    /**
     * Disconnect any current session and connect to this device's Wireless Debugging
     * daemon via mDNS (localhost TLS).
     */
    fun reconnectLocal(onResult: (success: Boolean, errorMessage: String?) -> Unit) {
        executor.submit {
            try {
                _adbState.value = AdbState.Connecting
                disconnectQuietly()
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    _adbState.value = AdbState.ErrorConnect
                    onResult(false, "Unsupported Android version")
                    return@submit
                }
                if (adbConnectionManager.connectTls(this.appContext!!, 5000)) {
                    val host = adbConnectionManager.hostAddress ?: "127.0.0.1"
                    setConnectedTarget(host, port = 0, label = "localhost")
                    _adbState.value = AdbState.ConnectedIdle
                    onResult(true, null)
                } else {
                    _adbState.value = AdbState.ErrorConnect
                    onResult(false, "Connection failed")
                }
            } catch (ie: AdbPairingRequiredException) {
                _adbState.value = AdbState.RequisitesMissing
                onResult(false, "Pairing required")
            } catch (th: Throwable) {
                Log.w(TAG, "reconnectLocal failed", th)
                _adbState.value = AdbState.ErrorConnect
                onResult(false, th.message ?: th.javaClass.simpleName)
            }
        }
    }

    /**
     * Pair with a remote ADB Wireless Debugging daemon on the LAN, then TLS-connect.
     * [onResult] is invoked on a background thread.
     */
    fun pairAndConnectRemote(
        host: String,
        pairingPort: Int?,
        pairingCode: String,
        connectPort: Int?,
        label: String = host,
        /**
         * Live LAN discovery already running in the UI. Prefer this over starting a
         * second NSD scan (Android often fails concurrent discoveries of the same type).
         */
        discovery: AdbNetworkDiscovery? = null,
        onResult: (success: Boolean, errorMessage: String?) -> Unit,
    ) {
        executor.submit {
            try {
                _adbState.value = AdbState.Connecting
                disconnectQuietly()
                val resolvedPairingPort = pairingPort?.takeIf { it > 0 }
                    ?: waitForPairingPort(host, PAIRING_DISCOVERY_TIMEOUT_MS, discovery)
                if (resolvedPairingPort == null || resolvedPairingPort <= 0) {
                    _adbState.value = AdbState.Ready
                    onResult(
                        false,
                        "No pairing port found. On the target, open Wireless debugging → Pair device with pairing code.",
                    )
                    return@submit
                }
                Log.d(TAG, "Pairing with $host:$resolvedPairingPort")
                val paired = adbConnectionManager.pair(host, resolvedPairingPort, pairingCode.trim())
                if (!paired) {
                    _adbState.value = AdbState.ErrorConnect
                    onResult(false, "Pairing rejected")
                    return@submit
                }
                // Use a known connect port immediately; otherwise wait on the existing
                // (or temporary) mDNS discovery. Do not start a competing scan first.
                val port = resolveConnectPort(host, connectPort, discovery)
                if (port == null || port <= 0) {
                    _adbState.value = AdbState.Ready
                    onResult(false, "Paired, but no connect port found yet. Open Wireless Debugging on the target and try Connect.")
                    return@submit
                }
                connectRemoteInternal(host, port, label = label, onResult)
            } catch (th: Throwable) {
                Log.w(TAG, "pairAndConnectRemote failed", th)
                _adbState.value = AdbState.ErrorConnect
                onResult(false, th.message ?: th.javaClass.simpleName)
            }
        }
    }

    /**
     * Wait for a target that scanned our QR ([AdbQrCredentials]) to advertise
     * `_adb-tls-pairing._tcp`, then pair with [AdbQrCredentials.password] and connect.
     * Call [cancelQrPairing] to abort. [onResult] runs on a background thread.
     */
    fun pairViaQr(
        credentials: AdbQrCredentials,
        onResult: (success: Boolean, errorMessage: String?) -> Unit,
    ) {
        qrPairingCancelled.set(false)
        executor.submit {
            val discovery = AdbNetworkDiscovery(appContext!!)
            try {
                _adbState.value = AdbState.Connecting
                disconnectQuietly()
                discovery.start()
                Thread.sleep(500)
                val baselineKeys = discovery.services.value
                    .filter {
                        it.kind == AdbNetworkDiscovery.ServiceKind.TLS_PAIRING &&
                            !AdbNetworkDiscovery.isLocalHost(it.host)
                    }
                    .map { "${it.host}:${it.port}" }
                    .toSet()
                val tried = mutableSetOf<String>()
                val deadline = System.currentTimeMillis() + QR_PAIRING_TIMEOUT_MS
                while (!qrPairingCancelled.get() && System.currentTimeMillis() < deadline) {
                    val pairingServices = discovery.services.value.filter {
                        it.kind == AdbNetworkDiscovery.ServiceKind.TLS_PAIRING &&
                            !AdbNetworkDiscovery.isLocalHost(it.host)
                    }
                    for (service in pairingServices) {
                        if (qrPairingCancelled.get()) break
                        val attemptKey = "${service.host}:${service.port}"
                        val nameMatches = service.serviceName.contains(
                            credentials.serviceName,
                            ignoreCase = true,
                        )
                        if (!nameMatches && attemptKey in baselineKeys) continue
                        if (!tried.add(attemptKey)) continue
                        Log.d(TAG, "QR pairing attempt $attemptKey (${service.serviceName})")
                        try {
                            val paired = adbConnectionManager.pair(
                                service.host,
                                service.port,
                                credentials.password,
                            )
                            if (!paired) continue
                            val port = resolveConnectPort(
                                host = service.host,
                                knownPort = null,
                                existing = discovery,
                            )
                            if (port == null || port <= 0) {
                                _adbState.value = AdbState.Ready
                                onResult(
                                    false,
                                    "Paired, but no connect port found yet. Open Wireless Debugging on the target and try Connect.",
                                )
                                return@submit
                            }
                            // Pairing mDNS uses the QR "S" name; the connect service is adb-*.
                            val label = connectServiceLabel(discovery, service.host, port)
                                ?: service.host
                            connectRemoteInternal(
                                service.host,
                                port,
                                label = label,
                                onResult,
                            )
                            return@submit
                        } catch (th: Throwable) {
                            Log.d(TAG, "QR pairing attempt failed for $attemptKey: ${th.message}")
                        }
                    }
                    Thread.sleep(300)
                }
                if (qrPairingCancelled.get()) {
                    _adbState.value = AdbState.Ready
                    onResult(false, "Cancelled")
                } else {
                    _adbState.value = AdbState.ErrorConnect
                    onResult(false, "Timed out waiting for QR scan")
                }
            } catch (th: Throwable) {
                Log.w(TAG, "pairViaQr failed", th)
                _adbState.value = AdbState.ErrorConnect
                onResult(false, th.message ?: th.javaClass.simpleName)
            } finally {
                discovery.stop()
            }
        }
    }

    fun cancelQrPairing() {
        qrPairingCancelled.set(true)
    }

    /**
     * Probe [host]:[port] with a temporary TLS connection that does not replace the
     * active [adbConnectionManager] session. Updates [probeStatus].
     */
    fun probeRemote(host: String, port: Int) {
        val key = host
        if (!probingHosts.add(key)) return
        val current = _probeStatus.value[key]
        if (current == RemoteProbeStatus.Ready || current == RemoteProbeStatus.PairingRequired) {
            // Still re-check periodically? For now skip if we already have a definitive answer
            // unless port changed — caller can clearProbe first.
            probingHosts.remove(key)
            return
        }
        updateProbeStatus(key, RemoteProbeStatus.Checking)
        executor.submit {
            try {
                val connected = _connectedTarget.value
                if (connected != null && hostsEqual(connected.host, host) && adbConnectionManager.isConnected) {
                    updateProbeStatus(key, RemoteProbeStatus.Ready)
                    return@submit
                }
                val status = probeRemoteInternal(host, port)
                updateProbeStatus(key, status)
            } finally {
                probingHosts.remove(key)
            }
        }
    }

    fun clearProbe(host: String) {
        _probeStatus.value = _probeStatus.value - host
        probingHosts.remove(host)
    }

    fun clearProbesExcept(hosts: Set<String>) {
        val next = _probeStatus.value.filterKeys { key ->
            hosts.any { hostsEqual(it, key) }
        }
        _probeStatus.value = next
    }

    fun forceProbeRemote(host: String, port: Int) {
        clearProbe(host)
        probeRemote(host, port)
    }

    @WorkerThread
    private fun probeRemoteInternal(host: String, port: Int): RemoteProbeStatus {
        var connection: io.github.muntashirakon.adb.AdbConnection? = null
        return try {
            connection = io.github.muntashirakon.adb.AdbConnection.Builder(host, port)
                .setApi(Build.VERSION.SDK_INT)
                .setPrivateKey(adbConnectionManager.adbPrivateKey())
                .setCertificate(adbConnectionManager.adbCertificate())
                .setDeviceName("Bugbane")
                .build()
            val ok = connection.connect(PROBE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS, true)
            if (ok) RemoteProbeStatus.Ready else RemoteProbeStatus.Unreachable
        } catch (_: AdbPairingRequiredException) {
            RemoteProbeStatus.PairingRequired
        } catch (_: io.github.muntashirakon.adb.AdbAuthenticationFailedException) {
            // Key not trusted yet — for wireless debugging this means pair (or accept the key).
            RemoteProbeStatus.PairingRequired
        } catch (th: Throwable) {
            Log.d(TAG, "probe $host:$port failed: ${th.message}")
            RemoteProbeStatus.Unreachable
        } finally {
            try {
                connection?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun updateProbeStatus(host: String, status: RemoteProbeStatus) {
        _probeStatus.value = _probeStatus.value + (host to status)
    }

    /**
     * Connect to an already-paired remote ADB Wireless Debugging daemon.
     * [onResult] is invoked on a background thread.
     */
    fun connectRemote(
        host: String,
        port: Int,
        onResult: (success: Boolean, errorMessage: String?) -> Unit,
    ) {
        connectRemote(host, port, label = host, onResult = onResult)
    }

    fun connectRemote(
        host: String,
        port: Int,
        label: String,
        onResult: (success: Boolean, errorMessage: String?) -> Unit,
    ) {
        executor.submit {
            try {
                _adbState.value = AdbState.Connecting
                disconnectQuietly()
                connectRemoteInternal(host, port, label, onResult)
            } catch (ie: AdbPairingRequiredException) {
                Log.i(TAG, "Pairing required for $host")
                _adbState.value = AdbState.RequisitesMissing
                onResult(false, "Pairing required")
            } catch (th: Throwable) {
                Log.w(TAG, "connectRemote failed", th)
                _adbState.value = AdbState.ErrorConnect
                onResult(false, th.message ?: th.javaClass.simpleName)
            }
        }
    }

    @WorkerThread
    private fun connectRemoteInternal(
        host: String,
        port: Int,
        label: String,
        onResult: (success: Boolean, errorMessage: String?) -> Unit,
    ) {
        Log.d(TAG, "Connecting to $host:$port")
        adbConnectionManager.setHostAddress(host)
        val connected = adbConnectionManager.connect(host, port)
        if (connected || adbConnectionManager.isConnected) {
            Log.d(TAG, "Remote connect successful")
            setConnectedTarget(host, port, label)
            rememberRemote(host, port, label)
            _adbState.value = AdbState.ConnectedIdle
            onResult(true, null)
        } else {
            Log.w(TAG, "Remote connect returned false")
            _connectedTarget.value = null
            _adbState.value = AdbState.ErrorConnect
            onResult(false, "Connection failed")
        }
    }

    private fun setConnectedTarget(host: String, port: Int, label: String) {
        val local = AdbNetworkDiscovery.isLocalHost(host) || host == "127.0.0.1"
        _connectedTarget.value = AdbTarget(
            host = host,
            port = port,
            label = if (local) label.ifBlank { "localhost" } else preferredRemoteLabel(label, host),
            isLocal = local,
        )
    }

    /** Keep remotes available in the scan-screen dropdown after switching to localhost. */
    private fun rememberRemote(host: String, port: Int, label: String) {
        if (AdbNetworkDiscovery.isLocalHost(host) || host == "127.0.0.1") return
        val niceLabel = preferredRemoteLabel(label, host)
        val next = _knownRemotes.value
            .filterNot { hostsEqual(it.host, host) }
            .toMutableList()
        next.add(
            AdbTarget(
                host = host,
                port = port,
                label = niceLabel,
                isLocal = false,
            ),
        )
        _knownRemotes.value = next.sortedBy { it.label.lowercase() }
        updateProbeStatus(host, RemoteProbeStatus.Ready)
    }

    private fun connectServiceLabel(
        discovery: AdbNetworkDiscovery,
        host: String,
        port: Int,
    ): String? {
        val connectServices = discovery.services.value.filter {
            it.kind == AdbNetworkDiscovery.ServiceKind.TLS_CONNECT &&
                hostsEqual(it.host, host)
        }
        val exact = connectServices.firstOrNull { it.port == port }
        val name = (exact ?: connectServices.firstOrNull())?.serviceName ?: return null
        return preferredRemoteLabel(name, fallback = host)
    }

    /** Prefer Android's `adb-*` connect name over QR pairing `S` names. */
    private fun preferredRemoteLabel(label: String, fallback: String): String {
        val trimmed = label.trim()
        if (trimmed.startsWith("adb-", ignoreCase = true)) return trimmed
        if (trimmed.isNotEmpty() && trimmed.contains('-')) return trimmed
        // QR pairing service names are random alphanumerics with no "adb-" prefix.
        if (trimmed.isNotEmpty() && trimmed.any { it == '.' || it == ':' }) return trimmed
        return fallback.ifBlank { trimmed }.ifBlank { label }
    }

    private fun disconnectQuietly() {
        try {
            if (adbConnectionManager.isConnected || adbConnectionManager.adbConnection != null) {
                adbConnectionManager.disconnect()
            }
        } catch (e: Exception) {
            Log.d(TAG, "disconnect before remote connect: ${e.message}")
        }
        adbShellStream = null
        _connectedTarget.value = null
    }

    /**
     * Prefer [knownPort], then any connect service already in [existing], then wait
     * for mDNS. When [existing] is null a temporary discovery is started.
     */
    @WorkerThread
    private fun resolveConnectPort(
        host: String,
        knownPort: Int?,
        existing: AdbNetworkDiscovery?,
    ): Int? {
        knownPort?.takeIf { it > 0 }?.let { port ->
            Log.d(TAG, "Using known connect port $host:$port")
            return port
        }
        findConnectPort(existing, host)?.let { port ->
            Log.d(TAG, "Using already-discovered connect port $host:$port")
            return port
        }
        // Pairing can briefly disrupt advertisements; give mDNS a moment.
        Thread.sleep(400)
        return waitForConnectPort(host, CONNECT_DISCOVERY_TIMEOUT_MS, existing)
    }

    @WorkerThread
    private fun waitForConnectPort(
        host: String,
        timeoutMs: Long,
        existing: AdbNetworkDiscovery?,
    ): Int? {
        val ownsDiscovery = existing == null
        val discovery = existing ?: AdbNetworkDiscovery(appContext!!).also { it.start() }
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (System.currentTimeMillis() < deadline) {
                findConnectPort(discovery, host)?.let { return it }
                Thread.sleep(200)
            }
        } finally {
            if (ownsDiscovery) {
                discovery.stop()
            }
        }
        return null
    }

    @WorkerThread
    private fun waitForPairingPort(
        host: String,
        timeoutMs: Long,
        existing: AdbNetworkDiscovery?,
    ): Int? {
        findPairingPort(existing, host)?.let { return it }
        val ownsDiscovery = existing == null
        val discovery = existing ?: AdbNetworkDiscovery(appContext!!).also { it.start() }
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (System.currentTimeMillis() < deadline) {
                findPairingPort(discovery, host)?.let { return it }
                Thread.sleep(200)
            }
        } finally {
            if (ownsDiscovery) {
                discovery.stop()
            }
        }
        return null
    }

    private fun findConnectPort(discovery: AdbNetworkDiscovery?, host: String): Int? {
        if (discovery == null) return null
        return discovery.services.value.firstOrNull {
            it.kind == AdbNetworkDiscovery.ServiceKind.TLS_CONNECT &&
                hostsEqual(it.host, host)
        }?.port
    }

    private fun findPairingPort(discovery: AdbNetworkDiscovery?, host: String): Int? {
        if (discovery == null) return null
        return discovery.services.value.firstOrNull {
            it.kind == AdbNetworkDiscovery.ServiceKind.TLS_PAIRING &&
                hostsEqual(it.host, host)
        }?.port
    }

    private fun hostsEqual(a: String, b: String): Boolean {
        if (a.equals(b, ignoreCase = true)) return true
        return try {
            java.net.InetAddress.getByName(a) == java.net.InetAddress.getByName(b)
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val CONNECT_DISCOVERY_TIMEOUT_MS = 15_000L
        private const val PAIRING_DISCOVERY_TIMEOUT_MS = 30_000L
        private const val PROBE_TIMEOUT_MS = 4_000L
        private const val QR_PAIRING_TIMEOUT_MS = 120_000L
    }

    fun checkState() {
        Log.d(TAG, "Adb received request to re-evaluate state.")
        try {
            // connection isn't null, isConnected, connection is established
            if (adbConnectionManager.isConnected) {
                if (_adbState.value != AdbState.ConnectedAcquiring) {
                    _adbState.value = AdbState.ConnectedIdle
                } else {
                    _adbState.value = AdbState.ConnectedAcquiring
                }
            } else {
                // connection isn't null, isConnected (not yet established)
                if (adbConnectionManager.adbConnection != null && adbConnectionManager.adbConnection!!.isConnected) {
                    _adbState.value = AdbState.Ready
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Couldn't get adbState: ${e.message}")
        }
        Log.d(TAG, "AdbState is ${adbState.value}")
    }

    @WorkerThread
    private fun autoConnectInternal() {
        try {
            if (adbConnectionManager.isConnected) {
                Log.d(TAG, "already connected")
                if (_adbState.value != AdbState.ConnectedAcquiring && _adbState.value != AdbState.Cancelling) {
                    _adbState.value = AdbState.ConnectedIdle
                }
                return
            }
            else if (_adbState.value in arrayOf(AdbState.Connecting, AdbState.ConnectedIdle, AdbState.ConnectedAcquiring)) {
                // This isn't necessarily an error (could be Connecting), but it's sus
                Log.w(TAG, "skipping autoConnectInternal: manager.isConnected was false but AdbState is ${adbState.value}.")
                return
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Log.d(TAG, "AdbState ${adbState.value}, try autoConnectInternal")
                    _adbState.value = AdbState.Connecting
                    try {
                        // Slight TOCTOU here, but only if manager transitioned from connecting ->
                        // connected while we were running this method.
                        // manager.connectTls returns false if the connection failed *or* there
                        // was another existing connection; we check manager.isConnected first
                        // so that we can try to distinguish between those two cases.
                        if (adbConnectionManager.connectTls(this.appContext!!, 5000)) {
                            Log.d(TAG, "autoconnect successful")
                            val host = adbConnectionManager.hostAddress ?: "127.0.0.1"
                            setConnectedTarget(host, port = 0, label = "localhost")
                            _adbState.value = AdbState.ConnectedIdle
                        } else {
                            // Probably an error but could also be a race :(
                            Log.w(TAG, "connectTls returned false")
                            _adbState.value = AdbState.Ready
                        }
                    } catch (ie: AdbPairingRequiredException) {
                        Log.i(TAG, "AdbPairingRequiredException during autoconnect")
                        _adbState.value = AdbState.RequisitesMissing
                    } catch (ie: InterruptedException) {
                        Log.w(TAG, "Error during autoconnect")
                        ie.printStackTrace()
                        _adbState.value = AdbState.ErrorConnect
                    } catch (th: Throwable) {
                        Log.e(TAG, "$th during autoconnect")
                        th.printStackTrace()
                        _adbState.value = AdbState.ErrorConnect
                    }
                }
            }
        } catch (th: Throwable) {
            Log.e(TAG, "Error retrieving AdbConnectionManager instance")
            th.printStackTrace()
            _adbState.value = AdbState.ErrorConnect
        }
    }
    @Volatile
    private var clearEnabled = false
    private val outputGenerator = Runnable {
        try {
            BufferedReader(InputStreamReader(adbShellStream?.openInputStream())).use { reader ->
                val sb = StringBuilder()
                var s: String?
                while ((reader.readLine().also { s = it }) != null) {
                    if (clearEnabled) {
                        sb.delete(0, sb.length)
                        clearEnabled = false
                    }
                    sb.append(s).append("\n")
                    commandOutput.postValue(sb)
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "${e.message} (adbStream error?)")
            _adbState.value = AdbState.Cancelled
            e.printStackTrace()
        }
    }

    init {
        this.appContext = applicationContext
        this.adbConnectionManager = AdbConnectionManager.getInstance(appContext!!)
    }

    fun execute(command: String) {
        executor.submit(Runnable {
            try {
                if (adbShellStream == null || adbShellStream!!.isClosed) {
                    adbShellStream = adbConnectionManager.openStream(LocalServices.SHELL)
                    Thread(outputGenerator).start()
                }
                if (command == "clear") {
                    clearEnabled = true
                }
                adbShellStream!!.openOutputStream().use { os ->
                    os.write(String.format("%1\$s\n", command).toByteArray(StandardCharsets.UTF_8))
                    os.flush()
                    os.write("\n".toByteArray(StandardCharsets.UTF_8))
                }
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
                Log.w(TAG, "adbShellStream error ${e.message}")
                _adbState.value = AdbState.Cancelled
            }
        })
    }

    @get:Synchronized
    val isQuickForensicsRunning: Boolean
        get() = qfFuture != null && !qfFuture!!.isDone()

    @get:Synchronized
    val isQuickForensicsCancelled: Boolean
        get() = qfCancelled.get()

    @Synchronized
    fun cancelQuickForensics() {
        if (_adbState.value == AdbState.ConnectedAcquiring) {
            _adbState.value = AdbState.Cancelling
        }
        qfCancelled.set(true)
    }

    fun runQuickForensics(
        baseDir: File,
        listener: AcquisitionRunner.ProgressListener
    ) {
        if (this.isQuickForensicsRunning) {
            Log.d(TAG, "QuickForensics already running")
            commandOutput.postValue("QuickForensics is still running")
            return
        } else if (!adbConnectionManager.isConnected) {
            Log.i(TAG, "Need to reconnect first")
            _adbState.value = AdbState.Ready
            return
        }
        qfCancelled.set(false)
        _adbState.value = AdbState.ConnectedAcquiring
        qfFuture = executor.submit(Runnable {
            try {
                val out = AcquisitionRunner()
                    .run(this.appContext!!, adbConnectionManager, baseDir, listener)
                if (qfCancelled.get()) {
                    commandOutput.postValue("QuickForensics cancelled")
                    _adbState.value = AdbState.ConnectedIdle
                } else {
                    commandOutput.postValue("QuickForensics completed: " + out.getAbsolutePath())
                    _adbState.value = AdbState.ConnectedIdle
                }
            } catch (io: IOException) {
                // Could be reconnection issue
                io.printStackTrace()
                commandOutput.postValue("Error running QuickForensics: " + io.message)
                _adbState.value = AdbState.Cancelled
                // Set the flag otherwise we will be stuck
                qfCancelled.set(true);
            }
            catch (e: java.lang.Exception) {
                e.printStackTrace()
                commandOutput.postValue("Error running QuickForensics: " + e.message)
                _adbState.value = AdbState.ErrorAcquisition
                qfCancelled.set(true);
            }
        })
    }
}

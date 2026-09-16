package org.osservatorionessuno.cadb

import android.content.Context
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
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
import org.osservatorionessuno.qf.storage.AcquisitionTransport
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.Volatile

private const val TAG = "AdbManager"
// Time for the target's user to accept the USB debugging prompt.
private const val USB_AUTH_TIMEOUT_S = 120L

class AdbManager(applicationContext: Context) {
    private val executor: ExecutorService = Executors.newFixedThreadPool(3)
    private var _adbState = MutableStateFlow<AdbState>(AdbState.Initial)
    val adbState: StateFlow<AdbState> = _adbState.asStateFlow()

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
                            transport = AcquisitionTransport(AcquisitionTransport.LOCAL)
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
    // Analyst mode: blocking calls from the wizard (Dispatchers.IO) that point the
    // shared AdbConnectionManager at another device.

    /** Written into the acquisition index. */
    @Volatile
    var transport: AcquisitionTransport = AcquisitionTransport(AcquisitionTransport.LOCAL)
        private set

    fun disconnect() {
        runCatching { adbConnectionManager.disconnect() }
            .onFailure { Log.w(TAG, "disconnect: ${it.message}") }
        if (_adbState.value != AdbState.ConnectedAcquiring && _adbState.value != AdbState.Cancelling) {
            _adbState.value = AdbState.Ready
        }
    }

    /** USB permission already granted; waits for the target's "Allow USB debugging?" prompt. */
    @WorkerThread
    fun connectUsb(device: UsbDevice) {
        disconnect()
        _adbState.value = AdbState.Connecting
        try {
            adbConnectionManager.setTimeout(USB_AUTH_TIMEOUT_S, TimeUnit.SECONDS)
            if (!adbConnectionManager.connectUsb(appContext!!, device)) throw IOException("USB connection refused")
            transport = AcquisitionTransport(AcquisitionTransport.USB)
            _adbState.value = AdbState.ConnectedIdle
        } catch (t: Throwable) {
            _adbState.value = AdbState.ErrorConnect
            throw t
        } finally {
            adbConnectionManager.setTimeout(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
        }
    }

    @WorkerThread
    fun pairRemote(host: String, port: Int, code: String): Boolean =
        adbConnectionManager.pair(host, port, code)

    /** Connect to a paired device's `_adb-tls-connect` service; [via] is recorded. */
    @WorkerThread
    fun connectRemote(host: String, port: Int, via: AcquisitionTransport) {
        disconnect()
        _adbState.value = AdbState.Connecting
        try {
            if (!adbConnectionManager.connect(host, port)) throw IOException("Connection refused by $host:$port")
            transport = via
            _adbState.value = AdbState.ConnectedIdle
        } catch (t: Throwable) {
            _adbState.value = AdbState.ErrorConnect
            throw t
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
                    .run(this.appContext!!, adbConnectionManager, baseDir, listener, transport)
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
            // Catch Error too (e.g. OOM) so the acquiring state always resets.
            catch (e: Throwable) {
                e.printStackTrace()
                commandOutput.postValue("Error running QuickForensics: " + e.message)
                _adbState.value = AdbState.ErrorAcquisition
                qfCancelled.set(true);
            }
        })
    }
}

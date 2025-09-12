package org.osservatorionessuno.bugbane.utils

import android.content.Context
import android.os.Build
import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.LocalServices
import io.github.muntashirakon.adb.android.AdbMdns
import io.github.muntashirakon.adb.android.AdbMdns.OnAdbDaemonDiscoveredListener
import io.github.muntashirakon.adb.android.AndroidUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.osservatorionessuno.bugbane.qf.QuickForensics
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.Volatile

class AdbManager(applicationContext: Context) {
    private val executor: ExecutorService = Executors.newFixedThreadPool(3)
    private val _adbState = MutableStateFlow<AdbState>(AdbState.ReadyToPair)
    val adbState: StateFlow<AdbState> = _adbState.asStateFlow()
    private val commandOutput = MutableLiveData<CharSequence?>()
    private val pairingPort = MutableLiveData<Int?>()
    private var qfFuture: Future<*>? = null
    private val qfCancelled = AtomicBoolean(false)

    private var mPairingHost: String? = null
    private var mPairingPort = -1

    private var appContext: Context? = null

    private var adbShellStream: AdbStream? = null

    fun watchCommandOutput(): LiveData<CharSequence?> {
       return commandOutput
    }

    fun cleanup() {
        val stream = adbShellStream
        adbShellStream = null
        executor.submit(Runnable {
            try {
                stream?.close()
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
            }
        })
        executor.shutdown()
    }

    fun connect(port: Int) {
        executor.submit(Runnable {
            try {
                val manager = AdbConnectionManager.getInstance(this.appContext!!)
                try {
                    if (manager.connect(AndroidUtils.getHostIpAddress(this.appContext!!), port)) {
                        _adbState.value = AdbState.ConnectedIdle
                    } // no "else" - method can return false if there is an existing connection already
                } catch (th: Throwable) {
                    th.printStackTrace()
                    _adbState.value = AdbState.ErrorPair
                }
            } catch (th: Throwable) {
                th.printStackTrace()
                _adbState.value = AdbState.ErrorPair
            }
        })
    }

    fun autoConnect() {
        executor.submit(Runnable { this.autoConnectInternal() })
    }

    fun disconnect() {
        executor.submit(Runnable {
            try {
                val manager = AdbConnectionManager.getInstance(this.appContext!!)
                manager.disconnect()
                _adbState.value = AdbState.ReadyToConnect
            } catch (th: Throwable) {
                th.printStackTrace()
                // TODO: ConnectedIdle not be accurate here, but it follows the prior logic
                _adbState.value = AdbState.ConnectedIdle
            }
        })
    }

    fun getPairingPort() {
        executor.submit(Runnable {
            val atomicPort = AtomicInteger(-1)
            val host = arrayOf<String?>(null)
            val resolveHostAndPort = CountDownLatch(1)

            val adbMdns = AdbMdns(
                this.appContext!!,
                AdbMdns.SERVICE_TYPE_TLS_PAIRING,
                OnAdbDaemonDiscoveredListener { hostAddress: InetAddress?, port: Int ->
                    atomicPort.set(port)
                    if (hostAddress != null) {
                        host[0] = hostAddress.getHostAddress()
                    }
                    resolveHostAndPort.countDown()
                })
            adbMdns.start()

            try {
                if (!resolveHostAndPort.await(1, TimeUnit.MINUTES)) {
                    return@Runnable
                }
            } catch (ignore: InterruptedException) {
            } finally {
                adbMdns.stop()
            }

            mPairingPort = atomicPort.get()
            mPairingHost = host[0]
            pairingPort.postValue(mPairingPort)
            _adbState.value = AdbState.ReadyToPair
        })
    }

    fun pair(port: Int, pairingCode: String) {
        executor.submit(Runnable {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val manager = AdbConnectionManager.getInstance(this.appContext!!)
                    val host =
                        (if (mPairingHost != null) mPairingHost else AndroidUtils.getHostIpAddress(
                            this.appContext!!
                        ))!!
                    val p = if (port > 0) port else mPairingPort
                    if (manager.pair(host, p, pairingCode)) {
                        _adbState.value = AdbState.ReadyToConnect
                    } else {
                        _adbState.value = AdbState.ErrorPair
                    }
                }
                autoConnectInternal()
            } catch (th: Throwable) {
                th.printStackTrace()
                _adbState.value = AdbState.ErrorPair
            }
        })
    }

    @WorkerThread
    private fun autoConnectInternal() {
        try {
            val manager = AdbConnectionManager.getInstance(this.appContext!!)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    if (manager.connectTls(this.appContext!!, 5000)) {
                        _adbState.value = AdbState.ConnectedIdle // no "else", because false could mean existing connection
                    }
                } catch (ie: AdbPairingRequiredException) {
                    _adbState.value = AdbState.RequisitesMissing
                } catch (ie: InterruptedException) {
                    _adbState.value = AdbState.ErrorConnect
                } catch (th: Throwable) {
                    th.printStackTrace()
                    _adbState.value = AdbState.ErrorConnect
                }
            }
        } catch (th: Throwable) {
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
            e.printStackTrace()
        }
    }

    init {
        this.appContext = applicationContext
    }

    fun execute(command: String) {
        executor.submit(Runnable {
            try {
                if (adbShellStream == null || adbShellStream!!.isClosed) {
                    val manager = AdbConnectionManager.getInstance(this.appContext!!)
                    adbShellStream = manager.openStream(LocalServices.SHELL)
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
                _adbState.value = AdbState.RequisitesMissing
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
        qfCancelled.set(true)
        if (_adbState.value == AdbState.ConnectedAcquiring) {
            _adbState.value = AdbState.ConnectedIdle
        }
    }

    fun runQuickForensics(
        baseDir: File,
        listener: QuickForensics.ProgressListener
    ) {
        if (this.isQuickForensicsRunning) {
            return
        }
        qfCancelled.set(false)
        _adbState.value = AdbState.ConnectedAcquiring
        qfFuture = executor.submit(Runnable {
            try {
                val manager = AdbConnectionManager.getInstance(this.appContext!!)
                val out = QuickForensics()
                    .run(this.appContext!!, manager, baseDir, listener)
                if (qfCancelled.get()) {
                    commandOutput.postValue("QuickForensics cancelled")
                    _adbState.value = AdbState.ConnectedIdle
                } else {
                    commandOutput.postValue("QuickForensics completed: " + out.getAbsolutePath())
                    _adbState.value = AdbState.ConnectedIdle
                }
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
                commandOutput.postValue("Error running QuickForensics: " + e.message)
                _adbState.value = AdbState.ErrorAcquisition
            }
        })
    }
}

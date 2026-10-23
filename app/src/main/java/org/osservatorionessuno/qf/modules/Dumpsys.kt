package org.osservatorionessuno.qf.modules

import android.content.Context
import org.osservatorionessuno.qf.AcquisitionLog
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.cadb.AdbShell
import org.osservatorionessuno.cadb.AdbConnectionManager
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.qf.storage.ArtifactSink
import java.io.OutputStream

/**
 * Runs full `dumpsys`, then fetches any [REQUIRED_SERVICES] that never appeared
 * as a dedicated `dumpsys <service>` artifact.
 */
class Dumpsys : Module {
    override val name: String = "dumpsys"

    /**
     * @param name service name as it appears in `DUMP OF SERVICE <name>:`
     * @param args arguments after `dumpsys` for the dedicated fetch
     */
    data class RequiredService(val name: String, val extraArgs: String = "")

    companion object {
        val REQUIRED_SERVICES = listOf(
            RequiredService("adb"),
            RequiredService("package"),
            RequiredService("appops"),
            RequiredService("accessibility"),
            RequiredService("batterystats"),
            RequiredService("platform_compat"),
            RequiredService("dbinfo"),
            RequiredService("device_policy"),
            RequiredService("dropbox", "--print"),
            RequiredService("settings"),
        )
    }

    override fun run(
        context: Context,
        manager: AdbConnectionManager,
        writer: ArtifactSink,
        progress: ((Long) -> Unit)?,
        log: AcquisitionLog,
    ) {
        val shell = AdbShell(manager, progress = progress)
        val seen = LinkedHashSet<String>()
        writer.useArtifact("dumpsys.txt") { output ->
            shell.execToStream("dumpsys", DumpsysServiceSniffer(output) { service ->
                seen += service
                log.show(R.string.step_dumpsys_service, service)
            })
        }

        for (svc in REQUIRED_SERVICES) {
            if (svc.name in seen && svc.extraArgs == "") continue
            
            // The service was not dumped or we need to dump it with extra args.
            log.step(R.string.step_dumpsys_service, svc.name)
            runCatching {
                writer.useArtifact("dumpsys_${svc.name}.txt") { output ->
                    // Frame like the full dump so libmvt extractDumpsysSection matches.
                    output.write("DUMP OF SERVICE ${svc.name}:\n".toByteArray(Charsets.UTF_8))
                    shell.execToStream("dumpsys ${svc.name} ${svc.extraArgs}", output)
                }
            }.onFailure { log.warning("Failed to collect dumpsys ${svc.name}: ${it.message}") }
        }
    }
}

/** Passes dumpsys output through unchanged, reporting each service as its "DUMP OF SERVICE" header goes by. */
internal class DumpsysServiceSniffer(
    private val out: OutputStream,
    private val onService: (String) -> Unit,
) : OutputStream() {
    private val line = StringBuilder()

    override fun write(b: Int) {
        out.write(b)
        scan(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        for (i in off until off + len) scan(b[i].toInt())
    }

    override fun flush() = out.flush()

    private fun scan(b: Int) {
        if (b == '\n'.code) {
            // Priority dumps prefix the name, e.g. "DUMP OF SERVICE CRITICAL SurfaceFlinger:".
            if (line.startsWith(HEADER)) onService(line.substring(HEADER.length).trimEnd(':', ' ', '\r').substringAfterLast(' '))
            line.setLength(0)
        } else if (line.length < 256) {
            line.append(b.toChar())
        }
    }

    private companion object {
        const val HEADER = "DUMP OF SERVICE "
    }
}

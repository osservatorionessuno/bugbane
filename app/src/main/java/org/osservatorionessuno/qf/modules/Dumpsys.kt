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
 * Sample module that runs `dumpsys` and stores the output.
 */
class Dumpsys : Module {
    override val name: String = "dumpsys"

    override fun run(
        context: Context,
        manager: AdbConnectionManager,
        writer: ArtifactSink,
        progress: ((Long) -> Unit)?,
        log: AcquisitionLog,
    ) {
        val shell = AdbShell(manager, progress = progress)
        writer.useArtifact("dumpsys.txt") { output ->
            shell.execToStream("dumpsys", DumpsysServiceSniffer(output) { log.show(R.string.step_dumpsys_service, it) })
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

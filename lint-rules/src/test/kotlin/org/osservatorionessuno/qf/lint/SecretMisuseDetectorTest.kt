package org.osservatorionessuno.qf.lint

import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import org.junit.Test

class SecretMisuseDetectorTest {

    private val secretStub = kotlin(
        """
        package org.osservatorionessuno.qf.crypto
        class Secret(source: ByteArray) : AutoCloseable {
            fun <T> withBytes(block: (ByteArray) -> T): T = block(source)
            fun copyBytes(): ByteArray = source
            override fun close() {}
        }
        """,
    ).indented()

    @Test
    fun `flags stringifying a secret`() {
        lint().files(
            secretStub,
            kotlin(
                """
                package test
                import org.osservatorionessuno.qf.crypto.Secret
                fun bad(s: Secret) {
                    val a = "value is ${'$'}s"
                    val b = s.toString()
                }
                """,
            ).indented(),
        ).issues(SecretMisuseDetector.ISSUE).allowMissingSdk().run().expectErrorCount(2)
    }

    @Test
    fun `clean usage passes`() {
        lint().files(
            secretStub,
            kotlin(
                """
                package test
                import org.osservatorionessuno.qf.crypto.Secret
                fun ok(s: Secret): Int = s.withBytes { it.size }
                """,
            ).indented(),
        ).issues(SecretMisuseDetector.ISSUE).allowMissingSdk().run().expectClean()
    }
}

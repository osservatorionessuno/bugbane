package org.osservatorionessuno.qf.lint

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.android.tools.lint.client.api.UElementHandler
import com.intellij.psi.PsiType
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UPolyadicExpression

/**
 * Flags key material ([Secret]) escaping into an immutable, unzeroable copy: a
 * `String` (interpolation or `toString`) or a log call. These are the leaks that
 * `Secret.close()` can never reach; transient access must go through `withBytes`.
 */
class SecretMisuseDetector : Detector(), SourceCodeScanner {

    override fun getApplicableUastTypes() =
        listOf(UCallExpression::class.java, UPolyadicExpression::class.java)

    override fun createUastHandler(context: JavaContext) = object : UElementHandler() {
        override fun visitCallExpression(node: UCallExpression) {
            if (node.methodName == "toString" && isSecret(node.receiverType)) {
                report(context, node, "Do not convert a Secret to a String")
                return
            }
            val owner = node.resolve()?.containingClass?.qualifiedName
            val logging = owner == "android.util.Log" || node.methodName == "println"
            if (logging && node.valueArguments.any { isSecret(it.getExpressionType()) }) {
                report(context, node, "Do not log a Secret")
            }
        }

        override fun visitPolyadicExpression(node: UPolyadicExpression) {
            if (node.getExpressionType()?.canonicalText != "java.lang.String") return
            if (node.operands.any { isSecret(it.getExpressionType()) }) {
                report(context, node, "Do not interpolate a Secret into a String")
            }
        }
    }

    private fun isSecret(type: PsiType?): Boolean = type?.canonicalText == SECRET_FQN

    private fun report(context: JavaContext, node: UElement, message: String) {
        context.report(ISSUE, node, context.getLocation(node), message)
    }

    companion object {
        private const val SECRET_FQN = "org.osservatorionessuno.qf.crypto.Secret"

        val ISSUE: Issue = Issue.create(
            id = "SecretLeak",
            briefDescription = "Secret material escapes into a String or log",
            explanation = "A Secret holds key material that close() wipes. Turning it into a String " +
                "or logging it creates an immutable copy that can never be zeroed and may linger in " +
                "memory or logs. Read the bytes only through withBytes { }, and never stringify or log a Secret.",
            category = Category.SECURITY,
            priority = 8,
            severity = Severity.ERROR,
            implementation = Implementation(SecretMisuseDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
}

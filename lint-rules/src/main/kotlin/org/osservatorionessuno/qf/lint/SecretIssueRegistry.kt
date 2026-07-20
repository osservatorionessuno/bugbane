package org.osservatorionessuno.qf.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

class SecretIssueRegistry : IssueRegistry() {
    override val issues: List<Issue> = listOf(SecretMisuseDetector.ISSUE)
    override val api: Int = CURRENT_API
    override val minApi: Int = 14
    override val vendor: Vendor = Vendor(
        vendorName = "bugbane",
        identifier = "bugbane-lint-rules",
    )
}

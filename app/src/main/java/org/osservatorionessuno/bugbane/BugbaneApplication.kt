package org.osservatorionessuno.bugbane

import android.app.Application
import org.acra.ReportField
import org.acra.config.mailSenderConfiguration
import org.acra.config.notificationConfiguration
import org.acra.data.StringFormat
import org.acra.ktx.initAcra
import org.osservatorionessuno.bugbane.utils.ViewModelFactory

class BugbaneApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.CRASH_REPORTING_ENABLED) {
            initAcra {
                buildConfigClass = BuildConfig::class.java
                alsoReportToAndroidFramework = false
                reportFormat = StringFormat.KEY_VALUE_LIST
                logcatArguments = listOf("-t", "200", "-v", "time")
                reportContent = listOf(
                    ReportField.APP_VERSION_CODE,
                    ReportField.APP_VERSION_NAME,
                    ReportField.ANDROID_VERSION,
                    ReportField.PHONE_MODEL,
                    ReportField.BRAND,
                    ReportField.PRODUCT,
                    ReportField.DEVICE_FEATURES,
                    ReportField.STACK_TRACE,
                    ReportField.LOGCAT,
                    ReportField.USER_CRASH_DATE,
                    ReportField.USER_APP_START_DATE,
                    ReportField.AVAILABLE_MEM_SIZE,
                    ReportField.TOTAL_MEM_SIZE,
                    ReportField.BUILD,
                    ReportField.BUILD_CONFIG,
                    ReportField.INSTALLATION_ID,
                )
                pluginConfigurations = listOf(
                    mailSenderConfiguration {
                        mailTo = BuildConfig.CRASH_REPORT_EMAIL
                        reportAsFile = true
                        reportFileName = "bugbane_crash.txt"
                        subject = getString(R.string.crash_report_mail_subject)
                        body = getString(R.string.crash_report_mail_body)
                    },
                    notificationConfiguration {
                        channelName = getString(R.string.notification_channel_crash)
                        channelDescription = getString(R.string.crash_notification_channel_desc)
                        title = getString(R.string.crash_notification_title)
                        text = getString(R.string.crash_notification_text)
                        tickerText = getString(R.string.crash_notification_ticker)
                        resIcon = R.drawable.ic_bugbane_foreground
                    },
                )
            }
        }

        ViewModelFactory.get(this)
    }
}

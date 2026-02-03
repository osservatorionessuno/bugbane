package org.osservatorionessuno.libmvt.common

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object Utils {
    private val ISO = DateTimeFormatter.ISO_INSTANT

    fun toIso(epochMillis: Long): String =
        ISO.format(Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC))

    @JvmField
    val ROOT_PACKAGES: Set<String> = setOf(
        "com.noshufou.android.su",
        "com.noshufou.android.su.elite",
        "eu.chainfire.supersu",
        "com.koushikdutta.superuser",
        "com.thirdparty.superuser",
        "com.yellowes.su",
        "com.koushikdutta.rommanager",
        "com.koushikdutta.rommanager.license",
        "com.dimonvideo.luckypatcher",
        "com.chelpus.lackypatch",
        "com.ramdroid.appquarantine",
        "com.ramdroid.appquarantinepro",
        "com.devadvance.rootcloak",
        "com.devadvance.rootcloakplus",
        "de.robv.android.xposed.installer",
        "com.saurik.substrate",
        "com.zachspong.temprootremovejb",
        "com.amphoras.hidemyroot",
        "com.amphoras.hidemyrootadfree",
        "com.formyhm.hiderootPremium",
        "com.formyhm.hideroot",
        "me.phh.superuser",
        "eu.chainfire.supersu.pro",
        "com.kingouser.com",
        "com.topjohnwu.magisk"
    )

    @JvmField
    val ROOT_BINARIES: Map<String, String> = mapOf(
        "su" to "SuperUser binary",
        "busybox" to "BusyBox utilities",
        "supersu" to "SuperSU root management",
        "superuser.apk" to "Superuser app",
        "kingouser.apk" to "KingRoot app",
        "supersu.apk" to "SuperSU app",
        "magisk" to "Magisk root framework",
        "magiskhide" to "Magisk hide utility",
        "magiskinit" to "Magisk init binary",
        "magiskpolicy" to "Magisk policy binary"
    )

    @JvmField
    val DANGEROUS_PERMISSIONS_THRESHOLD: Int = 10

    @JvmField
    val DANGEROUS_PERMISSIONS: Set<String> = setOf(
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.AUTHENTICATE_ACCOUNTS",
        "android.permission.CAMERA",
        "android.permission.DISABLE_KEYGUARD",
        "android.permission.PROCESS_OUTGOING_CALLS",
        "android.permission.READ_CALENDAR",
        "android.permission.READ_CALL_LOG",
        "android.permission.READ_CONTACTS",
        "android.permission.READ_PHONE_STATE",
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_MMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.RECEIVE_WAP_PUSH",
        "android.permission.RECORD_AUDIO",
        "android.permission.SEND_SMS",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.USE_CREDENTIALS",
        "android.permission.USE_SIP",
        "com.android.browser.permission.READ_HISTORY_BOOKMARKS"
    )

    @JvmField
    val SECURITY_PACKAGES: Set<String> = setOf(
        "com.policydm",
        "com.samsung.android.app.omcagent",
        "com.samsung.android.securitylogagent",
        "com.sec.android.soagent"
    )

    @JvmField
    val SYSTEM_UPDATE_PACKAGES: Set<String> = setOf(
        "com.android.updater",
        "com.google.android.gms",
        "com.huawei.android.hwouc",
        "com.lge.lgdmsclient",
        "com.motorola.ccc.ota",
        "com.oneplus.opbackup",
        "com.oppo.ota",
        "com.transsion.systemupdate",
        "com.wssyncmldm"
    )

    @JvmField
    val PLAY_STORE_INSTALLERS: Set<String> = setOf(
        "com.android.vending"
    )

    @JvmField
    val THIRD_PARTY_STORE_INSTALLERS: Set<String> = setOf(
        "com.aurora.store",
        "org.fdroid.fdroid"
    )

    @JvmField
    val BROWSER_INSTALLERS: Set<String> = setOf(
        "com.google.android.packageinstaller",
        "com.android.packageinstaller"
    )
}

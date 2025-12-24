package org.osservatorionessuno.libmvt.common;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Set;

public final class Utils {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private Utils() {}
    public static String toIso(long epochMillis) {
        return ISO.format(Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC));
    }

    public static final Set<String> ROOT_PACKAGES = Set.of(
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
    );

    public static final int DANGEROUS_PERMISSIONS_THRESHOLD = 10;

    public static final Set<String> DANGEROUS_PERMISSIONS = Set.of(
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
    );

    public static final Set<String> SECURITY_PACKAGES = Set.of(
        "com.policydm",
        "com.samsung.android.app.omcagent",
        "com.samsung.android.securitylogagent",
        "com.sec.android.soagent"
    );

    public static final Set<String> SYSTEM_UPDATE_PACKAGES = Set.of(
        "com.android.updater",
        "com.google.android.gms",
        "com.huawei.android.hwouc",
        "com.lge.lgdmsclient",
        "com.motorola.ccc.ota",
        "com.oneplus.opbackup",
        "com.oppo.ota",
        "com.transsion.systemupdate",
        "com.wssyncmldm"
    );

    public static final Set<String> PLAY_STORE_INSTALLERS = Set.of(
        "com.android.vending"
    );

    public static final Set<String> THIRD_PARTY_STORE_INSTALLERS = Set.of(
        "com.aurora.store",
        "org.fdroid.fdroid"
    );

    public static final Set<String> BROWSER_INSTALLERS = Set.of(
        "com.google.android.packageinstaller",
        "com.android.packageinstaller"
    );

}

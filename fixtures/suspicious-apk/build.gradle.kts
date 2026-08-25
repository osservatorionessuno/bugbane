plugins {
    alias(libs.plugins.android.application)
}

// A benign, code-less APK whose manifest alone trips libMVT's APKStaticAnalyzer
// (accessibility service + dangerous permissions). Installed on the CI emulator so
// the acquisition's Packages module flags it suspicious and stages it into the archive.
android {
    namespace = "org.osservatorionessuno.fixture.suspicious"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.osservatorionessuno.fixture.suspicious"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    // The accessibility service names no real class on purpose; don't fail lint on it.
    lint { abortOnError = false }
}

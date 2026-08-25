plugins {
    alias(libs.plugins.android.application)
}

// Benign code-less APK whose manifest trips libMVT's suspicious heuristic (accessibility
// service). The CI emulator installs it so the acquisition stages it (integration/run.sh).
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

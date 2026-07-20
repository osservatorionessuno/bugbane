import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.android.lint)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    // BouncyCastle: the single crypto backend for our from-scratch age impl.
    implementation("org.bouncycastle:bcprov-jdk15to18:1.84")
    // Last standalone commons-compress (1.26+ pulls commons-io/lang3/codec). Pure Java.
    // Used only as a robust, ZIP64-aware reader over our decrypting SeekableByteChannel.
    implementation("org.apache.commons:commons-compress:1.25.0")

    // kage is used ONLY in tests, as an interop oracle for the age implementation.
    testImplementation(libs.kage)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    // Match the platform launcher to Jupiter 5.13.x (Gradle bundles an older one).
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")

    lintChecks(project(":lint-rules"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
    System.getProperty("bugbane.dumpExport")?.let { systemProperty("bugbane.dumpExport", it) }
    System.getProperty("bugbane.dumpAge")?.let { systemProperty("bugbane.dumpAge", it) }
}

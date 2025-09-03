import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.junit5)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "org.osservatorionessuno.bugbane"
    compileSdk = 36

    // Lazy, configuration-cache-safe env reads
    val ksPath  = providers.environmentVariable("APK_KEYSTORE")
    val ksPass  = providers.environmentVariable("APK_KEYSTORE_PASSWORD")
    val alias   = providers.environmentVariable("APK_KEY_ALIAS")
    val keyPass = providers.environmentVariable("APK_KEY_PASSWORD")

    // When set, we build an *unsigned* release for F-Droid to sign later
    val isFdroidBuild = providers.environmentVariable("FDROID_BUILD").isPresent

    // Only define a signingConfig when we're NOT in F-Droid mode
    if (!isFdroidBuild && ksPath.isPresent && ksPass.isPresent && alias.isPresent && keyPass.isPresent) {
        signingConfigs {
            create("ciRelease") {
                storeFile     = file(ksPath.get())
                storePassword = ksPass.get()
                keyAlias      = alias.get()
                keyPassword   = keyPass.get()

                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    defaultConfig {
        applicationId = "org.osservatorionessuno.bugbane"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (!isFdroidBuild && this@android.signingConfigs.findByName("ciRelease") != null) {
                signingConfig = this@android.signingConfigs.getByName("ciRelease")
            } else {
                // No signingConfig assigned => Gradle will output app-release-unsigned.apk
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11 }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.foundation)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.snakeyaml)

    // libadb-android and its dependency
    implementation(libs.libadb.android)
    implementation(libs.sun.security.android)
    // Required for ADB encryption
    implementation(libs.conscrypt.android)

    // Required for age encrypted export/share
    implementation(libs.kage)

    // Quick string matching for mvt
    implementation(libs.ahocorasick)

    // Tombstone protobuf (lite)
    implementation(libs.protobuf.javalite)

    // --- Unit test (JUnit 5) ---
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)

    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

protobuf {
    protoc {
        artifact = libs.protoc.get().toString()
    }
    // Generate lite Java classes for Android
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                register("java") {
                    option("lite")
                }
            }
        }
    }
}

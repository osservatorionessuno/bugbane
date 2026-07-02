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

    defaultConfig {
        applicationId = "org.osservatorionessuno.bugbane"
        minSdk = 30
        targetSdk = 36
        versionCode = 5
        versionName = "0.1.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "version"
    productFlavors {
        create("beta") {
            dimension = "version"
            applicationIdSuffix = ".beta"
        }
        create("production") {
            dimension = "version"
        }
    }

    signingConfigs {
        // Auto generated when "debug"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
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

    packaging {
        jniLibs {
            // The app never invokes PathIterator (only standard Material/Compose components, which
            // render via the platform canvas), so the .so is useless.
            excludes += "**/libandroidx.graphics.path.so"
        }
        resources {
            // BouncyCastle ships large data files as java resources for the Picnic post-quantum
            // signature scheme and other stuff
            excludes += setOf(
                "org/bouncycastle/pqc/**",
                "org/bouncycastle/x509/CertPathReviewerMessages*.properties",
                "org/bouncycastle/pkix/CertPathReviewerMessages*.properties",
            )
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
    implementation(libs.androidx.foundation)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.google.accompanist.permissions)

    // external libMVT
    implementation(libs.libmvt)

    // OHTTP update transport (Oblivious HTTP, RFC 9458 + 9292 + 9180)
    implementation(libs.libohttp)
    implementation(libs.libbhttp)
    implementation(libs.libohttp.hpke.bc)

    // libadb-android (BouncyCastle fork)
    implementation(libs.libadb.android)
    // X.509 certificate generation for ADB pairing (BouncyCastle)
    implementation(libs.bouncycastle.pkix)

    // Streaming encrypted+compressed acquisition archive (age + ZIP) engine.
    // Provides our BouncyCastle-only age implementation; kage is no longer used.
    implementation(project(":crypto"))

    // Applying the update feed's unified-diff deltas
    implementation(libs.java.diff.utils)

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

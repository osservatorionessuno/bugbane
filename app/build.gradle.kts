
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

    lint {
        // Translations lag behind source strings; don't fail the build on them
        warning += "MissingTranslation"
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

// ---------------------------------------------------------------------------------------------
// Offline IOC snapshot bundled into the APK (issue #47).
//
// Committed under src/main/assets/bundled-indicators/ (raw indicators.json + update.json). The
// build packages it as a plain asset, keeping builds reproducible and F-Droid-buildable.
// BundledIndicators.seedIfStale adopts it at runtime as a cold-start seed.
//
// The "Refresh bundled indicators" workflow updates it from a bugbane-updater release and opens
// a PR; it is never fetched during a build.
// checkBundledIndicators fails the build if it is missing (-Pbugbane.allowMissingIndicators skips).
// ---------------------------------------------------------------------------------------------

val bundledIndicatorsAssets = layout.projectDirectory.dir("src/main/assets/bundled-indicators")
val allowMissingIndicators = (findProperty("bugbane.allowMissingIndicators") as String?).toBoolean()

// Fail the build if the committed snapshot is missing.
val checkBundledIndicators by tasks.registering {
    description = "Verify the committed bundled IOC snapshot is present (#47)"
    group = "bugbane"
    val meta = bundledIndicatorsAssets.file("update.json").asFile
    val bundle = bundledIndicatorsAssets.file("indicators.json").asFile
    val allowMissing = allowMissingIndicators
    doLast {
        if (allowMissing) return@doLast
        if (!meta.exists() || !bundle.exists() || bundle.length() == 0L) {
            throw GradleException(
                "Committed bundled indicators missing under src/main/assets/bundled-indicators/. " +
                    "Run the 'Refresh bundled indicators' workflow and merge its PR, or pass " +
                    "-Pbugbane.allowMissingIndicators=true to build without them.",
            )
        }
    }
}
tasks.named("preBuild") { dependsOn(checkBundledIndicators) }

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
    implementation(project(":crypto"))

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

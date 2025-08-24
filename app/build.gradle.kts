plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("de.mannodermaus.android-junit5") version "1.13.1.0"
    id("com.google.protobuf") version "0.9.5"
}

android {
    namespace = "org.osservatorionessuno.bugbane"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.osservatorionessuno.bugbane"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    kotlinOptions { jvmTarget = "11" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Important for local JVM tests that touch Android bits/resources
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
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.github.MuntashirAkon:libadb-android:3.0.0")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")
    // Required for adb encryption
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    // Quick string matching for mvt
    implementation("org.ahocorasick:ahocorasick:0.6.3")
    // Tombstone protobuf
    implementation("com.google.protobuf:protobuf-javalite:3.25.3")


    // --- Unit test (JUnit 5) ---
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.13.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.4")

    // If some tests need Android resources/framework behavior on JVM:
    testImplementation("org.robolectric:robolectric:4.15.1")

    testImplementation("org.json:json:20250517")

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3"
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

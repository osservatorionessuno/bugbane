plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(libs.lint.api)
    testImplementation(libs.lint.api)
    testImplementation(libs.lint.tests)
    testImplementation("junit:junit:4.13.2")
}

tasks.jar {
    manifest {
        attributes("Lint-Registry-v2" to "org.osservatorionessuno.qf.lint.SecretIssueRegistry")
    }
}

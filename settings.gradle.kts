import org.gradle.api.artifacts.verification.DependencyVerificationMode

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Bugbane"
include(":app")
include(":crypto")
include(":lint-rules")
include(":fixtures:suspicious-apk")

// Project-wide default value is set to true, but can be overridden in "gradle.properties".
val useLocalLibmvt = providers.gradleProperty("libmvtLocal")
    .orElse("false")
    .get()
    .toBoolean()

if (useLocalLibmvt) {
    // Included libmvt resolves its own plugin classpath (Plugin Portal POMs),
    // which is not the same set of checksums as the JitPack libmvt jar.
    gradle.startParameter.dependencyVerificationMode = DependencyVerificationMode.OFF
    includeBuild("../libmvt") {
        dependencySubstitution {
            substitute(module("com.github.osservatorionessuno:libmvt"))
                .using(project(":"))
        }
    }
    println("using local LibMVT, dependency verification is off")
    if (gradle.startParameter.taskNames.any { "Production" in it }) {
        throw GradleException("Cannot build the production flavor with libmvtLocal=true. Unset libmvtLocal for a production build.")
    }
}


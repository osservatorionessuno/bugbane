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

// Project-wide default value is set to true, but can be overridden in "gradle.properties".
val useLocalLibmvt = providers.gradleProperty("libmvtLocal")
    .orElse("false")
    .get()
    .toBoolean()

if (useLocalLibmvt) {
    // Instead of downloading libmvt from GitHub
    // Fall back on the local libmvt.
    includeBuild("../libmvt") {
        dependencySubstitution {
            substitute(module("com.github.osservatorionessuno:libmvt"))
                .using(project(":"))
        }
    }
    println("using local LibMVT")
}
 
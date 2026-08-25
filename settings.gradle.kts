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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // jaudiotagger-android (lee/escribe tags de audio: caratula y
        // letra dentro del archivo) solo se publica en JitPack, no en
        // Maven Central.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "MP_3"
include(":app")
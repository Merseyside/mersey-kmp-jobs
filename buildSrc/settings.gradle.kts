@file:Suppress("UnstableApiUsage")

plugins {
    // Version comes from the root settings of this build
    id("ru.izibook.gitlab-repository")
}

dependencyResolutionManagement {
    repositories {
        // Local first: packages published from a Mac have iOS, the ones from CI don't. Empty on the runner
        mavenLocal()
        izibookGitlab()
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }

    val catalogVersions = "1.8.9"
    val group = "io.github.merseyside"

    versionCatalogs {
        create("catalogGradle") {
            from("$group:catalog-version-gradle:$catalogVersions")
        }
    }
}

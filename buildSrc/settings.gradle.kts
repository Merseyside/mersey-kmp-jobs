@file:Suppress("UnstableApiUsage")

plugins {
    id("ru.izibook.gitlab-repository")
}

dependencyResolutionManagement {
    repositories {
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

    val catalogVersions = "1.8.10"
    val group = "io.github.merseyside"

    versionCatalogs {
        create("catalogGradle") {
            from("$group:catalog-version-gradle:$catalogVersions")
        }
    }
}

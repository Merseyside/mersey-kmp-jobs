@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        // Settings plugin with izibookGitlab(), anonymous pull from izi-gradle-repository
        maven("https://gitlab.izibook.ru/api/v4/projects/70/packages/maven") {
            content {
                includeGroupAndSubgroups("ru.izibook")
            }
        }
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        mavenLocal()
        gradlePluginPortal()
    }
}

plugins {
    id("ru.izibook.gitlab-repository") version "1.0.0"
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
        // The version catalogs are published here from ../izidesk-mobile/mersey-libs/catalog-versions
    }

    val group = "io.github.merseyside"
    val catalogVersions = "1.8.9"

    versionCatalogs {
        create("common") { from("$group:catalog-version-common:$catalogVersions") }
        create("multiplatformLibs") { from("$group:catalog-version-multiplatform:$catalogVersions") }
        create("androidLibs") { from("$group:catalog-version-android:$catalogVersions") }
        create("catalogPlugins") { from("$group:catalog-version-plugins:$catalogVersions") }
    }
}

rootProject.name = "mersey-kmp-jobs"

// Long-running jobs: the contract, the runtime and the process hold on every platform
include(":jobs-core")

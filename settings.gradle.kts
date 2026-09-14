@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
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

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // The version catalogs are published here from ../izidesk-mobile/mersey-libs/catalog-versions
        mavenLocal()
    }

    val group = "io.github.merseyside"
    val catalogVersions = "1.8.6"

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

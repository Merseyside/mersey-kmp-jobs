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
        // Каталоги версий публикуются сюда из ../izidesk-mobile/mersey-libs/catalog-versions
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

// Долгие задачи: контракт, рантайм и удержание процесса на каждой платформе
include(":jobs-core")

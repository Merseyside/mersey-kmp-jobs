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

    val catalogVersions = "1.8.27"
    val group = "io.github.merseyside"

    versionCatalogs {
        create("catalogGradle") {
            from("$group:catalog-version-gradle:$catalogVersions")
        }
    }
}

// Явное имя: с включёнными projects без него кэш сборки зависит от имени папки
rootProject.name = "buildSrc"

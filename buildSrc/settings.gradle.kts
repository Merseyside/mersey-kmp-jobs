plugins {
    // Version comes from the root settings of this build
    id("ru.izibook.gitlab-repository")
}

dependencyResolutionManagement {
    repositories {
        izibookGitlab()
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

    val catalogVersions = "1.8.9"
    val group = "io.github.merseyside"

    versionCatalogs {
        create("catalogGradle") {
            from("$group:catalog-version-gradle:$catalogVersions")
        }
    }
}

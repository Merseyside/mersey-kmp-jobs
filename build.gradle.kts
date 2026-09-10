/**
 * Artifact coordinates. The version lives here and not in the version catalog:
 * the catalog knows the versions of other people's libraries, while this one is
 * ours and changes together with the code itself.
 */
allprojects {
    plugins.withId("org.gradle.maven-publish") {
        group = "io.github.merseyside"
        version = "0.1.0"
    }
}

plugins {
    alias(catalogPlugins.plugins.kotlin.multiplatform) apply false
    alias(catalogPlugins.plugins.kotlin.serialization) apply false
    alias(catalogPlugins.plugins.android.kotlin.multiplatform.library) apply false
}

allprojects {
    // The coordinates are needed by the composite build: by them Gradle
    // substitutes the io.github.merseyside:jobs-core dependency with this project.
    group = "io.github.merseyside"
    version = "0.1.0"
}

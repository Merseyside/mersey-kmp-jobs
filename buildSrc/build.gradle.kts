plugins {
    `kotlin-dsl`
}

/**
 * The plugins in catalog-version-plugins are declared without versions: the
 * version comes from here, from the build classpath. That is why buildSrc is
 * needed even by a single module.
 */
dependencies {
    with(catalogGradle) {
        // The kotlin-extension plugin. Without it on the classpath the alias in jobs-core
        // is resolved by its catalog version from the repositories
        implementation(mersey.gradlePlugins)
        implementation(android.gradle)
        implementation(kotlin.gradle)
        implementation(kotlin.serialization)

        // Publishing the artifacts: the plugin is applied by a script
        // publication/maven-publish-plugin
        implementation(maven.publish.plugin)
    }
}

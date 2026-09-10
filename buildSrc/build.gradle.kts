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
        implementation(android.gradle)
        implementation(kotlin.gradle)
        implementation(kotlin.serialization)

        // Publishing the artifacts: the plugin is applied by a script
        // publication/maven-publish-plugin
        implementation(maven.publish.plugin)
    }
}

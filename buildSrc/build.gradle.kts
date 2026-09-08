plugins {
    `kotlin-dsl`
}

/**
 * Плагины в catalog-version-plugins объявлены без версий: версия приходит отсюда,
 * из classpath сборки. Поэтому buildSrc нужен даже одному-единственному модулю.
 */
dependencies {
    with(catalogGradle) {
        implementation(android.gradle)
        implementation(kotlin.gradle)
        implementation(kotlin.serialization)

        // Публикация артефактов: плагин подключается скриптом
        // publication/maven-publish-plugin
        implementation(maven.publish.plugin)
    }
}

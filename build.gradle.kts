/**
 * Координаты артефактов. Версия здесь, а не в каталоге версий: каталог знает
 * версии чужих библиотек, а эта — своя, и меняется она вместе с самим кодом.
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
    // Координаты нужны композитной сборке: по ним Gradle подменяет зависимость
    // io.github.merseyside:jobs-core на этот проект.
    group = "io.github.merseyside"
    version = "0.1.0"
}

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

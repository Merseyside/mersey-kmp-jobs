import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(catalogPlugins.plugins.kotlin.multiplatform)
    alias(catalogPlugins.plugins.android.kotlin.multiplatform.library)
    alias(catalogPlugins.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "com.merseyside.jobs"
        compileSdk = androidLibs.versions.compileSdk.get().toInt()
        minSdk = androidLibs.versions.compileMinSdk.get().toInt()
    }

    iosArm64()
    iosSimulatorArm64()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(common.coroutines)

            // api: задача сама описывает, как сохранять свои шаги, и объявляет
            // сериализаторы в собственном коде
            api(common.serialization)
        }

        androidMain.dependencies {
            // Уведомление сервиса, работающего на переднем плане
            implementation(androidLibs.androidx.core)
        }

        commonTest.dependencies {
            implementation(common.kotlin.test)
        }
    }
}

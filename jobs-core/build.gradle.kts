import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(catalogPlugins.plugins.kotlin.multiplatform)
    alias(catalogPlugins.plugins.android.kotlin.multiplatform.library)
    alias(catalogPlugins.plugins.kotlin.serialization)

    `maven-publish-plugin`
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

            // api: a job sets the pauses between attempts with its own TimeUnit
            api(common.mersey.time)

            // api: a job itself describes how to save its steps and declares the
            // serializers in its own code
            api(common.serialization)
        }

        androidMain.dependencies {
            // The notification of the foreground service
            implementation(androidLibs.androidx.core)
        }

        commonTest.dependencies {
            implementation(common.kotlin.test)
        }
    }
}

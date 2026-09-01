plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "com.akiha.akihalink.baselineprofile"
    compileSdk = 36
    targetProjectPath = ":app"
    defaultConfig {
        minSdk = 36
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.output.enable"] = "true"
    }

    buildTypes {
        create("benchmark") {
            isDebuggable = true
        }
    }

    testOptions {
        managedDevices {
            localDevices {
                create("pixel6Api36") {
                    device = "Pixel 6"
                    apiLevel = 36
                    systemImageSource = "aosp"
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.espresso.core)
    implementation(libs.benchmark.macro.junit4)
    implementation(libs.benchmark.junit4)
    implementation(libs.kotlinx.serialization.json)
}

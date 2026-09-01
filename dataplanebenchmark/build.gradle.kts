plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.akiha.akihalink.dataplanebenchmark"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.akiha.akihalink.dataplanebenchmark"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

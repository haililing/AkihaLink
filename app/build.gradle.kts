import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val configFixtureDirectory = providers.gradleProperty("akihalink.fixtureDir")
val subscriptionFixture = providers.gradleProperty("akihalink.subscriptionFixture")

tasks.withType<Test>().configureEach {
    if (configFixtureDirectory.isPresent) {
        val fixturePath = configFixtureDirectory.get()
        inputs.property("akihalink.fixtureDir", fixturePath)
        outputs.dir(fixturePath)
        systemProperty("akihalink.fixtureDir", fixturePath)
    }
    if (subscriptionFixture.isPresent) {
        val fixturePath = subscriptionFixture.get()
        inputs.file(fixturePath)
        systemProperty("akihalink.subscriptionFixture", fixturePath)
    }
}

android {
    namespace = "com.akiha.akihalink"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.akiha.akihalink"
        minSdk = 36
        targetSdk = 36
        versionCode = 24
        versionName = "1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "EDITION", "\"generic\"")
        buildConfigField("int", "CONTROL_PROTOCOL_VERSION", "16")
        buildConfigField("String", "CORE_COMMIT", "\"10e9a4258e44536ef30cefc3e603e39439ebc02c\"")
        buildConfigField("String", "CORE_PATCH_SET", "\"akihalink-upstream-ebpf-v17\"")
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("boolean", "BENCHMARK_MODE", "false")
            ndk {
                abiFilters += "x86_64"
            }
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            baselineProfile {
                ignoreFromAllExternalDependencies = true
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            buildConfigField("boolean", "BENCHMARK_MODE", "false")
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "BENCHMARK_MODE", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }

}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val mergedManifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
        val verifyTask = tasks.register("verifyReleaseHasNoBackgroundComponents") {
            inputs.file(mergedManifest)
            doLast {
                val manifest = mergedManifest.get().asFile.readText()
                val forbidden = linkedMapOf(
                    "background service" to Regex("<service\\b", RegexOption.IGNORE_CASE),
                    "wake lock permission" to Regex("android\\.permission\\.WAKE_LOCK", RegexOption.IGNORE_CASE),
                    "foreground service permission" to Regex("android\\.permission\\.FOREGROUND_SERVICE", RegexOption.IGNORE_CASE),
                    "WorkManager" to Regex("androidx\\.work|workmanager", RegexOption.IGNORE_CASE),
                    "alarm scheduling" to Regex("SCHEDULE_EXACT_ALARM|USE_EXACT_ALARM|AlarmManager", RegexOption.IGNORE_CASE),
                )
                val violations = forbidden.filterValues { it.containsMatchIn(manifest) }.keys
                check(violations.isEmpty()) {
                    "Release manifest contains forbidden background facilities: ${violations.joinToString()}"
                }
                val receiverNames = Regex(
                    "<receiver\\b[^>]*android:name=\"([^\"]+)\"",
                    RegexOption.IGNORE_CASE,
                ).findAll(manifest).map { it.groupValues[1] }.toSet()
                check(receiverNames == setOf("com.akiha.akihalink.PackageChangeReceiver")) {
                    "Release manifest contains an unexpected broadcast receiver: ${receiverNames.joinToString()}"
                }
            }
        }
        tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn(verifyTask) }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.libsu.core)
    implementation(libs.snakeyaml.engine)
    implementation(libs.lucide.icons)
    implementation(libs.profileinstaller)
    implementation(libs.tracing.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

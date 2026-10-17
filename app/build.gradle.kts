import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kiransaj.diyamonitor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kiransaj.diyamonitor"
        minSdk = 26
        targetSdk = 36
        // CI: the run number always increases, so every build can install over the previous one.
        // A v* tag (e.g. v1.2.0) sets the version name that the in-app updater compares against.
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        val tagVersion = System.getenv("GITHUB_REF_NAME")
            ?.takeIf { System.getenv("GITHUB_REF_TYPE") == "tag" && it.startsWith("v") }
            ?.removePrefix("v")
        versionCode = runNumber ?: 1
        versionName = tagVersion ?: "0.0.0-dev.${runNumber ?: 0}"
    }

    signingConfigs {
        // Committed debug keystore so every CI build has the same signature and
        // can be installed over the previous one without uninstalling.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    val camerax = "1.4.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

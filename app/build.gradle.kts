plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.lekathedon.visionlite"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.lekathedon.visionlite"
        minSdk = 24
        targetSdk = 36
        versionCode = (findProperty("vlCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("vlName") as String?) ?: "0.1.0"
    }

    signingConfigs {
        create("release") {
            val store = System.getenv("VL_STORE_FILE")
            if (store != null) {
                storeFile = file(store)
                storePassword = System.getenv("VL_STORE_PASSWORD")
                keyAlias = System.getenv("VL_KEY_ALIAS")
                keyPassword = System.getenv("VL_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (System.getenv("VL_STORE_FILE") != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }
    splits {
        abi { isEnable = true; reset(); include("arm64-v8a", "armeabi-v7a"); isUniversalApk = false }
    }
    // Native FEL (phase 2): externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.hls)
    implementation(libs.media3.okhttp)
    implementation(libs.media3.ui)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.zxing.core)
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.longnguyen.inkdiary"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.longnguyen.inkdiary"
        // Boox Go 6 Gen 2 runs Android 11 (API 30)
        minSdk = 26
        targetSdk = 30
        versionCode = 1
        versionName = "0.1-step1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")
    
    // Onyx Boox SDK with exclusions to fix broken transitive dependencies
    implementation("com.onyx.android.sdk:onyxsdk-device:1.3.5") {
        exclude(group = "com.tencent", module = "mmkv")
        exclude(group = "org.apache.commons.io", module = "commonsIO")
    }
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.2.1") {
        exclude(group = "com.tencent", module = "mmkv")
        exclude(group = "org.apache.commons.io", module = "commonsIO")
    }
    implementation("com.onyx.android.sdk:onyxsdk-base:1.5.8") {
        exclude(group = "com.tencent", module = "mmkv")
        exclude(group = "org.apache.commons.io", module = "commonsIO")
    }
    
    // Manually add the correct versions of the missing dependencies if needed
    implementation("commons-io:commons-io:2.6")
    implementation("com.tencent:mmkv-static:1.2.10")
}

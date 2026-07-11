import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

android {
    namespace = "com.longnguyen.inkdiary"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.longnguyen.inkdiary"
        // Boox Go 6 Gen 2 runs Android 11 (API 30)
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-step1"

        val properties = Properties()
        val localPropertiesFile = project.rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { properties.load(it) }
        }
        val apiKey = properties.getProperty("gemini.api.key") ?: ""
        buildConfigField("String", "GEMINI_API_KEY", "\"$apiKey\"")
        val sambaKey = properties.getProperty("sambanova.api.key") ?: ""
        buildConfigField("String", "SAMBANOVA_API_KEY", "\"$sambaKey\"")
    }

    buildFeatures {
        buildConfig = true
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            pickFirsts += "lib/**/libc++_shared.so"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")
    
    // Room
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")
    
    // Onyx Boox SDK with exclusions to fix broken transitive dependencies
    implementation("com.onyx.android.sdk:onyxsdk-device:1.3.5") {
        exclude(group = "com.tencent", module = "mmkv")
        exclude(group = "org.apache.commons.io", module = "commonsIO")
    }
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.4") {
        exclude(group = "com.tencent", module = "mmkv")
        exclude(group = "org.apache.commons.io", module = "commonsIO")
    }
    implementation("com.onyx.android.sdk:onyxsdk-base:1.8.5") {
        exclude(group = "com.tencent", module = "mmkv")
        exclude(group = "org.apache.commons.io", module = "commonsIO")
    }
    
    // Manually add the correct versions of the missing dependencies if needed
    implementation("commons-io:commons-io:2.6")
    implementation("com.tencent:mmkv-static:1.2.10")

    // Networking & JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // Hidden API Bypass
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}

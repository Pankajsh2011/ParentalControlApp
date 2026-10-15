plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.parental.control.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.parental.control.app"
        minSdk = 24
        targetSdk = 33
        versionCode = 3
        versionName = "2.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "role"
    productFlavors {
        create("parent") {
            dimension = "role"
            applicationIdSuffix = ".parent"
            versionNameSuffix = "-parent"
            manifestPlaceholders["appLabel"] = "Parental Hub (Parent)"
        }
        create("child") {
            dimension = "role"
            applicationIdSuffix = ".child"
            versionNameSuffix = "-child"
            manifestPlaceholders["appLabel"] = "Parental Companion (Child)"
        }
    }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")

    // Location & Geofencing (Module 4)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // WebRTC Streaming (Module 1)
    implementation("io.getstream:stream-webrtc-android:1.2.1")

    // Coroutines & WorkManager (Module 2 Snapshot Scheduling)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // JSON Parsing & Networking
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

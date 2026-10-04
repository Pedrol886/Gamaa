plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gama.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gama.assistant"
        minSdk = 26
        targetSdk = 35
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        versionCode = 24400136
        versionName = "Gama 9.3.0 JARVIS Reliability"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources { noCompress += listOf("task") }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    testImplementation("junit:junit:4.13.2")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.google.mediapipe:tasks-genai:0.10.27")
}

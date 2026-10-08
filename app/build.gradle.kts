plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

android {
    namespace = "com.example.studentlookup"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.studentlookup"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // 只保留手机实际用到的架构：ONNX Runtime 自带 4 种 ABI 的原生库，
        // 不限制的话 APK 会从 44MB 涨到 128MB
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // 固定签名（关键）：GitHub Actions 每次新建的随机 debug keystore 会让 APK 签名变化，
    // 导致 `adb install -r` 报 INSTALL_FAILED_UPDATE_INCOMPATIBLE（必须卸载重装、丢数据/授权）。
    // 这里用仓库内固定的 debug keystore，保证每次云端构建出来的包签名一致、可原地升级。
    // 仓库为私有；如需更严格，可改从 CI Secrets 注入。
    signingConfigs {
        create("slkdebug") {
            storeFile = rootProject.file("keystore/studentlookup-debug.keystore")
            storePassword = "studentlookup"
            keyAlias = "studentlookup"
            keyPassword = "studentlookup"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("slkdebug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 本地数据库（room-ktx 提供 suspend/Flow 协程支持）
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // 截图 OCR 兜底（中文模型，完全离线、不依赖 GMS，适合国产机）
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0-beta6")

    // 本地 OCR 引擎 B 方案：ONNX Runtime + PP-OCRv4 中文识别模型（离线、纯 Gradle 依赖）
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
}

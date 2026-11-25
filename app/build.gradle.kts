plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pribadi.mangatranslator"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pribadi.mangatranslator"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Only package ARM native libraries for real Android devices (removes ~55 MB of x86/x86_64 emulator libs)
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // Only keep Indonesian and English AndroidX/Material string resources
        resourceConfigurations += listOf("in", "id", "en")
    }

    signingConfigs {
        create("consistent") {
            storeFile = file("manga-translator.jks")
            storePassword = "manga2026sign"
            keyAlias = "mangatranslator"
            keyPassword = "manga2026sign"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("consistent")
        }
        release {
            signingConfig = signingConfigs.getByName("consistent")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        jniLibs {
            // Compress native ML Kit .so libraries inside the APK (cuts ~60% of native lib size)
            useLegacyPackaging = true
        }
        resources {
            excludes += listOf(
                "META-INF/*.kotlin_module",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "DebugProbesKt.bin"
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
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // ML Kit Text Recognition (Default/Latin, Japanese, Korean, Chinese)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // ML Kit Translation (Offline fallback)
    implementation("com.google.mlkit:translate:17.0.3")
}

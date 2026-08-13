plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.arena.motionphoto"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.arena.motionphoto"
        minSdk = 26
        targetSdk = 34
        // Versi diambil dari nomor run GitHub Actions supaya SELALU sinkron
        // dengan tag Release dan nama file APK. Build lokal jatuh ke 1/1.0.0-dev.
        // Catatan: versionCode WAJIB bilangan positif, jadi default-nya 1.
        versionCode = (System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 1).coerceAtLeast(1)
        versionName = System.getenv("BUILD_NUMBER")?.let { "1.0.$it" } ?: "1.0.0-dev"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // debug key dipakai supaya APK release langsung bisa di-install
            signingConfig = signingConfigs.getByName("debug")
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
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Media3 Transformer: trim + transcode, hardware accelerated
    implementation("androidx.media3:media3-transformer:1.3.1")
    implementation("androidx.media3:media3-effect:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")

    testImplementation("junit:junit:4.13.2")
}

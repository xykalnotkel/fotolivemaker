plugins {
    id("com.android.application")
}

android {
    namespace = "livefoto.xystudio.app"
    compileSdk = 37

    // Keystore rilis diambil dari GitHub Secrets saat build di CI.
    // Kalau tidak ada (build lokal), jatuh ke debug key supaya tetap jalan.
    val ksFile = rootProject.file("release.jks")
    val hasRelease = ksFile.exists() &&
        !System.getenv("KEYSTORE_PASSWORD").isNullOrBlank()

    signingConfigs {
        if (hasRelease) {
            create("release") {
                storeFile = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    defaultConfig {
        applicationId = "livefoto.xystudio.app"
        minSdk = 24
        targetSdk = 37
        // NDK untuk HD+ - membersihkan MP4 + JPG via C++
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        // Versi diambil dari nomor run GitHub Actions supaya SELALU sinkron
        // dengan tag Release dan nama file APK. Build lokal jatuh ke 1/1.0.0-dev.
        // Catatan: versionCode WAJIB bilangan positif, jadi default-nya 1.
        versionCode = (System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: (System.currentTimeMillis() / 1000L).toInt()).coerceAtLeast(1)
        versionName = System.getenv("VERSION_NAME")
            ?: System.getenv("BUILD_NUMBER")?.let { "1.0.$it" }
            ?: "1.0.0-dev"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "28.2.13676358"

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasRelease)
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Media3 Transformer: trim + transcode, hardware accelerated
    implementation("androidx.media3:media3-transformer:1.11.0")
    implementation("androidx.media3:media3-effect:1.11.0")
    implementation("androidx.media3:media3-common:1.11.0")
    // ExoPlayer: pemutar preview yang andal (VideoView sering gagal prepare
    // saat view-nya belum terlihat)
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-ui:1.11.0")

    testImplementation("junit:junit:4.13.2")
}
